package com.openclaw.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
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
 */
class AndroidBridge(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val scope: CoroutineScope
) {

    private var pendingFileCallbackId: String? = null
    private var pendingAssetPick: String? = null

    private val payloadOverrideFile: File
        get() = File(activity.cacheDir, "openclaw_payload_override.tar.xz")

    private val migrationOverrideFile: File
        get() = File(activity.cacheDir, "openclaw_migration_override.tar.gz")

    @JavascriptInterface
    fun getSetupStatus(): String {
        val payloadReady = OpenClawInstaller.isPayloadReady(activity)
        val onboardComplete = OpenClawInstaller.isOnboardComplete(activity)
        val assets = AssetDetector.detectSync(activity)
        val payloadOverride = payloadOverrideFile.takeIf { it.exists() && it.length() > 0L }
        val migrationOverride = migrationOverrideFile.takeIf { it.exists() && it.length() > 0L }
        return JSONObject().apply {
            put("bootstrapInstalled", payloadReady)
            put("platformInstalled", if (payloadReady) "openclaw" else "")
            put("onboardComplete", onboardComplete)
            put("payloadReady", payloadReady)
            put("payloadAvailable", assets.payloadAvailable || payloadOverride != null)
            put("payloadSizeBytes", payloadOverride?.length() ?: assets.payloadSizeBytes)
            put("payloadSource", if (payloadOverride != null) "local" else if (assets.payloadAvailable) "apk" else "missing")
            put("migrationAvailable", assets.migrationAvailable || migrationOverride != null)
            put("migrationSizeBytes", migrationOverride?.length() ?: assets.migrationSizeBytes)
            put("migrationSource", if (migrationOverride != null) "local" else if (assets.migrationAvailable) "apk" else "missing")
            put("freeSpaceMB", assets.freeSpaceBytes / 1024 / 1024)
            put("requiredSpaceMB", 400)
            put("hasEnoughSpace", assets.hasEnoughSpace)
        }.toString()
    }

    @JavascriptInterface
    fun checkInstallation(): String {
        return getSetupStatus()
    }

    @JavascriptInterface
    fun getBootstrapStatus(): String {
        return getSetupStatus()
    }

    @JavascriptInterface
    fun startInstallation() {
        startSetup()
    }

    @JavascriptInterface
    fun startSetup() {
        scope.launch(Dispatchers.IO) {
            val payloadOverride = payloadOverrideFile.takeIf { it.exists() && it.length() > 0L }
            val migrationOverride = migrationOverrideFile.takeIf { it.exists() && it.length() > 0L }
            OpenClawInstaller.installDetailedFromFiles(
                context = activity,
                payloadFile = payloadOverride,
                migrationFile = migrationOverride,
                onProgress = { progressJson ->
                    notifyReact("onInstallProgress", progressJson)
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
        pendingFileCallbackId = callbackId
        pendingAssetPick = null
        activity.runOnUiThread {
            if (activity is OpenClawDashboardActivity) {
                activity.filePicker.launch("*/*")
            }
        }
    }

    @JavascriptInterface
    fun installFromUri(payloadUri: String, configUri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val payloadFile = copyUriToTempFile(Uri.parse(payloadUri), "payload_manual.tar.xz")
                val configFile = if (configUri.isNotBlank()) {
                    copyUriToTempFile(Uri.parse(configUri), "config_manual.tar.gz")
                } else {
                    null
                }

                OpenClawInstaller.installDetailedFromFiles(
                    context = activity,
                    payloadFile = payloadFile,
                    migrationFile = configFile,
                    onProgress = { progressJson -> notifyReact("onInstallProgress", progressJson) },
                    onComplete = { notifyReact("onInstallComplete", "{\"success\":true}") },
                    onError = { error ->
                        notifyReact("onInstallError", JSONObject().apply { put("error", error) }.toString())
                    }
                )
            } catch (e: Exception) {
                notifyReact("onInstallError", JSONObject().apply { put("error", e.message ?: "Error desconocido") }.toString())
            }
        }
    }

    @JavascriptInterface
    fun pickMigrationFile() {
        pendingAssetPick = "migration"
        pendingFileCallbackId = null
        activity.runOnUiThread {
            if (activity is OpenClawDashboardActivity) {
                activity.filePicker.launch("*/*")
            }
        }
    }

    @JavascriptInterface
    fun pickPayloadFile() {
        pendingAssetPick = "payload"
        pendingFileCallbackId = null
        activity.runOnUiThread {
            if (activity is OpenClawDashboardActivity) {
                activity.filePicker.launch("*/*")
            }
        }
    }

    fun handlePickedFile(uri: Uri) {
        pendingFileCallbackId?.let { callbackId ->
            dispatchNativeFilePicked(callbackId, uri, true)
            pendingFileCallbackId = null
            return
        }

        val type = pendingAssetPick ?: "migration"
        pendingAssetPick = null
        scope.launch(Dispatchers.IO) {
            try {
                val target = if (type == "payload") payloadOverrideFile else migrationOverrideFile
                copyUriToFile(uri, target)
                val metadata = getUriMetadata(uri, target)
                notifyReact("onLocalAssetPicked", JSONObject().apply {
                    put("type", type)
                    put("filename", metadata.first)
                    put("sizeMB", metadata.second / 1024 / 1024)
                    put("source", "local")
                }.toString())
                if (type == "migration") {
                    notifyReact("onMigrationFilePicked", JSONObject().apply {
                        put("filename", metadata.first)
                        put("sizeMB", metadata.second / 1024 / 1024)
                    }.toString())
                }
            } catch (e: Exception) {
                notifyReact("onInstallError", JSONObject().apply {
                    put("error", "No se pudo cargar el archivo local: ${e.message ?: "error desconocido"}")
                }.toString())
            }
        }
    }

    @JavascriptInterface
    fun startGateway() {
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
        activity.runOnUiThread {
            OpenClawGatewayService.stop(activity)
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
        // Dashboard already runs inside this WebView. Kept for frontend compatibility.
    }

    @JavascriptInterface
    fun createSession(): String {
        return JSONObject().apply { put("id", "native") }.toString()
    }

    @JavascriptInterface
    fun switchSession(_id: String) {
        // Native terminal sessions are owned by OpenClawTerminalActivity.
    }

    @JavascriptInterface
    fun closeSession(_id: String) {
        // Native terminal sessions are owned by OpenClawTerminalActivity.
    }

    @JavascriptInterface
    fun getTerminalSessions(): String {
        return JSONArray().toString()
    }

    @JavascriptInterface
    fun writeToTerminal(_id: String, data: String) {
        if (data.isNotBlank()) {
            launchInteractiveCommand(data)
        }
    }

    @JavascriptInterface
    fun launchInteractiveCommand(command: String) {
        activity.runOnUiThread {
            OpenClawTerminalActivity.launchWithCommand(activity, command)
        }
    }

    @JavascriptInterface
    fun runCommand(command: String): String {
        return try {
            val termMgr = OpenClawTerminalManager(activity)
            val envMap = mutableMapOf<String, String>()
            termMgr.buildEnvironment().forEach {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) envMap[parts[0]] = parts[1]
            }

            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(activity.filesDir)
                .apply { environment().putAll(envMap) }
                .redirectErrorStream(true)
                .start()

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

    @JavascriptInterface
    fun getSystemInfo(): String {
        val nativeDir = File(activity.applicationInfo.nativeLibraryDir)
        val payloadDir = activity.getDir("payload", Context.MODE_PRIVATE)
        val ldlinux = File(nativeDir, "libldlinux.so")
        val nodeReal = File(nativeDir, "libnode.so")
        val glibcLibs = File(payloadDir, "glibc/lib").absolutePath
        val libs = "${nativeDir.absolutePath}:$glibcLibs"

        val nodeVersion = try {
            if (!ldlinux.exists() || !nodeReal.exists()) {
                "unknown"
            } else {
                val process = ProcessBuilder(
                    ldlinux.absolutePath,
                    "--library-path", libs,
                    nodeReal.absolutePath,
                    "--version"
                ).apply {
                    environment().apply {
                        remove("LD_PRELOAD")
                        put("LD_LIBRARY_PATH", libs)
                    }
                    redirectErrorStream(true)
                }.start()

                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "unknown"
                } else {
                    process.inputStream.bufferedReader().readLine()?.trim().orEmpty().ifBlank { "unknown" }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenClawBridge", "Node version error: ${e.message}")
            "unknown"
        }

        val openclawVersion = readPackageVersion(
            payloadDir,
            "lib/node_modules/openclaw/package.json"
        ) ?: "unknown"

        val npmVersion = readPackageVersion(
            payloadDir,
            "lib/node_modules/npm/package.json",
            "lib/node_modules/openclaw/node_modules/npm/package.json"
        ) ?: "no incluido"

        return JSONObject().apply {
            put("nodeVersion", nodeVersion)
            put("npmVersion", npmVersion)
            put("openclawVersion", openclawVersion)
            put("gitVersion", "no incluido")
            put("busyboxAvailable", File(nativeDir, "libbusybox.so").canExecute())
            put("freeSpaceMB", activity.filesDir.freeSpace / 1024 / 1024)
            put("nativeLibDir", nativeDir.absolutePath)
            put("payloadDir", payloadDir.absolutePath)
        }.toString()
    }

    private fun readPackageVersion(payloadDir: File, vararg relativePaths: String): String? {
        return relativePaths.firstNotNullOfOrNull { relativePath ->
            try {
                val pkgJson = File(payloadDir, relativePath)
                if (pkgJson.exists()) {
                    JSONObject(pkgJson.readText()).optString("version").ifBlank { null }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
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
        val payloadDir = OpenClawInstaller.getPayloadDir(activity)
        val wwwDir = File(activity.filesDir, "www")
        val totalBytes = activity.filesDir.totalSpace
        val freeBytes = activity.filesDir.freeSpace
        return JSONObject().apply {
            put("totalBytes", totalBytes)
            put("freeBytes", freeBytes)
            put("bootstrapBytes", payloadDir.sizeRecursively())
            put("wwwBytes", wwwDir.sizeRecursively())
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
            // Si se deshabilita, detener el servicio si está corriendo
            OpenClawGatewayService.stop(activity)
        }
        notifyReact("onBackgroundExecutionChanged", JSONObject().apply {
            put("enabled", enabled)
        }.toString())
    }

    @JavascriptInterface
    fun readOpenclawJson(): String {
        return try {
            val configDir = OpenClawInstaller.getConfigDir(activity)
            val configFile = java.io.File(configDir, "openclaw.json")
            if (configFile.exists()) {
                val content = configFile.readText()
                // Validar que es JSON válido
                org.json.JSONObject(content)
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
            // Validar JSON primero
            org.json.JSONObject(content)
            
            val configDir = OpenClawInstaller.getConfigDir(activity)
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            val configFile = java.io.File(configDir, "openclaw.json")
            configFile.writeText(content)
            
            JSONObject().apply {
                put("success", true)
            }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Error desconocido")
            }.toString()
        }
    }

    @JavascriptInterface
    fun getInstalledTools(): String {
        return "[]"
    }

    @JavascriptInterface
    fun saveToolSelections(json: String) {
        // Tool selections are currently informational in the mobile dashboard.
    }

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
                put("desc", "Runtime local de OpenClaw para Android")
            })
        }.toString()
    }

    @JavascriptInterface
    fun getInstalledPlatforms(): String {
        return JSONArray().apply {
            if (OpenClawInstaller.isPayloadReady(activity)) {
                put(JSONObject().apply {
                    put("id", "openclaw")
                    put("name", "OpenClaw")
                })
            }
        }.toString()
    }

    @JavascriptInterface
    fun getActivePlatform(): String {
        return JSONObject().apply {
            put("id", if (OpenClawInstaller.isPayloadReady(activity)) "openclaw" else "")
        }.toString()
    }

    @JavascriptInterface
    fun installPlatform(id: String) {
        notifyReact("install_progress", JSONObject().apply {
            put("target", id)
            put("progress", 1)
            put("message", "Plataforma lista")
        }.toString())
    }

    @JavascriptInterface
    fun uninstallPlatform(id: String) {
        notifyReact("install_progress", JSONObject().apply {
            put("target", id)
            put("progress", 1)
            put("message", "La plataforma base se mantiene instalada")
        }.toString())
    }

    @JavascriptInterface
    fun switchPlatform(id: String) {
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
        notifyReact("install_progress", JSONObject().apply {
            put("target", component)
            put("progress", 1)
            put("message", "Sin actualizaciones pendientes")
        }.toString())
    }

    @JavascriptInterface
    fun installTool(id: String) {
        notifyReact("install_progress", "{\"target\":\"$id\", \"progress\":1, \"message\": \"Instalación completada\"}")
    }

    @JavascriptInterface
    fun uninstallTool(id: String) {
        notifyReact("install_progress", "{\"target\":\"$id\", \"progress\":1, \"message\": \"Desinstalación completada\"}")
    }

    @JavascriptInterface
    fun getGatewayToken(): String {
        return OpenClawGatewayService.currentToken
    }

    @JavascriptInterface
    fun getGatewayUrl(): String {
        return "http://127.0.0.1:18789"
    }

    @JavascriptInterface
    fun getGatewayLogs(): String {
        OpenClawLogger.init(activity)
        val lines = OpenClawLogger.getRecentLogs(200)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return JSONObject().apply {
            put("logs", JSONArray().apply {
                lines.forEach { line ->
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
    fun clearGatewayLogs() {
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

    private fun copyUriToTempFile(uri: Uri, fileName: String): File {
        val tempFile = File(activity.cacheDir, fileName)
        copyUriToFile(uri, tempFile)
        return tempFile
    }

    private fun copyUriToFile(uri: Uri, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        activity.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open URI: $uri")
    }

    private fun getUriMetadata(uri: Uri, fallbackFile: File): Pair<String, Long> {
        var filename = fallbackFile.name
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                filename = cursor.getString(nameIndex)
            }
        }
        val size = try {
            activity.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: fallbackFile.length()
        } catch (_: Exception) {
            fallbackFile.length()
        }
        return filename to size
    }

    private fun dispatchNativeFilePicked(callbackId: String, uri: Uri, success: Boolean) {
        val escapedUri = JSONObject.quote(uri.toString())
        val successJson = if (success) "true" else "false"
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('native:file_picked_$callbackId', { detail: { uri: $escapedUri, success: $successJson } }));",
                null
            )
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
