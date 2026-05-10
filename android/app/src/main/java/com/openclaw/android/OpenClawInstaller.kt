package com.openclaw.android

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val TAG = "OpenClawInstaller"

const val PAYLOAD_ASSET_NAME = "payload-v2.tar.xz"
const val MIGRATION_ASSET_NAME = "openclaw-apk-migration.tar.gz"

// SharedPreferences keys
private const val PREFS_NAME            = "openclaw_install"
private const val KEY_PAYLOAD_INSTALLED = "payload_installed"
private const val KEY_CONFIG_RESTORED   = "config_restored"
private const val KEY_ONBOARD_COMPLETE  = "onboard_complete"

private const val PAYLOAD_SHA256 = "REPLACE_WITH_ACTUAL_SHA256_BEFORE_RELEASE"

object OpenClawInstaller {

    fun getPayloadDir(context: Context): File = context.getDir("payload", Context.MODE_PRIVATE)
    fun getConfigDir(context: Context):  File = File(context.filesDir, ".openclaw")

    fun isPayloadReady(context: Context): Boolean {
        val payloadDir = context.getDir("payload", Context.MODE_PRIVATE)
        return File(payloadDir, "lib/node_modules/openclaw").exists() &&
               File(context.applicationInfo.nativeLibraryDir, "libnode.so").exists()
    }

    fun uninstall(context: Context) {
        getPayloadDir(context).deleteRecursivelySafe()
        getConfigDir(context).deleteRecursivelySafe()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        Log.i(TAG, "Environment uninstalled successfully")
    }

    fun hasBundledAssets(context: Context): Boolean {
        return try {
            context.assets.open(PAYLOAD_ASSET_NAME).use { true }
        } catch (_: Exception) { false }
    }

