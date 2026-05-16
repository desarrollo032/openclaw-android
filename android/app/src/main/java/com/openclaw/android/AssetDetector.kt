package com.openclaw.android

import android.content.Context
import android.util.Log
import com.openclaw.android.proot.OpenClawProot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AssetDetectionResult — versión proot.
 *
 * El frontend espera estos campos (compatibilidad con el modelo legacy):
 *   - payloadAvailable / payloadSizeBytes
 *   - migrationAvailable / migrationSizeBytes
 *   - freeSpaceBytes / hasEnoughSpace
 *
 * En el modelo proot mapeamos los campos así:
 *   payloadAvailable    = libproot.so está presente (la APK incluye proot)
 *   payloadSizeBytes    = tamaño aproximado de la descarga de Alpine (~10 MB)
 *   migrationAvailable  = el rootfs Alpine ya está instalado en filesDir
 *   migrationSizeBytes  = tamaño actual del rootfs en disco
 */
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

    /** Tamaño aproximado de la descarga Alpine + paquetes (~50 MB libres requeridos). */
    private const val REQUIRED_BYTES = 200L * 1024 * 1024

    /** Tamaño aproximado del minirootfs Alpine descomprimido. */
    private const val ALPINE_TARBALL_APPROX_BYTES = 10L * 1024 * 1024

    suspend fun detect(context: Context): AssetDetectionResult = withContext(Dispatchers.IO) {
        detectSync(context)
    }

    fun detectSync(context: Context): AssetDetectionResult {
        val proot = OpenClawProot(context)
        val rootfsSize = if (proot.isAlpineInstalled()) dirSizeSafe(proot.rootfs) else 0L
        val freeSpace = context.filesDir.freeSpace

        Log.d(TAG, "detect: proot=${proot.isProotPresent()} alpine=${proot.isAlpineInstalled()} rootfsSize=${rootfsSize}B free=${freeSpace}B")

        return AssetDetectionResult(
            payloadAvailable = proot.isProotPresent(),
            payloadSizeBytes = ALPINE_TARBALL_APPROX_BYTES,
            payloadContents = emptyList(),
            migrationAvailable = proot.isAlpineInstalled(),
            migrationSizeBytes = rootfsSize,
            migrationContents = emptyList(),
            freeSpaceBytes = freeSpace,
            hasEnoughSpace = freeSpace >= REQUIRED_BYTES
        )
    }

    private fun dirSizeSafe(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            Log.w(TAG, "dirSizeSafe failed for ${dir.absolutePath}: ${e.message}")
            0L
        }
    }
}
