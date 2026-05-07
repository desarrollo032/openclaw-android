package com.openclaw.android

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class OpenClawDashboardActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorLayout: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var openBrowserButton: Button
    
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private val DASHBOARD_URL = "http://127.0.0.1:18789"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())
        
        setupWebView()
        waitForGateway()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.addJavascriptInterface(OpenClawBridge(this, webView), "OpenClaw")
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                showError("Error loading dashboard: ${error?.description}")
            }
        }
        
        webView.webChromeClient = WebChromeClient()
    }

    private fun waitForGateway() {
        progressBar.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE
        webView.visibility = View.GONE

        activityScope.launch {
            var ready = false
            val maxRetries = 45 // 90 seconds (45 * 2s) — Node.js can take time on first boot
            
            for (i in 1..maxRetries) {
                if (checkGatewayReady()) {
                    ready = true
                    break
                }
                // Update status text with elapsed time
                val elapsed = i * 2
                val statusMsg = when {
                    elapsed < 10 -> "Iniciando OpenClaw..."
                    elapsed < 30 -> "Cargando entorno Node.js... (${elapsed}s)"
                    elapsed < 60 -> "Esto puede tardar un momento... (${elapsed}s)"
                    else -> "Casi listo... (${elapsed}s)"
                }
                withContext(Dispatchers.Main) {
                    errorText.text = statusMsg
                    errorLayout.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    retryButton.visibility = View.GONE
                    openBrowserButton.visibility = View.GONE
                }
                delay(2000)
            }

            if (ready) {
                errorLayout.visibility = View.GONE
                progressBar.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.loadUrl(DASHBOARD_URL)
            } else {
                retryButton.visibility = View.VISIBLE
                openBrowserButton.visibility = View.VISIBLE
                showError("El gateway tardó demasiado en iniciar. Revisa la notificación para ver el estado.")
            }
        }
    }

    private suspend fun checkGatewayReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL("$DASHBOARD_URL/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        webView.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
        errorText.text = message
        retryButton.visibility = View.VISIBLE
        openBrowserButton.visibility = View.VISIBLE
    }

    private fun createLayout(): View {
        // Full-screen frame: WebView fills it, loading overlay sits on top
        val frame = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(android.graphics.Color.parseColor("#0d0d0f"))
        }

        webView = WebView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
            visibility = View.GONE
        }

        // Loading / status overlay (centered card)
        val overlayRoot = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(40), dpToPx(40), dpToPx(40), dpToPx(40))
        }

        // Card container
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(32), dpToPx(40), dpToPx(32), dpToPx(40))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(android.graphics.Color.parseColor("#1a1a2e"))
                setStroke(dpToPx(1), android.graphics.Color.parseColor("#2d2d4e"))
            }
        }

        // Logo / icon area
        val logoText = TextView(this).apply {
            text = "🦀"
            textSize = 56f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = dpToPx(16)
            }
        }

        val titleText = TextView(this).apply {
            text = "OpenClaw"
            textSize = 26f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = dpToPx(8)
            }
        }

        val subtitleText = TextView(this).apply {
            text = "Iniciando gateway..."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#8888aa"))
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = dpToPx(32)
            }
        }

        // Spinner
        progressBar = ProgressBar(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(24)
            }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#6366f1")
            )
        }

        // Status message (shown while waiting)
        errorText = TextView(this).apply {
            text = "Iniciando OpenClaw..."
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#a0a0c0"))
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dpToPx(24)
            }
        }

        // Error-only buttons (hidden during normal loading)
        errorLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }

        retryButton = Button(this).apply {
            text = "Reintentar"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(android.graphics.Color.parseColor("#6366f1"))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, dpToPx(52)).apply {
                bottomMargin = dpToPx(12)
            }
            visibility = View.GONE
            setOnClickListener { waitForGateway() }
        }

        openBrowserButton = Button(this).apply {
            text = "Abrir en Navegador Externo"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#6366f1"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, dpToPx(44))
            visibility = View.GONE
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DASHBOARD_URL)))
            }
        }

        (errorLayout as android.view.ViewGroup).addView(retryButton)
        (errorLayout as android.view.ViewGroup).addView(openBrowserButton)

        (card as android.view.ViewGroup).addView(logoText)
        (card as android.view.ViewGroup).addView(titleText)
        (card as android.view.ViewGroup).addView(subtitleText)
        (card as android.view.ViewGroup).addView(progressBar)
        (card as android.view.ViewGroup).addView(errorText)
        (card as android.view.ViewGroup).addView(errorLayout)

        (overlayRoot as android.view.ViewGroup).addView(card)

        (frame as android.view.ViewGroup).addView(webView)
        (frame as android.view.ViewGroup).addView(overlayRoot)

        return frame
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }


    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
