package com.openclaw.android

import android.content.Context
import com.openclaw.android.proot.OpenClawProot

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

    suspend fun detect(context: Context): AssetDetectionResult {
        val proot = OpenClawProot(context)
        val freeSpace = context.filesDir.freeSpace

        return AssetDetectionResult(
            payloadAvailable   = proot.isProotPresent(),
            payloadSizeBytes     = 10 * 1024 * 1024L, // Alpine ~10 MB descarga
            payloadContents    = emptyList(),
            migrationAvailable = false,
            migrationSizeBytes   = 0,
            migrationContents  = emptyList(),
            freeSpaceBytes     = freeSpace,
            hasEnoughSpace     = freeSpace >= 200 * 1024 * 1024L
        )
    }
}
