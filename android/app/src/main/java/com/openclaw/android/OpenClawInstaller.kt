package com.openclaw.android

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "OpenClawInstaller"

// SharedPreferences keys
private const val PREFS_NAME            = "openclaw_install"
private const val KEY_PAYLOAD_INSTALLED = "payload_installed"
private const val KEY_CONFIG_RESTORED   = "config_restored"

// Asset names
private const val PAYLOAD_ASSET = "payload.tar.xz"
private const val CONFIG_ASSET  = "openclaw-apk-migration.tar.gz"

object OpenClawInstaller {

    // ── Directory helpers ─────────────────────────────────────────────────────

    fun getPayloadDir(context: Context): File = context.getDir("payload", Context.MODE_PRIVATE)
    fun getConfigDir(context: Context):  File = File(context.filesDir, ".openclaw")

    /**
     * Returns the path to ld-linux that SELinux will actually allow to execute.
     *
     * Android 10+ enforces W^X: binaries extracted to app_payload are blocked by
     * SELinux even with 0777 permissions (error=13 from forkAndExec).
     * The only directories with the correct SELinux context for execution are:
     *   - /system/... (not writable)
     *   - nativeLibraryDir  ← set by the system, always executable
     *
     * We copy the loader there once after extraction and use that copy to launch.
     */
    fun getLoaderPath(context: Context): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        return File(nativeDir, "libloader.so")  // .so extension required by the linker dir
    }

    // ── Readiness checks ──────────────────────────────────────────────────────

    /**
     * True when all three critical files exist.
     * Also auto-repairs the SharedPreferences flag if files exist but flag was lost
     * (e.g. after APK reinstall without clearing data).
     */
    fun isPayloadReady(context: Context): Boolean {
        val base = getPayloadDir(context)
        val filesExist = File(base, "glibc/lib/ld-linux-aarch64.so.1").exists()
                      && File(base, "node/bin/node.real").exists()
                      && File(base, "lib/node_modules/openclaw/openclaw.mjs").exists()

        if (filesExist) {
            // Auto-repair flag if files are present but prefs were cleared
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_PAYLOAD_INSTALLED, false)) {
                Log.i(TAG, "Files exist but flag missing — auto-repairing prefs")
                prefs.edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()
            }
            return true
        }
        return false
    }

    fun isConfigRestored(context: Context): Boolean {
        val jsonExists = File(getConfigDir(context), "openclaw.json").exists()

        if (jsonExists) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_CONFIG_RESTORED, false)) {
                Log.i(TAG, "Config exists but flag missing — auto-repairing prefs")
                prefs.edit().putBoolean(KEY_CONFIG_RESTORED, true).apply()
            }
            return true
        }
        return false
    }

    /** True when both asset files are bundled in the APK. */
    fun hasBundledAssets(context: Context): Boolean = try {
        val list = context.assets.list("") ?: emptyArray()
        list.contains(PAYLOAD_ASSET) && list.contains(CONFIG_ASSET)
    } catch (e: Exception) { false }

    // ── Payload installation ──────────────────────────────────────────────────

    /** Install from bundled APK asset. */
    suspend fun installPayload(
        context: Context,
        onProgress: (msg: String, pct: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Preparando directorio...", 0)
        val base = getPayloadDir(context)
        base.deleteRecursivelySafe()
        base.mkdirs()

        onProgress("Extrayendo payload.tar.xz...", 1)
        val ok = context.extractTarXz(PAYLOAD_ASSET, base) { pct, read, total, _ ->
            val label = if (total > 0)
                "Extrayendo... ${formatBytes(read)} / ${formatBytes(total)}"
            else
                "Extrayendo... ${formatBytes(read)}"
            // pct here is 0-100 of the compressed stream; scale to 1-85 of overall
            val overall = if (pct >= 0) 1 + (pct * 84 / 100) else -1
            onProgress(label, overall)
        }
        if (!ok) {
            onProgress("Error al extraer payload.", -1)
            return@withContext false
        }

        onProgress("Aplicando permisos...", 86)
        fixPermissions(base)
        copyLoaderToNativeDir(context, base)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()
        onProgress("Payload instalado.", 90)
        true
    }

    /** Install from a user-selected file URI (SAF picker). */
    suspend fun installPayloadFromUri(
        context: Context,
        uri: Uri,
        onProgress: (msg: String, pct: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Preparando directorio...", 0)
        val base = getPayloadDir(context)
        base.deleteRecursivelySafe()
        base.mkdirs()

        // Try to get file size for progress
        val total = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) { -1L }

        onProgress("Extrayendo payload desde archivo...", 1)
        val stream = context.contentResolver.openInputStream(uri)
            ?: run { onProgress("No se pudo abrir el archivo.", -1); return@withContext false }

        val tracked = ProgressInputStream(stream, total) { read, tot ->
            val pct = if (tot > 0) ((read * 100) / tot).toInt() else -1
            val label = if (tot > 0)
                "Extrayendo... ${formatBytes(read)} / ${formatBytes(tot)}"
            else
                "Extrayendo... ${formatBytes(read)}"
            val overall = if (pct >= 0) 1 + (pct * 84 / 100) else -1
            onProgress(label, overall)
        }

        val ok = tracked.use { extractTarXzFromStream(it, base) }
        if (!ok) {
            onProgress("Error al extraer payload.", -1)
            return@withContext false
        }

        onProgress("Aplicando permisos...", 86)
        fixPermissions(base)
        copyLoaderToNativeDir(context, base)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()
        onProgress("Payload instalado.", 90)
        true
    }

    // ── Config restoration ────────────────────────────────────────────────────

    /** Restore config from bundled APK asset. */
    suspend fun restoreConfig(
        context: Context,
        onProgress: (msg: String, pct: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Restaurando configuración...", 91)
        val ok = context.extractTarGz(CONFIG_ASSET, context.filesDir) { pct, read, total, _ ->
            val label = if (total > 0)
                "Config... ${formatBytes(read)} / ${formatBytes(total)}"
            else
                "Config... ${formatBytes(read)}"
            val overall = if (pct >= 0) 91 + (pct * 7 / 100) else -1
            onProgress(label, overall)
        }
        if (!ok) {
            onProgress("Error al restaurar configuración.", -1)
            return@withContext false
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_CONFIG_RESTORED, true).apply()
        onProgress("Configuración restaurada.", 99)
        true
    }

    /** Restore config from a user-selected file URI. */
    suspend fun restoreConfigFromUri(
        context: Context,
        uri: Uri,
        onProgress: (msg: String, pct: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Restaurando configuración desde archivo...", 91)
        val stream = context.contentResolver.openInputStream(uri)
            ?: run { onProgress("No se pudo abrir el archivo.", -1); return@withContext false }

        val ok = stream.use { extractTarGzFromStream(it, context.filesDir) }
        if (!ok) {
            onProgress("Error al restaurar configuración.", -1)
            return@withContext false
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_CONFIG_RESTORED, true).apply()
        onProgress("Configuración restaurada.", 99)
        true
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    /**
     * Copy ld-linux loader to nativeLibraryDir which has the correct SELinux
     * context (system_file or apk_data_file with exec permission).
     * app_payload is labeled with a context that Android 10+ blocks for exec.
     */
    private fun copyLoaderToNativeDir(context: Context, base: File) {
        val src = File(base, "glibc/lib/ld-linux-aarch64.so.1")
        val dst = getLoaderPath(context)
        try {
            if (!src.exists()) { Log.e(TAG, "copyLoader: src missing $src"); return }
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            dst.setExecutable(true, false)
            dst.setReadable(true, false)
            Log.i(TAG, "copyLoader: ${src.absolutePath} → ${dst.absolutePath} canExec=${dst.canExecute()}")
        } catch (e: Exception) {
            Log.e(TAG, "copyLoader failed", e)
        }
    }

    /**
     * Apply 0755 to every file that must be executable.
     * Primary mechanism: setExecutable() called immediately after extraction.
     * This pass is a safety net for the critical binaries.
     */
    suspend fun fixPermissions(base: File) = withContext(Dispatchers.IO) {
        base.setExecutable(true, false)
        base.setReadable(true, false)

        // node.real may be named differently — find the actual node binary
        val nodeBin = File(base, "node/bin")
        val nodeExec = listOf("node.real", "node").map { File(nodeBin, it) }.firstOrNull { it.exists() }
        if (nodeExec == null) Log.e(TAG, "fixPermissions: no node binary found in $nodeBin")

        val critical = listOfNotNull(
            File(base, "glibc/lib/ld-linux-aarch64.so.1"),
            nodeExec,
            File(base, "lib/node_modules/openclaw/openclaw.mjs"),
        )
        critical.forEach { f ->
            if (f.exists()) {
                val rx = f.setReadable(true, false)
                val wx = f.setWritable(true, false)
                val ex = f.setExecutable(true, false)
                Log.i(TAG, "chmod ${f.name}: r=$rx w=$wx x=$ex canExec=${f.canExecute()} path=${f.absolutePath}")
            } else {
                Log.e(TAG, "fixPermissions: MISSING ${f.absolutePath}")
            }
        }

        listOf(File(base, "glibc/lib"), nodeBin).forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }

        base.walkTopDown().filter { it.isDirectory }.forEach { d ->
            d.setExecutable(true, false)
            d.setReadable(true, false)
        }

        Log.i(TAG, "fixPermissions done. base=${base.absolutePath}")
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────

    fun uninstall(context: Context) {
        getPayloadDir(context).deleteRecursivelySafe()
        getConfigDir(context).deleteRecursivelySafe()
        File(context.filesDir, "tmp").deleteRecursivelySafe()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().clear().apply()
        Log.i(TAG, "Uninstalled.")
    }

    // ── Onboard check ─────────────────────────────────────────────────────────

    /**
     * Returns true when openclaw has been configured via `openclaw onboard`.
     * We check for the presence of at least one configured provider/agent key
     * inside openclaw.json. A freshly-restored config has no providers set.
     */
    fun isOnboardComplete(context: Context): Boolean {
        val json = File(getConfigDir(context), "openclaw.json")
        if (!json.exists()) return false
        return try {
            val text = json.readText()
            // onboard writes agents.defaults.model.primary or models.providers
            text.contains("\"primary\"") || text.contains("\"providers\"") ||
            text.contains("\"openai\"")  || text.contains("\"anthropic\"") ||
            text.contains("\"google\"")  || text.contains("\"ollama\"")
        } catch (e: Exception) { false }
    }

    // ── Version ───────────────────────────────────────────────────────────────

    fun getInstalledVersion(context: Context): String? {
        val pkg = File(getPayloadDir(context), "lib/node_modules/openclaw/package.json")
        if (!pkg.exists()) return null
        return try {
            "\"version\":\\s*\"([^\"]+)\"".toRegex()
                .find(pkg.readText())?.groupValues?.get(1)
        } catch (e: Exception) { null }
    }
}
