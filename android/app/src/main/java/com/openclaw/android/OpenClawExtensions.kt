package com.openclaw.android

import android.system.Os
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "OpenClawExt"

// ── Permissions ───────────────────────────────────────────────────────────────

/**
 * Best-effort chmod. On Android 12+ SELinux blocks Os.chmod() and even /system/bin/chmod
 * from the app process. The primary mechanism is `setExecutable()` from the JVM API;
 * this is a secondary pass for safety.
 */
fun File.chmodWithOs(mode: Int = 493) {
    setReadable(true, false)
    setWritable(true, false)
    if (mode and 0b001_000_000 != 0) setExecutable(true, false)
    try {
        Os.chmod(absolutePath, mode)
    } catch (_: Exception) { /* SELinux: ignore */ }
}

// ── Health check ──────────────────────────────────────────────────────────────

fun isGatewayAlive(): Boolean =
    try {
        val conn = URL(
            "http://${OpenClawConstants.GATEWAY_HOST}:${OpenClawConstants.GATEWAY_PORT}" +
            OpenClawConstants.HEALTH_ENDPOINT
        ).openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (_: Exception) {
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
