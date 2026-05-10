package com.openclaw.android

import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * AndroidBridge
 * Expone métodos nativos a React via window.AndroidBridge.
 * Maneja eventos asíncronos via notifyReact (CustomEvents).
 */
class AndroidBridge(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val scope: CoroutineScope
) {

    @JavascriptInterface
    fun checkInstallation(): String {
        val isReady = OpenClawInstaller.isPayloadReady(activity)
        val assets = AssetDetector.detectSync(activity)
        return JSONObject().apply {
            put("payloadReady", isReady)
            put("payloadAvailable", assets.payloadAvailable)
            put("migrationAvailable", assets.migrationAvailable)
            put("freeSpaceMB", assets.freeSpaceBytes / 1024 / 1024)
            put("requiredSpaceMB", 400)
        }.toString()
    }

    @JavascriptInterface
    fun startInstallation() {
        scope.launch(Dispatchers.IO) {
            // Nota: El instalador debe soportar estos callbacks JSON
            OpenClawInstaller.installDetailed(
                context = activity,
                onProgress = { progressJson ->
                    notifyReact("onInstallProgress", progressJson)
                },
                onComplete = {
                    notifyReact("onInstallComplete", "{\"success\":true}")
                },
                onError = { error ->
                    notifyReact("onInstallError", "{\"error\":\"$error\"}")
                }
            )
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
    fun getStorageInfo(): String {
        val assets = AssetDetector.detectSync(activity)
        return JSONObject().apply {
            put("freeSpaceMB", assets.freeSpaceBytes / 1024 / 1024)
            put("totalSpaceMB", 0) // No crítico para el dashboard actual
        }.toString()
    }

    @JavascriptInterface
    fun getGatewayToken(): String {
        return OpenClawGatewayService.currentToken
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
