package com.openclaw.android

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "InstallActivity"

class InstallationActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var progressBar:    ProgressBar   // determinate 0-100
    private lateinit var pctText:        TextView      // "42%"
    private lateinit var statusText:     TextView      // current operation
    private lateinit var bytesText:      TextView      // "87.3 MB / 186 MB"
    private lateinit var errorText:      TextView
    private lateinit var modeCard:       LinearLayout
    private lateinit var btnPickPayload: Button
    private lateinit var btnPickConfig:  Button
    private lateinit var btnInstall:     Button

    // ── State ─────────────────────────────────────────────────────────────────
    private var pickedPayloadUri: Uri? = null
    private var pickedConfigUri:  Uri? = null
    private var hasBundled       = false

    // ── SAF launchers ─────────────────────────────────────────────────────────
    private val pickPayload = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedPayloadUri = uri
        btnPickPayload.text = "✓  payload.tar.xz seleccionado"
        btnPickPayload.setTextColor(Color.parseColor("#4ade80"))
        clearError(); checkManualReady()
    }

    private val pickConfig = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedConfigUri = uri
        btnPickConfig.text = "✓  openclaw-apk-migration.tar.gz seleccionado"
        btnPickConfig.setTextColor(Color.parseColor("#4ade80"))
        clearError(); checkManualReady()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OpenClawInstaller.isPayloadReady(this) && OpenClawInstaller.isConfigRestored(this)) {
            Log.i(TAG, "Already installed — routing past installer")
            launchNext()
            return
        }

        setContentView(buildLayout())

        hasBundled = OpenClawInstaller.hasBundledAssets(this)
        if (hasBundled) {
            // APK has bundled assets — auto-install mode
            modeCard.visibility   = View.GONE
            btnInstall.visibility = View.VISIBLE
            btnInstall.text       = "Instalar OpenClaw"
            btnInstall.setOnClickListener { runInstallFromAssets() }
        } else {
            // No bundled assets — show manual file picker
            // But first check if this is a fresh APK install over existing data
            // by checking if the config file exists (migration was restored before)
            val configExists = OpenClawInstaller.isConfigRestored(this)
            modeCard.visibility   = View.VISIBLE
            btnInstall.visibility = View.GONE
            statusText.text = if (configExists)
                "El payload fue eliminado. Selecciona los archivos para reinstalar."
            else
                "Selecciona los archivos para continuar"
        }
    }

    // ── Install: from bundled assets ──────────────────────────────────────────

    private fun runInstallFromAssets() {
        setInstalling(true)
        lifecycleScope.launch {
            val payloadOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.installPayload(this@InstallationActivity) { msg, pct ->
                    runOnUiThread { updateProgress(msg, pct) }
                }
            }
            if (!payloadOk) { onInstallFailed("Falló la extracción del payload."); return@launch }

            val configOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.restoreConfig(this@InstallationActivity) { msg, pct ->
                    runOnUiThread { updateProgress(msg, pct) }
                }
            }
            if (!configOk) { onInstallFailed("Falló la restauración de la configuración."); return@launch }

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
                OpenClawInstaller.installPayloadFromUri(this@InstallationActivity, payloadUri) { msg, pct ->
                    runOnUiThread { updateProgress(msg, pct) }
                }
            }
            if (!payloadOk) { onInstallFailed("Falló la extracción del payload."); return@launch }

            val configOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.restoreConfigFromUri(this@InstallationActivity, configUri) { msg, pct ->
                    runOnUiThread { updateProgress(msg, pct) }
                }
            }
            if (!configOk) { onInstallFailed("Falló la restauración de la configuración."); return@launch }

            onInstallSuccess()
        }
    }

    // ── Result handlers ───────────────────────────────────────────────────────

    private fun onInstallSuccess() {
        updateProgress("✓  Instalación completa", 100)
        statusText.setTextColor(Color.parseColor("#4ade80"))
        pctText.setTextColor(Color.parseColor("#4ade80"))
        clearError()
        launchNext()
    }

    private fun onInstallFailed(reason: String) {
        Log.e(TAG, "Install failed: $reason")
        setInstalling(false)
        showError(reason)
        if (!hasBundled) {
            modeCard.visibility   = View.VISIBLE
            pickedPayloadUri      = null
            pickedConfigUri       = null
            btnPickPayload.text   = "📦  Seleccionar  payload.tar.xz  (~186 MB)"
            btnPickPayload.setTextColor(Color.parseColor("#a0a0c0"))
            btnPickConfig.text    = "⚙️  Seleccionar  openclaw-apk-migration.tar.gz"
            btnPickConfig.setTextColor(Color.parseColor("#a0a0c0"))
            btnInstall.visibility = View.GONE
        } else {
            btnInstall.visibility = View.VISIBLE
            btnInstall.text       = "Reintentar instalación"
            btnInstall.setOnClickListener { clearError(); runInstallFromAssets() }
        }
    }

    private fun launchNext() {
        val next = if (OpenClawInstaller.isOnboardComplete(this)) {
            OpenClawGatewayService.start(this)
            OpenClawDashboardActivity::class.java
        } else {
            OnboardActivity::class.java
        }
        startActivity(
            Intent(this, next)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    /**
     * Update the progress bar, percentage label, and status text.
     * @param pct  0-100 for determinate; -1 to keep indeterminate animation
     */
    private fun updateProgress(msg: String, pct: Int) {
        statusText.text = msg
        if (pct in 0..100) {
            progressBar.isIndeterminate = false
            progressBar.progress        = pct
            pctText.text                = "$pct%"
            pctText.visibility          = View.VISIBLE
        } else {
            // -1 = unknown size, keep spinner
            progressBar.isIndeterminate = true
            pctText.visibility          = View.GONE
        }
    }

    private fun checkManualReady() {
        if (pickedPayloadUri != null && pickedConfigUri != null) {
            btnInstall.visibility = View.VISIBLE
            btnInstall.text       = "Instalar desde archivos seleccionados"
            btnInstall.setOnClickListener { runInstallFromFiles() }
        }
    }

    private fun showError(msg: String) {
        errorText.text       = "⚠  $msg"
        errorText.visibility = View.VISIBLE
    }

    private fun clearError() {
        errorText.text       = ""
        errorText.visibility = View.GONE
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(): ScrollView {
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0d0d12"))
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 56.dp(), 24.dp(), 48.dp())
        }
        root.addView(container)

        // Logo
        container.addView(TextView(this).apply {
            text      = "🦀"
            textSize  = 72f
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp() }
        })

        // Title
        container.addView(TextView(this).apply {
            text      = "OpenClaw"
            textSize  = 30f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6.dp() }
        })

        // Subtitle
        container.addView(TextView(this).apply {
            text      = "Instalación del entorno"
            textSize  = 14f
            setTextColor(Color.parseColor("#8888aa"))
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 36.dp() }
        })

        // ── Progress section ──────────────────────────────────────────────────
        val progressSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = View.GONE   // shown by setInstalling(true)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp() }
        }
        // Bind progressBar here so setInstalling can toggle its parent
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max             = 100
            progress        = 0
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(Color.parseColor("#6366f1"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#22223a"))
            layoutParams = LinearLayout.LayoutParams(-1, 10.dp()).apply { bottomMargin = 6.dp() }
        }
        progressSection.addView(progressBar)

        // Row: status text (left) + percentage (right)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4.dp() }
        }
        statusText = TextView(this).apply {
            text      = ""
            textSize  = 13f
            setTextColor(Color.parseColor("#a0a0c0"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        pctText = TextView(this).apply {
            text      = "0%"
            textSize  = 15f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#6366f1"))
            gravity   = Gravity.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        statusRow.addView(statusText)
        statusRow.addView(pctText)
        progressSection.addView(statusRow)

        container.addView(progressSection)

        // Override setInstalling to also toggle progressSection
        // (we do this by keeping a reference and toggling in setInstalling)
        // Store reference for use in setInstalling
        progressBar.tag = progressSection

        // ── Error text ────────────────────────────────────────────────────────
        errorText = TextView(this).apply {
            text      = ""
            textSize  = 13f
            setTextColor(Color.parseColor("#f87171"))
            gravity   = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 16.dp(); topMargin = 4.dp()
            }
        }
        container.addView(errorText)

        // ── Mode B card ───────────────────────────────────────────────────────
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
            visibility   = View.GONE
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

        btnPickPayload = pickerButton("📦  Seleccionar  payload.tar.xz  (~186 MB)")
        btnPickPayload.setOnClickListener { pickPayload.launch("*/*") }
        modeCard.addView(btnPickPayload)

        btnPickConfig = pickerButton("⚙️  Seleccionar  openclaw-apk-migration.tar.gz")
        btnPickConfig.setOnClickListener { pickConfig.launch("*/*") }
        modeCard.addView(btnPickConfig)

        // ── Install button ────────────────────────────────────────────────────
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
            visibility   = View.GONE
        }
        container.addView(btnInstall)

        return root
    }

    // Override setInstalling to also show/hide the progress section
    private fun setInstalling(on: Boolean) {
        val progressSection = progressBar.tag as? View
        progressSection?.visibility = if (on) View.VISIBLE else View.GONE
        btnInstall.isEnabled     = !on
        btnPickPayload.isEnabled = !on
        btnPickConfig.isEnabled  = !on
        if (on) { modeCard.visibility = View.GONE; clearError() }
        if (!on) {
            progressBar.progress = 0
            pctText.visibility   = View.GONE
        }
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
