package com.openclaw.android

import android.annotation.SuppressLint
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
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

private const val DASH_TAG = "OpenClawDash"

class OpenClawDashboardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DASHBOARD_TOKEN = "DASHBOARD_TOKEN"
        const val EXTRA_NEEDS_INSTALL   = "NEEDS_INSTALL"
    }

    // Gateway states for UI
    private enum class GwState { STOPPED, STARTING, RUNNING, ERROR }

    private lateinit var webView:        WebView
    private lateinit var dashboardPanel: LinearLayout
    private lateinit var statusDot:      TextView
    private lateinit var statusLabel:    TextView
    private lateinit var btnGateway:     Button
    private lateinit var btnTerminal:    Button
    private lateinit var btnLogs:        Button
    private lateinit var spinnerGw:      ProgressBar

    private var dashboardToken: String = ""
    private val DASHBOARD_URL = "http://127.0.0.1:18789"
    private var pollJob: Job? = null
    private var gwState = GwState.STOPPED

    private var pendingCallbackId: String? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cbId = pendingCallbackId ?: return@registerForActivityResult
        pendingCallbackId = null
        val result = JSONObject().apply {
            put("uri", uri?.toString() ?: "")
            put("success", uri != null)
        }
        webView.evaluateJavascript("window.__oc && window.__oc.emit('file_picked_$cbId', $result)", null)
    }

    fun pickFile(callbackId: String) {
        pendingCallbackId = callbackId
        filePicker.launch("*/*")
    }

    // -- Lifecycle --

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dashboardToken = intent.getStringExtra(EXTRA_DASHBOARD_TOKEN)
            ?: OpenClawGatewayService.currentToken

        setContentView(buildLayout())
        setupWebView()

        // Check if gateway is already running
        if (OpenClawGatewayService.isRunning()) {
            setGwState(GwState.RUNNING)
            showWebView()
        } else {
            setGwState(GwState.STOPPED)
        }

        // Observe gateway state from the service
        lifecycleScope.launch {
            OpenClawGatewayService.state.collect { state ->
                when (state) {
                    GatewayState.STARTING,
                    GatewayState.RESTARTING -> setGwState(GwState.STARTING)
                    GatewayState.READY      -> {
                        dashboardToken = OpenClawGatewayService.currentToken
                        setGwState(GwState.RUNNING)
                        showWebView()
                    }
                    GatewayState.FAILED     -> setGwState(GwState.ERROR)
                }
            }
        }

        // Show installation bottom sheet if payload is not ready
        val needsInstall = intent.getBooleanExtra(EXTRA_NEEDS_INSTALL, false)
        if (needsInstall && !OpenClawInstaller.isPayloadReady(this)) {
            showInstallationSheet(mandatory = true)
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE) {
            // If WebView is showing, hide it and go back to dashboard panel
            webView.visibility       = View.GONE
            dashboardPanel.visibility = View.VISIBLE
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Salir?")
            .setMessage("El gateway seguira corriendo en segundo plano.")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // -- Installation Bottom Sheet --

    /**
     * Shows the InstallationBottomSheet OVER the dashboard.
     * @param mandatory If true, the sheet cannot be dismissed until install completes.
     */
    fun showInstallationSheet(mandatory: Boolean = true) {
        val sheet = InstallationBottomSheet.newInstance(mandatory)
        sheet.setOnInstallComplete {
            Log.i(DASH_TAG, "Installation complete — refreshing dashboard state")
        }
        sheet.setOnGatewayRequested {
            Log.i(DASH_TAG, "User requested gateway start after install")
            onGatewayButtonClick()
        }
        sheet.show(supportFragmentManager, "install_sheet")
    }

    // -- Gateway state UI --

    private fun setGwState(state: GwState) {
        gwState = state
        runOnUiThread {
            when (state) {
                GwState.STOPPED -> {
                    statusDot.text = "\u25CF"
                    statusDot.setTextColor(Color.parseColor("#6b7280"))
                    statusLabel.text = "Detenido"
                    statusLabel.setTextColor(Color.parseColor("#6b7280"))
                    btnGateway.text = "INICIAR GATEWAY"
                    btnGateway.isEnabled = true
                    (btnGateway.background as? GradientDrawable)?.setColor(Color.parseColor("#6366f1"))
                    spinnerGw.visibility = View.GONE
                }
                GwState.STARTING -> {
                    statusDot.text = "\u25CF"
                    statusDot.setTextColor(Color.parseColor("#fbbf24"))
                    statusLabel.text = "Iniciando..."
                    statusLabel.setTextColor(Color.parseColor("#fbbf24"))
                    btnGateway.text = "Iniciando..."
                    btnGateway.isEnabled = false
                    (btnGateway.background as? GradientDrawable)?.setColor(Color.parseColor("#4b5563"))
                    spinnerGw.visibility = View.VISIBLE
                    startPolling()
                }
                GwState.RUNNING -> {
                    statusDot.text = "\u25CF"
                    statusDot.setTextColor(Color.parseColor("#4ade80"))
                    statusLabel.text = "En ejecucion"
                    statusLabel.setTextColor(Color.parseColor("#4ade80"))
                    btnGateway.text = "DETENER GATEWAY"
                    btnGateway.isEnabled = true
                    (btnGateway.background as? GradientDrawable)?.setColor(Color.parseColor("#ef4444"))
                    spinnerGw.visibility = View.GONE
                }
                GwState.ERROR -> {
                    statusDot.text = "\u25CF"
                    statusDot.setTextColor(Color.parseColor("#f87171"))
                    statusLabel.text = "Error"
                    statusLabel.setTextColor(Color.parseColor("#f87171"))
                    btnGateway.text = "REINTENTAR"
                    btnGateway.isEnabled = true
                    (btnGateway.background as? GradientDrawable)?.setColor(Color.parseColor("#f97316"))
                    spinnerGw.visibility = View.GONE
                }
            }
        }
    }

    // -- Gateway polling --

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            val maxRetries = 30  // 60 seconds (30 x 2s)
            for (i in 1..maxRetries) {
                val alive = withContext(Dispatchers.IO) { isGatewayAlive() }
                if (alive) {
                    dashboardToken = OpenClawGatewayService.currentToken
                    setGwState(GwState.RUNNING)
                    showWebView()
                    return@launch
                }
                val elapsed = i * 2
                runOnUiThread {
                    statusLabel.text = when {
                        elapsed < 10 -> "Iniciando gateway..."
                        elapsed < 30 -> "Cargando Node.js... (${elapsed}s)"
                        else         -> "Casi listo... (${elapsed}s)"
                    }
                }
                delay(2_000)
            }
            // Timeout
            setGwState(GwState.ERROR)
        }
    }

    // -- WebView --

    private fun showWebView() {
        pollJob?.cancel()
        runOnUiThread {
            dashboardPanel.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.alpha = 0f
            webView.animate().alpha(1f).setDuration(400).start()
            webView.loadUrl(DASHBOARD_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled     = true
            domStorageEnabled     = true
            @Suppress("DEPRECATION")
            databaseEnabled       = true
            loadWithOverviewMode  = true
            useWideViewPort       = true
            displayZoomControls   = false
            builtInZoomControls   = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs   = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.addJavascriptInterface(OpenClawBridge(this, webView), "OpenClaw")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Inject token
                if (dashboardToken.isNotEmpty()) {
                    view?.evaluateJavascript(
                        "window.__OPENCLAW_TOKEN = '${dashboardToken}';", null
                    )
                }
            }
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Log.e(DASH_TAG, "WebView error: ${error?.description}")
                    // Go back to dashboard panel on error
                    runOnUiThread {
                        webView.visibility       = View.GONE
                        dashboardPanel.visibility = View.VISIBLE
                    }
                }
            }
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!url.startsWith("http://127.0.0.1:18789")) return null
                if (dashboardToken.isEmpty()) return null

                return try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = request.method ?: "GET"
                    conn.setRequestProperty("Authorization", "Bearer $dashboardToken")
                    request.requestHeaders?.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    conn.connectTimeout = 5000
                    conn.readTimeout    = 10000
                    conn.connect()
                    val mimeType  = conn.contentType?.substringBefore(";") ?: "application/json"
                    val encoding  = conn.contentEncoding ?: "utf-8"
                    val status    = conn.responseCode
                    val stream    = if (status < 400) conn.inputStream else conn.errorStream
                    WebResourceResponse(mimeType, encoding, status, "OK",
                        conn.headerFields.mapValues { it.value.firstOrNull() ?: "" },
                        stream)
                } catch (e: Exception) {
                    null
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    // -- Layout --

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        // Root frame: WebView fills it, dashboard panel on top
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0d0d12"))
        }

        // WebView (full screen, hidden until gateway responds)
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            visibility   = View.GONE
        }
        frame.addView(webView)

        // Dashboard panel
        dashboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setPadding(32.dp(), 32.dp(), 32.dp(), 32.dp())
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(28.dp(), 36.dp(), 28.dp(), 36.dp())
            background  = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = 24.dp().toFloat()
                setColor(Color.parseColor("#1a1a2e"))
                setStroke(1.dp(), Color.parseColor("#2d2d4e"))
            }
        }

        // Logo
        card.addView(TextView(this).apply {
            text     = "\uD83E\uDD80"
            textSize = 52f
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 14.dp() }
        })

        // Title
        card.addView(TextView(this).apply {
            text     = "OpenClaw"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 8.dp() }
        })

        // Status row: dot + label
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 28.dp() }
        }
        statusDot = TextView(this).apply {
            text     = "\u25CF"
            textSize = 14f
            setTextColor(Color.parseColor("#6b7280"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp() }
        }
        statusLabel = TextView(this).apply {
            text     = "Detenido"
            textSize = 14f
            setTextColor(Color.parseColor("#6b7280"))
        }
        statusRow.addView(statusDot)
        statusRow.addView(statusLabel)
        card.addView(statusRow)

        // Spinner (shown during STARTING)
        spinnerGw = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp()).apply {
                gravity      = Gravity.CENTER_HORIZONTAL
                bottomMargin = 16.dp()
            }
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#6366f1"))
            visibility = View.GONE
        }
        card.addView(spinnerGw)

        // Gateway button
        btnGateway = Button(this).apply {
            text     = "INICIAR GATEWAY"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = 14.dp().toFloat()
                setColor(Color.parseColor("#6366f1"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, 52.dp()).apply { bottomMargin = 16.dp() }
            setOnClickListener { onGatewayButtonClick() }
        }
        card.addView(btnGateway)

        // Terminal button
        btnTerminal = Button(this).apply {
            text     = "Abrir Terminal"
            textSize = 13f
            setTextColor(Color.parseColor("#8be9fd"))
            setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(-1, 44.dp()).apply { bottomMargin = 4.dp() }
            setOnClickListener { onTerminalButtonClick() }
        }
        card.addView(btnTerminal)

        // Logs button
        btnLogs = Button(this).apply {
            text     = "Ver Logs"
            textSize = 13f
            setTextColor(Color.parseColor("#a0a0c0"))
            setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(-1, 44.dp())
            setOnClickListener {
                try {
                    startActivity(Intent(this@OpenClawDashboardActivity, OpenClawLogsActivity::class.java))
                } catch (e: Exception) {
                    Toast.makeText(this@OpenClawDashboardActivity, "Logs no disponibles", Toast.LENGTH_SHORT).show()
                }
            }
        }
        card.addView(btnLogs)

        dashboardPanel.addView(card)
        frame.addView(dashboardPanel)
        return frame
    }

    // -- Button handlers --

    private fun onGatewayButtonClick() {
        when (gwState) {
            GwState.STOPPED, GwState.ERROR -> {
                // Start gateway
                setGwState(GwState.STARTING)
                OpenClawGatewayService.start(this)
                startPolling()
            }
            GwState.RUNNING -> {
                // Stop gateway
                OpenClawGatewayService.stop(this)
                webView.visibility       = View.GONE
                dashboardPanel.visibility = View.VISIBLE
                setGwState(GwState.STOPPED)
            }
            GwState.STARTING -> {
                // Already starting, do nothing
            }
        }
    }

    private fun onTerminalButtonClick() {
        val busybox = File(applicationInfo.nativeLibraryDir, "libbusybox.so")
        if (!busybox.exists()) {
            Toast.makeText(
                this,
                "Terminal no disponible - libbusybox.so no encontrado en el APK",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        startActivity(Intent(this, OpenClawTerminalActivity::class.java))
    }
}