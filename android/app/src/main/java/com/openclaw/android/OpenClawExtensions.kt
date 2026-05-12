package com.openclaw.android

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

private const val TAG = "OpenClawExt"

// ── Progress-reporting InputStream wrapper ────────────────────────────────────

/**
 * Wraps an InputStream and calls [onProgress] with (bytesRead, totalBytes) every time data is read.
 * Used to drive a real progress bar during extraction.
 *
 * @param totalBytes Known total size (-1 if unknown → indeterminate fallback)
 * @param onProgress Called on the IO thread; post to main thread before touching UI
 */
class ProgressInputStream(
        source: InputStream,
        private val totalBytes: Long,
        private val onProgress: (read: Long, total: Long) -> Unit
) : FilterInputStream(source) {

    private var bytesRead = 0L
    private var lastReportedPct = -1

    override fun read(): Int {
        val b = super.read()
        if (b != -1) tick(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) tick(n.toLong())
        return n
    }

    private fun tick(n: Long) {
        bytesRead += n
        if (totalBytes > 0) {
            val pct = ((bytesRead * 100) / totalBytes).toInt()
            // Only fire callback when percentage changes (avoids flooding the UI)
            if (pct != lastReportedPct) {
                lastReportedPct = pct
                onProgress(bytesRead, totalBytes)
            }
        } else {
            // Unknown size — report raw bytes every ~1 MB
            if (bytesRead % (1L shl 20) < n) {
                onProgress(bytesRead, -1)
            }
        }
    }
}

// ── Asset size helper ─────────────────────────────────────────────────────────

/** Returns the uncompressed byte length of an asset, or -1 if unavailable. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun Context.assetSize(path: String): Long =
        try {
            assets.openFd(path).use { it.length }
        } catch (e: Exception) {
            -1L
        }

// ── Tar extraction ────────────────────────────────────────────────────────────

/**
 * Extract a .tar.xz asset with real byte-level progress. [onProgress] receives (pct 0-100,
 * bytesRead, totalBytes, currentFile).
 */
