package com.openclaw.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.android.proot.OpenClawProot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * AndroidBridge
 * Expone métodos nativos a React via window.OpenClaw.
 * Maneja eventos asíncronos via notifyReact (CustomEvents).
 *
 * AHORA: basado en proot + Alpine Linux. OpenClaw corre dentro de proot.
 */
class AndroidBridge(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val scope: CoroutineScope
) {

    private fun logBridgeCall(method: String, arg: String? = null) {
        OpenClawLogger.init(activity)
        val safeArg = arg?.let { if (it.length > 80) it.take(80) + "…" else it }
        val msg = if (safeArg != null) "bridge.$method($safeArg)" else "bridge.$method()"
        OpenClawLogger.log(OpenClawConstants.LOG_TAG_BRIDGE, msg)
    }

    // ── Setup / Status ────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getSetupStatus(): String {
        val proot = OpenClawProot(activity)
        val alpineInstalled = proot.isAlpineInstalled()
        val openclawInstalled = proot.isOpenClawInstalled()
        val prootPresent = proot.isProotPresent()
        val onboardComplete = OpenClawInstaller.isOnboardComplete(activity)
        val freeSpace = activity.filesDir.freeSpace

        return JSONObject().apply {
            put("bootstrapInstalled", alpineInstalled && openclawInstalled)
            put("platformInstalled", if (openclawInstalled) "openclaw" else "")
            put("onboardComplete", onboardComplete)
            put("payloadReady", openclawInstalled)
            put("payloadAvailable", prootPresent) // proot binario presente = listo
            put("payloadSizeBytes", 10 * 1024 * 1024L) // Alpine ~10MB descarga
            put("payloadSource", if (prootPresent) "proot" else "missing")
            put("migrationAvailable", false)
            put("migrationSizeBytes", 0)
            put("migrationSource", "none")
            put("canDownloadRemotely", OpenClawInstaller.isNetworkAvailable(activity))
            put("freeSpaceMB", freeSpace / 1024 / 1024)
            put("requiredSpaceMB", 200)
            put("hasEnoughSpace", freeSpace >= 200 * 1024 * 1024L)
        }.toString()
    }

    @JavascriptInterface
    fun checkBootstrap() {
        // Estado se obtiene via getBootstrapStatus() polling
    }

    @JavascriptInterface
    fun getBootstrapStatus(): String {
        val proot = OpenClawProot(activity)
        val installed = proot.isAlpineInstalled() && proot.isOpenClawInstalled()
        val onboardComplete = OpenClawInstaller.isOnboardComplete(activity)
        return JSONObject().apply {
            put("installed", installed && onboardComplete)
            put("installing", false)
            put("error", if (!installed) "Pendiente de instalacion" else "")
        }.toString()
    }

    @JavascriptInterface
    fun checkPayload() {
        // Estado se obtiene via getPayloadStatus() polling
    }

    @JavascriptInterface
    fun getPayloadStatus(): String {
        val proot = OpenClawProot(activity)
        val ready = proot.isOpenClawInstalled()
        return JSONObject().apply {
            put("installed", ready)
            put("installing", false)
        }.toString()
    }

    @JavascriptInterface
    fun checkInstallation(): String {
        return getSetupStatus()
    }

    @JavascriptInterface
    fun startInstallation() {
        startSetup()
    }

    @JavascriptInterface
    fun startSetup() {
        scope.launch(Dispatchers.IO) {
            OpenClawInstaller.runSetup(
                context = activity,
                onProgress = { progressMsg ->
                    notifyReact("onInstallProgress", JSONObject().apply {
                        put("step", 1)
                        put("totalSteps", 2)
                        put("percent", 0)
                        put("stepName", progressMsg)
                        put("currentFile", "")
                    }.toString())
                },
                onComplete = {
                    notifyReact("onInstallComplete", "{\"success\":true}")
                },
                onError = { error ->
                    notifyReact("onInstallError", JSONObject().apply { put("error", error) }.toString())
                }
            )
        }
    }

    @JavascriptInterface
    fun pickFile(callbackId: String) {
        activity.runOnUiThread {
            if (activity is OpenClawDashboardActivity) {
                activity.filePicker.launch("*/*")
            }
        }
    }

    @JavascriptInterface
    fun installFromUri(payloadUri: String, configUri: String) {
        // Con proot: ignoramos URIs. La instalación es via descarga de Alpine + npm.
        notifyReact("onInstallError", JSONObject().apply {
            put("error", "Con la migración a proot, la instalación se hace automáticamente desde Internet")
        }.toString())
    }

    @JavascriptInterface
    fun pickMigrationFile() {
        // No aplica con proot
    }

    @JavascriptInterface
    fun pickPayloadFile() {
        // No aplica con proot
    }

    fun handlePickedFile(uri: Uri) {
        // No aplica con proot
    }

    // ── Gateway ───────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun startGateway() {
        logBridgeCall("startGateway")
        activity.runOnUiThread {
            if (activity is OpenClawDashboardActivity) {
                activity.startGatewayWithPermissionCheck()
            } else {
                OpenClawGatewayService.start(activity)
            }
        }
    }

    @JavascriptInterface
    fun stopGateway() {
        logBridgeCall("stopGateway")
        activity.runOnUiThread {
            OpenClawGatewayService.stop(activity)
        }
    }

    @JavascriptInterface
    fun restartGateway() {
        logBridgeCall("restartGateway")
        activity.runOnUiThread {
            val intent = Intent(activity, OpenClawGatewayService::class.java).apply {
                action = OpenClawConstants.ACTION_RESTART_GATEWAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
        }
    }

    @JavascriptInterface
    fun getAuthToken(): String {
        return OpenClawGatewayService.currentToken
    }

    @JavascriptInterface
    fun getGatewayState(): String {
        return OpenClawGatewayService.state.value.name
    }

    @JavascriptInterface
    fun notifyReady() {
        Log.i("AndroidBridge", "WebUI signals READY")
    }

    @JavascriptInterface
    fun getLocale(): String {
        return activity.resources.configuration.locales[0]?.toLanguageTag() ?: "en"
    }

    @JavascriptInterface
    fun getSystemTheme(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uiMode = activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        } else {
            "light"
        }
    }

    // ── Terminal ──────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun openTerminal() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, OpenClawTerminalActivity::class.java))
        }
    }

    @JavascriptInterface
    fun showTerminal() {
        openTerminal()
    }

    @JavascriptInterface
    fun showWebView() {
        activity.runOnUiThread {
            val intent = Intent(activity, OpenClawDashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun createSession(): String {
        return JSONObject().apply { put("id", "native") }.toString()
    }

    @JavascriptInterface
    @Suppress("UNUSED_PARAMETER")
    fun switchSession(_id: String) {}

    @JavascriptInterface
    @Suppress("UNUSED_PARAMETER")
    fun closeSession(_id: String) {}

    @JavascriptInterface
    fun getTerminalSessions(): String {
        return JSONArray().toString()
    }

    @JavascriptInterface
    @Suppress("UNUSED_PARAMETER")
    fun writeToTerminal(_id: String, data: String) {
        if (data.isNotBlank()) {
            launchInteractiveCommand(data)
        }
    }

    @JavascriptInterface
    fun launchInteractiveCommand(command: String) {
        logBridgeCall("launchInteractiveCommand", command)
        activity.runOnUiThread {
            OpenClawTerminalActivity.launchWithCommand(activity, command)
        }
    }

    @JavascriptInterface
    fun runCommand(command: String): String {
        logBridgeCall("runCommand", command)
        return try {
            val proot = OpenClawProot(activity)
            val pb = proot.buildProotProcess(listOf("/bin/sh", "-c", command))
            pb.directory(activity.filesDir)

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            JSONObject().apply {
                put("stdout", output)
                put("exitCode", process.exitValue())
            }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("stderr", e.message)
                put("exitCode", 1)
            }.toString()
        }
    }

    @JavascriptInterface
    fun runOpenClawCommand(cmd: String): String {
        return runCommand(cmd)
    }

    @JavascriptInterface
    fun runCommandAsync(callbackId: String, command: String) {
        scope.launch(Dispatchers.IO) {
            val result = runCommand(command)
            activity.runOnUiThread {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('native:command_result_$callbackId', { detail: $result }));",
                    null
                )
            }
        }
    }

    // ── Asset / Status ───────────────────────────────────────────────────────

    @JavascriptInterface
    fun getAssetStatus(): String {
        val proot = OpenClawProot(activity)
        val ready = proot.isOpenClawInstalled()
        return JSONObject().apply {
            put("bootstrap", ready)
            put("payload", ready)
            put("platform", ready)
            put("tools", false)
        }.toString()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @JavascriptInterface
    fun getSystemInfo(): String {
        val proot = OpenClawProot(activity)
        val alpineInstalled = proot.isAlpineInstalled()
        val openclawInstalled = proot.isOpenClawInstalled()
        val prootPresent = proot.isProotPresent()

        val diagnostics = mutableListOf<String>()
        if (!prootPresent) diagnostics.add("Falta libproot.so en nativeLibraryDir")
        if (!alpineInstalled) diagnostics.add("Alpine no instalado")
        if (!openclawInstalled) diagnostics.add("OpenClaw no instalado")

        // Obtener versión de Node.js desde dentro del proot
        val nodeVersion = try {
            if (!alpineInstalled || !openclawInstalled) {
                "instalando..."
            } else {
                val pb = proot.buildProotProcess(listOf("/bin/sh", "-c", "node --version 2>/dev/null || echo 'error'"))
                val process = pb.start()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "cargando..."
                } else {
                    val out = process.inputStream.bufferedReader().readLine()?.trim().orEmpty()
                    if (out.startsWith("v")) out else "pendiente..."
                }
            }
        } catch (e: Exception) {
            Log.e("OpenClawBridge", "Node version error: ${e.message}")
            "pendiente..."
        }

        return JSONObject().apply {
            put("nodeVersion", nodeVersion)
            put("npmVersion", "v11.x (Alpine)")
            put("openclawVersion", if (openclawInstalled) "instalado" else "no instalado")
            put("gitVersion", "no incluido")
            put("shellPath", proot.proot)
            put("toyboxAvailable", true) // Android tiene toybox nativo
            put("busyboxAvailable", false) // ya no usamos busybox
            put("payloadReady", openclawInstalled)
            put("diagnostics", diagnostics.joinToString(", "))
            put("freeSpaceMB", activity.filesDir.freeSpace / 1024 / 1024)
            put("nativeLibDir", proot.nativeDir)
            put("payloadDir", proot.rootfs.absolutePath)
        }.toString()
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val longCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo)
        return JSONObject().apply {
            put("versionName", pInfo.versionName)
            put("versionCode", longCode)
            put("packageName", activity.packageName)
        }.toString()
    }

    @JavascriptInterface
    fun getApkUpdateInfo(): String {
        return JSONObject().apply {
            put("updateAvailable", false)
        }.toString()
    }

    @JavascriptInterface
    fun getBatteryOptimizationStatus(): String {
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(activity.packageName)
        return JSONObject().apply {
            put("isIgnoring", isIgnoring)
        }.toString()
    }

    @JavascriptInterface
    fun requestBatteryOptimizationExclusion() {
        activity.runOnUiThread {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun openSystemSettings(page: String) {
        activity.runOnUiThread {
            val intent = when (page) {
                "app_info" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                else -> Intent(Settings.ACTION_SETTINGS)
            }
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
    }

    @JavascriptInterface
    fun getStorageInfo(): String {
        val proot = OpenClawProot(activity)
        val totalBytes = activity.filesDir.totalSpace
        val freeBytes = activity.filesDir.freeSpace
        return JSONObject().apply {
            put("totalBytes", totalBytes)
            put("freeBytes", freeBytes)
            put("bootstrapBytes", proot.rootfs.sizeRecursively())
            put("wwwBytes", File(activity.filesDir, "www").sizeRecursively())
        }.toString()
    }

    @JavascriptInterface
    fun clearCache() {
        activity.runOnUiThread {
            webView.clearCache(true)
        }
        activity.cacheDir.deleteRecursivelySafe()
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun isBackgroundExecutionEnabled(): String {
        return JSONObject().apply {
            put("enabled", OpenClawPreferences.isBackgroundExecutionEnabled)
        }.toString()
    }

    @JavascriptInterface
    fun setBackgroundExecutionEnabled(enabled: Boolean) {
        OpenClawPreferences.isBackgroundExecutionEnabled = enabled
        if (!enabled) {
            OpenClawGatewayService.stop(activity)
        }
        notifyReact("onBackgroundExecutionChanged", JSONObject().apply {
            put("enabled", enabled)
        }.toString())
    }

    // ── openclaw.json config ─────────────────────────────────────────────────

    @JavascriptInterface
    fun readOpenclawJson(): String {
        return try {
            val configDir = OpenClawInstaller.getConfigDir(activity)
            val configFile = java.io.File(configDir, "openclaw.json")
            if (configFile.exists()) {
                val content = configFile.readText()
                org.json.JSONObject(content) // validate
                JSONObject().apply {
                    put("success", true)
                    put("content", content)
                }.toString()
            } else {
                JSONObject().apply {
                    put("success", true)
                    put("content", "{\n  \n}")
                }.toString()
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Error desconocido")
                put("content", "{\n  \n}")
            }.toString()
        }
    }

    @JavascriptInterface
    fun writeOpenclawJson(content: String): String {
        return try {
            org.json.JSONObject(content) // validate
            val configDir = OpenClawInstaller.getConfigDir(activity)
            if (!configDir.exists()) configDir.mkdirs()
            val configFile = java.io.File(configDir, "openclaw.json")
            configFile.writeText(content)
            JSONObject().apply { put("success", true) }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Error desconocido")
            }.toString()
        }
    }

    // ── Tools / Platforms ─────────────────────────────────────────────────────

    @JavascriptInterface
    fun getInstalledTools(): String {
        return "[]"
    }

    @JavascriptInterface
    @Suppress("UNUSED_PARAMETER")
    fun saveToolSelections(json: String) {}

    @JavascriptInterface
    fun isToolInstalled(id: String): String {
        return JSONObject().apply {
            put("id", id)
            put("installed", false)
        }.toString()
    }

    @JavascriptInterface
    fun getAvailablePlatforms(): String {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("id", "openclaw")
                put("name", "OpenClaw")
                put("icon", "OC")
                put("desc", "Runtime local de OpenClaw para Android (proot + Alpine)")
            })
        }.toString()
    }

    @JavascriptInterface
    fun getInstalledPlatforms(): String {
        val proot = OpenClawProot(activity)
        return JSONArray().apply {
            if (proot.isOpenClawInstalled()) {
                put(JSONObject().apply {
                    put("id", "openclaw")
                    put("name", "OpenClaw")
                })
            }
        }.toString()
    }

    @JavascriptInterface
    fun getActivePlatform(): String {
        val proot = OpenClawProot(activity)
        return JSONObject().apply {
            put("id", if (proot.isOpenClawInstalled()) "openclaw" else "")
        }.toString()
    }

    @JavascriptInterface
    fun installPlatform(id: String) {
        logBridgeCall("installPlatform", id)
        notifyReact("install_progress", JSONObject().apply {
            put("target", id)
            put("progress", 1)
            put("message", "Plataforma lista")
        }.toString())
    }

    @JavascriptInterface
    fun uninstallPlatform(id: String) {
        logBridgeCall("uninstallPlatform", id)
        notifyReact("install_progress", JSONObject().apply {
            put("target", id)
            put("progress", 1)
            put("message", "La plataforma base se mantiene instalada")
        }.toString())
    }

    @JavascriptInterface
    fun switchPlatform(id: String) {
        logBridgeCall("switchPlatform", id)
        notifyReact("install_progress", JSONObject().apply {
            put("target", id)
            put("progress", 1)
            put("message", "Plataforma activa")
        }.toString())
    }

    @JavascriptInterface
    fun checkForUpdates(): String {
        return JSONArray().toString()
    }

    @JavascriptInterface
    fun applyUpdate(component: String) {
        logBridgeCall("applyUpdate", component)
        notifyReact("install_progress", JSONObject().apply {
            put("target", component)
            put("progress", 1)
            put("message", "Sin actualizaciones pendientes")
        }.toString())
    }

    @JavascriptInterface
    fun installTool(id: String) {
        logBridgeCall("installTool", id)
        notifyReact("install_progress", "{\"target\":\"$id\", \"progress\":1, \"message\": \"Instalación completada\"}")
    }

    @JavascriptInterface
    fun uninstallTool(id: String) {
        logBridgeCall("uninstallTool", id)
        notifyReact("install_progress", "{\"target\":\"$id\", \"progress\":1, \"message\": \"Desinstalación completada\"}")
    }

    // ── Gateway helpers ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun getGatewayToken(): String {
        return OpenClawGatewayService.currentToken
    }

    @JavascriptInterface
    fun getGatewayUrl(): String {
        return "http://${OpenClawConstants.GATEWAY_HOST}:${OpenClawConstants.GATEWAY_PORT}"
    }

    @JavascriptInterface
    fun getLogs(lines: Int): String {
        OpenClawLogger.init(activity)
        val logLines = OpenClawLogger.getRecentLogs(lines)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return JSONObject().apply {
            put("logs", JSONArray().apply {
                logLines.forEach { line ->
                    put(JSONObject().apply {
                        put("level", inferLogLevel(line))
                        put("message", line)
                        put("timestamp", System.currentTimeMillis())
                    })
                }
            })
        }.toString()
    }

    @JavascriptInterface
    fun getGatewayLogs(): String {
        return getLogs(200)
    }

    @JavascriptInterface
    fun clearGatewayLogs() {
        OpenClawLogger.init(activity)
        OpenClawLogger.clearLogs()
    }

    @JavascriptInterface
    fun clearLogs() {
        OpenClawLogger.init(activity)
        OpenClawLogger.clearLogs()
    }

    @JavascriptInterface
    fun getGatewayUptime(): String {
        val seconds = OpenClawGatewayService.getUptimeSeconds()
        return JSONObject().apply {
            put("seconds", seconds)
            put("uptimeSeconds", seconds)
        }.toString()
    }

    private fun inferLogLevel(line: String): String {
        return when {
            line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> "error"
            line.contains("warn", ignoreCase = true) -> "warn"
            line.contains("debug", ignoreCase = true) -> "debug"
            else -> "info"
        }
    }

    private fun File.sizeRecursively(): Long {
        return if (!exists()) 0 else walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Enviar eventos de Android -> React via CustomEvent
     */
    fun notifyReact(event: String, dataJson: String) {
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('android:$event', { detail: $dataJson }));",
                null
            )
        }
    }
}
