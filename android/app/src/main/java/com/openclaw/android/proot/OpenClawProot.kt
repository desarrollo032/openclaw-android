package com.openclaw.android.proot

import android.content.Context
import android.util.Log as AndroidLog
import com.openclaw.android.OpenClawLogger
import com.openclaw.android.deleteRecursivelySafe
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenClawProot — Fachada principal de la integración proot + Alpine Linux.
 *
 * Orquesta la ejecución de un rootfs Alpine ARM64 usando el binario `proot`
 * estático. Delega responsabilidades a módulos especializados:
 *
 *   - [AlpineDownloader] — descarga y extracción del rootfs
 *   - [TarExtractor]     — extracción segura de archivos tar.gz
 *   - [InstallScript]    — shell script embebido de 12 fases
 *   - [InstallPhaseTracker] — gestión de markers de fases completadas
 *
 * Layout en disco (host Android):
 *
 *   filesDir/
 *     ├── alpine-rootfs/        Rootfs Alpine completo (~50 MB descomprimido)
 *     │   ├── bin/ etc/ usr/    ← sistema Alpine
 *     │   └── etc/resolv.conf   ← DNS escrito por nosotros
 *     ├── home/.openclaw/       OPENCLAW_HOME (bind → /data/home/.openclaw)
 *     │   └── tmp/              TMPDIR (fix EACCES link())
 *     └── cacheDir/proot-tmp/   PROOT_TMP_DIR
 *
 *   nativeLibraryDir/libproot.so        ← binario proot estático (~213 KB)
 *   nativeLibraryDir/libproot_loader.so ← loader desacoplado (~5.5 KB)
 */
class OpenClawProot(private val context: Context) {

    companion object {
        const val TAG = "OpenClawProot"

        /** Rutas del openclaw.mjs dentro del rootfs Alpine (npm global). */
        val OPENCLAW_PATHS_INSIDE_ALPINE = listOf(
            "usr/local/lib/node_modules/openclaw/openclaw.mjs",
            "usr/lib/node_modules/openclaw/openclaw.mjs"
        )
    }

    // ── Rutas en el host Android ─────────────────────────────────────────────

    val filesDir: File        get() = context.filesDir
    val nativeDir: String     get() = context.applicationInfo.nativeLibraryDir ?: ""
    val rootfs: File          get() = File(filesDir, "alpine-rootfs").apply { mkdirs() }
    val prootTmpDir: File     get() = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
    val homeDir: File         get() = File(filesDir, "home").apply { mkdirs() }
    val openclawHome: File    get() = File(homeDir, ".openclaw").apply { mkdirs() }
    val openclawTmp: File     get() = File(openclawHome, "tmp").apply { mkdirs() }

    /** Path absoluto del binario proot. */
    val proot: String         get() = "$nativeDir/libproot.so"

    // ── Módulos delegados ────────────────────────────────────────────────────

    private val downloader by lazy { AlpineDownloader(rootfs, prootTmpDir) }
    val phaseTracker by lazy { InstallPhaseTracker(rootfs) }

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

    // ── Descarga de Alpine (delega a AlpineDownloader) ───────────────────────

