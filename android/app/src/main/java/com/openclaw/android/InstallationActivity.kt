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
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "InstallActivity"

/**
 * InstallationActivity - UI completa de instalacion
 *
 * Estados de la pantalla:
 *  1. DETECTING  -> spinner "Verificando archivos..."
 *  2. READY      -> lista de assets encontrados + boton "Instalar"
 *  3. INSTALLING -> progress bar + texto de estado + porcentaje
 *  4. DONE       -> checkmark verde + boton "Continuar al Dashboard"
 *  5. ERROR      -> mensaje de error + boton "Reintentar"
 */
class InstallationActivity : AppCompatActivity() {

    // -- Views --
    private lateinit var progressBar:    ProgressBar
    private lateinit var pctText:        TextView
    private lateinit var statusText:     TextView
    private lateinit var errorText:      TextView
    private lateinit var assetListContainer: LinearLayout
    private lateinit var modeCard:       LinearLayout
    private lateinit var btnPickPayload: Button
    private lateinit var btnPickConfig:  Button
    private lateinit var btnInstall:     Button
    private lateinit var btnContinue:    Button
    private lateinit var progressSection: LinearLayout
    private lateinit var logoText:       TextView
    private lateinit var diskSpaceText:  TextView
    private lateinit var logLines:       TextView

    // -- State --
    private var pickedPayloadUri: Uri? = null
    private var pickedConfigUri:  Uri? = null
    private var hasBundled = false

    private enum class ScreenState { DETECTING, READY, INSTALLING, DONE, ERROR }

