package com.openclaw.android

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.android.bridge.GatewayBridge
import com.openclaw.android.bridge.SetupBridge
import com.openclaw.android.bridge.SystemBridge
import com.openclaw.android.bridge.TerminalBridge
import kotlinx.coroutines.CoroutineScope

/**
 * AndroidBridge — Fachada que expone métodos nativos a React via window.OpenClaw.
 *
 * Delega a sub-bridges especializados:
 *   - [SetupBridge]    — instalación, setup, fases, skip
 *   - [GatewayBridge]  — gateway start/stop, logs, token
 *   - [TerminalBridge]  — terminal, sesiones, comandos
 *   - [SystemBridge]   — system info, config, platforms, tools
 *
 * Cada sub-bridge recibe `notifyReact` y `logBridgeCall` como callbacks
 * para emitir eventos al WebView sin acoplarse a esta clase.
 */
class AndroidBridge(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val scope: CoroutineScope
) {

    // ── Shared helpers ───────────────────────────────────────────────────────

    private fun logBridgeCall(method: String, arg: String? = null) {
        OpenClawLogger.init(activity)
        val safeArg = arg?.let { if (it.length > 80) it.take(80) + "…" else it }
        val msg = if (safeArg != null) "bridge.$method($safeArg)" else "bridge.$method()"
        OpenClawLogger.log(OpenClawConstants.LOG_TAG_BRIDGE, msg)
    }

    /**
     * Enviar eventos de Android → React via CustomEvent.
     */
    fun notifyReact(event: String, dataJson: String) {
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('android:$event', { detail: $dataJson }));",
                null
            )
        }
    }

    // ── Sub-bridges ──────────────────────────────────────────────────────────

    private val setup = SetupBridge(activity, scope, ::notifyReact, ::logBridgeCall)
    private val gateway = GatewayBridge(activity, ::logBridgeCall)
    private val terminal = TerminalBridge(activity, webView, scope, ::logBridgeCall)
    private val system = SystemBridge(activity, webView, ::notifyReact, ::logBridgeCall)

    // ── Setup delegation ─────────────────────────────────────────────────────

    @JavascriptInterface fun getSetupStatus() = setup.getSetupStatus()
    @JavascriptInterface fun startSetupWithChannel(channel: String) = setup.startSetup(channel)
    @JavascriptInterface fun startSetup() = setup.startSetup()
    @JavascriptInterface fun getInstallPhases() = setup.getInstallPhases()
    @JavascriptInterface fun reinstallAlpineWithChannel(channel: String) = setup.reinstallAlpine(channel)
    @JavascriptInterface fun reinstallAlpine() = setup.reinstallAlpine()
    @JavascriptInterface fun skipPhase(key: String) = setup.skipPhase(key)
    @JavascriptInterface fun bypassInstall() = setup.bypassInstall()
    @JavascriptInterface fun getAssetStatus() = setup.getAssetStatus()
    @JavascriptInterface fun pickFile(callbackId: String) = setup.pickFile(callbackId)
    @JavascriptInterface fun installFromUri(uri: String, configUri: String) = setup.installFromUri(uri, configUri)

    // ── Gateway delegation ───────────────────────────────────────────────────

    @JavascriptInterface fun startGateway() = gateway.startGateway()
    @JavascriptInterface fun stopGateway() = gateway.stopGateway()
    @JavascriptInterface fun restartGateway() = gateway.restartGateway()
    @JavascriptInterface fun getAuthToken() = gateway.getAuthToken()
    @JavascriptInterface fun getGatewayToken() = gateway.getGatewayToken()
    @JavascriptInterface fun getGatewayState() = gateway.getGatewayState()
    @JavascriptInterface fun getGatewayUrl() = gateway.getGatewayUrl()
    @JavascriptInterface fun getGatewayUptime() = gateway.getGatewayUptime()
    @JavascriptInterface fun getLogs(lines: Int) = gateway.getLogs(lines)
    @JavascriptInterface fun getGatewayLogs() = gateway.getGatewayLogs()
    @JavascriptInterface fun clearGatewayLogs() = gateway.clearGatewayLogs()
    @JavascriptInterface fun clearLogs() = gateway.clearLogs()

    // ── Terminal delegation ──────────────────────────────────────────────────

    @JavascriptInterface fun openTerminal() = terminal.openTerminal()
    @JavascriptInterface fun showTerminal() = terminal.showTerminal()
    @JavascriptInterface fun showWebView() = terminal.showWebView()
    @JavascriptInterface fun createSession() = terminal.createSession()
    @JavascriptInterface fun switchSession(id: String) = terminal.switchSession(id)
    @JavascriptInterface fun closeSession(id: String) = terminal.closeSession(id)
    @JavascriptInterface fun getTerminalSessions() = terminal.getTerminalSessions()
    @JavascriptInterface fun writeToTerminal(id: String, data: String) = terminal.writeToTerminal(id, data)
    @JavascriptInterface fun launchInteractiveCommand(command: String) = terminal.launchInteractiveCommand(command)
    @JavascriptInterface fun runCommand(command: String) = terminal.runCommand(command)
    @JavascriptInterface fun runOpenClawCommand(cmd: String) = terminal.runOpenClawCommand(cmd)
    @JavascriptInterface fun runCommandAsync(callbackId: String, command: String) = terminal.runCommandAsync(callbackId, command)

    // ── System delegation ────────────────────────────────────────────────────

    @JavascriptInterface fun getSystemInfo() = system.getSystemInfo()
    @JavascriptInterface fun getAppInfo() = system.getAppInfo()
    @JavascriptInterface fun getApkUpdateInfo() = system.getApkUpdateInfo()
    @JavascriptInterface fun getBatteryOptimizationStatus() = system.getBatteryOptimizationStatus()
    @JavascriptInterface fun requestBatteryOptimizationExclusion() = system.requestBatteryOptimizationExclusion()
    @JavascriptInterface fun openSystemSettings(page: String) = system.openSystemSettings(page)
    @JavascriptInterface fun copyToClipboard(text: String) = system.copyToClipboard(text)
    @JavascriptInterface fun getStorageInfo() = system.getStorageInfo()
    @JavascriptInterface fun clearCache() = system.clearCache()
    @JavascriptInterface fun openUrl(url: String) = system.openUrl(url)
    @JavascriptInterface fun notifyReady() = system.notifyReady()
    @JavascriptInterface fun getLocale() = system.getLocale()
    @JavascriptInterface fun getSystemTheme() = system.getSystemTheme()
    @JavascriptInterface fun isBackgroundExecutionEnabled() = system.isBackgroundExecutionEnabled()
    @JavascriptInterface fun setBackgroundExecutionEnabled(enabled: Boolean) = system.setBackgroundExecutionEnabled(enabled)
    @JavascriptInterface fun readOpenclawJson() = system.readOpenclawJson()
    @JavascriptInterface fun writeOpenclawJson(content: String) = system.writeOpenclawJson(content)
    @JavascriptInterface fun getAvailablePlatforms() = system.getAvailablePlatforms()
    @JavascriptInterface fun getInstalledPlatforms() = system.getInstalledPlatforms()
    @JavascriptInterface fun getActivePlatform() = system.getActivePlatform()
    @JavascriptInterface fun installPlatform(id: String) = system.installPlatform(id)
    @JavascriptInterface fun uninstallPlatform(id: String) = system.uninstallPlatform(id)
    @JavascriptInterface fun switchPlatform(id: String) = system.switchPlatform(id)
    @JavascriptInterface fun getInstalledTools() = system.getInstalledTools()
    @JavascriptInterface fun saveToolSelections(json: String) = system.saveToolSelections(json)
    @JavascriptInterface fun isToolInstalled(id: String) = system.isToolInstalled(id)
    @JavascriptInterface fun installTool(id: String) = system.installTool(id)
    @JavascriptInterface fun uninstallTool(id: String) = system.uninstallTool(id)
    @JavascriptInterface fun checkForUpdates() = system.checkForUpdates()
    @JavascriptInterface fun applyUpdate(component: String) = system.applyUpdate(component)

    // ── Internal access for progress emission ────────────────────────────────

    fun emitInstallProgress(rawLine: String) = setup.emitInstallProgress(rawLine)
}
