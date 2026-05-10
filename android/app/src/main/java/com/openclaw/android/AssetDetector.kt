package com.openclaw.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AssetDetectionResult(
    val payloadAvailable: Boolean,
    val payloadSizeBytes: Long,
    val payloadContents: List<String>,
    val migrationAvailable: Boolean,
    val migrationSizeBytes: Long,
    val migrationContents: List<String>,
    val freeSpaceBytes: Long,
    val hasEnoughSpace: Boolean
)

object AssetDetector {
    private const val TAG = "AssetDetector"
    const val PAYLOAD_ASSET   = "payload-v2.tar.xz"
    const val MIGRATION_ASSET = "openclaw-apk-migration.tar.gz"

    suspend fun detect(context: Context): AssetDetectionResult = withContext(Dispatchers.IO) {
        detectSync(context)
    }

    fun detectSync(context: Context): AssetDetectionResult {
        logAllAssets(context)

        val payloadActualName   = resolveAssetName(context, PAYLOAD_ASSET)
        val migrationActualName = resolveAssetName(context, MIGRATION_ASSET)

        val payloadSize   = getAssetSize(context, payloadActualName)
        val migrationSize = getAssetSize(context, migrationActualName)

        val internalDir = context.filesDir
        val freeSpace   = internalDir.freeSpace
        val required    = 400 * 1024 * 1024L // 400MB safety margin

        return AssetDetectionResult(
            payloadAvailable   = payloadActualName != null,
            payloadSizeBytes     = payloadSize,
            payloadContents    = emptyList(),
            migrationAvailable = migrationActualName != null,
            migrationSizeBytes   = migrationSize,
            migrationContents  = emptyList(),
            freeSpaceBytes     = freeSpace,
            hasEnoughSpace     = freeSpace >= required
        )
    }

    private fun logAllAssets(context: Context) {
        try {
            val assets = context.assets.list("") ?: emptyArray()
            Log.d(TAG, "Assets disponibles en raíz: ${assets.toList()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error listando assets: ${e.message}")
        }
    }

    private fun resolveAssetName(context: Context, target: String): String? {
        // 1. Intento nombre exacto
        if (assetExists(context, target)) return target

        // 2. Intento case-insensitive en raíz
        val assets = context.assets.list("") ?: emptyArray()
        val match = assets.find { it.equals(target, ignoreCase = true) }
        if (match != null) {
            Log.w(TAG, "Encontrado con capitalización diferente: $match")
            return match
        }

        // 3. Intento en subdirectorios comunes (por si acaso)
        val commonDirs = arrayOf("scripts", "www", "data")
        for (dir in commonDirs) {
            val subAssets = context.assets.list(dir) ?: emptyArray()
            val subMatch = subAssets.find { it.equals(target, ignoreCase = true) }
            if (subMatch != null) {
                Log.w(TAG, "Encontrado en subdirectorio '$dir': $subMatch")
                return "$dir/$subMatch"
            }
        }

        return null
    }

    private fun assetExists(context: Context, filename: String): Boolean {
        return try {
            context.assets.open(filename).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getAssetSize(context: Context, filename: String?): Long {
        if (filename == null) return 0L
        return try {
            context.assets.openFd(filename).use { it.length }
        } catch (e: Exception) {
            // Fallback para archivos comprimidos por aapt si noCompress falló
            try {
                context.assets.open(filename).use { it.available().toLong() }
            } catch (e2: Exception) {
                0L
            }
        }
    }
}