    fun isConfigRestored(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARD_COMPLETE, false)) return true
        if (prefs.getBoolean(KEY_CONFIG_RESTORED, false)) return true
        val jsonExists = File(getConfigDir(context), "openclaw.json").exists()
        if (jsonExists) {
            prefs.edit().putBoolean(KEY_CONFIG_RESTORED, true).apply()
            return true
        }
        return false
    }

    fun verifyPayloadIntegrity(context: Context): Boolean {
        if (PAYLOAD_SHA256 == "REPLACE_WITH_ACTUAL_SHA256_BEFORE_RELEASE") {
            Log.w(TAG, "SHA-256 verification SKIPPED (dev mode)")
            return true
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8 * 1024)
            context.assets.open(PAYLOAD_ASSET_NAME).use { stream ->
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val computed = digest.digest().joinToString("") { "%02x".format(it) }
            computed.equals(PAYLOAD_SHA256, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "verifyPayloadIntegrity failed", e)
            false
        }
    }

    suspend fun installDetailed(
        context: Context,
        onProgress: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val base = getPayloadDir(context)
            base.deleteRecursivelySafe()
            base.mkdirs()

            val ok = context.extractTarXz(PAYLOAD_ASSET_NAME, base) { pct, read, total, currentFile ->
                val json = JSONObject().apply {
                    put("step", 1)
                    put("totalSteps", 2)
                    put("extractedMB", read / 1024 / 1024)
                    put("totalMB", total / 1024 / 1024)
                    put("percent", pct)
                    put("currentFile", currentFile)
                    put("stepName", PAYLOAD_ASSET_NAME)
                }.toString()
                onProgress(json)
            }
            if (!ok) throw Exception("Fallo en extracción de $PAYLOAD_ASSET_NAME")

            deployScripts(context, base)
            fixPermissions(base)

            val migrationExists = try {
                context.assets.open(MIGRATION_ASSET_NAME).close()
                true
            } catch (_: Exception) { false }

            if (migrationExists) {
                context.extractTarGz(MIGRATION_ASSET_NAME, context.filesDir) { pct, read, total, currentFile ->
                    val json = JSONObject().apply {
                        put("step", 2)
                        put("totalSteps", 2)
                        put("extractedMB", read / 1024 / 1024)
                        put("totalMB", total / 1024 / 1024)
                        put("percent", pct)
                        put("currentFile", currentFile)
                        put("stepName", MIGRATION_ASSET_NAME)
                    }.toString()
                    onProgress(json)
                }
            }

            OpenClawTerminalManager(context).createBusyboxSymlinks()

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()

            onComplete()
        } catch (e: Exception) {
            onError(e.message ?: "Error desconocido")
        }
    }

    suspend fun installPayload(
        context: Context,
        onProgress: (msg: String, pct: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val base = getPayloadDir(context)
        base.deleteRecursivelySafe()
        base.mkdirs()
        val ok = context.extractTarXz(PAYLOAD_ASSET_NAME, base) { pct, _, _, _ ->
            onProgress("Extrayendo...", pct)
        }
        if (ok) {
            deployScripts(context, base)
            fixPermissions(base)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().putBoolean(KEY_PAYLOAD_INSTALLED, true).apply()
        }
        ok
    }

    suspend fun fixPermissions(base: File) = withContext(Dispatchers.IO) {
        try {
            // Forzar permisos de ejecución en el payload con APIs de plataforma
            if (base.exists()) {
                Log.i("Installer", "Aplicando permisos globales a: ${base.absolutePath}")
                base.walkTopDown().forEach { file ->
                    try {
                        file.setReadable(true, false)
                        file.setWritable(true, false)
                        if (file.isDirectory || file.path.contains("/bin/") || file.path.contains("/lib/") || file.name.endsWith(".sh")) {
                            file.setExecutable(true, false)
                        } else {
                            // Keep readable for non-executable files
                        }
                        file.chmodWithOs()
                    } catch (e: Exception) {
                        Log.w("Installer", "chmod failed on ${file.absolutePath}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Installer", "Error aplicando chmod global", e)
        }
    }

    fun deployScripts(context: Context, base: File) {
        val binDir = File(base, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val linker = "$nativeDir/libldlinux.so"
        val nodeLib = "$nativeDir/libnode.so"

        // Crear wrapper para 'node' (Dinámico)
        val nodeWrapper = File(binDir, "node")
        if (nodeWrapper.exists()) nodeWrapper.delete()
        nodeWrapper.writeText("""
            #!/system/bin/sh
            # Detectar ruta de forma dinámica
            BIN_DIR=${'$'}(dirname "${'$'}0")
            PAYLOAD_DIR=${'$'}(dirname "${'$'}BIN_DIR")
            LINKER="$linker"
            NODE_LIB="$nodeLib"
            
            exec "${'$'}LINKER" "${'$'}NODE_LIB" "${'$'}@"
        """.trimIndent())
        nodeWrapper.chmodWithOs()
        
        // Crear wrapper para 'openclaw' (Dinámico y usa sh)
        val ocPathRel = "lib/node_modules/openclaw/openclaw.mjs"
        val openClawWrapper = File(binDir, "openclaw")
        if (openClawWrapper.exists()) openClawWrapper.delete()
        openClawWrapper.writeText("""
            #!/system/bin/sh
            BIN_DIR=${'$'}(dirname "${'$'}0")
            PAYLOAD_DIR=${'$'}(dirname "${'$'}BIN_DIR")
            exec sh "${'$'}BIN_DIR/node" "${'$'}PAYLOAD_DIR/$ocPathRel" "${'$'}@"
        """.trimIndent())
        openClawWrapper.chmodWithOs()
        
        // Crear wrapper para 'npm' (Dinámico y usa sh)
        val npmPathRel = "lib/node_modules/npm/bin/npm-cli.js"
        val npmWrapper = File(binDir, "npm")
        if (npmWrapper.exists()) npmWrapper.delete()
        npmWrapper.writeText("""
            #!/system/bin/sh
            BIN_DIR=${'$'}(dirname "${'$'}0")
            PAYLOAD_DIR=${'$'}(dirname "${'$'}BIN_DIR")
            exec sh "${'$'}BIN_DIR/node" "${'$'}PAYLOAD_DIR/$npmPathRel" "${'$'}@"
        """.trimIndent())
        npmWrapper.chmodWithOs()

        // Crear .mkshrc para alias automáticos en el terminal
        val mkshrc = File(base, ".mkshrc")
        mkshrc.writeText("""
            alias node='sh "${File(binDir, "node").absolutePath}"'
            alias npm='sh "${File(binDir, "npm").absolutePath}"'
            alias openclaw='sh "${File(binDir, "openclaw").absolutePath}"'
            export PATH=${binDir.absolutePath}:${'$'}PATH
        """.trimIndent())

        try {
            context.assets.list("scripts")?.forEach { name ->
                context.assets.open("scripts/$name").use { input ->
                    File(binDir, name).outputStream().use { input.copyTo(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy assets/scripts", e)
        }
    }

    fun isOnboardComplete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ONBOARD_COMPLETE, false)
    }

    fun markOnboardComplete(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_ONBOARD_COMPLETE, true).apply()
    }
}
