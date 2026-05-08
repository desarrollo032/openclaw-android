package com.openclaw.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OpenClawLogsActivity
 *
 * Pantalla de diagnóstico que muestra los logs persistentes del gateway.
 * - Usa ViewBinding inflado desde activity_logs.xml
 * - Carga OpenClawLogger.getRecentLogs(200) al abrirse
 * - Auto-scroll al final
 * - Botón "Copiar" → clipboard
 * - Botón "Limpiar" → clearLogs() + recarga
 * - Botón "←" → finish()
 *
 * Se abre desde la notificación del ForegroundService (acción "Ver logs")
 * y opcionalmente desde OpenClawDashboardActivity.
 */
class OpenClawLogsActivity : AppCompatActivity() {

    // ── Views (sin ViewBinding generado — usamos findViewById para no depender
    //    de un binding propio, ya que activity_logs.xml no está en el buildFeatures
    //    hasta que se compile; usamos la misma técnica que OpenClawDashboardActivity) ──
    private lateinit var scrollView:  ScrollView
    private lateinit var logsText:    TextView
    private lateinit var statusBar:   TextView
    private lateinit var logSizeText: TextView
    private lateinit var btnCopy:     Button
    private lateinit var btnClear:    Button

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        scrollView  = findViewById(R.id.logsScrollView)
        logsText    = findViewById(R.id.logsTextView)
        statusBar   = findViewById(R.id.logsStatusBar)
        logSizeText = findViewById(R.id.logSizeText)
        btnCopy     = findViewById(R.id.btnCopy)
        btnClear    = findViewById(R.id.btnClear)

        // Botón atrás
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        // Botón copiar
        btnCopy.setOnClickListener { copyLogsToClipboard() }

        // Botón limpiar
        btnClear.setOnClickListener { clearAndRefresh() }

        // Cargar logs al abrir
        loadLogs()
    }

    override fun onResume() {
        super.onResume()
        // Refrescar al volver a la pantalla
        loadLogs()
    }

    // ── Load logs ─────────────────────────────────────────────────────────────

    private fun loadLogs() {
        lifecycleScope.launch {
            val (content, sizeKb, lineCount) = withContext(Dispatchers.IO) {
                val text  = OpenClawLogger.getRecentLogs(200)
                val size  = OpenClawLogger.currentLogSize()
                val lines = text.lines().size
                Triple(text, size / 1024L, lines)
            }

            logsText.text = content
            logSizeText.text = "${sizeKb} KB"
            statusBar.text = "Mostrando $lineCount líneas · ${sizeKb} KB · " +
                             "toca y mantén para seleccionar texto"

            // Auto-scroll al final
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    // ── Copy ──────────────────────────────────────────────────────────────────

    private fun copyLogsToClipboard() {
        val text = logsText.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Sin logs que copiar", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("openclaw_logs", text))
        Toast.makeText(this, "Logs copiados al portapapeles", Toast.LENGTH_SHORT).show()
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    private fun clearAndRefresh() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { OpenClawLogger.clearLogs() }
            logsText.text = "(logs eliminados)"
            logSizeText.text = "0 KB"
            statusBar.text = "Logs borrados"
            Toast.makeText(
                this@OpenClawLogsActivity,
                "Logs eliminados",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
