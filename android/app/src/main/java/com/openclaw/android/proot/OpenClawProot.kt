package com.openclaw.android.proot

import android.content.Context
import android.util.Log
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
import java.security.MessageDigest

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

        /** URL oficial del minirootfs ARM64. */
        val ALPINE_URL: String =
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/" +
            "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"

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
            log("Downloading: $ALPINE_URL")

            val conn = (URL(ALPINE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OpenClaw-Android/1.0")
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                onError("HTTP ${conn.responseCode} al descargar Alpine")
                return false
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
                                var entry = tais.nextTarEntry
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
                                        extractedCount++
                                        if (extractedCount % 500 == 0) {
                                            onProgress("Extrayendo Alpine… (archivo $extractedCount)")
                                        }
                                    }
                                    entry = tais.nextTarEntry
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

            // DNS dentro del rootfs (Alpine no trae resolv.conf por defecto)
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/resolv.conf").writeText(
                "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
            )

            onProgress("Alpine instalado correctamente ✓")
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
     *
     * Llama [onProgress] por cada línea de stdout/stderr.
     */
    fun installOpenClaw(
        onProgress: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val script = """
            set -e
            echo "[1/4] Actualizando indice de paquetes (apk update)..."
            apk update --no-cache
            echo "[2/4] Instalando nodejs, npm y ca-certificates..."
            apk add --no-cache nodejs npm ca-certificates
            echo "[3/4] Instalando openclaw via npm..."
            npm install -g openclaw --prefer-offline || npm install -g openclaw
            echo "[4/4] Verificando..."
            node --version
            npm --version
            openclaw --version || true
            echo "DONE"
        """.trimIndent()

        runInProot(
            command = listOf("/bin/sh", "-c", script),
            onOutput = { line ->
                onProgress(line)
                log(line)
            },
            onExit = { code ->
                if (code == 0) onDone() else onError("install falló código $code")
            }
        )
    }

    /** `npm update -g openclaw` dentro del proot. */
    fun updateOpenClaw(
        onProgress: (String) -> Unit,
        onDone: () -> Unit
    ) {
        runInProot(
            command = listOf("/bin/sh", "-c", "npm update -g openclaw && openclaw --version"),
            onOutput = onProgress,
            onExit = { if (it == 0) onDone() }
        )
    }

    /** Ejecuta `openclaw onboard` en el proot, asíncrono. */
    fun runOnboard(
        onProgress: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        runInProot(
            command = listOf("/bin/sh", "-c", "openclaw onboard"),
            onOutput = onProgress,
            onExit = { code ->
                if (code == 0) onDone() else onError("onboard falló código $code")
            }
        )
    }

    /**
     * Arranca el gateway de OpenClaw como `Process` síncrono.
     * El llamador es responsable de leer stdout y manejar el ciclo de vida
     * (idéntico al ProcessBuilder anterior basado en libnode).
     */
    fun startGatewayProcess(extraEnv: Map<String, String> = emptyMap()): Process {
        val pb = buildProotProcess(listOf("/bin/sh", "-lc", "openclaw gateway"))
        if (extraEnv.isNotEmpty()) {
            pb.environment().putAll(extraEnv)
        }
        return pb.start()
    }

    // ── Núcleo: runInProot ───────────────────────────────────────────────────

    /**
     * Ejecuta un comando dentro del Alpine en un thread aparte.
     * Recoge stdout y reporta el exit code via [onExit].
     */
    fun runInProot(
        command: List<String>,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit
    ) {
        Thread({
            val code = try {
                val proc = buildProotProcess(command).start()
                proc.inputStream.bufferedReader().forEachLine { onOutput(it) }
                proc.waitFor()
            } catch (e: Exception) {
                log("proot error: ${e.message}")
                onOutput("[error] ${e.message}")
                -1
            }
            onExit(code)
        }, "OpenClawProot-runner").apply { isDaemon = true }.start()
    }

    /**
     * Construye el ProcessBuilder de proot listo para `.start()`.
     * Aplica binds canónicos, simula uid 0 dentro del rootfs y configura
     * el environment mínimo necesario para que Node/openclaw funcionen.
     */
    fun buildProotProcess(command: List<String>): ProcessBuilder {
        require(command.isNotEmpty()) { "command must not be empty" }
        val dataBindPath = filesDir.absolutePath

        val args = mutableListOf<String>().apply {
            add(proot)
            add("--rootfs=${rootfs.absolutePath}")
            add("--bind=/proc")
            add("--bind=/dev")
            add("--bind=/sys")
            add("--bind=/dev/urandom")
            add("--bind=$dataBindPath:/data")
            add("--bind=${prootTmpDir.absolutePath}:/tmp")
            add("--change-id=0:0")
            add("--cwd=/root")
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

    /** Verifica el SHA-256 de un archivo descargado. */
    fun verifySha256(file: File, expected: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
                .equals(expected, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "sha256 failed for ${file.name}: ${e.message}")
            false
        }
    }

    private fun log(msg: String) {
        OpenClawLogger.init(context)
        OpenClawLogger.log(TAG, msg)
    }
}
