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

data class AssetDetectionResult(
    val payloadAvailable: Boolean,
    val migrationAvailable: Boolean,
    val payloadSizeBytes: Long,
    val migrationSizeBytes: Long,
    val freeSpaceBytes: Long,
    val requiredSpaceBytes: Long = 400L * 1024 * 1024,
    val payloadContents: List<String>,
    val migrationContents: List<String>
) {
    val hasEnoughSpace: Boolean get() = freeSpaceBytes > requiredSpaceBytes
}

object AssetDetector {

    /**
     * Detección completa con diagnóstico robusto.
     * DEBE llamarse desde Dispatchers.IO.
     */
    suspend fun detect(context: Context): AssetDetectionResult = withContext(Dispatchers.IO) {
        // ── DIAGNÓSTICO: listar todos los assets del APK ──
        val rootAssets = try {
            context.assets.list("") ?: emptyArray()
        } catch (_: Exception) { emptyArray() }
        Log.d(TAG, "Assets en raíz del APK: ${rootAssets.toList()}")

        // Listar subdirectorios conocidos
        listOf("www", "scripts", "webkit").forEach { dir ->
            try {
                val files = context.assets.list(dir)
                if (!files.isNullOrEmpty()) {
                    Log.d(TAG, "assets/$dir/: ${files.take(10).toList()}${if (files.size > 10) " (+${files.size - 10} más)" else ""}")
                }
            } catch (_: Exception) { /* dir no existe */ }
        }

        // ── Detección robusta de cada asset ──
        val payloadAvailable = assetExists(context, PAYLOAD_ASSET_NAME)
        val migrationAvailable = assetExists(context, MIGRATION_ASSET_NAME)

        Log.d(TAG, "Detección: payload=$payloadAvailable, migration=$migrationAvailable")

        val payloadSize = if (payloadAvailable) getAssetSize(context, PAYLOAD_ASSET_NAME) else 0L
        val migrationSize = if (migrationAvailable) getAssetSize(context, MIGRATION_ASSET_NAME) else 0L

        Log.d(TAG, "Tamaños: payload=${formatBytes(payloadSize)}, migration=${formatBytes(migrationSize)}")

        val freeSpace = getFreeSpace(context)

        // List tar contents (headers only — no extraction)
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

    // ── Robust asset detection ────────────────────────────────────────

    /**
     * Búsqueda robusta de un asset. Intenta:
     *  1. Abrir por nombre exacto
     *  2. Buscar en la lista del directorio raíz (case-insensitive)
     *  3. Buscar en subdirectorios comunes
     */
    private fun assetExists(context: Context, filename: String): Boolean {
        // Intento 1 — nombre exacto
        try {
            context.assets.open(filename).close()
            Log.d(TAG, "assetExists($filename): FOUND (exact match)")
            return true
        } catch (_: Exception) { /* no encontrado por nombre exacto */ }

        // Intento 2 — búsqueda case-insensitive en el directorio raíz
        try {
            val allFiles = context.assets.list("") ?: emptyArray()
            val match = allFiles.find { it.equals(filename, ignoreCase = true) }
            if (match != null) {
                Log.w(TAG, "assetExists($filename): FOUND via case-insensitive match → '$match'")
                return true
            }
        } catch (_: Exception) { /* error listando */ }

        // Intento 3 — buscar en subdirectorios comunes
        val searchDirs = listOf("assets", "www", "scripts")
        for (dir in searchDirs) {
            try {
                context.assets.open("$dir/$filename").close()
                Log.w(TAG, "assetExists($filename): FOUND in subdirectory '$dir/'")
                return true
            } catch (_: Exception) { /* no encontrado en este dir */ }
        }

        Log.w(TAG, "assetExists($filename): NOT FOUND (tried exact, case-insensitive, subdirs)")
        return false
    }

    /**
     * Returns the actual name of an asset in the APK (handles case differences).
     * Returns null if not found.
     */
    private fun resolveAssetName(context: Context, filename: String): String? {
        // Exact match
        try {
            context.assets.open(filename).close()
            return filename
        } catch (_: Exception) {}

        // Case-insensitive in root
        try {
            val allFiles = context.assets.list("") ?: emptyArray()
            return allFiles.find { it.equals(filename, ignoreCase = true) }
        } catch (_: Exception) {}

        return null
    }

    // ── Tar listing ──────────────────────────────────────────────────

    suspend fun listTarContents(context: Context, assetName: String): List<String> =
        withContext(Dispatchers.IO) {
            val maxEntries = 200
            val entries = mutableListOf<String>()
            val resolved = resolveAssetName(context, assetName) ?: assetName

            context.assets.open(resolved).use { raw ->
                val decompressed = when {
                    resolved.endsWith(".tar.xz") -> XZCompressorInputStream(raw.buffered(1 shl 16))
                    resolved.endsWith(".tar.gz") -> GZIPInputStream(raw.buffered(1 shl 16))
                    else -> throw IllegalArgumentException("Unsupported format: $resolved")
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

    // ── Free space ───────────────────────────────────────────────────

    fun getFreeSpace(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.path)
            stat.availableBytes
        } catch (e: Exception) {
            Log.e(TAG, "getFreeSpace failed: ${e.message}")
            -1L
        }
    }

    // ── Payload readiness ────────────────────────────────────────────

    fun isPayloadReady(context: Context): Boolean {
        val payloadDir = context.getDir("payload", Context.MODE_PRIVATE)
        val openclawExists = File(payloadDir, "lib/node_modules/openclaw/openclaw.mjs").exists()
        val nodeExists = File(context.applicationInfo.nativeLibraryDir, "libnode.so").exists()
        return openclawExists && nodeExists
    }

    // ── Asset size ────────────────────────────────────────────────────

    /**
     * Returns the size of an asset. Uses openFd() first (fast).
     * Falls back to streaming read if aapt compressed the file
     * (shouldn't happen with noCompress but just in case).
     */
    private fun getAssetSize(context: Context, assetName: String): Long {
        val resolved = resolveAssetName(context, assetName) ?: assetName

        // openFd() — fast, gives on-disk size for uncompressed assets
        try {
            return context.assets.openFd(resolved).use { it.length }
        } catch (e: Exception) {
            Log.d(TAG, "openFd($resolved) failed (may be compressed by aapt): ${e.message}")
        }

        // Fallback: stream and count bytes
        try {
            return context.assets.open(resolved).use { stream ->
                var total = 0L
                val buf = ByteArray(8192)
                var n: Int
                while (stream.read(buf).also { n = it } != -1) { total += n }
                Log.d(TAG, "getAssetSize($resolved) via streaming: ${formatBytes(total)}")
                total
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAssetSize($resolved) failed completely: ${e.message}")
        }

        return -1L
    }
}
