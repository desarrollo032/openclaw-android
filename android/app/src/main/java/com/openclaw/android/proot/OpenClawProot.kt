package com.openclaw.android.proot

import android.content.Context
import android.util.Log as AndroidLog
import com.openclaw.android.OpenClawLogger
import com.openclaw.android.deleteRecursivelySafe
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenClawProot — núcleo de la integración proot + Alpine Linux.
 *
 * Reemplaza la antigua cadena `libldlinux.so → libnode.so → openclaw.mjs` por
 * un binario `proot` estático que ejecuta un rootfs Alpine ARM64 con Node.js
 * instalado via `apk`.
 *
 * Layout en disco (host Android):
 *
 *   filesDir/
 *     ├── alpine-rootfs/        Rootfs Alpine completo (~50 MB descomprimido)
 *     │   ├── bin/ etc/ usr/    ← sistema Alpine
 *     │   └── etc/resolv.conf   ← DNS escrito por nosotros
 *     ├── home/.openclaw/       OPENCLAW_HOME (visto como /data/home/.openclaw)
 *     │   └── tmp/              TMPDIR (fix EACCES link())
 *     └── proot-tmp/            PROOT_TMP_DIR (Android no tiene /tmp en el host)
 *
 *   nativeLibraryDir/libproot.so   ← binario proot ARM64 (estático ~500 KB)
 *
 * Layout visto desde dentro del proot Alpine:
 *
 *   /         → alpine-rootfs/
 *   /data/    → bind mount de filesDir
 *   /tmp/     → bind mount de proot-tmp/
 *
 * Variables CRÍTICAS:
 *   PROOT_TMP_DIR     — sin esto proot falla porque Android no tiene /tmp en host
 *   PROOT_NO_SECCOMP  — fix para kernels Android 12+ con seccomp estricto
 *
 * Nunca usar LD_PRELOAD ni LD_LIBRARY_PATH: Alpine usa sus propias libs musl,
 * no las del NDK de Android.
 */
class OpenClawProot(private val context: Context) {

    companion object {
        const val TAG = "OpenClawProot"

        /** Versión de Alpine descargada. Cambiar requiere también verificar el SHA. */
        const val ALPINE_VERSION = "3.22.0"

        /** URL oficial del minirootfs ARM64 (HTTPS). */
        val ALPINE_URL: String =
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/" +
            "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"

        /** Fallback HTTP si HTTPS falla por problemas de certificados SSL en el dispositivo. */
        val ALPINE_HTTP_URL: String = ALPINE_URL.replace("https://", "http://")

        /** Path absoluto del openclaw.mjs dentro del rootfs Alpine (npm global). */
        private val OPENCLAW_PATHS_INSIDE_ALPINE = listOf(
            "usr/local/lib/node_modules/openclaw/openclaw.mjs",
            "usr/lib/node_modules/openclaw/openclaw.mjs"
        )
    }

    // ── Rutas en el host Android ─────────────────────────────────────────────

    val filesDir: File        get() = context.filesDir
    val nativeDir: String     get() = context.applicationInfo.nativeLibraryDir
    val rootfs: File          get() = File(filesDir, "alpine-rootfs")
    val prootTmpDir: File     get() = File(filesDir, "proot-tmp").apply { mkdirs() }
    val homeDir: File         get() = File(filesDir, "home").apply { mkdirs() }
    val openclawHome: File    get() = File(homeDir, ".openclaw").apply { mkdirs() }
    val openclawTmp: File     get() = File(openclawHome, "tmp").apply { mkdirs() }

    /** Path absoluto del binario proot. Vive en nativeLibraryDir como ELF estático. */
    val proot: String         get() = "$nativeDir/libproot.so"

    // ── Estado ───────────────────────────────────────────────────────────────

    /** El rootfs Alpine está extraído y tiene `bin/sh`. */
    fun isAlpineInstalled(): Boolean = File(rootfs, "bin/sh").exists()

    /** OpenClaw está instalado como módulo global de npm dentro del rootfs. */
    fun isOpenClawInstalled(): Boolean =
        OPENCLAW_PATHS_INSIDE_ALPINE.any { File(rootfs, it).exists() }

    /** El binario proot está presente. Si falta, la APK fue construida mal. */
    fun isProotPresent(): Boolean = File(proot).exists() && File(proot).canExecute()

    /** Hay que correr el flujo completo (descargar Alpine + instalar openclaw). */
    fun needsSetup(): Boolean = !isAlpineInstalled() || !isOpenClawInstalled()

    // ── Descarga y extracción de Alpine ──────────────────────────────────────

