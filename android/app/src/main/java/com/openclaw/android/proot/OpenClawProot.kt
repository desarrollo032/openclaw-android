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
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenClawProot — núcleo de la integración proot + Alpine Linux.
 *
 * Ejecuta un rootfs Alpine ARM64 completo usando el binario `proot` estático,
 * con Node.js instalado via `apk` dentro del contenedor.
 *
 * Layout en disco (host Android):
 *
 *   filesDir/
 *     ├── alpine-rootfs/        Rootfs Alpine completo (~50 MB descomprimido)
 *     │   ├── bin/ etc/ usr/    ← sistema Alpine
 *     │   └── etc/resolv.conf   ← DNS escrito por nosotros
 *     ├── home/.openclaw/       OPENCLAW_HOME (visto como /data/home/.openclaw)
 *     │   └── tmp/              TMPDIR (fix EACCES link())     *     └── cacheDir/proot-tmp/   PROOT_TMP_DIR (Android no tiene /tmp en el host)
     *
     * Importante: PROOT_TMP_DIR usa cacheDir y NO filesDir porque algunos
     * dispositivos Android imponen restricciones de chmod y enlaces simbólicos
     * en filesDir, lo que provoca "Function not implemented" al ejecutar proot.
 *
 *   nativeLibraryDir/libproot.so    ← binario proot (green-green-avk, ~213 KB)
 *   nativeLibraryDir/libproot_loader.so    ← loader desacoplado (PROOT_UNBUNDLE_LOADER=1, ~5.5 KB)
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
 *   PROOT_LOADER      — ruta al loader desacoplado (necesario para Samsung Knox)
 *
 * El binario proot es de green-green-avk/build-proot-android compilado con
 * PROOT_UNBUNDLE_LOADER=1. Usa --link2symlink y -0 para compatibilidad con
 * Samsung Knox / Android 12+. El loader estático se inyecta en cada nuevo
 * proceso creado por proot, evitando depender del linker del sistema.
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
    val rootfs: File          get() = File(filesDir, "alpine-rootfs").apply { mkdirs() }
    val prootTmpDir: File     get() = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
    val homeDir: File         get() = File(filesDir, "home").apply { mkdirs() }
    val openclawHome: File    get() = File(homeDir, ".openclaw").apply { mkdirs() }
    val openclawTmp: File     get() = File(openclawHome, "tmp").apply { mkdirs() }

    /** Path absoluto del binario proot. Vive en nativeLibraryDir como ELF estático. */
    val proot: String         get() = "$nativeDir/libproot.so"

    // ── Estado ───────────────────────────────────────────────────────────────

    /** El rootfs Alpine está extraído y tiene un `bin/sh` ejecutable. */
    fun isAlpineInstalled(): Boolean =
        File(rootfs, "bin/sh").exists() &&
        File(rootfs, "etc").exists() &&
        File(rootfs, "root").exists()

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
            // ── Limpiar rootfs antiguo completamente ────────────────────────
            // Si hay una instalación previa de Alpine (de la versión antigua
            // que no manejaba symlinks), los archivos viejos se mezclarían con
            // la nueva extracción. Eliminamos todo para partir de cero.
            if (rootfs.exists()) {
                onProgress("Limpiando instalación anterior de Alpine...")
                rootfs.deleteRecursivelySafe()
            }
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

            if (!extractAlpineArchive(tarFile, onProgress, onError)) {
                return false
            }
            tarFile.delete()

            // ── Directorios que proot necesita encontrar en el rootfs ────────
            File(rootfs, "root").mkdirs()      // --cwd=/root
            File(rootfs, "tmp").mkdirs()       // /tmp dentro del rootfs
            File(rootfs, ".l2s").mkdirs()      // directorio para --link2symlink

            // DNS dentro del rootfs (Alpine no trae resolv.conf por defecto)
            File(rootfs, "etc").mkdirs()
            val resolv = File(rootfs, "etc/resolv.conf")
            if (!resolv.exists() || resolv.readText().isBlank()) {
                resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }

            // ── Aplicar +x a todos los binarios (Commons Compress no preserva) ──
            onProgress("Aplicando permisos de ejecución...")
            listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin")
                .map { File(rootfs, it) }
                .filter { it.isDirectory }
                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                .filter { it.isFile }
                .forEach { it.setExecutable(true, false) }

            // Verificación final
            val sh = File(rootfs, "bin/sh")
            if (!sh.exists() || !sh.canExecute()) {
                onError("bin/sh no ejecutable — extracción fallida")
                return false
            }
            onProgress("Alpine verificado ✓")
            true
        } catch (e: Exception) {
            log("downloadAndExtractAlpine failed: ${e.message}")
            onError("Error descargando Alpine: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Extrae el rootfs preservando enlaces del tar. Alpine depende de symlinks
     * críticos como /bin/sh -> busybox y /lib/ld-musl-aarch64.so.1 -> libc.
     */
    fun extractAlpineArchive(
        tarFile: File,
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val pendingLinks = mutableListOf<PendingLink>()

        onProgress("Extrayendo Alpine (~50 MB)…")
        return try {
            FileInputStream(tarFile).use { fis ->
                BufferedInputStream(fis, 64 * 1024).use { bis ->
                    GzipCompressorInputStream(bis).use { gzis ->
                        TarArchiveInputStream(gzis).use { tais ->
                            var entry = tais.nextEntry
                            var extractedCount = 0
                            while (entry != null) {
                                val outputFile = safeRootfsFile(entry.name)
                                if (outputFile == null) {
                                    log("Skipping unsafe tar entry: ${entry.name}")
                                    entry = tais.nextEntry
                                    continue
                                }

                                when {
                                    entry.isDirectory -> outputFile.mkdirs()
                                    entry.isSymbolicLink -> {
                                        createArchiveLink(
                                            outputFile = outputFile,
                                            linkName = entry.linkName,
                                            hardLink = false,
                                            pendingLinks = pendingLinks
                                        )
                                    }
                                    entry.isLink -> {
                                        createArchiveLink(
                                            outputFile = outputFile,
                                            linkName = entry.linkName,
                                            hardLink = true,
                                            pendingLinks = pendingLinks
                                        )
                                    }
                                    else -> {
                                        outputFile.parentFile?.mkdirs()
                                        outputFile.outputStream().use { out ->
                                            val buf = ByteArray(64 * 1024)
                                            var n = tais.read(buf, 0, buf.size)
                                            while (n != -1) {
                                                out.write(buf, 0, n)
                                                n = tais.read(buf, 0, buf.size)
                                            }
                                        }
                                        applyTarFilePermissions(outputFile, entry.mode)
                                    }
                                }

                                extractedCount++
                                if (extractedCount % 500 == 0) {
                                    onProgress("Extrayendo Alpine… (archivo $extractedCount)")
                                }
                                entry = tais.nextEntry
                            }
                        }
                    }
                }
            }

            resolvePendingLinks(pendingLinks)
            true
        } catch (e: Exception) {
            log("tar extraction via commons-compress failed: ${e.message}")
            onError("Error extrayendo Alpine: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    // ── Instalación de Node.js + openclaw dentro de Alpine ───────────────────

    /**
     * Dentro del proot, realiza la instalación completa de OpenClaw:
     *   1. Detecta arquitectura ARM64
     *   2. Verifica/instala Node.js y npm (skip si ya existen)
     *   3. Verifica/instala npm (skip si ya existe)
     *   4. Instala dependencias del sistema (python3, make, g++, libc6-compat, libstdc++)
     *   5. Instala pnpm globalmente via npm
     *   6. Configura PNPM_HOME en .bashrc y .profile para persistencia
     *   7. Verifica versiones de node, npm, pnpm
     *   8. Instala openclaw@beta via pnpm
     *   9. Ejecuta openclaw onboard (con stdin vacío para evitar prompts)
     *  10. Verificación final (openclaw --version)
     *
     * Retorna `true` si todo OK, `false` si falla.
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

        // ── Script de instalación completo (9 pasos) ─────────────────────────
        // Cada paso reporta explícitamente su resultado para saber exactamente dónde falla.
        // No usamos `set -e` porque oculta qué paso falló.
        // La variable PNPM_HOME se añade a .bashrc para persistencia entre sesiones.
        val script = """
            echo "[1/10] Detectando arquitectura..."
            arch=$(uname -m)
            echo "Arquitectura detectada: ${'$'}arch"
            case "${'$'}arch" in
                aarch64|arm64) echo "✓ ARM64 confirmado" ;;
                *) echo "[WARN] Arquitectura no esperada: ${'$'}arch (se esperaba aarch64)" ;;
            esac

            echo "[2/10] Verificando Node.js..."
            if command -v node > /dev/null 2>&1; then
                echo "Node.js $(node --version) encontrado ✓"
            else
                echo "Node.js no encontrado. Instalando..."
                apk update --no-cache 2>&1 || echo "[WARN] apk update falló"
                if ! apk add --no-cache nodejs npm 2>&1; then
                    echo "FALLO:PASO2 error instalando nodejs"
                    exit 1
                fi
            fi

            echo "[3/10] Verificando npm..."
            if command -v npm > /dev/null 2>&1; then
                echo "npm $(npm --version) encontrado ✓"
            else
                echo "npm no encontrado. Instalando Node.js + npm..."
                apk update --no-cache 2>&1 || echo "[WARN] apk update falló"
                if ! apk add --no-cache nodejs npm 2>&1; then
                    echo "FALLO:PASO3 error instalando nodejs+npm"
                    exit 1
                fi
            fi

            echo "[4/10] Instalando dependencias del sistema..."
            apk update --no-cache 2>&1 || echo "[WARN] apk update falló"
            if ! apk add --no-cache \
                python3 make g++ curl git \
                libc6-compat libstdc++ \
                ca-certificates bash 2>&1; then
                echo "FALLO:PASO4 error instalando dependencias del sistema"
                exit 1
            fi
            echo "Dependencias del sistema instaladas ✓"

            echo "[5/10] Instalando pnpm globalmente..."
            if command -v pnpm > /dev/null 2>&1; then
                echo "pnpm $(pnpm --version) ya instalado ✓"
            else
                if ! npm install -g pnpm 2>&1; then
                    echo "FALLO:PASO5 npm install -g pnpm falló"
                    exit 1
                fi
                echo "pnpm $(pnpm --version) instalado ✓"
            fi

            echo "[6/10] Configurando PNPM_HOME en .bashrc..."
            mkdir -p /root/.local/share/pnpm
            export PNPM_HOME="/root/.local/share/pnpm"
            export PATH="${'$'}PNPM_HOME:${'$'}PATH"
            if ! grep -q "PNPM_HOME" /root/.bashrc 2>/dev/null; then
                cat >> /root/.bashrc << 'ENVEOF'
    export PNPM_HOME="/root/.local/share/pnpm"
    export PATH="${'$'}PNPM_HOME:${'$'}PATH"
    ENVEOF
                echo "PNPM_HOME configurado en .bashrc ✓"
            else
                echo "PNPM_HOME ya está en .bashrc ✓"
            fi
            # También en .profile por si el shell login no carga .bashrc
            if ! grep -q "PNPM_HOME" /root/.profile 2>/dev/null; then
                cat >> /root/.profile << 'ENVEOF'
    export PNPM_HOME="/root/.local/share/pnpm"
    export PATH="${'$'}PNPM_HOME:${'$'}PATH"
    ENVEOF
            fi

            echo "[7/10] Verificando versiones..."
            echo "node  => $(node --version 2>/dev/null || echo ERROR)"
            echo "npm   => $(npm --version 2>/dev/null || echo ERROR)"
            echo "pnpm  => $(pnpm --version 2>/dev/null || echo ERROR)"

            echo "[8/10] Instalando OpenClaw (beta) con pnpm..."
            if ! pnpm add -g openclaw@beta 2>&1; then
                echo "FALLO:PASO8 pnpm add -g openclaw@beta falló"
                exit 1
            fi
            echo "OpenClaw beta instalado ✓"

            echo "[9/10] Ejecutando openclaw onboard..."
            if ! echo "" | openclaw onboard 2>&1; then
                echo "FALLO:PASO9 openclaw onboard falló"
                exit 1
            fi
            echo "openclaw onboard completado ✓"

            echo "[10/10] Verificación final..."
            if ! openclaw --version 2>&1; then
                echo "FALLO:PASO10 openclaw --version falló"
                exit 1
            fi
            echo "DONE"
        """.trimIndent()

        // Capturar la última línea FALLO: como mensaje de error específico
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

    /** `pnpm update -g openclaw@beta` dentro del proot. Retorna true si OK. */
    suspend fun updateOpenClaw(onProgress: (String) -> Unit): Boolean {
        val code = runInProot(
            command = listOf("/bin/sh", "-c", "pnpm update -g openclaw@beta && openclaw --version"),
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
     */
    fun startGatewayProcess(extraEnv: Map<String, String> = emptyMap()): Process {
        val pb = buildProotProcess(
            listOf("/bin/sh", "-lc", "openclaw gateway")
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
     * Usa --link2symlink y -0 para compatibilidad con Samsung Knox / Android 12+,
     * binds canónicos, y configura el environment mínimo necesario para
     * que Node/openclaw funcionen.
     */
    fun buildProotProcess(command: List<String>): ProcessBuilder {
        require(command.isNotEmpty()) { "command must not be empty" }

        // Asegurar que /root exista (Alpine minirootfs no lo incluye)
        File(rootfs, "root").mkdirs()
        File(rootfs, ".l2s").mkdirs()  // requerido por --link2symlink

        val args = mutableListOf<String>().apply {
            add(proot)
            add("--link2symlink")                          // fix symlinks Alpine en filesDir Android
            add("-0")                                      // fake root compatible Samsung/Android 12+
            add("--rootfs=${rootfs.absolutePath}")
            add("--bind=/proc")
            add("--bind=/dev")
            add("--bind=/sys")
            add("--bind=/dev/urandom")
            add("--bind=${filesDir.absolutePath}:/data")
            add("--bind=${prootTmpDir.absolutePath}:/tmp")
            add("--cwd=/root")
            addAll(command)
        }

        return ProcessBuilder(args).apply {
            redirectErrorStream(true)
            environment().apply {
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")
                put("PROOT_TMP_DIR",    prootTmpDir.absolutePath)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_LOADER",     "$nativeDir/libproot_loader.so")
                put("HOME",             "/root")
                put("TMPDIR",           "/data/home/.openclaw/tmp")
                put("PATH",             "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("TERM",             "xterm-256color")
                put("COLORTERM",        "truecolor")
                put("LANG",             "en_US.UTF-8")
                put("LC_ALL",           "en_US.UTF-8")
                put("OPENCLAW_HOME",    "/data/home/.openclaw")
                put("SSL_CERT_FILE",    "/etc/ssl/certs/ca-certificates.crt")
                put("npm_config_cache", "/tmp/npm-cache")
            }
        }
    }

    // ── Terminal PTY: comando y env para TerminalSession ─────────────────────

    /**
     * Argumentos para construir un `TerminalSession`. El primer elemento debe
     * ser argv[0] (el nombre del proceso) para que TerminalSession lo use
     * correctamente como ejecutable.
     */
    fun buildShellCommand(): Array<String> = arrayOf(
        proot,                                           // argv[0]
        "--link2symlink",
        "-0",
        "--rootfs=${rootfs.absolutePath}",
        "--bind=/proc",
        "--bind=/dev",
        "--bind=/sys",
        "--bind=/dev/urandom",
        "--bind=${filesDir.absolutePath}:/data",
        "--bind=${prootTmpDir.absolutePath}:/tmp",
        "--cwd=/data/home/.openclaw",
        "/bin/sh",
        "-i"
    )

    /** Environment array (formato KEY=VALUE) para el terminal interactivo. */
    fun buildShellEnv(): Array<String> = arrayOf(
        "PROOT_TMP_DIR=${prootTmpDir.absolutePath}",
        "PROOT_NO_SECCOMP=1",
        "PROOT_LOADER=$nativeDir/libproot_loader.so",
        "HOME=/root",
        "TMPDIR=/data/home/.openclaw/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=en_US.UTF-8",
        "LC_ALL=en_US.UTF-8",
        "OPENCLAW_HOME=/data/home/.openclaw",
        "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
        "PS1=~ \\$ "
    )

    // ── Uninstall ────────────────────────────────────────────────────────────

    /** Borra todo el rootfs Alpine y los temporales de proot. */
    fun wipeAlpine() {
        rootfs.deleteRecursivelySafe()
        prootTmpDir.deleteRecursivelySafe()
        prootTmpDir.mkdirs()
    }

    private data class PendingLink(
        val outputFile: File,
        val linkName: String,
        val hardLink: Boolean
    )

    private fun safeRootfsFile(entryName: String): File? {
        val safeName = entryName.replace('\\', '/').removePrefix("/")
        if (safeName.isBlank()) return null

        val root = rootfs.toPath().toAbsolutePath().normalize()
        val output = root.resolve(safeName).normalize()
        return if (output.startsWith(root)) output.toFile() else null
    }

    private fun createArchiveLink(
        outputFile: File,
        linkName: String,
        hardLink: Boolean,
        pendingLinks: MutableList<PendingLink>
    ) {
        outputFile.parentFile?.mkdirs()
        Files.deleteIfExists(outputFile.toPath())

        if (hardLink) {
            val targetFile = resolveArchiveLinkTarget(outputFile, linkName)
            if (targetFile == null || !targetFile.exists()) {
                pendingLinks += PendingLink(outputFile, linkName, hardLink = true)
                return
            }

            try {
                Files.createLink(outputFile.toPath(), targetFile.toPath())
            } catch (e: Exception) {
                if (!copyResolvedLinkTarget(outputFile, linkName)) {
                    pendingLinks += PendingLink(outputFile, linkName, hardLink = true)
                }
            }
            return
        }

        val symlinkTarget = symlinkTargetForHost(outputFile, linkName)
        if (symlinkTarget == null) {
            log("Skipping unsafe symlink ${outputFile.name} -> $linkName")
            return
        }

        try {
            Files.createSymbolicLink(outputFile.toPath(), Paths.get(symlinkTarget))
        } catch (e: Exception) {
            if (!copyResolvedLinkTarget(outputFile, linkName)) {
                pendingLinks += PendingLink(outputFile, linkName, hardLink = false)
            }
        }
    }

    private fun resolvePendingLinks(pendingLinks: List<PendingLink>) {
        pendingLinks.forEach { link ->
            if (link.outputFile.exists()) return@forEach

            val resolved = if (link.hardLink) {
                copyResolvedLinkTarget(link.outputFile, link.linkName)
            } else {
                val symlinkTarget = symlinkTargetForHost(link.outputFile, link.linkName)
                if (symlinkTarget != null) {
                    try {
                        link.outputFile.parentFile?.mkdirs()
                        Files.createSymbolicLink(link.outputFile.toPath(), Paths.get(symlinkTarget))
                        true
                    } catch (e: Exception) {
                        copyResolvedLinkTarget(link.outputFile, link.linkName)
                    }
                } else {
                    false
                }
            }

            if (!resolved) {
                log("Unresolved tar link: ${link.outputFile.absolutePath} -> ${link.linkName}")
            }
        }
    }

    private fun symlinkTargetForHost(outputFile: File, linkName: String): String? {
        val targetFile = resolveArchiveLinkTarget(outputFile, linkName) ?: return null
        val parent = outputFile.parentFile?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        return parent.relativize(targetFile.toPath().toAbsolutePath().normalize()).toString()
    }

    private fun resolveArchiveLinkTarget(outputFile: File, linkName: String): File? {
        val root = rootfs.toPath().toAbsolutePath().normalize()
        val parent = outputFile.parentFile?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        val target = if (linkName.startsWith("/")) {
            root.resolve(linkName.removePrefix("/"))
        } else {
            parent.resolve(linkName)
        }.normalize()

        return if (target.startsWith(root)) target.toFile() else null
    }

    private fun copyResolvedLinkTarget(outputFile: File, linkName: String): Boolean {
        val targetFile = resolveArchiveLinkTarget(outputFile, linkName) ?: return false
        if (!targetFile.exists()) return false

        outputFile.parentFile?.mkdirs()
        return try {
            if (targetFile.isDirectory) {
                outputFile.mkdirs()
            } else {
                Files.copy(
                    targetFile.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
                outputFile.setReadable(true, false)
                if (targetFile.canExecute()) {
                    outputFile.setExecutable(true, false)
                }
            }
            true
        } catch (e: Exception) {
            log("Could not materialize tar link ${outputFile.absolutePath} -> $linkName: ${e.message}")
            false
        }
    }

    private fun applyTarFilePermissions(outputFile: File, mode: Int) {
        val ownerExec  = (mode and 0b001_000_000) != 0
        val groupExec  = (mode and 0b000_001_000) != 0
        val otherExec  = (mode and 0b000_000_001) != 0
        val anyExec = ownerExec || groupExec || otherExec
        if (anyExec) {
            outputFile.setExecutable(true, false)
        }
        outputFile.setReadable(true, false)
    }

    // ── Preparación del entorno antes de ejecutar Proot ───────────────────────

    /**
     * Crea todos los directorios críticos en el host Android ANTES de que
     * Proot se ejecute por primera vez.
     *
     * Rutas creadas:
     *   - cacheDir/proot-tmp/           PROOT_TMP_DIR (bind-mount a /tmp dentro del rootfs)
     *   - filesDir/home/                Directorio home en el host
     *   - filesDir/home/.openclaw/      OPENCLAW_HOME (bind-mount a /data/home/.openclaw)
     *   - filesDir/home/.openclaw/tmp/  OPENCLAW_TMP (TMPDIR para Node/NPM)
     *   - filesDir/alpine-rootfs/       Rootfs Alpine
     *   - filesDir/alpine-rootfs/root/  Directorio de trabajo seguro dentro del rootfs (--cwd=/root)
     *   - filesDir/alpine-rootfs/.l2s/  Directorio para --link2symlink
     *
     * Es seguro llamarlo múltiples veces (mkdirs es idempotente).
     */
    fun ensureDirectories() {
        // Acceder a cada propiedad fuerza la creación del directorio
        prootTmpDir
        homeDir
        openclawHome
        openclawTmp
        rootfs
        File(rootfs, "root").mkdirs()
        File(rootfs, ".l2s").mkdirs()
    }

    // ── Utilidades ───────────────────────────────────────────────────────────


    /**
     * Aplica permisos de ejecución a todos los binarios dentro del rootfs Alpine.
     * Safety net: si el bucle de extracción no pudo aplicar permisos por algún motivo
     * (entry.mode = 0, tar corrupto, etc.), este método garantiza que los binarios
     * críticos tengan +x.
     *
     * También maneja el caso de symlinks rotos o copias de archivos linkados:
     * si /bin/sh apunta a busybox pero el symlink no pudo crearse (Android
     * restringe symlinks en ciertos FS), buscamos busybox como archivo regular
     * y hacemos una copia con permisos.
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
                // Si es symlink roto (target no existe o no es ejecutable),
                // materializarlo como copia del target real
                if (Files.isSymbolicLink(f.toPath())) {
                    val target = Files.readSymbolicLink(f.toPath())
                    val targetFile = File(f.parentFile, target.toString())
                    if (!targetFile.exists() || !targetFile.canExecute()) {
                        log("Reparando symlink ${f.absolutePath} -> $target (target no ejecutable)")
                        Files.delete(f.toPath())
                        // Buscar el target real en el rootfs y copiarlo
                        val resolvedTarget = resolveArchiveLinkTarget(f, target.toString())
                        if (resolvedTarget != null && resolvedTarget.exists() && resolvedTarget.canExecute()) {
                            resolvedTarget.copyTo(f, overwrite = true)
                            log("Symlink materializado como copia de ${resolvedTarget.absolutePath}")
                        } else {
                            log("No se pudo resolver target $target para symlink ${f.name}")
                        }
                    }
                }
                f.setExecutable(true, false)
            }
        }
    }

    private fun log(msg: String) {
        OpenClawLogger.init(context)
        OpenClawLogger.log(TAG, msg)
    }
}
