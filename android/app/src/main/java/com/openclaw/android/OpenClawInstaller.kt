package com.openclaw.android

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "OpenClawInstaller"

// SharedPreferences keys
private const val PREFS_NAME            = "openclaw_install"
private const val KEY_PAYLOAD_INSTALLED = "payload_installed"
private const val KEY_CONFIG_RESTORED   = "config_restored"
private const val KEY_ONBOARD_COMPLETE  = "onboard_complete"

// Asset names
private const val PAYLOAD_ASSET = "payload-v2.tar.xz"
private const val CONFIG_ASSET  = "openclaw-apk-migration.tar.gz"

object OpenClawInstaller {

    // ── Directory helpers ─────────────────────────────────────────────────────

    fun getPayloadDir(context: Context): File = context.getDir("payload", Context.MODE_PRIVATE)
    fun getConfigDir(context: Context):  File = File(context.filesDir, ".openclaw")

    /**
     * The glibc dynamic linker, packaged as a native .so so Android installs
     * it in nativeLibraryDir (which has execute permission on Android 12+).
     * Pass context.applicationInfo.nativeLibraryDir as [nativeDir].
     */
    fun getLoaderFile(nativeDir: String): File = File(nativeDir, "libldlinux.so")

    /**
     * The node binary, packaged as a native .so so Android installs it in
     * nativeLibraryDir (which has execute permission on Android 12+).
     * Pass context.applicationInfo.nativeLibraryDir as [nativeDir].
     */
    fun getNodeFile(nativeDir: String): File = File(nativeDir, "libnode.so")

    // ── Readiness checks ──────────────────────────────────────────────────────

    fun isPayloadReady(context: Context): Boolean {
        val prefs     = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base      = getPayloadDir(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)

        // Check the critical files that must exist for the gateway to run
        val libLoader = File(nativeDir, "libldlinux.so")
        val libNode   = File(nativeDir, "libnode.so")
        // Note: ld-linux-aarch64.so.1 from the payload is NOT used — the gateway
        // uses libldlinux.so from nativeLibraryDir (installed by Android from jniLibs/).
        val ocMjs     = File(base, "lib/node_modules/openclaw/openclaw.mjs")

        Log.d(TAG, "isPayloadReady check:")
        Log.d(TAG, "  libldlinux.so @ ${libLoader.absolutePath} → ${libLoader.exists()}")
        Log.d(TAG, "  libnode.so    @ ${libNode.absolutePath}   → ${libNode.exists()}")
        Log.d(TAG, "  openclaw.mjs  @ ${ocMjs.absolutePath}     → ${ocMjs.exists()}")

        val filesExist = libLoader.exists() && libNode.exists() && ocMjs.exists()

        if (filesExist) {
            // Files present — ensure flag is set
            if (!prefs.getBoolean(KEY_PAYLOAD_INSTALLED, false)) {
                Log.i(TAG, "Files exist but flag missing — auto-repairing prefs")
                prefs.edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()
            }
            return true
        }

        // Files missing — but if the .so libs are in nativeLibraryDir (always present after
        // APK install) and the flag is set, only the extracted payload is missing.
        // Don't send user back to installer just because of the flag — check what's actually
        // missing and only return false if the payload dir itself is empty.
        if (prefs.getBoolean(KEY_PAYLOAD_INSTALLED, false)) {
            // Flag says installed but files are gone — payload dir was cleared
            // (e.g. Android cleared app data). Reset flag so installer runs again.
            Log.w(TAG, "Flag set but files missing — clearing flag, need reinstall")
            prefs.edit().putBoolean(KEY_PAYLOAD_INSTALLED, false).apply()
        }

        return false
    }