    // -- SAF launchers --
    private val pickPayload = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedPayloadUri = uri
        btnPickPayload.text = "  payload.tar.xz seleccionado"
        btnPickPayload.setTextColor(Color.parseColor("#4ade80"))
        clearError(); checkManualReady()
    }

    private val pickConfig = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedConfigUri = uri
        btnPickConfig.text = "  openclaw-apk-migration.tar.gz seleccionado"
        btnPickConfig.setTextColor(Color.parseColor("#4ade80"))
        clearError(); checkManualReady()
    }

    // -- Lifecycle --

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OpenClawInstaller.isPayloadReady(this) && OpenClawInstaller.isConfigRestored(this)) {
            Log.i(TAG, "Already installed -> routing past installer")
            launchDashboard()
            return
        }

        setContentView(buildLayout())
        setState(ScreenState.DETECTING)

        // Detect assets in background
        lifecycleScope.launch {
            val assets = withContext(Dispatchers.IO) {
                OpenClawInstaller.detectAvailableAssets(this@InstallationActivity)
            }
            hasBundled = assets.any { it.filename.contains("payload") && it.available }

            // Populate asset list
            populateAssetList(assets)

            if (hasBundled) {
                // APK has bundled assets -> show install button
                modeCard.visibility   = View.GONE
                btnInstall.text       = "Iniciar instalacion"
                btnInstall.visibility = View.VISIBLE
                btnInstall.setOnClickListener { runInstallFromAssets() }
                // Only enable if payload is available
                val payloadAvailable = assets.any { it.filename.contains("payload") && it.available }
                btnInstall.isEnabled = payloadAvailable
            } else {
                // No bundled assets -> show manual file picker
                modeCard.visibility   = View.VISIBLE
                btnInstall.visibility = View.GONE
            }

            setState(ScreenState.READY)
        }
    }

    // -- Asset list display --

    private fun populateAssetList(assets: List<AssetInfo>) {
        assetListContainer.removeAllViews()
        val d = resources.displayMetrics.density

        for (asset in assets) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }

            val icon = TextView(this).apply {
                text = if (asset.available) "+" else "x"
                textSize = 16f
                setTextColor(if (asset.available) Color.parseColor("#4ade80") else Color.parseColor("#f87171"))
                layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), -2)
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }

            info.addView(TextView(this).apply {
                text = asset.filename
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })

            info.addView(TextView(this).apply {
                text = asset.description
                textSize = 11f
                setTextColor(Color.parseColor("#8888aa"))
            })

            row.addView(icon)
            row.addView(info)
            assetListContainer.addView(row)
        }
    }

    // -- State management --

    private fun setState(state: ScreenState) {
        when (state) {
            ScreenState.DETECTING -> {
                progressSection.visibility     = View.VISIBLE
                progressBar.isIndeterminate     = true
                statusText.text                 = "Verificando archivos..."
                pctText.visibility              = View.GONE
                assetListContainer.visibility   = View.GONE
                btnInstall.visibility           = View.GONE
                btnContinue.visibility          = View.GONE
                modeCard.visibility             = View.GONE
                errorText.visibility            = View.GONE
                diskSpaceText.visibility        = View.GONE
                logLines.visibility             = View.GONE
            }
            ScreenState.READY -> {
                progressSection.visibility     = View.GONE
                assetListContainer.visibility  = View.VISIBLE
                diskSpaceText.visibility       = View.VISIBLE
                btnContinue.visibility         = View.GONE
                errorText.visibility           = View.GONE
                logLines.visibility            = View.GONE
            }
            ScreenState.INSTALLING -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progressSection.visibility     = View.VISIBLE
                progressBar.isIndeterminate     = false
                progressBar.progress            = 0
                pctText.visibility              = View.VISIBLE
                pctText.text                    = "0%"
                statusText.text                 = "Extrayendo payload... (esto puede tardar 2-3 minutos)"
                assetListContainer.visibility   = View.GONE
                diskSpaceText.visibility        = View.GONE
                btnInstall.visibility           = View.GONE
                btnContinue.visibility          = View.GONE
                modeCard.visibility             = View.GONE
                errorText.visibility            = View.GONE
                logLines.visibility             = View.VISIBLE
                logLines.text                   = ""
                btnInstall.isEnabled            = false
            }
            ScreenState.DONE -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progressSection.visibility     = View.VISIBLE
                progressBar.isIndeterminate     = false
                progressBar.progress            = 100
                pctText.text                    = "100%"
                pctText.setTextColor(Color.parseColor("#4ade80"))
                statusText.text                 = "Instalacion completada"
                statusText.setTextColor(Color.parseColor("#4ade80"))
                logLines.visibility             = View.GONE
                btnInstall.visibility           = View.GONE
                btnContinue.visibility          = View.VISIBLE
                errorText.visibility            = View.GONE
            }
            ScreenState.ERROR -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progressSection.visibility     = View.GONE
                logLines.visibility             = View.GONE
                btnContinue.visibility          = View.GONE
                errorText.visibility            = View.VISIBLE
                // Show retry button
                if (hasBundled) {
                    btnInstall.visibility = View.VISIBLE
                    btnInstall.text       = "Reintentar instalacion"
                    btnInstall.isEnabled  = true
                    btnInstall.setOnClickListener { clearError(); runInstallFromAssets() }
                } else {
                    modeCard.visibility   = View.VISIBLE
                    pickedPayloadUri      = null
                    pickedConfigUri       = null
                    btnPickPayload.text   = "Seleccionar payload.tar.xz (~186 MB)"
                    btnPickPayload.setTextColor(Color.parseColor("#a0a0c0"))
                    btnPickConfig.text    = "Seleccionar openclaw-apk-migration.tar.gz"
                    btnPickConfig.setTextColor(Color.parseColor("#a0a0c0"))
                    btnInstall.visibility = View.GONE
                }
            }
        }
    }

    // -- Install: from bundled assets --

    private fun runInstallFromAssets() {
        setState(ScreenState.INSTALLING)
        lifecycleScope.launch {
            // PASO 0: Verify SHA-256 integrity
            appendLog("Verificando integridad SHA-256...")
            val integrityOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.verifyPayloadIntegrity(this@InstallationActivity)
            }
            if (!integrityOk) {
                onInstallFailed("Instalacion corrupta - el archivo payload esta danado.\nReinstala la app para obtener una copia limpia.")
                return@launch
            }

            val payloadOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.installPayload(this@InstallationActivity) { msg, pct ->
                    runOnUiThread { updateProgress(msg, pct) }
                }
            }
            if (!payloadOk) { onInstallFailed("Fallo la extraccion del payload."); return@launch }

            val configOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.restoreConfig(this@InstallationActivity) { msg, pct ->
                    runOnUiThread { updateProgress(msg, pct) }
                }
            }
            if (!configOk) { onInstallFailed("Fallo la restauracion de la configuracion."); return@launch }

            // Create BusyBox symlinks
            withContext(Dispatchers.IO) {
                runOnUiThread { appendLog("Configurando BusyBox...") }
                val count = OpenClawTerminalManager(this@InstallationActivity).createBusyboxSymlinks()
                Log.i(TAG, "BusyBox symlinks creados: $count")
            }

            onInstallSuccess()
        }
    }

    // -- Install: from user-picked files --

    private fun runInstallFromFiles() {
        val payloadUri = pickedPayloadUri ?: return
        val configUri  = pickedConfigUri  ?: return
        setState(ScreenState.INSTALLING)
        lifecycleScope.launch {
            val payloadOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.installPayloadFromUri(this@InstallationActivity, payloadUri) { msg, pct ->
                    runOnUiThread { updateProgress(msg, pct) }
                }
            }
            if (!payloadOk) { onInstallFailed("Fallo la extraccion del payload."); return@launch }

            val configOk = withContext(Dispatchers.IO) {
                OpenClawInstaller.restoreConfigFromUri(this@InstallationActivity, configUri) { msg, pct ->
                    runOnUiThread { updateProgress(msg, pct) }
                }
            }
            if (!configOk) { onInstallFailed("Fallo la restauracion de la configuracion."); return@launch }

            // Create BusyBox symlinks
            withContext(Dispatchers.IO) {
                runOnUiThread { appendLog("Configurando BusyBox...") }
                val count = OpenClawTerminalManager(this@InstallationActivity).createBusyboxSymlinks()
                Log.i(TAG, "BusyBox symlinks creados: $count")
            }

            onInstallSuccess()
        }
    }

    // -- Result handlers --

    private fun onInstallSuccess() {
        setState(ScreenState.DONE)
    }

    private fun onInstallFailed(reason: String) {
        Log.e(TAG, "Install failed: $reason")
        showError(reason)
        setState(ScreenState.ERROR)
    }

    private fun launchDashboard() {
        startActivity(
            Intent(this, OpenClawDashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // -- UI helpers --

    private fun updateProgress(msg: String, pct: Int) {
        statusText.text = msg
        appendLog(msg)
        if (pct in 0..100) {
            progressBar.isIndeterminate = false
            progressBar.progress        = pct
            pctText.text                = "$pct%"
            pctText.visibility          = View.VISIBLE
        } else {
            progressBar.isIndeterminate = true
            pctText.visibility          = View.GONE
        }
    }

    private fun appendLog(line: String) {
        val current = logLines.text.toString()
        val lines = current.split("\n").toMutableList()
        lines.add(line)
        // Keep only last 3 lines
        while (lines.size > 3) lines.removeAt(0)
        logLines.text = lines.joinToString("\n")
    }

    private fun checkManualReady() {
        if (pickedPayloadUri != null && pickedConfigUri != null) {
            btnInstall.visibility = View.VISIBLE
            btnInstall.text       = "Instalar desde archivos seleccionados"
            btnInstall.isEnabled  = true
            btnInstall.setOnClickListener { runInstallFromFiles() }
        }
    }

    private fun showError(msg: String) {
        errorText.text       = msg
        errorText.visibility = View.VISIBLE
    }

    private fun clearError() {
        errorText.text       = ""
        errorText.visibility = View.GONE
    }

    // -- Layout (programmatic) --

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
        logoText = TextView(this).apply {
            text      = "\uD83E\uDD80"
            textSize  = 72f
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp() }
        }
        container.addView(logoText)

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
            text      = "Instalacion del entorno"
            textSize  = 14f
            setTextColor(Color.parseColor("#8888aa"))
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24.dp() }
        })

        // Subtitle: "Se encontraron los siguientes archivos:"
        container.addView(TextView(this).apply {
            text      = "Se encontraron los siguientes archivos:"
            textSize  = 13f
            setTextColor(Color.parseColor("#a0a0c0"))
            gravity   = Gravity.START
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp() }
        })

        // Asset list container
        assetListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp() }
        }
        container.addView(assetListContainer)

        // Disk space info
        diskSpaceText = TextView(this).apply {
            text      = "Espacio en disco requerido: ~400MB"
            textSize  = 12f
            setTextColor(Color.parseColor("#6366f1"))
            gravity   = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24.dp() }
        }
        container.addView(diskSpaceText)

        // -- Progress section --
        progressSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp() }
        }

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

        // Log lines (last 3 extraction steps)
        logLines = TextView(this).apply {
            text      = ""
            textSize  = 11f
            setTextColor(Color.parseColor("#666688"))
            visibility = View.GONE
            maxLines  = 3
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16.dp() }
        }
        container.addView(logLines)

        // -- Error text --
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

        // -- Mode B card (manual file picker) --
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
            text      = "Selecciona los archivos de instalacion"
            textSize  = 15f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4.dp() }
        })
        modeCard.addView(TextView(this).apply {
            text      = "El APK no incluye los archivos de payload. Seleccionalos desde tu almacenamiento."
            textSize  = 12f
            setTextColor(Color.parseColor("#8888aa"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20.dp() }
        })

        btnPickPayload = pickerButton("Seleccionar payload.tar.xz (~186 MB)")
        btnPickPayload.setOnClickListener { pickPayload.launch("*/*") }
        modeCard.addView(btnPickPayload)

        btnPickConfig = pickerButton("Seleccionar openclaw-apk-migration.tar.gz")
        btnPickConfig.setOnClickListener { pickConfig.launch("*/*") }
        modeCard.addView(btnPickConfig)

        // -- Install button --
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

        // -- Continue button (shown after success) --
        btnContinue = Button(this).apply {
            text      = "Ir al Dashboard"
            textSize  = 16f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = 16.dp().toFloat()
                setColor(Color.parseColor("#4ade80"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, 56.dp()).apply { bottomMargin = 16.dp() }
            visibility   = View.GONE
            setOnClickListener { launchDashboard() }
        }
        container.addView(btnContinue)

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