package com.openclaw.android

import android.content.Context
import com.openclaw.android.proot.OpenClawProot

data class AssetDetectionResult(
    val alpineAvailable: Boolean,
    val alpineSizeBytes: Long,
    val alpineContents: List<String>,
    val freeSpaceBytes: Long,
    val hasEnoughSpace: Boolean
)

object AssetDetector {
    private const val TAG = "AssetDetector"

    /** Wrapper síncrono para tests — lanza corrutina en runBlocking. */
    fun detectSync(context: Context): AssetDetectionResult {
        return kotlinx.coroutines.runBlocking { detect(context) }
    }

    suspend fun detect(context: Context): AssetDetectionResult {
        val proot = OpenClawProot(context)
        val freeSpace = context.filesDir.freeSpace

        return AssetDetectionResult(
            alpineAvailable   = proot.isProotPresent(),
            alpineSizeBytes     = 10 * 1024 * 1024L, // Alpine ~10 MB descarga
            alpineContents    = emptyList(),
            freeSpaceBytes     = freeSpace,
            hasEnoughSpace     = freeSpace >= 200 * 1024 * 1024L
        )
    }
}
