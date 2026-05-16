package com.openclaw.android

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "OpenClawExt"

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

// ── File utilities ────────────────────────────────────────────────────────────

fun File.deleteRecursivelySafe() {
    try {
        if (isDirectory) listFiles()?.forEach { it.deleteRecursivelySafe() }
        delete()
    } catch (e: Exception) {
        Log.w(TAG, "deleteRecursivelySafe($absolutePath): ${e.message}")
    }
}
