package com.openclaw.android

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.util.zip.GZIPInputStream

private const val TAG = "AssetDetector"

// Asset filenames — single source of truth
const val PAYLOAD_ASSET_NAME = "payload-v2.tar.xz"
const val MIGRATION_ASSET_NAME = "openclaw-apk-migration.tar.gz"

/**
 * Result of scanning the APK's assets/ folder and the device filesystem.
 * All sizes in bytes. [payloadContents] and [migrationContents] are populated
 * lazily via [AssetDetector.listTarContents].
 */
data class AssetDetectionResult(
    val payloadAvailable: Boolean,
    val migrationAvailable: Boolean,
    val payloadSizeBytes: Long,
    val migrationSizeBytes: Long,
    val freeSpaceBytes: Long,
    val requiredSpaceBytes: Long = 400L * 1024 * 1024, // 400 MB
    val payloadContents: List<String>,
    val migrationContents: List<String>
) {
    val hasEnoughSpace: Boolean get() = freeSpaceBytes > requiredSpaceBytes
}

/**
 * AssetDetector — módulo dedicado a la detección de assets.
 *
 * Responsabilidades:
 *  - Detectar presencia de payload y migration en assets/
 *  - Listar contenido de tars SIN extraer (solo headers)
 *  - Calcular espacio libre en filesDir
 *  - Verificar integridad post-instalación (archivos reales en disco)
 *
 * REGLAS:
 *  - NUNCA hardcodear /data/data/ → usa context.getDir() / filesDir
 *  - SIEMPRE Dispatchers.IO para operaciones de archivo
 *  - isPayloadReady() verifica archivos REALES, no SharedPreferences
 */
object AssetDetector {

    /**
     * Detección completa: qué assets están en el APK, su tamaño,
     * espacio libre disponible, y lista parcial de contenidos del tar.
     *
     * Tiempo esperado: < 2 segundos (solo lee headers, no extrae).
     * DEBE llamarse desde Dispatchers.IO.
     */
    suspend fun detect(context: Context): AssetDetectionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting asset detection...")

        val payloadAvailable = isAssetPresent(context, PAYLOAD_ASSET_NAME)
        val migrationAvailable = isAssetPresent(context, MIGRATION_ASSET_NAME)

        val payloadSize = if (payloadAvailable) getAssetSize(context, PAYLOAD_ASSET_NAME) else 0L
        val migrationSize = if (migrationAvailable) getAssetSize(context, MIGRATION_ASSET_NAME) else 0L

        val freeSpace = getFreeSpace(context)

        // List tar contents (headers only — fast, ~1s for 186MB xz)
        val payloadContents = if (payloadAvailable) {
            try { listTarContents(context, PAYLOAD_ASSET_NAME) }
            catch (e: Exception) {
                Log.w(TAG, "Failed to list payload contents: ${e.message}")
                listOf("(no se pudo leer contenido)")
            }
        } else emptyList()

        val migrationContents = if (migrationAvailable) {
            try { listTarContents(context, MIGRATION_ASSET_NAME) }
            catch (e: Exception) {
                Log.w(TAG, "Failed to list migration contents: ${e.message}")
                listOf("(no se pudo leer contenido)")
            }
        } else emptyList()

        Log.d(TAG, "Detection complete: payload=$payloadAvailable (${formatBytes(payloadSize)}), " +
                "migration=$migrationAvailable (${formatBytes(migrationSize)}), " +
                "free=${formatBytes(freeSpace)}, " +
                "payloadEntries=${payloadContents.size}, migrationEntries=${migrationContents.size}")

        AssetDetectionResult(
            payloadAvailable = payloadAvailable,
            migrationAvailable = migrationAvailable,
            payloadSizeBytes = payloadSize,
            migrationSizeBytes = migrationSize,
            freeSpaceBytes = freeSpace,
            payloadContents = payloadContents,
            migrationContents = migrationContents
        )
    }

    /**
     * Lista el contenido de un tar (xz o gz) leyendo SOLO los headers.
     * No extrae ningún archivo — rápido y sin consumo de RAM significativo.
     *
     * Para tar.xz: descomprime XZ en streaming y lee headers tar.
     * Para tar.gz: descomprime GZIP en streaming y lee headers tar.
     *
     * Limita a los primeros 200 entries para evitar lecturas excesivas.
     */
    suspend fun listTarContents(context: Context, assetName: String): List<String> =
        withContext(Dispatchers.IO) {
            val maxEntries = 200
            val entries = mutableListOf<String>()

            context.assets.open(assetName).use { raw ->
                val decompressed = when {
                    assetName.endsWith(".tar.xz") -> XZCompressorInputStream(raw.buffered(1 shl 16))
                    assetName.endsWith(".tar.gz") -> GZIPInputStream(raw.buffered(1 shl 16))
                    else -> throw IllegalArgumentException("Unsupported format: $assetName")
                }

                TarArchiveInputStream(decompressed).use { tarIn ->
                    @Suppress("DEPRECATION")
                    var entry = tarIn.nextTarEntry
                    while (entry != null && entries.size < maxEntries) {
                        val name = entry.name.trimStart('.', '/')
                        if (name.isNotEmpty()) {
                            entries.add(name)
                        }
                        @Suppress("DEPRECATION")
                        entry = tarIn.nextTarEntry
                    }
                }
            }

            Log.d(TAG, "listTarContents($assetName): ${entries.size} entries found")
            entries
        }

    /**
     * Espacio libre disponible en el filesystem de filesDir.
     * Usa StatFs — preciso y sin permisos especiales.
     */
    fun getFreeSpace(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.path)
            stat.availableBytes
        } catch (e: Exception) {
            Log.e(TAG, "getFreeSpace failed: ${e.message}")
            -1L
        }
    }

    /**
     * Verifica si el payload principal ya fue extraído y está íntegro.
     * Comprueba archivos REALES en disco — NUNCA SharedPreferences como única fuente.
     */
    fun isPayloadReady(context: Context): Boolean {
        val payloadDir = context.getDir("payload", Context.MODE_PRIVATE)
        val openclawExists = File(payloadDir, "lib/node_modules/openclaw/openclaw.mjs").exists()
        val nodeExists = File(context.applicationInfo.nativeLibraryDir, "libnode.so").exists()
        return openclawExists && nodeExists
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun isAssetPresent(context: Context, assetName: String): Boolean {
        return try {
            context.assets.open(assetName).close()
            true
        } catch (_: Exception) { false }
    }

    /**
     * Returns the raw compressed size of an asset in the APK.
     * Uses openFd() which gives the on-disk size. Returns -1 if unavailable
     * (e.g., asset is stored compressed by aapt — but we have noCompress in gradle).
     */
    private fun getAssetSize(context: Context, assetName: String): Long {
        return try {
            context.assets.openFd(assetName).use { it.length }
        } catch (_: Exception) {
            // Fallback: read and count bytes (slower but works for compressed assets)
            try {
                context.assets.open(assetName).use { stream ->
                    var total = 0L
                    val buf = ByteArray(8192)
                    var n: Int
                    while (stream.read(buf).also { n = it } != -1) { total += n }
                    total
                }
            } catch (_: Exception) { -1L }
        }
    }
}
