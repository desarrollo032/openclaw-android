package com.openclaw.android

import android.annotation.SuppressLint
import android.content.Intent
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

class OpenClawDashboardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DASHBOARD_TOKEN = "DASHBOARD_TOKEN"
    }

    private lateinit var webView:          WebView
    private lateinit var progressBar:      ProgressBar
    private lateinit var statusCard:       View
    private lateinit var statusText:       TextView
    private lateinit var retryButton:      Button
    private lateinit var openBrowserButton: Button
    private lateinit var terminalButton:   Button

    // Token de autenticación recibido del Intent (o leido del Service companion)
    private var dashboardToken: String = ""

    private val DASHBOARD_URL = "file:///android_asset/www/index.html"
    private var waitJob: Job? = null

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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Leer token del Intent; fallback al valor actual del Service companion
        dashboardToken = intent.getStringExtra(EXTRA_DASHBOARD_TOKEN)
            ?: OpenClawGatewayService.currentToken

        if (dashboardToken.isEmpty()) {
            Log.w("OpenClawDash", "Dashboard token is empty — gateway may not be running yet")
            // No bloqueamos: el token puede llegar cuando el gateway arranque
        }

        setContentView(buildLayout())
        setupWebView()

        // Observe gateway state from the service
        lifecycleScope.launch {
            OpenClawGatewayService.state.collect { state ->
                when (state) {
                    GatewayState.STARTING,
                    GatewayState.RESTARTING -> showLoading(
                        if (state == GatewayState.RESTARTING) "Reiniciando gateway..." else "Iniciando gateway..."
                    )
                    GatewayState.READY      -> loadDashboard()
                    GatewayState.FAILED     -> showError("El gateway falló. Verifica la notificación.")
                }
            }
        }

        // Also poll directly — covers the case where service was already running
        startPolling()
    }

    override fun onDestroy() {
        waitJob?.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("¿Salir?")
            .setMessage("El gateway seguirá corriendo en segundo plano.")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Gateway polling ───────────────────────────────────────────────────────

    private fun startPolling() {
        waitJob?.cancel()
        showLoading("Iniciando gateway...")

        waitJob = lifecycleScope.launch {
            val maxRetries = 30   // 60 seconds (30 × 2s)
            for (i in 1..maxRetries) {
                val alive = withContext(Dispatchers.IO) { isGatewayAlive() }
                if (alive) {
                    loadDashboard()
                    return@launch
                }
                val elapsed = i * 2
                val msg = when {
                    elapsed < 10 -> "Iniciando gateway..."
                    elapsed < 30 -> "Cargando Node.js... (${elapsed}s)"
                    else         -> "Casi listo... (${elapsed}s)"
                }
                showLoading(msg)
                delay(2_000)
            }
            // Timeout
            showError("El gateway tardó demasiado. Revisa la notificación.")
        }
    }

    // ── WebView ───────────────────────────────────────────────────────────────

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
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.addJavascriptInterface(OpenClawBridge(this, webView), "OpenClaw")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                statusCard.visibility  = View.GONE
                webView.visibility     = View.VISIBLE
                // Inyectar token en window.__OPENCLAW_TOKEN para que el frontend lo use
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
                    showError("Error cargando dashboard: \${error?.description}")
                }
            }
            // Añadir header Authorization en todas las requests al gateway
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                // Solo interceptar requests al gateway local (no el file:// del HTML)
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
                    null   // Fallback: dejar que el WebView haga la request normal
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    private fun loadDashboard() {
        waitJob?.cancel()
        progressBar.visibility = View.GONE
        statusCard.visibility  = View.GONE
        webView.visibility     = View.VISIBLE
        webView.loadUrl(DASHBOARD_URL)
    }

    // ── UI state helpers ──────────────────────────────────────────────────────

    private fun showLoading(msg: String) {
        progressBar.visibility      = View.VISIBLE
        statusCard.visibility       = View.VISIBLE
        webView.visibility          = View.GONE
        statusText.text             = msg
        retryButton.visibility      = View.GONE
        openBrowserButton.visibility = View.GONE
        terminalButton.visibility   = View.GONE
    }

    private fun showError(msg: String) {
        progressBar.visibility      = View.GONE
        statusCard.visibility       = View.VISIBLE
        webView.visibility          = View.GONE
        statusText.text             = msg
        retryButton.visibility      = View.VISIBLE
        openBrowserButton.visibility = View.VISIBLE
        terminalButton.visibility   = View.VISIBLE
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        // Root frame: WebView fills it, overlay on top
        val frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0d0d12"))
        }

        // WebView (full screen, hidden until ready)
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            visibility   = View.GONE
        }
        frame.addView(webView)

        // Overlay: centered card
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setPadding(32.dp(), 32.dp(), 32.dp(), 32.dp())
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(28.dp(), 36.dp(), 28.dp(), 36.dp())
            background  = android.graphics.drawable.GradientDrawable().apply {
                shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24.dp().toFloat()
                setColor(android.graphics.Color.parseColor("#1a1a2e"))
                setStroke(1.dp(), android.graphics.Color.parseColor("#2d2d4e"))
            }
        }

        // Logo
        card.addView(TextView(this).apply {
            text     = "🦀"
            textSize = 52f
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 14.dp() }
        })

        // Title
        card.addView(TextView(this).apply {
            text     = "OpenClaw"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 28.dp() }
        })

        // Spinner
        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(44.dp(), 44.dp()).apply {
                gravity      = Gravity.CENTER_HORIZONTAL
                bottomMargin = 20.dp()
            }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#6366f1")
            )
        }
        card.addView(progressBar)

        // Status text
        statusText = TextView(this).apply {
            text     = "Iniciando gateway..."
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#a0a0c0"))
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20.dp() }
        }
        card.addView(statusText)

        // Status card (wraps text + buttons)
        statusCard = card

        // Retry button
        retryButton = Button(this).apply {
            text     = "Reintentar"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12.dp().toFloat()
                setColor(android.graphics.Color.parseColor("#6366f1"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, 52.dp()).apply { bottomMargin = 10.dp() }
            visibility   = View.GONE
            setOnClickListener {
                OpenClawGatewayService.start(this@OpenClawDashboardActivity)
                startPolling()
            }
        }
        card.addView(retryButton)

        // Open in browser button
        openBrowserButton = Button(this).apply {
            text     = "Abrir en navegador externo"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#6366f1"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(-1, 44.dp())
            visibility   = View.GONE
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DASHBOARD_URL)))
            }
        }
        card.addView(openBrowserButton)

        // Terminal button — siempre visible en el overlay (útil si el gateway falla)
        terminalButton = Button(this).apply {
            text     = "⌨ Abrir terminal"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#8be9fd"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(-1, 44.dp()).apply { topMargin = 4.dp() }
            visibility   = View.GONE   // visible en showError()
            setOnClickListener {
                startActivity(Intent(this@OpenClawDashboardActivity, OpenClawTerminalActivity::class.java))
            }
        }
        card.addView(terminalButton)

        overlay.addView(card)
        frame.addView(overlay)
        return frame
    }
}
