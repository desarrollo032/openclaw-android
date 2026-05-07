package com.openclaw.android

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

object OpenClawInstaller {

    private const val PAYLOAD_ASSET = "payload.tar.xz"
    private const val CONFIG_ASSET = "openclaw-apk-migration.tar.gz"
    
    fun getPayloadDir(context: Context): File = File(context.filesDir, "home/payload")
    fun getConfigDir(context: Context): File = File(context.filesDir, ".openclaw")

    suspend fun installPayloadFromAsset(context: Context, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        onProgress("Extracting payload from assets...")
        val targetDir = getPayloadDir(context)
        if (context.extractTarXz(PAYLOAD_ASSET, targetDir)) {
            finalizePayload(targetDir)
            onProgress("Payload extracted successfully.")
            true
        } else {
            onProgress("Failed to extract payload from assets.")
            false
        }
    }

    suspend fun installPayloadFromUri(context: Context, uri: Uri, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        onProgress("Extracting payload from file...")
        val targetDir = getPayloadDir(context)
        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream != null && extractTarXzFromStream(inputStream, targetDir)) {
            finalizePayload(targetDir)
            onProgress("Payload extracted successfully.")
            true
        } else {
            onProgress("Failed to extract payload from file.")
            false
        }
    }

    private fun finalizePayload(targetDir: File) {
        File(targetDir, "bin/node").chmod755()
        File(targetDir, ".installed").createNewFile()
    }

    suspend fun restoreConfigFromAsset(context: Context, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        onProgress("Restoring configuration from assets...")
        val targetDir = getConfigDir(context)
        if (context.extractTarGz(CONFIG_ASSET, targetDir)) {
            finalizeConfig(targetDir)
            onProgress("Configuration restored successfully.")
            true
        } else {
            onProgress("Failed to restore configuration from assets.")
            false
        }
    }

    suspend fun restoreConfigFromUri(context: Context, uri: Uri, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        onProgress("Restoring configuration from file...")
        val targetDir = getConfigDir(context)
        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream != null && extractTarGzFromStream(inputStream, targetDir)) {
            finalizeConfig(targetDir)
            onProgress("Configuration restored successfully.")
            true
        } else {
            onProgress("Failed to restore configuration from file.")
            false
        }
    }

    private fun finalizeConfig(targetDir: File) {
        File(targetDir, ".restored").createNewFile()
    }

    fun isPayloadInstalled(context: Context): Boolean {
        return File(getPayloadDir(context), ".installed").exists()
    }

    fun isConfigRestored(context: Context): Boolean {
        return File(getConfigDir(context), ".restored").exists()
    }

    fun hasAssets(context: Context): Boolean {
        return try {
            context.assets.list("")?.contains(PAYLOAD_ASSET) == true &&
            context.assets.list("")?.contains(CONFIG_ASSET) == true
        } catch (e: Exception) {
            false
        }
    }

    fun uninstall(context: Context) {
        getPayloadDir(context).deleteRecursivelySafe()
        getConfigDir(context).deleteRecursivelySafe()
        File(context.filesDir, "tmp").deleteRecursivelySafe()
    }

    fun getInstalledVersion(context: Context): String? {
        val packageJson = File(getPayloadDir(context), "lib/node_modules/openclaw/package.json")
        if (!packageJson.exists()) return null
        return try {
            val content = packageJson.readText()
            "\"version\":\\s*\"([^\"]+)\"".toRegex().find(content)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}