    fun isConfigRestored(context: Context): Boolean {
        // If onboard was completed, config is implicitly restored
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARD_COMPLETE, false)) return true
        if (prefs.getBoolean(KEY_CONFIG_RESTORED, false)) return true

        // Check if the file physically exists
        val jsonExists = File(getConfigDir(context), "openclaw.json").exists()
        if (jsonExists) {
            Log.i(TAG, "Config exists but flag missing — auto-repairing prefs")
            prefs.edit().putBoolean(KEY_CONFIG_RESTORED, true).apply()
            return true
        }
        return false
    }

    fun hasBundledAssets(context: Context): Boolean = try {
        val list = context.assets.list("") ?: emptyArray()
        list.contains(PAYLOAD_ASSET) && list.contains(CONFIG_ASSET)
    } catch (e: Exception) { false }

    // ── Payload installation ──────────────────────────────────────────────────

    suspend fun installPayload(
        context: Context,
        onProgress: (msg: String, pct: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Preparando directorio...", 0)
        val base = getPayloadDir(context)
        base.deleteRecursivelySafe()
        base.mkdirs()

        onProgress("Extrayendo payload-v2.tar.xz...", 1)
        val ok = context.extractTarXz(PAYLOAD_ASSET, base) { pct, read, total, _ ->
            val label = if (total > 0)
                "Extrayendo... ${formatBytes(read)} / ${formatBytes(total)}"
            else
                "Extrayendo... ${formatBytes(read)}"
            val overall = if (pct >= 0) 1 + (pct * 84 / 100) else -1
            onProgress(label, overall)
        }
        if (!ok) {
            onProgress("Error al extraer payload.", -1)
            return@withContext false
        }

        onProgress("Aplicando permisos...", 86)
        fixPermissions(base)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()
        onProgress("Payload instalado.", 90)
        true
    }

    suspend fun installPayloadFromUri(
        context: Context,
        uri: Uri,
        onProgress: (msg: String, pct: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Preparando directorio...", 0)
        val base = getPayloadDir(context)
        base.deleteRecursivelySafe()
        base.mkdirs()

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

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()
        onProgress("Payload instalado.", 90)
        true
    }

    // ── Config restoration ────────────────────────────────────────────────────

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

    suspend fun fixPermissions(base: File) = withContext(Dispatchers.IO) {
        base.setExecutable(true, false)
        base.setReadable(true, false)

        val critical = listOf(
            File(base, "lib/node_modules/openclaw/openclaw.mjs"),
        )
        critical.forEach { f ->
            if (f.exists()) {
                val rx = f.setReadable(true, false)
                val wx = f.setWritable(true, false)
                val ex = f.setExecutable(true, false)
                Log.i(TAG, "chmod ${f.name}: r=$rx w=$wx x=$ex canExec=${f.canExecute()}")
            } else {
                Log.e(TAG, "fixPermissions: MISSING ${f.absolutePath}")
            }
        }

        // Note: libldlinux.so and libnode.so live in nativeLibraryDir —
        // Android installs them with correct execute permissions automatically.

        // chmod all .so files and all binaries in glibc/lib and bin
        listOf(File(base, "glibc/lib"), File(base, "bin")).forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }

        // All directories must be traversable
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

    fun isOnboardComplete(context: Context): Boolean {
        // Primary check: explicit flag set when `openclaw onboard` exits with code 0
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARD_COMPLETE, false)) return true

        // Fallback: inspect openclaw.json for a configured gateway or agent model,
        // which only exists after a real onboard run (not just the migration skeleton).
        val json = File(getConfigDir(context), "openclaw.json")
        if (!json.exists()) return false
        return try {
            val text = json.readText()
            // Must have gateway section with auth token OR agents section with a primary model
            // The migration config only has skills/wizard/meta — no gateway or agents
            (text.contains("\"gateway\"") && text.contains("\"token\"")) ||
            (text.contains("\"agents\"")  && text.contains("\"primary\""))
        } catch (e: Exception) { false }
    }

    fun markOnboardComplete(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_ONBOARD_COMPLETE, true).apply()
        Log.i(TAG, "Onboard marked complete")
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
