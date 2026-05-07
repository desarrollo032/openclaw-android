package com.openclaw.android

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

private const val TAG = "OpenClawExt"

// ── Tar extraction ────────────────────────────────────────────────────────────

fun Context.extractTarXz(assetPath: String, targetDir: File): Boolean {
    return try {
        assets.open(assetPath).use { stream ->
            extractTarXzFromStream(stream, targetDir)
        }
    } catch (e: Exception) {
        Log.e(TAG, "extractTarXz($assetPath) failed", e)
        false
    }
}

fun Context.extractTarGz(assetPath: String, targetDir: File): Boolean {
    return try {
        assets.open(assetPath).use { stream ->
            extractTarGzFromStream(stream, targetDir)
        }
    } catch (e: Exception) {
        Log.e(TAG, "extractTarGz($assetPath) failed", e)
        false
    }
}

fun extractTarXzFromStream(inputStream: InputStream, targetDir: File): Boolean {
    return try {
        targetDir.mkdirs()
        XZCompressorInputStream(inputStream.buffered(1 shl 16)).use { xzIn ->
            TarArchiveInputStream(xzIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    // Strip leading "./" from paths
                    val name = entry.name.trimStart('.', '/')
                    if (name.isEmpty()) { entry = tarIn.nextTarEntry; continue }

                    val outFile = File(targetDir, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> tarIn.copyTo(fos, bufferSize = 65536) }
                        // Preserve executable bit from tar mode
                        if (entry.mode and 0b001_000_000 != 0) {
                            outFile.chmodWithOs(493) // 0755
                        }
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "extractTarXzFromStream failed", e)
        false
    }
}

fun extractTarGzFromStream(inputStream: InputStream, targetDir: File): Boolean {
    return try {
        targetDir.mkdirs()
        GZIPInputStream(inputStream.buffered(1 shl 16)).use { gzIn ->
            TarArchiveInputStream(gzIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    val name = entry.name.trimStart('.', '/')
                    if (name.isEmpty()) { entry = tarIn.nextTarEntry; continue }

                    val outFile = File(targetDir, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> tarIn.copyTo(fos, bufferSize = 65536) }
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "extractTarGzFromStream failed", e)
        false
    }
}

// ── Permissions ───────────────────────────────────────────────────────────────

/**
 * Apply chmod via android.system.Os — the only reliable way on Android 12+.
 * setExecutable() does NOT work for ELF loaders on modern Android.
 * mode 493 = 0755 octal
 */
fun File.chmodWithOs(mode: Int = 493) {
    try {
        Os.chmod(absolutePath, mode)
    } catch (e: Exception) {
        Log.w(TAG, "chmod($absolutePath, $mode) failed: ${e.message}")
    }
}

// ── Health check ──────────────────────────────────────────────────────────────

fun isGatewayAlive(): Boolean {
    return try {
        val conn = URL("http://127.0.0.1:18789/health").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout    = 2000
        conn.requestMethod  = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (e: Exception) {
        false
    }
}

// ── Misc ──────────────────────────────────────────────────────────────────────

fun File.deleteRecursivelySafe() {
    try {
        if (isDirectory) listFiles()?.forEach { it.deleteRecursivelySafe() }
        delete()
    } catch (e: Exception) {
        Log.w(TAG, "deleteRecursivelySafe($absolutePath): ${e.message}")
    }
}