    /**
     * Descarga el minirootfs Alpine de dl-cdn.alpinelinux.org y lo extrae en
     * `rootfs`. Usa Apache Commons Compress (Java) en vez de `/system/bin/tar`
     * para ser compatible con todos los dispositivos Android.
     *
     * Devuelve `true` si todo salió bien. Errores y progreso se reportan vía
     * los callbacks; también se replican en [OpenClawLogger].
     */
    fun downloadAndExtractAlpine(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        return try {
            rootfs.mkdirs()
            val tarFile = File(prootTmpDir, "alpine-rootfs.tar.gz")
            tarFile.parentFile?.mkdirs()
            if (tarFile.exists()) tarFile.delete()

            onProgress("Descargando Alpine Linux ARM64 $ALPINE_VERSION…")

            // ── Descarga con fallback HTTPS → HTTP ────────────────────────────
            // Algunos dispositivos tienen CAs desactualizados o configuraciones
            // de red que bloquean la validación SSL (proxies corporativos,
            // custom ROMs, etc.). Primero intentamos HTTPS; si falla por SSL,
            // reintentamos con HTTP.
            val urlsToTry = listOf(ALPINE_URL, ALPINE_HTTP_URL)
            var downloaded = false

            for (attemptUrl in urlsToTry) {
                if (downloaded) break
                log("Download attempt: $attemptUrl")

                try {
                    val conn = (URL(attemptUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "OpenClaw-Android/1.0")
                    }
                    if (conn.responseCode != 200) {
                        conn.disconnect()
                        continue
                    }
                    val total = conn.contentLength.toLong().coerceAtLeast(0L)
                    var copied = 0L
                    var lastPct = -1
                    tarFile.outputStream().use { out ->
                        conn.inputStream.use { input: InputStream ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = ((copied * 100) / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress("Descargando Alpine… $pct%")
                                    }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    downloaded = true
                    if (attemptUrl != ALPINE_URL) {
                        log("Downloaded via HTTP fallback (HTTPS SSL error on this device)")
                        onProgress("Descarga completada (vía HTTP)")
                    }
                } catch (e: javax.net.ssl.SSLException) {
                    log("HTTPS failed: ${e.message}, trying HTTP fallback...")
                    // Intentar con HTTP en la siguiente iteración
                }
            }

            if (!downloaded) {
                onError("No se pudo descargar Alpine (HTTPS y HTTP fallaron)")
                return false
            }

            if (tarFile.length() == 0L) {
                tarFile.delete()
                onError("Descarga vacía de Alpine")
                return false
            }

            onProgress("Extrayendo Alpine (~50 MB)…")
            try {
                FileInputStream(tarFile).use { fis ->
                    BufferedInputStream(fis, 64 * 1024).use { bis ->
                        GzipCompressorInputStream(bis).use { gzis ->
                            TarArchiveInputStream(gzis).use { tais ->
                                var entry = tais.nextEntry
                                var extractedCount = 0
                                while (entry != null) {
                                    // Sanitizar: eliminar leading "/" para evitar
                                    // escritura fuera del rootfs si el tar tiene paths absolutos
                                    val safeName = entry.name.removePrefix("/")
                                    val outputFile = File(rootfs, safeName)
                                    if (entry.isDirectory) {
                                        outputFile.mkdirs()
                                    } else {
                                        outputFile.parentFile?.mkdirs()
                                        outputFile.outputStream().use { out ->
                                            val buf = ByteArray(64 * 1024)
                                            var n = tais.read(buf, 0, buf.size)
                                            while (n != -1) {
                                                out.write(buf, 0, n)
                                                n = tais.read(buf, 0, buf.size)
                                            }
                                        }

                                        // ── APLICAR PERMISOS DEL TAR ─────────────────────
                                        // Apache Commons Compress NO aplica los bits de
                                        // ejecución del tar automáticamente. Sin esto,
                                        // todos los binarios de Alpine (sh, apk, busybox,
                                        // node, npm) quedan sin permiso +x y proot falla
                                        // con "'/bin/sh' is not executable".
                                        val mode = entry.mode
                                        val ownerExec  = (mode and 0b001_000_000) != 0
                                        val groupExec  = (mode and 0b000_001_000) != 0
                                        val otherExec  = (mode and 0b000_000_001) != 0
                                        val anyExec = ownerExec || groupExec || otherExec
                                        if (anyExec) {
                                            outputFile.setExecutable(true, false)
                                        }
                                        // readable para todos (por defecto del tar)
                                        outputFile.setReadable(true, false)

                                        extractedCount++
                                        if (extractedCount % 500 == 0) {
                                            onProgress("Extrayendo Alpine… (archivo $extractedCount)")
                                        }
                                    }
                                    entry = tais.nextEntry
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("tar extraction via commons-compress failed: ${e.message}")
                onError("Error extrayendo Alpine: ${e.message ?: e.javaClass.simpleName}")
                tarFile.delete()
                return false
            }
            tarFile.delete()

            // ── Paso de seguridad: aplicar permisos a todos los binarios ─────
            onProgress("Aplicando permisos de ejecución...")
            applyAlpineExecutablePermissions()

            // DNS dentro del rootfs (Alpine no trae resolv.conf por defecto)
            // Múltiples DNS por si algún proveedor está bloqueado en la red.
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/resolv.conf").writeText(
                "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 208.67.222.222\nnameserver 9.9.9.9\n"
            )

            // ── Verificación final: bin/sh debe existir y ser ejecutable ─────
            val sh = File(rootfs, "bin/sh")
            if (!sh.exists()) {
                onError("Extracción incompleta — bin/sh no existe en el rootfs")
                return false
            }
            if (!sh.canExecute()) {
                sh.setExecutable(true, false)
                if (!sh.canExecute()) {
                    onError("bin/sh existe pero no es ejecutable — problema de permisos en el dispositivo")
                    return false
                }
            }
            onProgress("Alpine verificado ✓ (bin/sh ejecutable)")
            true
        } catch (e: Exception) {
            log("downloadAndExtractAlpine failed: ${e.message}")
            onError("Error descargando Alpine: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    // ── Instalación de Node.js + openclaw dentro de Alpine ───────────────────

    /**
     * Dentro del proot, ejecuta `apk add nodejs npm ca-certificates`,
     * `npm install -g openclaw` y verifica las versiones.
     * Retorna `true` si todo OK, `false` si falla.
     *
     * Si falla, captura la última línea que comienza con "FALLO:" y la usa como
     * mensaje de error en vez del genérico "install falló código 1".
     */
    suspend fun installOpenClaw(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        // ── Pre-verificaciones antes de ejecutar proot ────────────────────────
        val prootFile = File(proot)
        if (!prootFile.exists()) {
            onError("libproot.so no encontrado en $proot")
            return false
        }
        if (!prootFile.canExecute()) {
            AndroidLog.w(TAG, "libproot.so no es ejecutable — corrigiendo...")
            prootFile.setExecutable(true, false)
        }

        // Verificar que Alpine tiene apk
        val hasApk = File(rootfs, "sbin/apk").exists() ||
                     File(rootfs, "usr/bin/apk").exists() ||
                     File(rootfs, "bin/apk").exists()
        if (!hasApk) {
            onError("Alpine incompleto — falta binario apk. Reinstalar Alpine.")
            return false
        }

        // Asegurar resolv.conf (DNS) dentro del rootfs
        val resolv = File(rootfs, "etc/resolv.conf")
        if (!resolv.exists() || resolv.readText().isBlank()) {
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 208.67.222.222\nnameserver 9.9.9.9\n")
            AndroidLog.i(TAG, "resolv.conf re-escrito antes de instalar")
        }

        // ── Sanity check: verificar que proot puede ejecutar comandos ──
        // Específicamente diseñado para capturar errores de proot (ej: no puede
        // ejecutar /bin/sh por permisos, falta ptrace, etc.) que de otra forma
        // aparecerían como genérico "Error sin diagnóstico de paso".
        onProgress("Verificando proot...")
        var sanityOutput = StringBuilder()
        val sanityCode = runInProot(
            command = listOf("/bin/sh", "-c", "echo proot_sanity_ok"),
            onOutput = { line ->
                sanityOutput.appendLine(line)
                AndroidLog.v(TAG, "[sanity] $line")
            }
        )

        if (sanityCode != 0) {
            val prootError = sanityOutput.toString().trim().ifBlank { "(sin output — proot no produjo mensaje de error)" }
            val msg = "proot falló (exit=$sanityCode): $prootError"
            AndroidLog.e(TAG, msg)
            onError(msg)
            return false
        }

        val outputOk = sanityOutput.contains("proot_sanity_ok")
        if (!outputOk) {
            AndroidLog.w(TAG, "sanity check: output no contiene proot_sanity_ok, pero exit=0: ${sanityOutput}")
        }
        AndroidLog.i(TAG, "Sanity check OK — proot puede ejecutar /bin/sh")
        onProgress("Verificación de proot OK ✓")

        // ── Script de instalación ────────────────────────────────────────────
        // Cada paso reporta explícitamente su resultado para saber exactamente dónde falla.
        // No usamos `set -e` porque oculta qué paso falló.
        // `--no-audit --no-fund` en npm evita errores silenciosos de auditoría.
        val script = """
            echo "[0/4] Verificando red..."
            ping -c 1 -W 3 1.1.1.1 > /dev/null 2>&1 || echo "[WARN] sin respuesta de ping — puede haber problemas de red"

            echo "[1/4] Actualizando indice de paquetes..."
            if ! apk update --no-cache 2>&1; then
                echo "FALLO:PASO1 apk update falló"
                exit 1
            fi

            echo "[2/4] Instalando nodejs y npm..."
            if ! apk add --no-cache nodejs npm 2>&1; then
                echo "FALLO:PASO2 apk add nodejs npm falló"
                exit 1
            fi

            echo "[2b/4] Instalando ca-certificates..."
            apk add --no-cache ca-certificates 2>&1 || echo "[WARN] ca-certificates no disponible (no crítico)"

            echo "[3/4] Instalando openclaw via npm..."
            if ! npm install -g openclaw --no-audit --no-fund 2>&1; then
                echo "FALLO:PASO3 npm install -g openclaw falló"
                exit 1
            fi

            echo "[4/4] Verificando..."
            echo "Node.js: $(node --version 2>/dev/null || echo error), npm: $(npm --version 2>/dev/null || echo error)"
            openclaw --version 2>/dev/null || echo "[WARN] openclaw --version no responde"
            echo "DONE"
        """.trimIndent()

        // Capturar la última línea FALLO: como mensaje de error específico
        // Si nunca se emite FALLO: (ej: proot falla antes de ejecutar el script),
        // lastErrorLine conserva el default y se reporta con el exit code real.
        var lastErrorLine = "Error sin diagnóstico de paso"

        val code = runInProot(
            command = listOf("/bin/sh", "-c", script),
            onOutput = { line ->
                onProgress(line)
                log(line)
                if (line.startsWith("FALLO:")) {
                    lastErrorLine = line
                }
            }
        )

        return if (code == 0) {
            true
        } else {
            onError("$lastErrorLine [exit=$code]")
            false
        }
    }

    /** `npm update -g openclaw` dentro del proot. Retorna true si OK. */
    suspend fun updateOpenClaw(onProgress: (String) -> Unit): Boolean {
        val code = runInProot(
            command = listOf("/bin/sh", "-c", "npm update -g openclaw && openclaw --version"),
            onOutput = onProgress
        )
        return code == 0
    }

    /** Ejecuta `openclaw onboard` en el proot. Retorna true si OK. */
    suspend fun runOnboard(
        onProgress: (String) -> Unit
    ): Boolean {
        val code = runInProot(
            command = listOf("/bin/sh", "-c", "openclaw onboard"),
            onOutput = onProgress
        )
        return code == 0
    }

    /**
     * Arranca el gateway de OpenClaw como `Process` síncrono.
     * El llamador es responsable de leer stdout y manejar el ciclo de vida
     * (idéntico al ProcessBuilder anterior basado en libnode).
     */
    fun startGatewayProcess(extraEnv: Map<String, String> = emptyMap()): Process {
        val pb = buildProotProcess(
            command = listOf("/bin/sh", "-lc", "openclaw gateway"),
            cwd = "/data/home/.openclaw"
        )
        if (extraEnv.isNotEmpty()) {
            pb.environment().putAll(extraEnv)
        }
        return pb.start()
    }

    // ── Núcleo: runInProot ───────────────────────────────────────────────────

    /**
     * Ejecuta un comando dentro del Alpine de forma bloqueante (suspend)
     * y retorna el código de salida. Toda la salida stdout/stderr se
     * pasa a [onOutput] línea por línea.
     */
    suspend fun runInProot(
        command: List<String>,
        onOutput: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        try {
            val proc = buildProotProcess(command).start()
            proc.inputStream.bufferedReader().forEachLine { line ->
                AndroidLog.d(TAG, line)
                onOutput(line)
            }
            proc.waitFor()
        } catch (e: Exception) {
            log("proot error: ${e.message}")
            AndroidLog.e(TAG, "proot error: ${e.message}", e)
            onOutput("[error] ${e.message}")
            -1
        }
    }

    /**
     * Construye el ProcessBuilder de proot listo para `.start()`.
     * Aplica binds canónicos, simula uid 0 dentro del rootfs y configura
     * el environment mínimo necesario para que Node/openclaw funcionen.
     */
    fun buildProotProcess(command: List<String>, cwd: String = "/root"): ProcessBuilder {
        require(command.isNotEmpty()) { "command must not be empty" }
        val dataBindPath = filesDir.absolutePath

        val args = mutableListOf<String>().apply {
            add(proot)
            add("--rootfs=${rootfs.absolutePath}")
            add("--bind=/proc")
            add("--bind=/dev")
            add("--bind=/dev/null")
            add("--bind=/sys")
            add("--bind=/dev/urandom")
            add("--bind=$dataBindPath:/data")
            add("--bind=${prootTmpDir.absolutePath}:/tmp")
            add("--change-id=0:0")
            add("--cwd=$cwd")
            addAll(command)
        }

        return ProcessBuilder(args).apply {
            redirectErrorStream(true)
            environment().apply {
                // Limpieza obligatoria: nunca filtrar libs/preloads del host Android
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")

                // Variables CRÍTICAS para proot
                put("PROOT_TMP_DIR",   prootTmpDir.absolutePath)
                put("PROOT_NO_SECCOMP","1")

                // Entorno Linux estándar dentro del proot
                put("HOME",   "/root")
                put("TMPDIR", "/data/home/.openclaw/tmp")
                put("PATH",   "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("TERM",   "xterm-256color")
                put("COLORTERM","truecolor")
                put("LANG",   "en_US.UTF-8")
                put("LC_ALL", "en_US.UTF-8")

                // OpenClaw espera estas dos:
                put("OPENCLAW_HOME", "/data/home/.openclaw")
                put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt")

                // npm necesita un cache escribible (no en /home, distinto fs)
                put("npm_config_cache", "/tmp/npm-cache")
            }
        }
    }

    // ── Terminal PTY: comando y env para TerminalSession ─────────────────────

    /**
     * Argumentos para construir un `TerminalSession`. El primer elemento es la
     * ejecutable (proot), el resto los binds + el sh interactivo de Alpine.
     */
    fun buildShellCommand(): Array<String> = arrayOf(
        // proot ejecutable (primer arg pasado a TerminalSession se ignora;
        // realmente vale el `executable` separado, pero conservamos esto por
        // simetría con la API anterior).
        "--rootfs=${rootfs.absolutePath}",
        "--bind=/proc",
        "--bind=/dev",
        "--bind=/dev/null",
        "--bind=/sys",
        "--bind=/dev/urandom",
        "--bind=${filesDir.absolutePath}:/data",
        "--bind=${prootTmpDir.absolutePath}:/tmp",
        "--change-id=0:0",
        "--cwd=/data/home/.openclaw",
        "/bin/sh",
        "-i"
    )

    /** Environment array (formato KEY=VALUE) para el terminal interactivo. */
    fun buildShellEnv(): Array<String> = arrayOf(
        "PROOT_TMP_DIR=${prootTmpDir.absolutePath}",
        "PROOT_NO_SECCOMP=1",
        "HOME=/root",
        "TMPDIR=/data/home/.openclaw/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=en_US.UTF-8",
        "LC_ALL=en_US.UTF-8",
        "OPENCLAW_HOME=/data/home/.openclaw",
        "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
        "PS1=~ \$ "
    )

    // ── Uninstall ────────────────────────────────────────────────────────────

    /** Borra todo el rootfs Alpine y los temporales de proot. */
    fun wipeAlpine() {
        rootfs.deleteRecursivelySafe()
        prootTmpDir.deleteRecursivelySafe()
        prootTmpDir.mkdirs()
    }

    // ── Utilidades ───────────────────────────────────────────────────────────


    /**
     * Aplica permisos de ejecución a todos los binarios dentro del rootfs Alpine.
     * Safety net: si el bucle de extracción no pudo aplicar permisos por algún motivo
     * (entry.mode = 0, tar corrupto, etc.), este método garantiza que los binarios
     * críticos tengan +x.
     */
    private fun applyAlpineExecutablePermissions() {
        val binDirs = listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin")

        for (dirName in binDirs) {
            val dir = File(rootfs, dirName)
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && !file.canExecute()) {
                    file.setExecutable(true, false)
                }
            }
        }

        // Binarios críticos que deben ser ejecutables
        val criticals = listOf(
            "bin/sh", "bin/busybox", "sbin/apk",
            "usr/bin/node", "usr/bin/npm", "usr/local/bin/openclaw"
        )
        criticals.forEach { rel ->
            val f = File(rootfs, rel)
            if (f.exists() && !f.canExecute()) {
                f.setExecutable(true, false)
            }
        }
    }

    private fun log(msg: String) {
        OpenClawLogger.init(context)
        OpenClawLogger.log(TAG, msg)
    }
}
