package com.openclaw.android.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.android.OpenClawGatewayService
import com.openclaw.android.OpenClawInstaller
import com.openclaw.android.OpenClawPreferences
import com.openclaw.android.deleteRecursivelySafe
import com.openclaw.android.proot.OpenClawProot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SystemBridge — Métodos de sistema, info, configuración y plataformas.
 *
 * Responsabilidades:
 *   - System info, app info, storage info
 *   - Battery optimization, clipboard, settings
 *   - Locale, theme, background execution
 *   - openclaw.json config
 *   - Platforms and tools (stubs)
 *   - Updates (stubs)
 */
class SystemBridge(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val notifyReact: (String, String) -> Unit,
    private val logBridgeCall: (String, String?) -> Unit
) {

    // ── System Info ──────────────────────────────────────────────────────────

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
            Log.e("SystemBridge", "Node version error: ${e.message}")
            "pendiente..."
        }

        return JSONObject().apply {
            put("nodeVersion", nodeVersion)
            put("npmVersion", "v11.x (Alpine)")
            put("openclawVersion", if (openclawInstalled) "instalado" else "no instalado")
            put("gitVersion", "no incluido")
            put("shellPath", proot.proot)
            put("toyboxAvailable", true)
            put("busyboxAvailable", false)
            put("alpineReady", openclawInstalled)
            put("diagnostics", diagnostics.joinToString(", "))
            put("freeSpaceMB", activity.filesDir.freeSpace / 1024 / 1024)
            put("nativeLibDir", proot.nativeDir)
            put("alpineDir", proot.rootfs.absolutePath)
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
    fun getApkUpdateInfo(): String =
        JSONObject().apply { put("updateAvailable", false) }.toString()

    // ── Battery & Settings ───────────────────────────────────────────────────

    @JavascriptInterface
    fun getBatteryOptimizationStatus(): String {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject().apply {
            put("isIgnoring", pm.isIgnoringBatteryOptimizations(activity.packageName))
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

    // ── Clipboard & Storage ──────────────────────────────────────────────────

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
    }

    @JavascriptInterface
    fun getStorageInfo(): String {
        val proot = OpenClawProot(activity)
        return JSONObject().apply {
            put("totalBytes", activity.filesDir.totalSpace)
            put("freeBytes", activity.filesDir.freeSpace)
            put("bootstrapBytes", proot.rootfs.sizeRecursively())
            put("wwwBytes", File(activity.filesDir, "www").sizeRecursively())
        }.toString()
    }

    @JavascriptInterface
    fun clearCache() {
        activity.runOnUiThread { webView.clearCache(true) }
        activity.cacheDir.deleteRecursivelySafe()
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        activity.runOnUiThread {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    // ── Locale & Theme ───────────────────────────────────────────────────────

    @JavascriptInterface
    fun notifyReady() {
        Log.i("SystemBridge", "WebUI signals READY")
    }

    @JavascriptInterface
    fun getLocale(): String =
        activity.resources.configuration.locales[0]?.toLanguageTag() ?: "en"

    @JavascriptInterface
    fun getSystemTheme(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val uiMode = activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    } else "light"

    // ── Background Execution ─────────────────────────────────────────────────

    @JavascriptInterface
    fun isBackgroundExecutionEnabled(): String =
        JSONObject().apply { put("enabled", OpenClawPreferences.isBackgroundExecutionEnabled) }.toString()

    @JavascriptInterface
    fun setBackgroundExecutionEnabled(enabled: Boolean) {
        OpenClawPreferences.isBackgroundExecutionEnabled = enabled
        if (!enabled) OpenClawGatewayService.stop(activity)
        notifyReact("onBackgroundExecutionChanged", JSONObject().apply {
            put("enabled", enabled)
        }.toString())
    }

    // ── openclaw.json Config ─────────────────────────────────────────────────

    @JavascriptInterface
    fun readOpenclawJson(): String = try {
        val configFile = File(OpenClawInstaller.getConfigDir(activity), "openclaw.json")
        if (configFile.exists()) {
            val content = configFile.readText()
            JSONObject(content) // validate
            JSONObject().apply { put("success", true); put("content", content) }.toString()
        } else {
            JSONObject().apply { put("success", true); put("content", "{\n  \n}") }.toString()
        }
    } catch (e: Exception) {
        JSONObject().apply {
            put("success", false)
            put("error", e.message ?: "Error desconocido")
            put("content", "{\n  \n}")
        }.toString()
    }

    @JavascriptInterface
    fun writeOpenclawJson(content: String): String = try {
        JSONObject(content) // validate
        val configDir = OpenClawInstaller.getConfigDir(activity)
        if (!configDir.exists()) configDir.mkdirs()
        File(configDir, "openclaw.json").writeText(content)
        JSONObject().apply { put("success", true) }.toString()
    } catch (e: Exception) {
        JSONObject().apply { put("success", false); put("error", e.message ?: "Error desconocido") }.toString()
    }

    // ── Platforms & Tools (stubs) ────────────────────────────────────────────

    @JavascriptInterface
    fun getAvailablePlatforms(): String = JSONArray().apply {
        put(JSONObject().apply {
            put("id", "openclaw"); put("name", "OpenClaw"); put("icon", "OC")
            put("desc", "Runtime local de OpenClaw para Android (proot + Alpine)")
        })
    }.toString()

    @JavascriptInterface
    fun getInstalledPlatforms(): String {
        val installed = OpenClawProot(activity).isOpenClawInstalled()
        return JSONArray().apply {
            if (installed) put(JSONObject().apply { put("id", "openclaw"); put("name", "OpenClaw") })
        }.toString()
    }

    @JavascriptInterface
    fun getActivePlatform(): String = JSONObject().apply {
        put("id", if (OpenClawProot(activity).isOpenClawInstalled()) "openclaw" else "")
    }.toString()

    @JavascriptInterface
    fun installPlatform(id: String) {
        logBridgeCall("installPlatform", id)
        notifyReact("install_progress", JSONObject().apply {
            put("target", id); put("progress", 1); put("message", "Plataforma lista")
        }.toString())
    }

    @JavascriptInterface
    fun uninstallPlatform(id: String) {
        logBridgeCall("uninstallPlatform", id)
        notifyReact("install_progress", JSONObject().apply {
            put("target", id); put("progress", 1); put("message", "La plataforma base se mantiene instalada")
        }.toString())
    }

    @JavascriptInterface
    fun switchPlatform(id: String) {
        logBridgeCall("switchPlatform", id)
        notifyReact("install_progress", JSONObject().apply {
            put("target", id); put("progress", 1); put("message", "Plataforma activa")
        }.toString())
    }

    @JavascriptInterface
    fun getInstalledTools(): String = "[]"

    @JavascriptInterface
    @Suppress("UNUSED_PARAMETER")
    fun saveToolSelections(json: String) {}

    @JavascriptInterface
    fun isToolInstalled(id: String): String =
        JSONObject().apply { put("id", id); put("installed", false) }.toString()

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

    @JavascriptInterface
    fun checkForUpdates(): String = JSONArray().toString()

    @JavascriptInterface
    fun applyUpdate(component: String) {
        logBridgeCall("applyUpdate", component)
        notifyReact("install_progress", JSONObject().apply {
            put("target", component); put("progress", 1); put("message", "Sin actualizaciones pendientes")
        }.toString())
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private fun File.sizeRecursively(): Long =
        if (!exists()) 0 else walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
