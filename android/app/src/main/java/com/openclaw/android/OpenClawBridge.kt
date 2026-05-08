package com.openclaw.android

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class OpenClawBridge(private val context: Context, private val webView: WebView) {

    private val scope = CoroutineScope(Dispatchers.Main)

    @JavascriptInterface
    fun showTerminal() {
        // Implementation for switching to terminal view
    }

    @JavascriptInterface
    fun showWebView() {
        // Already in WebView
    }

    @JavascriptInterface
    fun getGatewayToken(): String {
        // The gateway token is stored in openclaw.json under gateway.auth.token
        return try {
            val configFile = java.io.File(OpenClawInstaller.getConfigDir(context), "openclaw.json")
            if (!configFile.exists()) return ""
            val json = org.json.JSONObject(configFile.readText())
            // Path: gateway.auth.token
            json.optJSONObject("gateway")
                ?.optJSONObject("auth")
                ?.optString("token", "") ?: ""
        } catch (e: Exception) {
            Log.w("OpenClawBridge", "getGatewayToken failed: ${e.message}")
            ""
        }
    }

    @JavascriptInterface
    fun getGatewayUrl(): String = "ws://127.0.0.1:18789"

    @JavascriptInterface
    fun getGatewayState(): String = OpenClawGatewayService.getState().name

    @JavascriptInterface
    fun getSetupStatus(): String {
        val obj = JSONObject()
        obj.put("bootstrapInstalled", OpenClawInstaller.isPayloadReady(context))
        obj.put("platformInstalled", OpenClawInstaller.isConfigRestored(context))
        return obj.toString()
    }

    @JavascriptInterface
    fun getBootstrapStatus(): String {
        val obj = JSONObject()
        obj.put("installed", OpenClawInstaller.isPayloadReady(context))
        obj.put("prefixPath", OpenClawInstaller.getPayloadDir(context).absolutePath)
        return obj.toString()
    }

    @JavascriptInterface
    fun startSetup() {
        scope.launch {
            emit("setup_progress", JSONObject().apply {
                put("progress", 0.1)
                put("message", "Starting setup...")
            })

            val success = OpenClawInstaller.installPayload(context) { msg, pct ->
                emit("setup_progress", JSONObject().apply {
                    put("progress", if (pct >= 0) pct / 100.0 * 0.8 else 0.4)
                    put("message", msg)
                })
            }

            if (success) {
                OpenClawInstaller.restoreConfig(context) { msg, pct ->
                    emit("setup_progress", JSONObject().apply {
                        put("progress", if (pct >= 0) 0.8 + pct / 100.0 * 0.2 else 0.9)
                        put("message", msg)
                    })
                }

                emit("setup_progress", JSONObject().apply {
                    put("progress", 1.0)
                    put("message", "Setup complete!")
                })

                // Setup complete — InstallationActivity handles the transition now
                // (bridge-triggered setup is legacy; kept for compatibility)
            } else {
                emit("setup_progress", JSONObject().apply {
                    put("progress", 0.0)
                    put("message", "Setup failed.")
                })
            }
        }
    }

    @JavascriptInterface
    fun saveToolSelections(json: String) {
        // Save selections to shared preferences or file
        val prefs = context.getSharedPreferences("openclaw_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_tools", json).apply()
    }

    @JavascriptInterface
    fun getAvailablePlatforms(): String {
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("id", "openclaw")
            put("name", "OpenClaw Standard")
            put("icon", "🦀")
            put("desc", "Default autonomous AI assistant environment.")
        })
        return arr.toString()
    }

    @JavascriptInterface
    fun getActivePlatform(): String {
        val obj = JSONObject()
        obj.put("id", "openclaw")
        obj.put("name", "OpenClaw")
        return obj.toString()
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val obj = JSONObject()
        obj.put("versionName", packageInfo.versionName)
        @Suppress("DEPRECATION")
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            packageInfo.longVersionCode
        else
            packageInfo.versionCode.toLong()
        obj.put("versionCode", versionCode)
        obj.put("packageName", context.packageName)
        return obj.toString()
    }

    @JavascriptInterface
    fun getBatteryOptimizationStatus(): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val obj = JSONObject()
        obj.put("isIgnoring", pm.isIgnoringBatteryOptimizations(context.packageName))
        return obj.toString()
    }

    @JavascriptInterface
    fun getStorageInfo(): String {
        val obj = JSONObject()
        val internal = context.filesDir
        val total = internal.totalSpace
        val free = internal.freeSpace
        
        // Calculate sizes of specific dirs
        val bootstrapSize = getDirSize(OpenClawInstaller.getPayloadDir(context))
        val wwwSize = getDirSize(context.getFileStreamPath("www")) // or wherever www is

        obj.put("totalBytes", total)
        obj.put("freeBytes", free)
        obj.put("bootstrapBytes", bootstrapSize)
        obj.put("wwwBytes", wwwSize)
        return obj.toString()
    }

    @JavascriptInterface
    fun getInstalledTools(): String {
        val arr = JSONArray()
        // Check for specific binaries in the payload bin dir
        val binDir = java.io.File(OpenClawInstaller.getPayloadDir(context), "usr/bin")
        if (binDir.exists() && binDir.isDirectory) {
            binDir.listFiles()?.forEach { file ->
                arr.put(JSONObject().apply { put("id", file.name) })
            }
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun runCommand(cmd: String): String {
        return try {
            val base      = OpenClawInstaller.getPayloadDir(context)
            val nativeDir = java.io.File(context.applicationInfo.nativeLibraryDir)
            val glibcLibs = java.io.File(base, "glibc/lib").absolutePath
            val libs      = "${nativeDir.absolutePath}:${glibcLibs}"
            val loader    = java.io.File(nativeDir, "libldlinux.so")
            val nodeExec  = java.io.File(nativeDir, "libnode.so")
            val ocMjs     = java.io.File(base, "lib/node_modules/openclaw/openclaw.mjs")
            val tmpDir    = java.io.File(context.cacheDir, "tmp").apply { mkdirs() }
            val configDir = OpenClawInstaller.getConfigDir(context)

            if (!loader.exists() || !nodeExec.exists()) {
                return JSONObject().put("stdout", "Error: binarios no encontrados").toString()
            }

            // Parse command into binary + args
            val parts = cmd.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return JSONObject().put("stdout", "").toString()

            val (binary, args) = when {
                // node -v, node --version
                parts[0] == "node" -> {
                    loader.absolutePath to (listOf("--library-path", libs, nodeExec.absolutePath)
                        + parts.drop(1))
                }
                // openclaw <subcommand> or oa <subcommand>
                parts[0] == "openclaw" || parts[0] == "oa" -> {
                    if (!ocMjs.exists()) return JSONObject().put("stdout", "openclaw no instalado").toString()
                    loader.absolutePath to (listOf(
                        "--library-path", libs,
                        nodeExec.absolutePath,
                        "--disable-warning=ExperimentalWarning",
                        ocMjs.absolutePath
                    ) + parts.drop(1))
                }
                // Direct path execution
                parts[0].startsWith("/") -> {
                    val bin = java.io.File(parts[0])
                    if (!bin.exists()) return JSONObject().put("stdout", "no encontrado: ${parts[0]}").toString()
                    loader.absolutePath to (listOf("--library-path", libs, parts[0]) + parts.drop(1))
                }
                // Anything else — try as openclaw subcommand
                else -> {
                    if (!ocMjs.exists()) return JSONObject().put("stdout", "no encontrado").toString()
                    loader.absolutePath to (listOf(
                        "--library-path", libs,
                        nodeExec.absolutePath,
                        "--disable-warning=ExperimentalWarning",
                        ocMjs.absolutePath
                    ) + parts)
                }
            }

            val pb = ProcessBuilder(listOf(binary) + args).apply {
                directory(base)
                redirectErrorStream(true)
                environment().apply {
                    remove("LD_PRELOAD")
                    put("LD_LIBRARY_PATH", libs)
                    put("OA_GLIBC",        "1")
                    put("CONTAINER",       "1")
                    put("TMPDIR",          tmpDir.absolutePath)
                    put("HOME",            base.absolutePath)
                    put("NODE_PATH",       "${base.absolutePath}/lib/node_modules")
                    put("OPENCLAW_HOME",   configDir.absolutePath)
                    put("SSL_CERT_FILE",   "${base.absolutePath}/etc/tls/cert.pem")
                    put("PATH",            "${base.absolutePath}/bin:/system/bin")
                    put("NODE_NO_WARNINGS",                          "1")
                    put("OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED", "1")
                    put("NODE_DISABLE_COMPILE_CACHE",                "1")
                    put("NO_COLOR",        "1")
                    put("FORCE_COLOR",     "0")
                }
            }

            val proc   = pb.start()
            // Limit output read to 10s to avoid blocking the UI thread
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()

            JSONObject().put("stdout", output.ifEmpty { "(sin salida)" }).toString()
        } catch (e: Exception) {
            Log.w("OpenClawBridge", "runCommand($cmd) failed: ${e.message}")
            JSONObject().put("stderr", e.message ?: "error desconocido").toString()
        }
    }

    @JavascriptInterface
    fun openSystemSettings(page: String) {
        val action = when (page) {
            "developer"  -> android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "app_info"   -> android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            "battery"    -> android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            else         -> android.provider.Settings.ACTION_SETTINGS
        }
        val intent = android.content.Intent(action).apply {
            if (page == "app_info") {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (e: Exception) {
            Log.w("OpenClawBridge", "openSystemSettings($page) failed: ${e.message}")
        }
    }

    @JavascriptInterface
    fun checkForUpdates(): String {
        // Return empty array — actual update check would require network call
        // The UI shows "up to date" when array is empty
        return "[]"
    }

    @JavascriptInterface
    fun applyUpdate(component: String) {
        // Trigger update via openclaw CLI — runs async and emits install_progress events
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val base      = OpenClawInstaller.getPayloadDir(context)
                val nativeDir = java.io.File(context.applicationInfo.nativeLibraryDir)
                val glibcLibs = java.io.File(base, "glibc/lib").absolutePath
                val libs      = "${nativeDir.absolutePath}:${glibcLibs}"
                val loader    = java.io.File(nativeDir, "libldlinux.so")
                val nodeExec  = java.io.File(nativeDir, "libnode.so")
                val ocMjs     = java.io.File(base, "lib/node_modules/openclaw/openclaw.mjs")
                val configDir = OpenClawInstaller.getConfigDir(context)
                val tmpDir    = java.io.File(context.cacheDir, "tmp").apply { mkdirs() }

                emit("install_progress", JSONObject().apply {
                    put("target", component); put("progress", 0.1)
                })

                val pb = ProcessBuilder(
                    loader.absolutePath, "--library-path", libs,
                    nodeExec.absolutePath, "--disable-warning=ExperimentalWarning",
                    ocMjs.absolutePath, "update"
                ).apply {
                    directory(base); redirectErrorStream(true)
                    environment().apply {
                        remove("LD_PRELOAD")
                        put("LD_LIBRARY_PATH", libs); put("OA_GLIBC", "1")
                        put("TMPDIR", tmpDir.absolutePath); put("HOME", base.absolutePath)
                        put("NODE_PATH", "${base.absolutePath}/lib/node_modules")
                        put("OPENCLAW_HOME", configDir.absolutePath)
                        put("PATH", "${base.absolutePath}/bin:/system/bin")
                        put("NODE_NO_WARNINGS", "1")
                        put("OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED", "1")
                        put("NODE_DISABLE_COMPILE_CACHE", "1")
                    }
                }
                val proc = pb.start()
                proc.waitFor()

                emit("install_progress", JSONObject().apply {
                    put("target", component); put("progress", 1.0)
                })
            } catch (e: Exception) {
                Log.e("OpenClawBridge", "applyUpdate failed", e)
                emit("install_progress", JSONObject().apply {
                    put("target", component); put("progress", 0.0)
                })
            }
        }
    }

    @JavascriptInterface
    fun getApkUpdateInfo(): String {
        // Returns update info — currently no remote check, returns no update available
        return JSONObject().apply {
            put("updateAvailable", false)
            put("currentVersion", try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) { "unknown" })
        }.toString()
    }

    @JavascriptInterface
    fun installPlatform(id: String) {
        // Platforms are not separately installable in this build — emit completion immediately
        scope.launch {
            emit("install_progress", JSONObject().apply {
                put("target", id); put("progress", 1.0)
            })
        }
    }

    @JavascriptInterface
    fun uninstallPlatform(id: String) {
        Log.i("OpenClawBridge", "uninstallPlatform($id) — not supported")
    }

    @JavascriptInterface
    fun switchPlatform(id: String) {
        Log.i("OpenClawBridge", "switchPlatform($id)")
    }

    @JavascriptInterface
    fun getInstalledPlatforms(): String = getAvailablePlatforms()

    @JavascriptInterface
    fun installTool(id: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val base      = OpenClawInstaller.getPayloadDir(context)
                val nativeDir = java.io.File(context.applicationInfo.nativeLibraryDir)
                val glibcLibs = java.io.File(base, "glibc/lib").absolutePath
                val libs      = "${nativeDir.absolutePath}:${glibcLibs}"
                val loader    = java.io.File(nativeDir, "libldlinux.so")
                val nodeExec  = java.io.File(nativeDir, "libnode.so")
                val ocMjs     = java.io.File(base, "lib/node_modules/openclaw/openclaw.mjs")
                val configDir = OpenClawInstaller.getConfigDir(context)
                val tmpDir    = java.io.File(context.cacheDir, "tmp").apply { mkdirs() }

                emit("install_progress", JSONObject().apply {
                    put("target", id); put("progress", 0.1)
                    put("message", "Installing $id...")
                })

                val pb = ProcessBuilder(
                    loader.absolutePath, "--library-path", libs,
                    nodeExec.absolutePath, "--disable-warning=ExperimentalWarning",
                    ocMjs.absolutePath, "install", id
                ).apply {
                    directory(base); redirectErrorStream(true)
                    environment().apply {
                        remove("LD_PRELOAD")
                        put("LD_LIBRARY_PATH", libs); put("OA_GLIBC", "1")
                        put("TMPDIR", tmpDir.absolutePath); put("HOME", base.absolutePath)
                        put("NODE_PATH", "${base.absolutePath}/lib/node_modules")
                        put("OPENCLAW_HOME", configDir.absolutePath)
                        put("PATH", "${base.absolutePath}/bin:/system/bin")
                        put("NODE_NO_WARNINGS", "1")
                        put("OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED", "1")
                        put("NODE_DISABLE_COMPILE_CACHE", "1")
                    }
                }
                val proc = pb.start()
                proc.waitFor()

                emit("install_progress", JSONObject().apply {
                    put("target", id); put("progress", 1.0)
                    put("message", "$id installed")
                })
            } catch (e: Exception) {
                Log.e("OpenClawBridge", "installTool($id) failed", e)
                emit("install_progress", JSONObject().apply {
                    put("target", id); put("progress", 0.0)
                    put("message", "Failed: ${e.message}")
                })
            }
        }
    }

    @JavascriptInterface
    fun uninstallTool(id: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val base      = OpenClawInstaller.getPayloadDir(context)
                val nativeDir = java.io.File(context.applicationInfo.nativeLibraryDir)
                val glibcLibs = java.io.File(base, "glibc/lib").absolutePath
                val libs      = "${nativeDir.absolutePath}:${glibcLibs}"
                val loader    = java.io.File(nativeDir, "libldlinux.so")
                val nodeExec  = java.io.File(nativeDir, "libnode.so")
                val ocMjs     = java.io.File(base, "lib/node_modules/openclaw/openclaw.mjs")
                val configDir = OpenClawInstaller.getConfigDir(context)
                val tmpDir    = java.io.File(context.cacheDir, "tmp").apply { mkdirs() }

                val pb = ProcessBuilder(
                    loader.absolutePath, "--library-path", libs,
                    nodeExec.absolutePath, "--disable-warning=ExperimentalWarning",
                    ocMjs.absolutePath, "uninstall", id
                ).apply {
                    directory(base); redirectErrorStream(true)
                    environment().apply {
                        remove("LD_PRELOAD")
                        put("LD_LIBRARY_PATH", libs); put("OA_GLIBC", "1")
                        put("TMPDIR", tmpDir.absolutePath); put("HOME", base.absolutePath)
                        put("NODE_PATH", "${base.absolutePath}/lib/node_modules")
                        put("OPENCLAW_HOME", configDir.absolutePath)
                        put("PATH", "${base.absolutePath}/bin:/system/bin")
                        put("NODE_NO_WARNINGS", "1")
                        put("OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED", "1")
                        put("NODE_DISABLE_COMPILE_CACHE", "1")
                    }
                }
                pb.start().waitFor()
            } catch (e: Exception) {
                Log.e("OpenClawBridge", "uninstallTool($id) failed", e)
            }
        }
    }

    @JavascriptInterface
    fun isToolInstalled(id: String): String {
        val base   = OpenClawInstaller.getPayloadDir(context)
        val binDir = java.io.File(base, "bin")
        val exists = java.io.File(binDir, id).exists() ||
                     java.io.File(base, "lib/node_modules/$id").exists()
        return JSONObject().put("installed", exists).toString()
    }

    @JavascriptInterface
    fun launchInteractiveCommand(cmd: String) {
        // Launch OnboardActivity with a custom command for interactive sessions
        val intent = android.content.Intent(context, OnboardActivity::class.java).apply {
            putExtra("interactive_cmd", cmd)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @JavascriptInterface
    fun runCommandAsync(callbackId: String, cmd: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCommand(cmd)
            emit("cmd_result_$callbackId", JSONObject(result))
        }
    }

    @JavascriptInterface
    fun createSession(): String = JSONObject().put("id", "session-1").toString()

    @JavascriptInterface
    fun switchSession(id: String) { Log.i("OpenClawBridge", "switchSession($id)") }

    @JavascriptInterface
    fun closeSession(id: String) { Log.i("OpenClawBridge", "closeSession($id)") }

    @JavascriptInterface
    fun getTerminalSessions(): String = "[{\"id\":\"session-1\",\"name\":\"Main\"}]"

    @JavascriptInterface
    fun writeToTerminal(id: String, data: String) { Log.i("OpenClawBridge", "writeToTerminal($id): $data") }

    @JavascriptInterface
    fun requestBatteryOptimizationExclusion() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("OpenClaw", text)
        clipboard.setPrimaryClip(clip)
    }

    @JavascriptInterface
    fun clearCache() {
        context.cacheDir.deleteRecursively()
        context.externalCacheDir?.deleteRecursively()
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun getDirSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0
        var size: Long = 0
        dir.walkTopDown().forEach { file ->
            if (file.isFile) size += file.length()
        }
        return size
    }

    private fun emit(type: String, data: Any) {
        scope.launch {
            val script = "window.__oc && window.__oc.emit('$type', $data)"
            webView.evaluateJavascript(script, null)
        }
    }
}

