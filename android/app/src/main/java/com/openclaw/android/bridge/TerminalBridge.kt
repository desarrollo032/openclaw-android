package com.openclaw.android.bridge

import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.android.OpenClawDashboardActivity
import com.openclaw.android.OpenClawTerminalActivity
import com.openclaw.android.proot.OpenClawProot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * TerminalBridge — Métodos de terminal expuestos al WebView.
 *
 * Responsabilidades:
 *   - Abrir/mostrar terminal nativo
 *   - Sesiones (create, switch, close, write)
 *   - Ejecutar comandos sincrónicos y asíncronos
 */
class TerminalBridge(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val logBridgeCall: (String, String?) -> Unit
) {

    @JavascriptInterface
    fun openTerminal() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, OpenClawTerminalActivity::class.java))
        }
    }

    @JavascriptInterface
    fun showTerminal() = openTerminal()

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
    fun createSession(): String =
        JSONObject().apply { put("id", "native") }.toString()

    @JavascriptInterface
    @Suppress("UNUSED_PARAMETER")
    fun switchSession(_id: String) {}

    @JavascriptInterface
    @Suppress("UNUSED_PARAMETER")
    fun closeSession(_id: String) {}

    @JavascriptInterface
    fun getTerminalSessions(): String = JSONArray().toString()

    @JavascriptInterface
    @Suppress("UNUSED_PARAMETER")
    fun writeToTerminal(_id: String, data: String) {
        if (data.isNotBlank()) launchInteractiveCommand(data)
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
    fun runOpenClawCommand(cmd: String): String = runCommand(cmd)

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
}
