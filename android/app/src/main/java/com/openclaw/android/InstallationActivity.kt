package com.openclaw.android

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstallationActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var progressBar:    ProgressBar
    private lateinit var statusText:     TextView
    private lateinit var modeCard:       LinearLayout
    private lateinit var btnPickPayload: Button
    private lateinit var btnPickConfig:  Button
    private lateinit var btnInstall:     Button

    // ── State ─────────────────────────────────────────────────────────────────
    private var pickedPayloadUri: Uri? = null
    private var pickedConfigUri:  Uri? = null

    // ── SAF launchers ─────────────────────────────────────────────────────────
    private val pickPayload = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedPayloadUri = uri
        btnPickPayload.text = "✓  payload.tar.xz seleccionado"
        btnPickPayload.setTextColor(Color.parseColor("#4ade80"))
        checkManualReady()
    }

    private val pickConfig = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedConfigUri = uri
        btnPickConfig.text = "✓  openclaw-apk-migration.tar.gz seleccionado"
        btnPickConfig.setTextColor(Color.parseColor("#4ade80"))
        checkManualReady()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build layout first — this assigns all lateinit vars
        val root = buildLayout()
        setContentView(root)

        // Now configure visibility based on whether assets are bundled
        if (OpenClawInstaller.hasBundledAssets(this)) {
            // Mode A: assets in APK — show one-tap install button
            modeCard.visibility  = View.GONE
            btnInstall.visibility = View.VISIBLE
            btnInstall.text      = "Instalar OpenClaw"
            btnInstall.setOnClickListener { runInstallFromAssets() }
        } else {
            // Mode B: no bundled assets — show file pickers, install button hidden until both picked
            modeCard.visibility  = View.VISIBLE
            btnInstall.visibility = View.GONE
        }
    }

    // ── Install: from bundled assets ──────────────────────────────────────────

    private fun runInstallFromAssets() {
        setInstalling(true)
        lifecycleScope.launch {
            val payloadOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.installPayload(this@InstallationActivity) { msg ->
                    runOnUiThread { statusText.text = msg }
                }
            }
            if (!payloadOk) { showError("Error instalando payload."); return@launch }

            val configOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.restoreConfig(this@InstallationActivity) { msg ->
                    runOnUiThread { statusText.text = msg }
                }
            }
            if (!configOk) { showError("Error restaurando configuración."); return@launch }

            onInstallSuccess()
        }
    }

    // ── Install: from user-picked files ───────────────────────────────────────

    private fun runInstallFromFiles() {
        val payloadUri = pickedPayloadUri ?: return
        val configUri  = pickedConfigUri  ?: return

        setInstalling(true)
        lifecycleScope.launch {
            val payloadOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.installPayloadFromUri(
                    this@InstallationActivity, payloadUri
                ) { msg -> runOnUiThread { statusText.text = msg } }
            }
            if (!payloadOk) { showError("Error instalando payload."); return@launch }

            val configOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.restoreConfigFromUri(
                    this@InstallationActivity, configUri
                ) { msg -> runOnUiThread { statusText.text = msg } }
            }
            if (!configOk) { showError("Error restaurando configuración."); return@launch }

            onInstallSuccess()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun onInstallSuccess() {
        statusText.text = "¡Instalación completa! Iniciando..."
        OpenClawGatewayService.start(this)
        startActivity(Intent(this, OpenClawDashboardActivity::class.java))
        finish()
    }

    private fun setInstalling(on: Boolean) {
        progressBar.visibility   = if (on) View.VISIBLE else View.GONE
        btnInstall.isEnabled     = !on
        btnPickPayload.isEnabled = !on
        btnPickConfig.isEnabled  = !on
        if (on) modeCard.visibility = View.GONE
    }

    private fun checkManualReady() {
        if (pickedPayloadUri != null && pickedConfigUri != null) {
            btnInstall.visibility = View.VISIBLE
            btnInstall.text       = "Instalar desde archivos seleccionados"
            btnInstall.setOnClickListener { runInstallFromFiles() }
        }
    }

    private fun showError(msg: String) {
        setInstalling(false)
        // Restore pickers if in manual mode
        if (!OpenClawInstaller.hasBundledAssets(this)) {
            modeCard.visibility = View.VISIBLE
        }
        AlertDialog.Builder(this)
            .setTitle("Error de instalación")
            .setMessage(msg)
            .setPositiveButton("Salir")    { _, _ -> finish() }
            .setNegativeButton("Reintentar") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
    }

    // ── Layout builder ────────────────────────────────────────────────────────
    // IMPORTANT: all lateinit vars are assigned here, then setContentView is
    // called with the returned view — never call buildLayout() twice.

    private fun buildLayout(): ScrollView {
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        // ── Root scroll ───────────────────────────────────────────────────────
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0d0d12"))
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 56.dp(), 24.dp(), 48.dp())
            layoutParams = FrameLayout.LayoutParams(-1, -2)
        }
        root.addView(container)

        // ── Logo ──────────────────────────────────────────────────────────────
        container.addView(TextView(this).apply {
            text      = "🦀"
            textSize  = 72f
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp() }
        })

        // ── Title ─────────────────────────────────────────────────────────────
        container.addView(TextView(this).apply {
            text      = "OpenClaw"
            textSize  = 30f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6.dp() }
        })

        // ── Subtitle ──────────────────────────────────────────────────────────
        container.addView(TextView(this).apply {
            text      = "Instalación del entorno"
            textSize  = 14f
            setTextColor(Color.parseColor("#8888aa"))
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 40.dp() }
        })

        // ── Progress bar ──────────────────────────────────────────────────────
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate       = true
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#6366f1"))
            visibility            = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, 6.dp()).apply { bottomMargin = 14.dp() }
        }
        container.addView(progressBar)

        // ── Status text ───────────────────────────────────────────────────────
        statusText = TextView(this).apply {
            text      = "Listo para instalar"
            textSize  = 14f
            setTextColor(Color.parseColor("#a0a0c0"))
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 32.dp() }
        }
        container.addView(statusText)

        // ── Mode B card (manual file pickers) ─────────────────────────────────
        modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 20.dp(), 20.dp(), 20.dp())
            background  = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = 20.dp().toFloat()
                setColor(Color.parseColor("#1a1a2e"))
                setStroke(1.dp(), Color.parseColor("#2d2d4e"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24.dp() }
            visibility   = View.GONE   // shown only when no bundled assets
        }
        container.addView(modeCard)

        modeCard.addView(TextView(this).apply {
            text      = "Selecciona los archivos de instalación"
            textSize  = 15f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4.dp() }
        })

        modeCard.addView(TextView(this).apply {
            text      = "El APK no incluye los archivos de payload. Selecciónalos desde tu almacenamiento."
            textSize  = 12f
            setTextColor(Color.parseColor("#8888aa"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20.dp() }
        })

        // Payload picker button
        btnPickPayload = pickerButton("📦  Seleccionar  payload.tar.xz  (~186 MB)")
        btnPickPayload.setOnClickListener { pickPayload.launch("*/*") }
        modeCard.addView(btnPickPayload)

        // Config picker button
        btnPickConfig = pickerButton("⚙️  Seleccionar  openclaw-apk-migration.tar.gz")
        btnPickConfig.setOnClickListener { pickConfig.launch("*/*") }
        modeCard.addView(btnPickConfig)

        // ── Primary install button ─────────────────────────────────────────────
        btnInstall = Button(this).apply {
            text      = "Instalar"
            textSize  = 16f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = 16.dp().toFloat()
                setColor(Color.parseColor("#6366f1"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, 56.dp()).apply { bottomMargin = 16.dp() }
            visibility   = View.GONE   // shown after mode is determined in onCreate
        }
        container.addView(btnInstall)

        return root
    }

    private fun pickerButton(label: String): Button {
        val d = resources.displayMetrics.density
        return Button(this).apply {
            text      = label
            textSize  = 13f
            setTextColor(Color.parseColor("#a0a0c0"))
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = (12 * d).toInt().toFloat()
                setColor(Color.parseColor("#22223a"))
                setStroke((1 * d).toInt(), Color.parseColor("#3d3d5e"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, (52 * d).toInt()).apply {
                bottomMargin = (12 * d).toInt()
            }
        }
    }
}
