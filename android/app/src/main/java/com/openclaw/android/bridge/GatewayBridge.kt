package com.openclaw.android.bridge

import android.content.Intent
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.android.OpenClawConstants
import com.openclaw.android.OpenClawDashboardActivity
import com.openclaw.android.OpenClawGatewayService
import com.openclaw.android.OpenClawLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * GatewayBridge — Métodos de gateway y logs expuestos al WebView.
 *
 * Responsabilidades:
 *   - Start/stop/restart gateway
 *   - Token y URL del gateway
 *   - Uptime y estado
 *   - Lectura y limpieza de logs
 */
class GatewayBridge(
    private val activity: AppCompatActivity,
    private val logBridgeCall: (String, String?) -> Unit
) {

    @JavascriptInterface
    fun startGateway() {
        logBridgeCall("startGateway", null)
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
        logBridgeCall("stopGateway", null)
        activity.runOnUiThread {
            OpenClawGatewayService.stop(activity)
        }
    }

    @JavascriptInterface
    fun restartGateway() {
        logBridgeCall("restartGateway", null)
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
    fun getAuthToken(): String = OpenClawGatewayService.currentToken

    @JavascriptInterface
    fun getGatewayToken(): String = OpenClawGatewayService.currentToken

    @JavascriptInterface
    fun getGatewayState(): String = OpenClawGatewayService.state.value.name

    @JavascriptInterface
    fun getGatewayUrl(): String =
        "http://${OpenClawConstants.GATEWAY_HOST}:${OpenClawConstants.GATEWAY_PORT}"

    @JavascriptInterface
    fun getGatewayUptime(): String {
        val seconds = OpenClawGatewayService.getUptimeSeconds()
        return JSONObject().apply {
            put("seconds", seconds)
            put("uptimeSeconds", seconds)
        }.toString()
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
    fun getGatewayLogs(): String = getLogs(200)

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

    private fun inferLogLevel(line: String): String = when {
        line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> "error"
        line.contains("warn", ignoreCase = true) -> "warn"
        line.contains("debug", ignoreCase = true) -> "debug"
        else -> "info"
    }
}
