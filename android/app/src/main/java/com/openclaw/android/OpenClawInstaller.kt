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

    fun getPayloadDir(context: Context): File = File(context.filesDir, "home/payload")
    fun getConfigDir(context: Context):  File = File(context.filesDir, ".openclaw")

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
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Preparando directorio...")
        val base = getPayloadDir(context)
        base.deleteRecursivelySafe()
        base.mkdirs()

        onProgress("Extrayendo payload.tar.xz (186 MB)...")
        val ok = context.extractTarXz(PAYLOAD_ASSET, base)
        if (!ok) {
            onProgress("Error al extraer payload.")
            return@withContext false
        }

        onProgress("Aplicando permisos...")
        fixPermissions(base)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()
        onProgress("Payload instalado correctamente.")
        true
    }

    /** Install from a user-selected file URI (SAF picker). */
    suspend fun installPayloadFromUri(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Preparando directorio...")
        val base = getPayloadDir(context)
        base.deleteRecursivelySafe()
        base.mkdirs()

        onProgress("Extrayendo payload desde archivo seleccionado...")
        val stream = context.contentResolver.openInputStream(uri)
            ?: run { onProgress("No se pudo abrir el archivo."); return@withContext false }

        val ok = stream.use { extractTarXzFromStream(it, base) }
        if (!ok) {
            onProgress("Error al extraer payload.")
            return@withContext false
        }

        onProgress("Aplicando permisos...")
        fixPermissions(base)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()
        onProgress("Payload instalado correctamente.")
        true
    }

    // ── Config restoration ────────────────────────────────────────────────────

    /** Restore config from bundled APK asset. */
    suspend fun restoreConfig(
        context: Context,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Restaurando configuración...")
        // Config tar extracts to filesDir/ so that .openclaw/ lands at filesDir/.openclaw/
        val ok = context.extractTarGz(CONFIG_ASSET, context.filesDir)
        if (!ok) {
            onProgress("Error al restaurar configuración.")
            return@withContext false
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_CONFIG_RESTORED, true).apply()
        onProgress("Configuración restaurada.")
        true
    }

    /** Restore config from a user-selected file URI. */
    suspend fun restoreConfigFromUri(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Restaurando configuración desde archivo...")
        val stream = context.contentResolver.openInputStream(uri)
            ?: run { onProgress("No se pudo abrir el archivo."); return@withContext false }

        val ok = stream.use { extractTarGzFromStream(it, context.filesDir) }
        if (!ok) {
            onProgress("Error al restaurar configuración.")
            return@withContext false
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_CONFIG_RESTORED, true).apply()
        onProgress("Configuración restaurada.")
        true
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    /**
     * Apply Os.chmod(0755) to every file that must be executable.
     * setExecutable() is unreliable on Android 12+ for ELF loaders.
     */
    suspend fun fixPermissions(base: File) = withContext(Dispatchers.IO) {
        val critical = listOf(
            File(base, "glibc/lib/ld-linux-aarch64.so.1"),
            File(base, "node/bin/node.real"),
            File(base, "lib/node_modules/openclaw/openclaw.mjs"),
        )
        critical.forEach { f ->
            if (f.exists()) f.chmodWithOs(493) // 0755
            else Log.w(TAG, "fixPermissions: missing ${f.absolutePath}")
        }

        // chmod all .so files in glibc/lib/
        File(base, "glibc/lib").walkTopDown()
            .filter { it.isFile }
            .forEach { it.chmodWithOs(493) }

        // chmod all files in node/bin/
        File(base, "node/bin").walkTopDown()
            .filter { it.isFile }
            .forEach { it.chmodWithOs(493) }
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
