package com.openclaw.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * WebView → Kotlin bridge via @JavascriptInterface (§2.6).
 * All methods callable from JavaScript as window.OpenClaw.<method>().
 * All return values are JSON strings. Async operations use EventBridge (§2.8).
 */
@Suppress("TooManyFunctions", "LargeClass") // WebView bridge — single facade by design
class JsBridge(
    private val activity: MainActivity,
    private val sessionManager: TerminalSessionManager,
    private val installerManager: InstallerManager,
    private val eventBridge: EventBridge,
) {
    private val gson = Gson()

    // Shared IO dispatcher — avoids spawning a new thread pool per coroutine
    private val ioScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /** Cancel all pending coroutines — call from MainActivity.onDestroy(). */
    fun cancel() = ioScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

    companion object {
        private const val TAG = "JsBridge"
        private const val SHELL_INIT_DELAY_MS = 500L
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private const val PLATFORM_LIST_TIMEOUT_MS = 10_000L
        private const val API_TIMEOUT_MS = 5000
        private const val PROGRESS_START = 0f
        private const val PROGRESS_HALF = 0.5f
        private const val PROGRESS_DONE = 1f
        private const val PROGRESS_DOWNLOAD = 0.2f
        private const val PROGRESS_EXTRACT = 0.6f
        private const val PROGRESS_APPLY = 0.9f
        private const val PROGRESS_BOOTSTRAP_START = 0.1f
        private const val PROGRESS_VERIFY = 0.4f
    }

    /**
     * Launch a coroutine on the shared IO scope with structured error handling.
     * Catches all exceptions to prevent app crashes from unhandled coroutine failures.
     * Errors are logged and emitted to the WebView via EventBridge.
     */
    private fun launchWithErrorHandling(
        errorEventType: String = "error",
        errorContext: Map<String, Any?> = emptyMap(),
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val handler =
            CoroutineExceptionHandler { _, throwable ->
                AppLogger.e(TAG, "Coroutine error [$errorEventType]: ${throwable.message}", throwable)
                eventBridge.emit(
                    errorEventType,
                    errorContext +
                        mapOf(
                            "error" to (throwable.message ?: "Unknown error"),
                            "progress" to PROGRESS_START,
                            "message" to "Error: ${throwable.message}",
                        ),
                )
            }
        ioScope.launch(handler, block = block)
    }
    // ═══════════════════════════════════════════
    // Terminal domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun showTerminal() {
        // Create session if none exists (e.g., after first-time setup)
        if (sessionManager.activeSession == null) {
            val session = sessionManager.createSession()
            if (!installerManager.isInstalled()) {
                // If not installed, we can't really do much in the terminal,
                // but we could trigger the install or show a message.
                session.write("echo 'Entorno no instalado. Por favor, usa el Dashboard para instalar.'\n")
            }
        }
        activity.showTerminal()
    }

    @JavascriptInterface
    fun showWebView() = activity.showWebView()

    @JavascriptInterface
    fun createSession(): String {
        val session = sessionManager.createSession()
        return gson.toJson(mapOf("id" to session.mHandle, "name" to (session.title ?: "Terminal")))
    }

    @JavascriptInterface
    fun switchSession(id: String) =
        activity.runOnUiThread {
            sessionManager.switchSession(id)
        }

    @JavascriptInterface
    fun closeSession(id: String) {
        sessionManager.closeSession(id)
    }

    @JavascriptInterface
    fun getTerminalSessions(): String = gson.toJson(sessionManager.getSessionsInfo())

    @JavascriptInterface
    fun writeToTerminal(
        id: String,
        data: String,
    ) {
        val session =
            if (id.isBlank()) {
                sessionManager.activeSession
            } else {
                sessionManager.getSessionById(id) ?: sessionManager.activeSession
            }
        session?.write(data)
    }

    @JavascriptInterface
    fun runInNewSession(command: String) {
        val session = sessionManager.createSession()
        activity.showTerminal()
        // Delay write until shell process initializes (same pattern as showTerminal post-setup)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            session.write(command)
        }, SHELL_INIT_DELAY_MS)
    }

    // ═══════════════════════════════════════════
    // Setup domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getSetupStatus(): String {
        val installer = InstallerManager(activity)
        val isInstalled = installer.isInstalled()
        val setupManager = SetupManager(activity)

        return gson.toJson(mapOf(
            "bootstrapInstalled" to isInstalled,
            "runtimeInstalled" to isInstalled,
            "wwwInstalled" to isInstalled,
            "platformInstalled" to isInstalled,
            "source" to if (File(activity.filesDir, ".proot-installed").exists()) "proot" else "payload",
            "prootReady" to ProotManager.isProotReady(activity),
            "rootfsReady" to ProotManager.isRootfsReady(activity),
            "openclawReady" to setupManager.isOpenClawInstalledInRootfs(),
        ))
    }

    @JavascriptInterface
    fun getBootstrapStatus(): String {
        val installer = InstallerManager(activity)
        val installed = installer.isInstalled()
        val (prefix, home) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)

        return gson.toJson(
            mapOf(
                "installed" to installed,
                "openclawInstalled" to installed,
                "prefixPath" to prefix,
                "homePath" to home,
                "source" to "payload"
            ),
        )
    }

    @JavascriptInterface
    fun getAppFilesDir(): String =
        gson.toJson(
            mapOf(
                "filesDir"  to activity.filesDir.absolutePath,
                "prefix"    to EnvironmentBuilder.resolveActivePaths(activity.filesDir).first,
                "home"      to EnvironmentBuilder.resolveActivePaths(activity.filesDir).second,
            ),
        )

    @JavascriptInterface
    fun startSetup(mode: String = "auto") {
        activity.runOnUiThread {
            activity.startInstallFromUi(mode) { success ->
                AppLogger.i("JsBridge", "Setup finished: success=$success")
            }
        }
    }

    /**
     * Install from pre-built rootfs asset (no network, no apt/pkg).
     * Emits setup_progress events identical to startSetup() so the UI
     * can use the same progress handler for both flows.
     */
    @JavascriptInterface
    fun startRootfsInstall() {
        val rootfsManager = RootfsManager(activity)
        launchWithErrorHandling(
            errorEventType = "setup_progress",
            errorContext = mapOf("progress" to PROGRESS_START),
        ) {
            rootfsManager.install { progress, message ->
                eventBridge.emit(
                    "setup_progress",
                    mapOf("progress" to progress, "message" to message),
                )
            }
            // After rootfs install, show terminal and launch gateway
            activity.runOnUiThread { activity.showTerminal() }
            val session = sessionManager.createSession()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val startScript = File(activity.filesDir, "home/openclaw-start.sh")
                session.write("${startScript.absolutePath}\n")
            }, SHELL_INIT_DELAY_MS)
        }
    }

    /**
     * Returns rootfs installation status (pre-built rootfs flow).
     */
    @JavascriptInterface
    fun getRootfsStatus(): String {
        val rootfsManager = RootfsManager(activity)
        return gson.toJson(rootfsManager.getStatus())
    }

    /**
     * Install from self-contained payload (assets/payload/).
     * Fully offline — no network, no Termux dependency.
     *
     * Delegates to MainActivity.startInstallFromUi() which:
     *   1. Shows the install progress overlay (not shell-based)
     *   2. Runs InstallerManager (decides offline vs online)
     *   3. Validates before writing .installed marker
     *   4. Calls back on completion
     *
     * This prevents the old bug where this method ran a SEPARATE install
     * that called prefix.deleteRecursively(), wiping files from a previous
     * terminal-based installation.
     */
    @JavascriptInterface
    fun startPayloadInstall() {
        activity.runOnUiThread {
            activity.startInstallFromUi { success ->
                if (success) {
                    // Emit success event to WebView (in case it's listening)
                    eventBridge.emit(
                        "setup_progress",
                        mapOf("progress" to 1.0f, "message" to "Instalación completada"),
                    )
                    // Show terminal and run gateway
                    activity.showTerminal()
                    val session = sessionManager.createSession()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val installer = InstallerManager(activity)
                        val runFile = installer.getRunScriptPath()
                        if (runFile.exists()) {
                            runFile.setExecutable(true)
                            session.write("${runFile.absolutePath}\n")
                        } else {
                            session.write("echo 'Instalación completada. Escribe openclaw para iniciar.'\n")
                        }
                    }, SHELL_INIT_DELAY_MS)
                } else {
                    // Error is already shown in the overlay
                    eventBridge.emit(
                        "setup_progress",
                        mapOf("progress" to 0f, "message" to "Error en la instalación"),
                    )
                }
            }
        }
    }

    /**
     * Returns payload installation status.
     */
    @JavascriptInterface
    fun getPayloadStatus(): String {
        val payloadManager = PayloadManager(activity)
        return gson.toJson(payloadManager.getStatus())
    }

    /**
     * Returns true if the APK contains a bundled payload asset.
     */
    @JavascriptInterface
    fun hasPayloadAsset(): String {
        val installer = InstallerManager(activity)
        return gson.toJson(mapOf("hasPayload" to installer.hasPayloadAsset()))
    }

    @JavascriptInterface
    fun pickPayloadFile() {
        activity.runOnUiThread {
            activity.pickPayloadFile()
        }
    }

    /**
     * Retorna el estado de glibc y versiones instaladas para la UI.
     * Detecta: versión de OpenClaw, Node.js, y estado de glibc.
     */
    @JavascriptInterface
    fun getGlibcStatus(): String {
        val installer = InstallerManager(activity)
        val homeDir = installer.getHomeDir()
        val payloadDir = listOf(
            java.io.File(homeDir, "payload"),
            java.io.File(homeDir, "openclaw-payload"),
            java.io.File(activity.filesDir, "payload"),
            java.io.File(activity.filesDir, "openclaw-payload"),
        ).firstOrNull { it.isDirectory }

        val ldso = payloadDir?.let { java.io.File(it, "glibc/lib/ld-linux-aarch64.so.1") }
        val nodeInGlibc = payloadDir?.let { java.io.File(it, "glibc/bin/node") }
        val ocMjs = payloadDir?.let { java.io.File(it, "openclaw/openclaw.mjs") }

        return gson.toJson(mapOf(
            "installed" to (ldso?.exists() == true && (ldso.length()) > 100_000),
            "linkerPath" to (ldso?.absolutePath ?: ""),
            "linkerSize" to (ldso?.length() ?: 0L),
            "nodeInGlibc" to (nodeInGlibc?.exists() == true),
            "openclawMjs" to (ocMjs?.exists() == true),
            "payloadDir" to (payloadDir?.absolutePath ?: "not found"),
            "manualInstallPath" to homeDir.absolutePath,
        ))
    }

    /**
     * Retorna las versiones instaladas de OpenClaw, Node.js y npm.
     * Lee desde package.json (sin ejecutar nada) para ser rápido y seguro.
     */
    @JavascriptInterface
    fun getVersionInfo(): String {
        val installer = InstallerManager(activity)
        val homeDir = installer.getHomeDir()

        val payloadDir = listOf(
            java.io.File(homeDir, "payload"),
            java.io.File(homeDir, "openclaw-payload"),
            java.io.File(activity.filesDir, "payload"),
            java.io.File(activity.filesDir, "openclaw-payload"),
        ).firstOrNull { it.isDirectory }

        // OpenClaw version desde package.json
        val openclawVersion = try {
            val pkg = payloadDir?.let { java.io.File(it, "openclaw/package.json") }
            if (pkg?.exists() == true) {
                val match = Regex(""""version"\s*:\s*"([^"]+)"""").find(pkg.readText())
                match?.groupValues?.getOrNull(1) ?: "unknown"
            } else "not installed"
        } catch (_: Exception) { "unknown" }

        // Node version desde installed.json (escrito durante instalación)
        val ocaDir = java.io.File(homeDir, ".openclaw-android")
        val installedJson = java.io.File(ocaDir, "installed.json")
        var nodeVersion = "unknown"
        var installedAt = ""
        var source = "unknown"
        if (installedJson.exists()) {
            try {
                val content = installedJson.readText()
                nodeVersion = Regex(""""nodeVersion"\s*:\s*"([^"]+)"""")
                    .find(content)?.groupValues?.getOrNull(1) ?: "unknown"
                installedAt = Regex(""""installedAt"\s*:\s*(\d+)""")
                    .find(content)?.groupValues?.getOrNull(1) ?: ""
                source = Regex(""""source"\s*:\s*"([^"]+)"""")
                    .find(content)?.groupValues?.getOrNull(1) ?: "unknown"
            } catch (_: Exception) {}
        }

        // npm version desde node_modules/npm/package.json
        val npmVersion = try {
            val npmPkg = payloadDir?.let {
                java.io.File(it, "glibc/lib/node_modules/npm/package.json")
            }
            if (npmPkg?.exists() == true) {
                val match = Regex(""""version"\s*:\s*"([^"]+)"""").find(npmPkg.readText())
                match?.groupValues?.getOrNull(1) ?: "unknown"
            } else "unknown"
        } catch (_: Exception) { "unknown" }

        // glibc version desde ld-linux size (proxy)
        val ldso = payloadDir?.let { java.io.File(it, "glibc/lib/ld-linux-aarch64.so.1") }
        val glibcOk = ldso?.exists() == true && (ldso.length()) > 100_000

        return gson.toJson(mapOf(
            "openclaw" to mapOf(
                "version" to openclawVersion,
                "installed" to (openclawVersion != "not installed"),
                "path" to (payloadDir?.let { "${it.absolutePath}/openclaw/openclaw.mjs" } ?: ""),
            ),
            "node" to mapOf(
                "version" to nodeVersion,
                "installed" to (nodeVersion != "unknown"),
                "path" to (payloadDir?.let { "${it.absolutePath}/glibc/bin/node" } ?: ""),
            ),
            "npm" to mapOf(
                "version" to npmVersion,
                "installed" to (npmVersion != "unknown"),
            ),
            "glibc" to mapOf(
                "ok" to glibcOk,
                "linkerPath" to (ldso?.absolutePath ?: ""),
                "linkerSize" to (ldso?.length() ?: 0L),
            ),
            "installedAt" to installedAt,
            "source" to source,
            "payloadDir" to (payloadDir?.absolutePath ?: ""),
        ))
    }

    /**
     * Instala glibc desde un archivo externo seleccionado por el usuario.
     * El usuario debe colocar glibc-aarch64.tar.xz en:
     *   - Descargas del teléfono, o
     *   - ${homeDir}/glibc-aarch64.tar.xz
     * y luego llamar este método.
     *
     * También acepta el archivo seleccionado via pickGlibcFile().
     */
    @JavascriptInterface
    fun installGlibcManually() {
        launchWithErrorHandling(errorEventType = "glibc_install") {
            val installer = InstallerManager(activity)
            val homeDir = installer.getHomeDir()

            // Buscar el archivo en ubicaciones conocidas
            val candidates = listOf(
                java.io.File(homeDir, "glibc-aarch64.tar.xz"),
                java.io.File(activity.filesDir, "glibc-aarch64.tar.xz"),
                java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS),
                    "glibc-aarch64.tar.xz"
                ),
            )

            val archive = candidates.firstOrNull { it.exists() && it.length() > 10_000 }
            if (archive == null) {
                eventBridge.emit("glibc_install", mapOf(
                    "success" to false,
                    "error" to "glibc-aarch64.tar.xz no encontrado. Colócalo en: ${homeDir.absolutePath}",
                    "searchedPaths" to candidates.map { it.absolutePath },
                ))
                return@launchWithErrorHandling
            }

            eventBridge.emit("glibc_install", mapOf(
                "success" to null,
                "message" to "Instalando glibc desde ${archive.absolutePath}...",
            ))

            val ok = installer.installGlibcFromFile(archive)
            eventBridge.emit("glibc_install", mapOf(
                "success" to ok,
                "message" to if (ok) "glibc instalado correctamente" else "Error instalando glibc",
                "linkerPath" to "${installer.getPrefixDir().absolutePath}/glibc/lib/ld-linux-aarch64.so.1",
            ))
        }
    }

    /**
     * Abre el selector de archivos para que el usuario elija glibc-aarch64.tar.xz manualmente.
     * Después de seleccionar, llama installGlibcManually() automáticamente.
     */
    @JavascriptInterface
    fun pickGlibcFile() {
        activity.runOnUiThread {
            activity.pickGlibcFile()
        }
    }

    @JavascriptInterface
    fun saveInstallPath(path: String) {
        // "local" = app-local filesDir/usr, "termux" = /data/data/com.termux/files/usr
        activity.getSharedPreferences("openclaw", 0)
            .edit()
            .putString("install_path", path)
            .apply()
    }

    @JavascriptInterface
    fun saveToolSelections(json: String) {
        val configFile = java.io.File(installerManager.getHomeDir(), ".openclaw-android/tool-selections.conf")
        configFile.parentFile?.mkdirs()
        val selections = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return
        val lines =
            selections.entries.joinToString("\n") { (key, value) ->
                val envKey = "INSTALL_${(key as String).uppercase().replace("-", "_")}"
                "$envKey=$value"
            }
        configFile.writeText(lines + "\n")
    }

    // ═══════════════════════════════════════════
    // Platform domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getAvailablePlatforms(): String {
        // Read from cached config.json or return defaults
        return gson.toJson(
            listOf(
                mapOf(
                    "id" to "openclaw",
                    "name" to "OpenClaw",
                    "icon" to "/openclaw.svg",
                    "desc" to "AI agent platform",
                ),
            ),
        )
    }

    @JavascriptInterface
    fun getInstalledPlatforms(): String {
        // Check which platforms are installed via npm/filesystem
        val env = CommandRunner.buildTermuxEnv(activity)
        val result =
            CommandRunner.runSync(
                "npm list -g --depth=0 --json 2>/dev/null",
                env,
                installerManager.getPrefixDir(),
                timeoutMs = PLATFORM_LIST_TIMEOUT_MS,
            )
        return result.stdout.ifBlank { "[]" }
    }

    @JavascriptInterface
    fun installPlatform(id: String) {
        val safeId = id.replace(Regex("[^a-zA-Z0-9@/_.-]"), "")
        launchWithErrorHandling(
            errorEventType = "install_progress",
            errorContext = mapOf("target" to safeId),
        ) {
            if (safeId == "openclaw") {
                val (ok, error) = OpenClawManager(activity).installOrUpdate { percent, message ->
                    eventBridge.emit(
                        "install_progress",
                        mapOf("target" to safeId, "progress" to percent / 100f, "message" to message),
                    )
                }
                if (!ok) {
                    eventBridge.emit(
                        "install_progress",
                        mapOf(
                            "target" to safeId,
                            "progress" to PROGRESS_START,
                            "message" to (error ?: "OpenClaw install failed"),
                        ),
                    )
                }
                return@launchWithErrorHandling
            }

            val env = CommandRunner.buildTermuxEnv(activity)
            val (_, activeHome) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
            val workDir = java.io.File(activeHome).also { it.mkdirs() }
            val packageName = "$safeId@latest"

            eventBridge.emit(
                "install_progress",
                mapOf("target" to safeId, "progress" to PROGRESS_START, "message" to "Installing $safeId..."),
            )
            val result = CommandRunner.runStreaming(
                "npm install -g $packageName --ignore-scripts --no-fund --no-audit",
                env,
                workDir,
            ) { output: String ->
                eventBridge.emit(
                    "install_progress",
                    mapOf("target" to safeId, "progress" to PROGRESS_HALF, "message" to output),
                )
            }
            if (result.exitCode != 0) {
                eventBridge.emit(
                    "install_progress",
                    mapOf("target" to safeId, "progress" to PROGRESS_START, "message" to result.stderr),
                )
                return@launchWithErrorHandling
            }

            eventBridge.emit(
                "install_progress",
                mapOf("target" to safeId, "progress" to PROGRESS_DONE, "message" to "$safeId installed"),
            )
        }
    }

    @JavascriptInterface
    fun uninstallPlatform(id: String) {
        launchWithErrorHandling(
            errorEventType = "install_progress",
            errorContext = mapOf("target" to id),
        ) {
            val env = CommandRunner.buildTermuxEnv(activity)
            val (_, activeHome) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
            CommandRunner.runSync("npm uninstall -g $id", env, java.io.File(activeHome))
        }
    }

    @JavascriptInterface
    fun switchPlatform(id: String) {
        // Write active platform marker
        val markerFile = java.io.File(installerManager.getHomeDir(), ".openclaw-android/.platform")
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(id)
    }

    @JavascriptInterface
    fun getActivePlatform(): String {
        val markerFile = java.io.File(installerManager.getHomeDir(), ".openclaw-android/.platform")
        val id = if (markerFile.exists()) markerFile.readText().trim() else "openclaw"
        return gson.toJson(mapOf("id" to id, "name" to id.replaceFirstChar { it.uppercase() }))
    }

    // ═══════════════════════════════════════════
    // Tools domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getInstalledTools(): String {
        // Check both the app-local prefix and the real Termux prefix
        val (activePrefix, _) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
        val termuxPrefix = CommandRunner.TERMUX_PREFIX
        val prefixes = linkedSetOf(activePrefix, termuxPrefix) // dedup, local first

        val tools = mutableListOf<Map<String, String>>()
        val seen = mutableSetOf<String>()

        for (prefix in prefixes) {
            // Pre-fetch directory contents to minimize I/O calls
            val binDirContents = java.io.File("$prefix/bin").list()?.toSet() ?: emptySet()

            // Termux packages — check binary path
            val pkgChecks = mapOf(
                "tmux" to "tmux",
                "ttyd" to "ttyd",
                "dufs" to "dufs",
                "openssh-server" to "sshd",
                "android-tools" to "adb",
                "code-server" to "code-server",
            )
            for ((id, bin) in pkgChecks) {
                if (!seen.contains(id) && binDirContents.contains(bin)) {
                    tools.add(mapOf("id" to id, "name" to id, "version" to "installed"))
                    seen.add(id)
                }
            }

            // Chromium — check multiple possible paths
            if (!seen.contains("chromium") &&
                (binDirContents.contains("chromium-browser") ||
                    binDirContents.contains("chromium"))
            ) {
                tools.add(mapOf("id" to "chromium", "name" to "chromium", "version" to "installed"))
                seen.add("chromium")
            }
        }

        // npm global packages — check in both openclaw bin dirs
        val (activeHome, _) = EnvironmentBuilder.resolveActivePaths(activity.filesDir).let {
            Pair(it.second, it.first)
        }
        val binDirs = linkedSetOf(
            "$activeHome/.openclaw-android/bin",
            CommandRunner.OPENCLAW_BIN,
        )
        val binDirContentsMap = binDirs.associateWith { dir ->
            java.io.File(dir).list()?.toSet() ?: emptySet()
        }
        val npmBinChecks = mapOf(
            "claude-code" to "claude",
            "gemini-cli" to "gemini",
            "codex-cli" to "codex",
            "opencode" to "opencode",
        )
        for ((id, bin) in npmBinChecks) {
            if (!seen.contains(id)) {
                for (binDir in binDirs) {
                    if (binDirContentsMap[binDir]?.contains(bin) == true) {
                        tools.add(mapOf("id" to id, "name" to id, "version" to "installed"))
                        seen.add(id)
                        break
                    }
                }
            }
        }

        return gson.toJson(tools)
    }

    /**
     * Returns detected versions of core runtime components (node, git, openclaw).
     * Checks both the app-local prefix and the real Termux prefix.
     */
    @JavascriptInterface
    fun getEnvironmentInfo(): String {
        val env = CommandRunner.buildTermuxEnv(activity)
        val (activePrefix, activeHome) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
        val homeDir = java.io.File(activeHome)

        fun runV(cmd: String): String {
            val r = CommandRunner.runSync(cmd, env, homeDir, timeoutMs = 5_000)
            return r.stdout.trim().ifEmpty { r.stderr.trim() }
        }

        val nodeRaw = runV("node -v 2>/dev/null || node --version 2>/dev/null")
        val gitRaw = runV("git --version 2>/dev/null")
        val ocRaw = runV("openclaw --version 2>/dev/null")

        // Resolve which prefix is active
        // (already resolved above)

        return gson.toJson(mapOf(
            "node" to mapOf(
                "version" to nodeRaw.ifEmpty { null },
                "detected" to nodeRaw.isNotEmpty(),
                "path" to "$activeHome/.openclaw-android/bin/node",
            ),
            "git" to mapOf(
                "version" to gitRaw.replace("git version ", "").ifEmpty { null },
                "detected" to gitRaw.isNotEmpty(),
                "path" to "$activePrefix/bin/git",
            ),
            "openclaw" to mapOf(
                "version" to ocRaw.ifEmpty { null },
                "detected" to ocRaw.isNotEmpty(),
                "path" to "$activePrefix/bin/openclaw",
            ),
            "prefix" to activePrefix,
            "home" to activeHome,
        ))
    }

    @JavascriptInterface
    fun installTool(id: String) {
        launchWithErrorHandling(
            errorEventType = "install_progress",
            errorContext = mapOf("target" to id),
        ) {
            val env = CommandRunner.buildTermuxEnv(activity)
            val (prefix, activeHome) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
            val aptGet =
                "DEBIAN_FRONTEND=noninteractive $prefix/bin/apt-get" +
                    " -y -o Acquire::AllowInsecureRepositories=true" +
                    " -o APT::Get::AllowUnauthenticated=true"
            val cmd =
                when (id) {
                    // Termux packages (apt-get)
                    "tmux", "ttyd", "dufs", "openssh-server", "android-tools" ->
                        "$aptGet install ${if (id == "openssh-server") "openssh" else id}"
                    // Chromium (from x11-repo)
                    "chromium" ->
                        "$aptGet install chromium"
                    // code-server (custom)
                    "code-server" ->
                        "npm install -g code-server"
                    // npm-based AI CLI tools — use grun node, never node directly
                    "claude-code" ->
                        "npm install -g @anthropic-ai/claude-code"
                    "gemini-cli" ->
                        "npm install -g @google/gemini-cli"
                    "codex-cli" ->
                        "npm install -g @mmmbuto/codex-cli-termux"
                    // OpenCode (Bun-based) — requires proot + ld.so concatenation
                    "opencode" ->
                        "curl -fsSL https://raw.githubusercontent.com/" +
                            "AidanPark/openclaw-android/main/scripts/install-opencode.sh | bash"
                    else -> "echo 'Unknown tool: $id'"
                }
            eventBridge.emit(
                "install_progress",
                mapOf("target" to id, "progress" to PROGRESS_START, "message" to "Installing $id..."),
            )
            CommandRunner.runStreaming(cmd, env, java.io.File(activeHome)) { output: String ->
                eventBridge.emit(
                    "install_progress",
                    mapOf("target" to id, "progress" to PROGRESS_HALF, "message" to output),
                )
            }
            eventBridge.emit(
                "install_progress",
                mapOf("target" to id, "progress" to PROGRESS_DONE, "message" to "$id installed"),
            )
        }
    }

    @JavascriptInterface
    fun uninstallTool(id: String) {
        launchWithErrorHandling(
            errorEventType = "install_progress",
            errorContext = mapOf("target" to id),
        ) {
            val env = CommandRunner.buildTermuxEnv(activity)
            val (prefix, activeHome) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
            val cmd =
                when (id) {
                    "tmux", "ttyd", "dufs", "openssh-server", "android-tools", "chromium" -> {
                        val pkg = if (id == "openssh-server") "openssh" else id
                        "$prefix/bin/apt-get remove -y $pkg"
                    }
                    "code-server" ->
                        "npm uninstall -g code-server"
                    "claude-code" ->
                        "npm uninstall -g @anthropic-ai/claude-code"
                    "gemini-cli" ->
                        "npm uninstall -g @google/gemini-cli"
                    "codex-cli" ->
                        "npm uninstall -g @mmmbuto/codex-cli-termux"
                    "opencode" ->
                        "rm -f \$PREFIX/bin/opencode" +
                            " \$HOME/.openclaw-android/bin/ld.so.opencode" +
                            " \$PREFIX/tmp/ld.so.opencode" +
                            " && rm -rf \$HOME/.config/opencode"
                    else -> "echo 'Unknown tool: $id'"
                }
            CommandRunner.runSync(cmd, env, java.io.File(activeHome))
        }
    }

    @JavascriptInterface
    fun isToolInstalled(id: String): String {
        val (prefix, activeHome) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
        val env = CommandRunner.buildTermuxEnv(activity)
        val exists =
            when (id) {
                "openssh-server" -> java.io.File("$prefix/bin/sshd").exists()
                "tmux", "ttyd", "dufs", "android-tools" -> {
                    val bin = if (id == "android-tools") "adb" else id
                    java.io.File("$prefix/bin/$bin").exists()
                }
                "chromium" -> {
                    java.io.File("$prefix/bin/chromium-browser").exists() ||
                        java.io.File("$prefix/bin/chromium").exists()
                }
                "code-server" -> java.io.File("$prefix/bin/code-server").exists()
                else -> {
                    // npm global packages: check via command -v
                    // Sanitize id to prevent shell injection (allow only alphanumeric, dash, @, /)
                    val safeId = id.replace(Regex("[^a-zA-Z0-9@/_.-]"), "")
                    val result =
                        CommandRunner.runSync(
                            "command -v $safeId 2>/dev/null",
                            env,
                            java.io.File(activeHome),
                            timeoutMs = COMMAND_TIMEOUT_MS,
                        )
                    result.stdout.trim().isNotEmpty()
                }
            }
        return gson.toJson(mapOf("installed" to exists))
    }

    // ═══════════════════════════════════════════
    // Commands domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun runCommand(cmd: String): String {
        val env = CommandRunner.buildTermuxEnv(activity)
        val (_, activeHome) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
        val result = CommandRunner.runSync(cmd, env, java.io.File(activeHome))
        return gson.toJson(result)
    }

    /**
     * Test grun node availability — minimum viability check.
     * Returns result of: bash -l -c "grun node -v"
     */
    @JavascriptInterface
    fun testGrunNode(): String {
        val env = CommandRunner.buildTermuxEnv(activity)
        val result = CommandRunner.runSync(
            "grun node -v",
            env,
            java.io.File(EnvironmentBuilder.resolveActivePaths(activity.filesDir).second),
            timeoutMs = 10_000,
        )
        return gson.toJson(result)
    }

    /**
     * Launch OpenClaw gateway using the wrapper script.
     * Uses the app-local runtime — no dependency on Termux.
     */
    @JavascriptInterface
    fun launchGateway() {
        launchWithErrorHandling(errorEventType = "gateway_status") {
            CommandRunner.createWrapperScript(activity.filesDir)
            activity.runOnUiThread {
                activity.showTerminal()
                val session = sessionManager.createSession()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val (prefix, home) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
                    val startScript = File(home, "openclaw-start.sh")
                    session.write("${startScript.absolutePath}\n")
                }, SHELL_INIT_DELAY_MS)
            }
            eventBridge.emit("gateway_status", mapOf("status" to "launched"))
        }
    }

    @JavascriptInterface
    fun runCommandAsync(
        callbackId: String,
        cmd: String,
    ) {
        launchWithErrorHandling(
            errorEventType = "command_output",
            errorContext = mapOf("callbackId" to callbackId, "done" to true),
        ) {
            val env = CommandRunner.buildTermuxEnv(activity)
            val (_, activeHome) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
            CommandRunner.runStreaming(cmd, env, java.io.File(activeHome)) { output: String ->
                eventBridge.emit(
                    "command_output",
                    mapOf("callbackId" to callbackId, "data" to output, "done" to false),
                )
            }
            eventBridge.emit(
                "command_output",
                mapOf("callbackId" to callbackId, "data" to "", "done" to true),
            )
        }
    }

    // ═══════════════════════════════════════════
    // Updates domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun checkForUpdates(): String {
        // Compare local versions with config.json remote versions
        val updates = mutableListOf<Map<String, String>>()
        try {
            val (activePrefix, activeHome) = EnvironmentBuilder.resolveActivePaths(activity.filesDir)
            val openClawPkg = java.io.File(activePrefix, "lib/node_modules/openclaw/package.json")
            if (openClawPkg.exists()) {
                val currentOpenClaw =
                    Regex(""""version"\s*:\s*"([^"]+)"""")
                        .find(openClawPkg.readText())
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: "installed"
                val latestOpenClaw =
                    CommandRunner.runSync(
                        "npm view openclaw version 2>/dev/null",
                        CommandRunner.buildTermuxEnv(activity),
                        java.io.File(activeHome),
                        timeoutMs = 10_000,
                    ).stdout.trim()
                if (latestOpenClaw.isNotBlank() && latestOpenClaw != currentOpenClaw) {
                    updates.add(
                        mapOf(
                            "component" to "openclaw",
                            "currentVersion" to currentOpenClaw,
                            "newVersion" to latestOpenClaw,
                        ),
                    )
                }
            }

            val configFile =
                java.io.File(
                    activity.filesDir,
                    "usr/share/openclaw-app/config.json",
                )
            if (configFile.exists()) {
                val config = gson.fromJson(configFile.readText(), Map::class.java) as? Map<*, *>
                val localWwwVersion =
                    activity
                        .getSharedPreferences("openclaw", 0)
                        .getString("www_version", "0.0.0")
                val remoteWwwVersion = ((config?.get("www") as? Map<*, *>)?.get("version") as? String)
                if (remoteWwwVersion != null && remoteWwwVersion != localWwwVersion) {
                    updates.add(
                        mapOf(
                            "component" to "www",
                            "currentVersion" to (localWwwVersion ?: "0.0.0"),
                            "newVersion" to remoteWwwVersion,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            // ignore parse errors
        }
        return gson.toJson(updates)
    }

    @JavascriptInterface
    fun getApkUpdateInfo(): String {
        return try {
            val url = java.net.URL("https://api.github.com/repos/AidanPark/openclaw-android/releases/latest")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = API_TIMEOUT_MS
            conn.readTimeout = API_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val release = gson.fromJson(body, Map::class.java) as? Map<*, *>
            val tagName =
                release?.get("tagName") as? String
                    ?: release?.get("tag_name") as? String
                    ?: return gson.toJson(mapOf("error" to "no tag"))
            val latestVersion = tagName.trimStart('v')
            val currentVersion =
                activity.packageManager
                    .getPackageInfo(activity.packageName, 0)
                    .versionName ?: "0.0.0"
            gson.toJson(
                mapOf(
                    "currentVersion" to currentVersion,
                    "latestVersion" to latestVersion,
                    "updateAvailable" to (compareVersions(latestVersion, currentVersion) > 0),
                ),
            )
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to e.message))
        }
    }

    @JavascriptInterface
    fun applyUpdate(component: String) {
        launchWithErrorHandling(
            errorEventType = "install_progress",
            errorContext = mapOf("target" to component),
        ) {
            emitProgress(component, PROGRESS_START, "Updating $component...")
            when (component) {
                "openclaw" -> updateOpenClaw()
                "www" -> updateWww()
                "bootstrap" -> updateBootstrap()
                "scripts" -> emitProgress("scripts", PROGRESS_HALF, "Scripts are updated with bootstrap")
            }
            emitProgress(component, PROGRESS_DONE, "$component updated")
        }
    }

    private fun emitProgress(
        target: String,
        progress: Float,
        message: String,
    ) {
        eventBridge.emit(
            "install_progress",
            mapOf(
                "target" to target,
                "progress" to progress,
                "message" to message,
            ),
        )
    }

    private suspend fun updateWww() {
        val resolver = UrlResolver(activity)
        val wwwConfig = resolver.getWwwConfig()
        val url = wwwConfig?.url ?: BuildConfig.WWW_URL

        val zipFile = java.io.File(activity.cacheDir, "www.zip")
        val stagingWww = java.io.File(activity.cacheDir, "www-staging")
        val wwwDir = installerManager.getWwwDir()
        // Keep a backup of the current www so we can roll back on failure
        val backupWww = java.io.File(activity.cacheDir, "www-backup")

        try {
            // ── 1. Download ───────────────────────────────────────────────
            emitProgress("www", PROGRESS_DOWNLOAD, "Downloading...")
            stagingWww.deleteRecursively()
            stagingWww.mkdirs()
            zipFile.delete()

            java.net.URL(url).openStream().use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            }

            // ── 2. SHA-256 integrity check (if hash provided in config) ──
            val expectedSha256 = wwwConfig?.sha256
            if (!expectedSha256.isNullOrBlank()) {
                emitProgress("www", PROGRESS_VERIFY, "Verifying integrity...")
                val actualSha256 = sha256Hex(zipFile)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    zipFile.delete()
                    throw SecurityException(
                        "www.zip integrity check failed: " +
                            "expected=$expectedSha256 actual=$actualSha256",
                    )
                }
                AppLogger.i(TAG, "www.zip SHA-256 verified: $actualSha256")
            } else {
                AppLogger.w(TAG, "No SHA-256 provided for www.zip — skipping integrity check")
            }

            // ── 3. Extract to staging ─────────────────────────────────────
            emitProgress("www", PROGRESS_EXTRACT, "Extracting...")
            extractZipToDir(zipFile, stagingWww)
            zipFile.delete()

            // ── 4. Backup current www, then atomic swap ───────────────────
            emitProgress("www", PROGRESS_APPLY, "Applying...")
            backupWww.deleteRecursively()
            if (wwwDir.exists()) {
                // Copy (not rename) so wwwDir stays available during the swap
                wwwDir.copyRecursively(backupWww, overwrite = true)
            }

            wwwDir.deleteRecursively()
            wwwDir.parentFile?.mkdirs()
            val renamed = stagingWww.renameTo(wwwDir)
            if (!renamed) {
                // renameTo can fail across mount points — fall back to copy
                stagingWww.copyRecursively(wwwDir, overwrite = true)
                stagingWww.deleteRecursively()
            }

            // ── 5. Reload WebView ─────────────────────────────────────────
            backupWww.deleteRecursively()
            activity.runOnUiThread { activity.reloadWebView() }
            AppLogger.i(TAG, "www updated successfully from $url")
        } catch (e: Exception) {
            AppLogger.e(TAG, "www update failed: ${e.message}", e)

            // Roll back to backup if the swap already happened
            if (!wwwDir.resolve("index.html").exists() && backupWww.exists()) {
                AppLogger.w(TAG, "Rolling back www to previous version")
                wwwDir.deleteRecursively()
                backupWww.renameTo(wwwDir)
            }

            // Clean up temp files
            zipFile.delete()
            stagingWww.deleteRecursively()

            emitProgress("www", PROGRESS_START, "Update failed: ${e.message}")
        }
    }

    private suspend fun updateOpenClaw() {
        val (ok, error) = OpenClawManager(activity).installOrUpdate { percent, message ->
            emitProgress("openclaw", percent / 100f, message)
        }
        if (!ok) {
            emitProgress("openclaw", PROGRESS_START, error ?: "OpenClaw update failed")
        }
    }

    private suspend fun updateBootstrap() {
        emitProgress("bootstrap", PROGRESS_BOOTSTRAP_START, "Downloading bootstrap...")
        installerManager.install("online", null, object : InstallerManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                emitProgress("bootstrap", percent / 100f, message)
            }

            override fun onSuccess() {
                AppLogger.i(TAG, "Bootstrap updated successfully")
            }

            override fun onError(message: String, cause: Throwable?) {
                emitProgress("bootstrap", PROGRESS_START, "Update failed: $message")
            }
        })
    }

    /**
     * Computes the SHA-256 hex digest of a file.
     * Used to verify www.zip integrity before applying an OTA update.
     */
    private fun sha256Hex(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractZipToDir(
        zipFile: java.io.File,
        targetDir: java.io.File,
    ) {
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                extractZipEntry(zis, entry, targetDir)
                entry = zis.nextEntry
            }
        }
    }

    private fun extractZipEntry(
        zis: java.util.zip.ZipInputStream,
        entry: java.util.zip.ZipEntry,
        targetDir: java.io.File,
    ) {
        val destFile = java.io.File(targetDir, entry.name)
        if (entry.isDirectory) {
            destFile.mkdirs()
        } else {
            destFile.parentFile?.mkdirs()
            destFile.outputStream().use { out -> zis.copyTo(out) }
        }
    }

    // ═══════════════════════════════════════════
    // System domain
    // ═══════════════════════════════════════════

    @JavascriptInterface
    fun getAppInfo(): String {
        val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return gson.toJson(
            mapOf(
                "versionName" to (pInfo.versionName ?: "unknown"),
                "versionCode" to PackageInfoCompat.getLongVersionCode(pInfo),
                "packageName" to activity.packageName,
            ),
        )
    }

    @JavascriptInterface
    fun getBatteryOptimizationStatus(): String {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        return gson.toJson(
            mapOf("isIgnoring" to pm.isIgnoringBatteryOptimizations(activity.packageName)),
        )
    }

    @JavascriptInterface
    fun requestBatteryOptimizationExclusion() {
        activity.runOnUiThread {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun openSystemSettings(page: String) {
        activity.runOnUiThread {
            val intent =
                when (page) {
                    "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    "app_info" ->
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    else -> Intent(Settings.ACTION_SETTINGS)
                }
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        activity.runOnUiThread {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
        }
    }

    @JavascriptInterface
    fun getStorageInfo(): String {
        val filesDir = activity.filesDir
        val totalSpace = filesDir.totalSpace
        val freeSpace = filesDir.freeSpace
        val bootstrapSize = installerManager.getPrefixDir().walkTopDown().sumOf { it.length() }
        val wwwSize = installerManager.getWwwDir().walkTopDown().sumOf { it.length() }

        return gson.toJson(
            mapOf(
                "totalBytes" to totalSpace,
                "freeBytes" to freeSpace,
                "bootstrapBytes" to bootstrapSize,
                "wwwBytes" to wwwSize,
            ),
        )
    }

    @JavascriptInterface
    fun clearCache() {
        activity.cacheDir.deleteRecursively()
        activity.cacheDir.mkdirs()
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    /** Returns positive if a > b, negative if a < b, 0 if equal (semver: major.minor.patch) */
    private fun compareVersions(
        a: String,
        b: String,
    ): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val diff = (aParts.getOrElse(i) { 0 }) - (bParts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
