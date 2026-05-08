package com.openclaw.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OpenClawLogger — Logger persistente con rotación de archivos.
 *
 * - Log file: context.filesDir/logs/openclaw.log
 * - Tamaño máximo: 2 MB. Al superarlo → openclaw.log.bak + nuevo openclaw.log
 * - Thread-safe: synchronized sobre el objeto singleton
 * - Redacta tokens sensibles si están registrados via [registerSensitiveToken]
 * - También emite a logcat (Log.d) para desarrollo
 */
object OpenClawLogger {

    private const val LOG_DIR       = "logs"
    private const val LOG_FILE      = "openclaw.log"
    private const val LOG_BAK       = "openclaw.log.bak"
    private const val MAX_BYTES     = 2L * 1024 * 1024   // 2 MB
    private const val CHUNK_LINES   = 8192               // buffer de readLines

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    // Token sensible actual — nunca lo logueamos
    @Volatile private var sensitiveToken: String? = null

    // Contexto de aplicación (se inicializa en la primera llamada)
    @Volatile private var appContext: Context? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    /**
     * Debe llamarse una vez desde Application.onCreate() o desde el primer Service.
     * Acepta cualquier Context — se guarda solo applicationContext.
     */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    // ── Token redaction ───────────────────────────────────────────────────────

    /**
     * Registra un token que será redactado como "[REDACTED]" en todos los logs.
     * Llamar cada vez que se genera un nuevo token de dashboard.
     */
    fun registerSensitiveToken(token: String?) {
        sensitiveToken = token
    }

    private fun redact(message: String): String {
        val t = sensitiveToken ?: return message
        return if (t.isNotEmpty() && message.contains(t)) {
            message.replace(t, "[REDACTED]")
        } else {
            message
        }
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    /**
     * Escribe una línea de log con timestamp ISO-8601.
     * Thread-safe. Opera en el hilo llamador — invocar desde Dispatchers.IO
     * cuando se llama en volumen (e.g. stdout del gateway).
     */
    fun log(tag: String, message: String) {
        val safe = redact(message)
        // Siempre emitir a logcat
        Log.d(tag, safe)

        val ctx = appContext ?: return
        synchronized(this) {
            try {
                val logDir  = File(ctx.filesDir, LOG_DIR).also { it.mkdirs() }
                val logFile = File(logDir, LOG_FILE)

                // Rotación si supera MAX_BYTES
                if (logFile.exists() && logFile.length() >= MAX_BYTES) {
                    val bak = File(logDir, LOG_BAK)
                    bak.delete()
                    logFile.renameTo(bak)
                }

                val ts   = ISO.format(Date())
                val line = "[$ts] [$tag] $safe\n"
                FileWriter(logFile, true).use { it.write(line) }
            } catch (e: Exception) {
                Log.w("OpenClawLogger", "Failed to write log: ${e.message}")
            }
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Lee las últimas [lines] líneas del log actual.
     * Si el archivo no existe, retorna un string indicándolo.
     */
    fun getRecentLogs(lines: Int = 100): String {
        val ctx = appContext ?: return "(Logger no inicializado)"
        return synchronized(this) {
            try {
                val logFile = File(File(ctx.filesDir, LOG_DIR), LOG_FILE)
                if (!logFile.exists()) return@synchronized "(sin logs aún)"
                val all = logFile.readLines()
                if (all.size <= lines) all.joinToString("\n")
                else all.takeLast(lines).joinToString("\n")
            } catch (e: Exception) {
                "(error leyendo logs: ${e.message})"
            }
        }
    }

    /**
     * Retorna el contenido del backup (.bak) si existe.
     */
    fun getBackupLogs(lines: Int = 100): String {
        val ctx = appContext ?: return ""
        return synchronized(this) {
            try {
                val bak = File(File(ctx.filesDir, LOG_DIR), LOG_BAK)
                if (!bak.exists()) return@synchronized ""
                val all = bak.readLines()
                if (all.size <= lines) all.joinToString("\n")
                else all.takeLast(lines).joinToString("\n")
            } catch (e: Exception) { "" }
        }
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    /**
     * Elimina openclaw.log y openclaw.log.bak.
     */
    fun clearLogs() {
        val ctx = appContext ?: return
        synchronized(this) {
            try {
                val logDir = File(ctx.filesDir, LOG_DIR)
                File(logDir, LOG_FILE).delete()
                File(logDir, LOG_BAK).delete()
                Log.d("OpenClawLogger", "Logs cleared")
            } catch (e: Exception) {
                Log.w("OpenClawLogger", "clearLogs failed: ${e.message}")
            }
        }
    }

    // ── Size info ─────────────────────────────────────────────────────────────

    /** Tamaño del log actual en bytes, 0 si no existe. */
    fun currentLogSize(): Long {
        val ctx = appContext ?: return 0L
        return File(File(ctx.filesDir, LOG_DIR), LOG_FILE).length()
    }
}