    fun downloadAndExtractAlpine(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean = downloader.downloadAndExtract(onProgress, onError)

    // ── Fases completadas (delega a InstallPhaseTracker) ─────────────────────

    fun getCompletedPhases(): Set<String> = phaseTracker.getCompletedPhases()

    fun markPhaseSkipped(phaseKey: String) = phaseTracker.markPhaseSkipped(phaseKey)

    // ── Instalación de OpenClaw (12 fases) ───────────────────────────────────

    /**
     * Ejecuta la instalación completa de OpenClaw en 12 fases dentro del proot.
     * Cada fase es resumible gracias a los marker files.
     */
    suspend fun installOpenClaw(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        // Pre-verificaciones
        if (!verifyProotReadiness(onError)) return false
        configureAlpineForInstall()

        // Sanity check
        onProgress("Verificando proot...")
        if (!runSanityCheck(onProgress, onError)) return false

        // Generar y ejecutar script
        val script = InstallScript.generate()
        var lastErrorLine = "Error sin diagnóstico de paso"

        val code = runInProot(
            command = listOf("/bin/sh", "-c", script),
            onOutput = { line ->
                onProgress(line)
                log(line)
                when {
                    line.startsWith("PHASE:") && line.contains(":error:") -> {
                        val parts = line.removePrefix("PHASE:").split(":", limit = 3)
                        if (parts.size == 3) {
                            lastErrorLine = "${parts[0].uppercase()}: ${parts[2]}"
                        } else {
                            lastErrorLine = line
                        }
                    }
                    line.startsWith("FALLO:") -> {
                        lastErrorLine = line
                    }
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

    /** `npm install -g openclaw@beta` dentro del proot. */
    suspend fun updateOpenClaw(onProgress: (String) -> Unit): Boolean {
        val code = runInProot(
            command = listOf(
                "/bin/sh",
                "-c",
                ". /etc/profile.d/openclaw-node.sh 2>/dev/null || true; npm install -g openclaw@beta --no-audit --no-fund && openclaw --version"
            ),
            onOutput = onProgress
        )
        return code == 0
    }

    /** Ejecuta `openclaw onboard` en el proot. */
    suspend fun runOnboard(onProgress: (String) -> Unit): Boolean {
        val code = runInProot(
            command = listOf("/bin/sh", "-c", "openclaw onboard"),
            onOutput = onProgress
        )
        return code == 0
    }

    /** Arranca el gateway como `Process` síncrono. */
    fun startGatewayProcess(extraEnv: Map<String, String> = emptyMap()): Process {
        val pb = buildProotProcess(listOf("/bin/sh", "-lc", "openclaw gateway"))
        if (extraEnv.isNotEmpty()) {
            pb.environment().putAll(extraEnv)
        }
        return pb.start()
    }

    // ── Núcleo: runInProot ───────────────────────────────────────────────────

    /**
     * Ejecuta un comando dentro del Alpine de forma bloqueante (suspend)
     * y retorna el código de salida.
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
     */
    fun buildProotProcess(command: List<String>): ProcessBuilder {
        require(command.isNotEmpty()) { "command must not be empty" }

        File(rootfs, "root").mkdirs()
        File(rootfs, ".l2s").mkdirs()

        val args = mutableListOf<String>().apply {
            add(proot)
            add("--link2symlink")
            add("--change-id=0:0")
            add("--rootfs=${rootfs.canonicalPath}")
            add("--bind=/proc")
            add("--bind=/dev")
            add("--bind=/sys")
            add("--bind=/dev/urandom")
            add("--bind=${filesDir.canonicalPath}:/data")
            add("--bind=${prootTmpDir.canonicalPath}:/tmp")
            add("--cwd=/")
            addAll(command)
        }

        return ProcessBuilder(args).apply {
            redirectErrorStream(true)
            environment().apply {
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")
                put("PROOT_TMP_DIR",    prootTmpDir.canonicalPath)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_LOADER",     "$nativeDir/libproot_loader.so")
                put("HOME",             "/root")
                put("PWD",              "/root")
                put("TMPDIR",           "/root/tmp")
                put("PATH",             "/usr/local/bin:/usr/local/node/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("ENV",              "/root/.profile")
                put("TERM",             "xterm-256color")
                put("COLORTERM",        "truecolor")
                put("LANG",             "en_US.UTF-8")
                put("LC_ALL",           "en_US.UTF-8")
                put("OPENCLAW_HOME",    "/data/home/.openclaw")
                put("SSL_CERT_FILE",    "/etc/ssl/certs/ca-certificates.crt")
                put("NPM_CONFIG_PREFIX", "/usr/local")
                put("npm_config_prefix", "/usr/local")
                put("npm_config_cache", "/root/tmp/npm-cache")
            }
        }
    }

    // ── Terminal PTY ─────────────────────────────────────────────────────────

    /** Argumentos para construir un `TerminalSession`. */
    fun buildShellCommand(): Array<String> = arrayOf(
        proot,
        "--link2symlink",
        "--change-id=0:0",
        "--rootfs=${rootfs.canonicalPath}",
        "--bind=/proc",
        "--bind=/dev",
        "--bind=/sys",
        "--bind=/dev/urandom",
        "--bind=${filesDir.canonicalPath}:/data",
        "--bind=${prootTmpDir.canonicalPath}:/tmp",
        "--cwd=/data/home/.openclaw",
        "/bin/sh",
        "-i"
    )

    /** Environment array (formato KEY=VALUE) para el terminal interactivo. */
    fun buildShellEnv(): Array<String> = arrayOf(
        "PROOT_TMP_DIR=${prootTmpDir.canonicalPath}",
        "PROOT_NO_SECCOMP=1",
        "PROOT_LOADER=$nativeDir/libproot_loader.so",
        "HOME=/root",
        "PWD=/data/home/.openclaw",
        "TMPDIR=/data/home/.openclaw/tmp",
        "PATH=/usr/local/bin:/usr/local/node/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin",
        "ENV=/root/.profile",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=en_US.UTF-8",
        "LC_ALL=en_US.UTF-8",
        "OPENCLAW_HOME=/data/home/.openclaw",
        "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
        "NPM_CONFIG_PREFIX=/usr/local",
        "npm_config_prefix=/usr/local",
        "npm_config_cache=/root/tmp/npm-cache"
    )

    // ── Limpieza ─────────────────────────────────────────────────────────────

    /** Borra todo el rootfs Alpine y los temporales de proot. */
    fun wipeAlpine() {
        rootfs.deleteRecursivelySafe()
        prootTmpDir.deleteRecursivelySafe()
        prootTmpDir.mkdirs()
    }

    /** Crea todos los directorios críticos en el host Android. */
    fun ensureDirectories() {
        prootTmpDir
        homeDir
        openclawHome
        openclawTmp
        rootfs
        File(rootfs, "root").mkdirs()
        File(rootfs, ".l2s").mkdirs()
    }

    // ── Helpers privados ─────────────────────────────────────────────────────

    private fun verifyProotReadiness(onError: (String) -> Unit): Boolean {
        val prootFile = File(proot)
        if (!prootFile.exists()) {
            onError("libproot.so no encontrado en $proot")
            return false
        }
        if (!prootFile.canExecute()) {
            AndroidLog.w(TAG, "libproot.so no es ejecutable — corrigiendo...")
            prootFile.setExecutable(true, false)
        }

        val hasApk = File(rootfs, "sbin/apk").exists() ||
                     File(rootfs, "usr/bin/apk").exists() ||
                     File(rootfs, "bin/apk").exists()
        if (!hasApk) {
            onError("Alpine incompleto — falta binario apk. Reinstalar Alpine.")
            return false
        }
        return true
    }

    private fun configureAlpineForInstall() {
        // DNS
        val resolv = File(rootfs, "etc/resolv.conf")
        if (!resolv.exists() || resolv.readText().isBlank()) {
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 208.67.222.222\nnameserver 9.9.9.9\n")
            AndroidLog.i(TAG, "resolv.conf re-escrito antes de instalar")
        }

        // Repositorios APK
        val apkVersionBranch = "v" + AlpineDownloader.ALPINE_VERSION.substringBeforeLast('.')
        val repos = File(rootfs, "etc/apk/repositories")
        repos.parentFile?.mkdirs()
        repos.writeText(
            "https://dl-cdn.alpinelinux.org/alpine/$apkVersionBranch/main\n" +
            "https://dl-cdn.alpinelinux.org/alpine/$apkVersionBranch/community\n"
        )
        AndroidLog.i(TAG, "/etc/apk/repositories escrito (branch=$apkVersionBranch)")

        // Directorios de apk
        File(rootfs, "var/cache/apk").mkdirs()
        File(rootfs, "tmp").mkdirs()
    }

    private suspend fun runSanityCheck(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val sanityOutput = StringBuilder()
        val sanityCode = runInProot(
            command = listOf("/bin/sh", "-c", "echo proot_sanity_ok"),
            onOutput = { line ->
                sanityOutput.appendLine(line)
                AndroidLog.v(TAG, "[sanity] $line")
            }
        )

        if (sanityCode != 0) {
            val prootError = sanityOutput.toString().trim()
                .ifBlank { "(sin output — proot no produjo mensaje de error)" }
            onError("proot falló (exit=$sanityCode): $prootError")
            return false
        }
        AndroidLog.i(TAG, "Sanity check OK — proot puede ejecutar /bin/sh")
        onProgress("Verificación de proot OK ✓")
        return true
    }

    private fun log(msg: String) {
        OpenClawLogger.init(context)
        OpenClawLogger.log(TAG, msg)
    }
}
