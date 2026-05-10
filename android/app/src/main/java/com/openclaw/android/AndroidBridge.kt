package com.openclaw.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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

    @JavascriptInterface
    fun getSetupStatus(): String {
        val payloadReady = OpenClawInstaller.isPayloadReady(activity)
        val onboardComplete = OpenClawInstaller.isOnboardComplete(activity)
        return JSONObject().apply {
            put("bootstrapInstalled", payloadReady)
            put("platformInstalled", if (payloadReady) "openclaw" else "")
            put("onboardComplete", onboardComplete)
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
            OpenClawInstaller.installDetailed(
                context = activity,
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
                payloadFile.inputStream().use { stream ->
                    val payloadDir = OpenClawInstaller.getPayloadDir(activity)
                    payloadDir.deleteRecursivelySafe()
                    payloadDir.mkdirs()
                    extractTarXzFromStream(stream, payloadDir)
                    OpenClawInstaller.deployScripts(activity, payloadDir)
                    OpenClawInstaller.fixPermissions(payloadDir)
                }

                if (configUri.isNotBlank()) {
                    val configFile = copyUriToTempFile(Uri.parse(configUri), "config_manual.tar.gz")
                    configFile.inputStream().use { stream ->
                        extractTarGzFromStream(stream, activity.filesDir)
                    }
                }

                notifyReact("onInstallComplete", "{\"success\":true}")
            } catch (e: Exception) {
                notifyReact("onInstallError", JSONObject().apply { put("error", e.message ?: "Error desconocido") }.toString())
            }
        }
    }

    @JavascriptInterface
    fun pickMigrationFile() {
        activity.runOnUiThread {
            if (activity is OpenClawDashboardActivity) {
                activity.filePicker.launch("*/*")
            }
        }
    }

    @JavascriptInterface
    fun startGateway() {
        activity.runOnUiThread {
            OpenClawGatewayService.start(activity)
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
    fun launchInteractiveCommand(command: String) {
        activity.runOnUiThread {
            val intent = Intent(activity, OpenClawTerminalActivity::class.java).apply {
                putExtra("initial_command", command)
            }
            activity.startActivity(intent)
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
    fun getInstalledTools(): String {
        return "[]"
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
    fun getGatewayLogs(): String {
        return JSONObject().apply { put("logs", emptyList<String>()) }.toString()
    }

    @JavascriptInterface
    fun clearGatewayLogs() {}

    @JavascriptInterface
    fun getGatewayUptime(): String {
        return JSONObject().apply { put("uptimeSeconds", 0) }.toString()
    }

    private fun copyUriToTempFile(uri: Uri, fileName: String): File {
        val tempFile = File(activity.cacheDir, fileName)
        activity.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open URI: $uri")
        return tempFile
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