fun Context.extractTarXz(
        assetPath: String,
        targetDir: File,
        onProgress: ((pct: Int, read: Long, total: Long, file: String) -> Unit)? = null
): Boolean =
        try {
            val total = assetSize(assetPath)
            assets.open(assetPath).use { raw ->
                val tracked =
                        if (onProgress != null) {
                            ProgressInputStream(raw, total) { read, tot ->
                                val pct = if (tot > 0) ((read * 100) / tot).toInt() else -1
                                onProgress(pct, read, tot, "")
                            }
                        } else raw
                extractTarXzFromStream(tracked, targetDir, onProgress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractTarXz($assetPath) failed", e)
            false
        }

/** Extract a .tar.gz asset with real byte-level progress. */
fun Context.extractTarGz(
        assetPath: String,
        targetDir: File,
        onProgress: ((pct: Int, read: Long, total: Long, file: String) -> Unit)? = null
): Boolean =
        try {
            val total = assetSize(assetPath)
            assets.open(assetPath).use { raw ->
                val tracked =
                        if (onProgress != null) {
                            ProgressInputStream(raw, total) { read, tot ->
                                val pct = if (tot > 0) ((read * 100) / tot).toInt() else -1
                                onProgress(pct, read, tot, "")
                            }
                        } else raw
                extractTarGzFromStream(tracked, targetDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractTarGz($assetPath) failed", e)
            false
        }

// ── Stream-based extraction (used for both asset and URI sources) ─────────────

fun extractTarXzFromStream(
        inputStream: InputStream,
        targetDir: File,
        @Suppress("UNUSED_PARAMETER")
        onProgress: ((pct: Int, read: Long, total: Long, file: String) -> Unit)? = null
): Boolean =
        try {
            targetDir.mkdirs()
            XZCompressorInputStream(inputStream.buffered(1 shl 16)).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    @Suppress("DEPRECATION") var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val name = entry.name.trimStart('.', '/')
                        if (name.isEmpty()) {
                            @Suppress("DEPRECATION")
                            entry = tarIn.nextTarEntry
                            continue
                        }

                        val outFile = File(targetDir, name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                            outFile.setExecutable(true, false)
                            outFile.setReadable(true, false)
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                tarIn.copyTo(fos, bufferSize = 65536)
                            }
                            // Apply permissions, but BLOCK executable bits for:
                            // 1. Any file with .js/.mjs/.ts/.json extension
                            // 2. Any file located in /lib/ directory
                            val isJavascriptFile =
                                    name.endsWith(".js") ||
                                            name.endsWith(".mjs") ||
                                            name.endsWith(".ts") ||
                                            name.endsWith(".json")
                            val isInLibDir = name.contains("/lib/")
                            val isExec = entry.mode and 0b001_000_000 != 0

                            outFile.setReadable(true, false)
                            outFile.setWritable(true, false)

                            // Never make these files executable
                            if (!isJavascriptFile && !isInLibDir && isExec) {
                                outFile.setExecutable(true, false)
                            } else {
                                outFile.setExecutable(false, false)
                            }
                        }
                        @Suppress("DEPRECATION")
                        entry = tarIn.nextTarEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "extractTarXzFromStream failed", e)
            false
        }

fun extractTarGzFromStream(inputStream: InputStream, targetDir: File): Boolean =
        try {
            targetDir.mkdirs()
            GZIPInputStream(inputStream.buffered(1 shl 16)).use { gzIn ->
                TarArchiveInputStream(gzIn).use { tarIn ->
                    @Suppress("DEPRECATION") var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val name = entry.name.trimStart('.', '/')
                        if (name.isEmpty()) {
                            @Suppress("DEPRECATION")
                            entry = tarIn.nextTarEntry
                            continue
                        }

                        val outFile = File(targetDir, name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                            outFile.setExecutable(true, false)
                            outFile.setReadable(true, false)
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                tarIn.copyTo(fos, bufferSize = 65536)
                            }
                            // Apply permissions, same rules as extractTarXzFromStream!
                            val isJavascriptFile =
                                    name.endsWith(".js") ||
                                            name.endsWith(".mjs") ||
                                            name.endsWith(".ts") ||
                                            name.endsWith(".json")
                            val isInLibDir = name.contains("/lib/")
                            val isExec = entry.mode and 0b001_000_000 != 0

                            outFile.setReadable(true, false)
                            outFile.setWritable(true, false)

                            // Never make these files executable
                            if (!isJavascriptFile && !isInLibDir && isExec) {
                                outFile.setExecutable(true, false)
                            } else {
                                outFile.setExecutable(false, false)
                            }
                        }
                        @Suppress("DEPRECATION")
                        entry = tarIn.nextTarEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "extractTarGzFromStream failed", e)
            false
        }

// ── Permissions ───────────────────────────────────────────────────────────────

/**
 * Best-effort chmod. On Android 12+ SELinux blocks Os.chmod() and even /system/bin/chmod from the
 * app process. The primary mechanism is setExecutable() called immediately after file creation (in
 * extractTarXzFromStream). This function is a secondary pass for safety.
 */
fun File.chmodWithOs(mode: Int = 493) {
    // setExecutable/setReadable work on files owned by this process
    setReadable(true, false)
    setWritable(true, false)
    if (mode and 0b001_000_000 != 0) setExecutable(true, false)
    // Also try Os.chmod as a best-effort fallback
    try {
        Os.chmod(absolutePath, mode)
    } catch (_: Exception) {}
}

// ── Health check ──────────────────────────────────────────────────────────────

fun isGatewayAlive(): Boolean =
        try {
            val conn = URL("http://127.0.0.1:18789/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            false
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

fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024f)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024))
            else -> "%.2f GB".format(bytes / (1024f * 1024 * 1024))
        }
