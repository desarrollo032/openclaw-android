package com.openclaw.android.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.android.OpenClawConstants
import com.openclaw.android.OpenClawInstaller
import com.openclaw.android.OpenClawLogger
import com.openclaw.android.proot.OpenClawProot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * SetupBridge — Métodos de instalación y setup expuestos al WebView.
 *
 * Responsabilidades:
 *   - Estado de la instalación (getSetupStatus, getInstallPhases)
 *   - Iniciar y reiniciar la instalación (startSetup, reinstallAlpine)
 *   - Saltar fases fallidas (skipPhase)
 *   - Estado de assets (getAssetStatus)
 */
class SetupBridge(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val notifyReact: (String, String) -> Unit,
    private val logBridgeCall: (String, String?) -> Unit
) {

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
            put("alpineReady", openclawInstalled)
            put("alpineAvailable", prootPresent)
            put("alpineSizeBytes", 10 * 1024 * 1024L)
            put("alpineSource", if (prootPresent) "proot" else "missing")
            put("canDownloadRemotely", OpenClawInstaller.isNetworkAvailable(activity))
            put("freeSpaceMB", freeSpace / 1024 / 1024)
            put("requiredSpaceMB", 200)
            put("hasEnoughSpace", freeSpace >= 200 * 1024 * 1024L)
        }.toString()
    }

    @JavascriptInterface
    fun startSetup(channel: String) {
        scope.launch(Dispatchers.IO) {
            OpenClawInstaller.runSetup(
                context = activity,
                channel = channel,
                onProgress = { progressMsg -> emitInstallProgress(progressMsg) },
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
    fun startSetup() {
        startSetup("estable")
    }

    @JavascriptInterface
    fun getInstallPhases(): String {
        val proot = OpenClawProot(activity)
        val done = mutableSetOf<String>()

        if (proot.isProotPresent()) done += "proot"
        if (proot.isAlpineInstalled()) done += "alpine"
        done += proot.getCompletedPhases()

        if (OpenClawInstaller.isOnboardComplete(activity)) done += "onboard"
        if (proot.isOpenClawInstalled() && done.contains("onboard")) done += "verify"

        return JSONObject().apply {
            put("phases", JSONArray(done.toList()))
        }.toString()
    }

    @JavascriptInterface
    fun reinstallAlpine(channel: String) {
        logBridgeCall("reinstallAlpine", channel)
        scope.launch(Dispatchers.IO) {
            try {
                val proot = OpenClawProot(activity)
                emitInstallProgress("Eliminando Alpine anterior...")
                proot.wipeAlpine()
                Log.i("SetupBridge", "Alpine rootfs wiped — starting fresh install")
                OpenClawInstaller.runSetup(
                    context = activity,
                    channel = channel,
                    onProgress = { progressMsg -> emitInstallProgress(progressMsg) },
                    onComplete = {
                        notifyReact("onInstallComplete", "{\"success\":true}")
                    },
                    onError = { error ->
                        notifyReact("onInstallError", JSONObject().apply { put("error", error) }.toString())
                    }
                )
            } catch (e: Exception) {
                Log.e("SetupBridge", "reinstallAlpine failed: ${e.message}", e)
                notifyReact("onInstallError", JSONObject().apply {
                    put("error", "Error al reinstalar Alpine: ${e.message}")
                }.toString())
            }
        }
    }

    @JavascriptInterface
    fun reinstallAlpine() {
        reinstallAlpine("estable")
    }

    @JavascriptInterface
    fun skipPhase(key: String) {
        logBridgeCall("skipPhase", key)
        val safeToSkip = setOf("pnpm", "pnpm_env", "versions", "sys_deps")
        if (key !in safeToSkip) {
            notifyReact("onInstallError", JSONObject().apply {
                put("error", "La fase '$key' es obligatoria y no se puede saltar")
            }.toString())
            return
        }
        val proot = OpenClawProot(activity)
        proot.markPhaseSkipped(key)
        emitInstallProgress("PHASE:$key:skip:Saltado por el usuario")
    }

    @JavascriptInterface
    fun getAssetStatus(): String {
        val proot = OpenClawProot(activity)
        val ready = proot.isOpenClawInstalled()
        return JSONObject().apply {
            put("bootstrap", ready)
            put("alpine", ready)
            put("platform", ready)
            put("tools", false)
        }.toString()
    }

    @JavascriptInterface
    fun pickFile(@Suppress("UNUSED_PARAMETER") callbackId: String) {
        // Migración a proot: no aplica
    }

    @JavascriptInterface
    fun installFromUri(
        @Suppress("UNUSED_PARAMETER") uri: String,
        @Suppress("UNUSED_PARAMETER") configUri: String
    ) {
        notifyReact("onInstallError", JSONObject().apply {
            put("error", "Con la migración a proot, la instalación se hace automáticamente desde Internet")
        }.toString())
    }

    /**
     * Convierte una línea cruda de progreso en un evento estructurado.
     */
    fun emitInstallProgress(rawLine: String) {
        val phaseRegex = Regex("^PHASE:([^:]+):([a-z]+)(?::(.*))?$")
        val match = phaseRegex.find(rawLine)
        val json = JSONObject()
        if (match != null) {
            val (key, status, detail) = match.destructured
            json.put("phase", key)
            json.put("phaseStatus", status)
            json.put("message", detail)
            json.put("logLine", rawLine)
        } else {
            json.put("phase", "")
            json.put("phaseStatus", "log")
            json.put("message", rawLine)
            json.put("logLine", rawLine)
        }
        notifyReact("onInstallProgress", json.toString())
    }
}
