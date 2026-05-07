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
            val maxRetries = 15 // 30 seconds (15 * 2s)
            
            for (i in 1..maxRetries) {
                if (checkGatewayReady()) {
                    ready = true
                    break
                }
                delay(2000)
            }

            if (ready) {
                webView.visibility = View.VISIBLE
                webView.loadUrl(DASHBOARD_URL)
            } else {
                showError("Gateway is taking too long to start. Please check the notification for status.")
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
    }

    private fun createLayout(): View {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -1)
        }
        
        progressBar = ProgressBar(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                gravity = android.view.Gravity.CENTER
                topMargin = 50
            }
        }
        
        webView = WebView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -1)
            visibility = View.GONE
        }
        
        errorLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -1)
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            setPadding(40, 40, 40, 40)
        }
        
        errorText = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 18f
        }
        
        retryButton = Button(this).apply {
            text = "Retry"
            setOnClickListener { waitForGateway() }
        }
        
        openBrowserButton = Button(this).apply {
            text = "Open in External Browser"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DASHBOARD_URL)))
            }
        }
        
        (errorLayout as android.view.ViewGroup).addView(errorText)
        (errorLayout as android.view.ViewGroup).addView(retryButton)
        (errorLayout as android.view.ViewGroup).addView(openBrowserButton)
        
        (root as android.view.ViewGroup).addView(progressBar)
        (root as android.view.ViewGroup).addView(webView)
        (root as android.view.ViewGroup).addView(errorLayout)
        
        return root
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
