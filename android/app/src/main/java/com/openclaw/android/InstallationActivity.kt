package com.openclaw.android

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "InstallActivity"

class InstallationActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var progressBar:    ProgressBar
    private lateinit var statusText:     TextView
    private lateinit var errorText:      TextView   // red error line below status
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
        clearError()
        checkManualReady()
    }

    private val pickConfig = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedConfigUri = uri
        btnPickConfig.text = "✓  openclaw-apk-migration.tar.gz seleccionado"
        btnPickConfig.setTextColor(Color.parseColor("#4ade80"))
        clearError()
        checkManualReady()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Guard: if already fully installed, skip straight to dashboard ──
        if (OpenClawInstaller.isPayloadReady(this) && OpenClawInstaller.isConfigRestored(this)) {
            Log.i(TAG, "Already installed — going to dashboard")
            launchDashboard()
            return
        }

        val root = buildLayout()
        setContentView(root)

        hasBundled = OpenClawInstaller.hasBundledAssets(this)

        if (hasBundled) {
            // Mode A: assets bundled in APK
            modeCard.visibility   = View.GONE
            btnInstall.visibility = View.VISIBLE
            btnInstall.text       = "Instalar OpenClaw"
            btnInstall.setOnClickListener { runInstallFromAssets() }
        } else {
            // Mode B: no bundled assets — user must pick files
            modeCard.visibility   = View.VISIBLE
            btnInstall.visibility = View.GONE
            statusText.text       = "Selecciona los archivos para continuar"
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
            if (!payloadOk) {
                onInstallFailed("Falló la extracción del payload.\nVerifica que el APK no esté corrupto.")
                return@launch
            }

            val configOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.restoreConfig(this@InstallationActivity) { msg ->
                    runOnUiThread { statusText.text = msg }
                }
            }
            if (!configOk) {
                onInstallFailed("Falló la restauración de la configuración.")
                return@launch
            }

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
            if (!payloadOk) {
                onInstallFailed("Falló la extracción del payload.\nAsegúrate de que el archivo sea payload.tar.xz correcto.")
                return@launch
            }

            val configOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.restoreConfigFromUri(
                    this@InstallationActivity, configUri
                ) { msg -> runOnUiThread { statusText.text = msg } }
            }
            if (!configOk) {
                onInstallFailed("Falló la restauración de la configuración.\nAsegúrate de que el archivo sea openclaw-apk-migration.tar.gz correcto.")
                return@launch
            }

            onInstallSuccess()
        }
    }

    // ── Result handlers ───────────────────────────────────────────────────────

    private fun onInstallSuccess() {
        Log.i(TAG, "Installation successful")
        progressBar.visibility = View.GONE
        statusText.text        = "✓  Instalación completa"
        statusText.setTextColor(Color.parseColor("#4ade80"))
        clearError()
        launchDashboard()
    }

    private fun onInstallFailed(reason: String) {
        Log.e(TAG, "Installation failed: $reason")
        setInstalling(false)

        // Show error inline — no dialog, user sees it immediately
        showError(reason)

        // Restore pickers in manual mode so user can retry with different files
        if (!hasBundled) {
            modeCard.visibility = View.VISIBLE
            // Reset picked state so user must re-pick
            pickedPayloadUri = null
            pickedConfigUri  = null
            btnPickPayload.text = "📦  Seleccionar  payload.tar.xz  (~186 MB)"
            btnPickPayload.setTextColor(Color.parseColor("#a0a0c0"))
            btnPickConfig.text  = "⚙️  Seleccionar  openclaw-apk-migration.tar.gz"
            btnPickConfig.setTextColor(Color.parseColor("#a0a0c0"))
            btnInstall.visibility = View.GONE
        } else {
            // Bundled mode: show retry button
            btnInstall.visibility = View.VISIBLE
            btnInstall.text       = "Reintentar instalación"
            btnInstall.setOnClickListener { clearError(); runInstallFromAssets() }
        }
    }

    private fun launchDashboard() {
        OpenClawGatewayService.start(this)
        startActivity(
            Intent(this, OpenClawDashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setInstalling(on: Boolean) {
        progressBar.visibility   = if (on) View.VISIBLE else View.GONE
        btnInstall.isEnabled     = !on
        btnPickPayload.isEnabled = !on
        btnPickConfig.isEnabled  = !on
        if (on) {
            modeCard.visibility = View.GONE
            clearError()
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

        // Progress bar (indeterminate, hidden until installing)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate       = true
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#6366f1"))
            visibility            = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, 6.dp()).apply { bottomMargin = 12.dp() }
        }
        container.addView(progressBar)

        // Status text (progress messages)
        statusText = TextView(this).apply {
            text      = "Listo para instalar"
            textSize  = 14f
            setTextColor(Color.parseColor("#a0a0c0"))
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp() }
        }
        container.addView(statusText)

        // Error text (shown only on failure, inline — no dialog)
        errorText = TextView(this).apply {
            text      = ""
            textSize  = 13f
            setTextColor(Color.parseColor("#f87171"))
            gravity   = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 20.dp()
                topMargin    = 4.dp()
            }
        }
        container.addView(errorText)

        // ── Mode B card: manual file pickers ──────────────────────────────────
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

        // ── Primary install / retry button ────────────────────────────────────
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
