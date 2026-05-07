package com.openclaw.android

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if already installed
        if (OpenClawInstaller.isPayloadInstalled(this) && OpenClawInstaller.isConfigRestored(this)) {
            startOpenClaw()
            return
        }

        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Start setup page
                }
            }
            webChromeClient = WebChromeClient()
        }

        webView.addJavascriptInterface(OpenClawBridge(this, webView), "OpenClaw")
        setContentView(webView)

        // Load setup route
        webView.loadUrl("file:///android_asset/www/index.html#/setup")
    }

    private fun startOpenClaw() {
        // Start gateway service
        val serviceIntent = Intent(this, OpenClawGatewayService::class.java)
        startService(serviceIntent)

        // Switch to Dashboard
        val dashboardIntent = Intent(this, OpenClawDashboardActivity::class.java)
        startActivity(dashboardIntent)
        finish()
    }

    // This can be called from the bridge when setup is complete
    fun onSetupComplete() {
        runOnUiThread {
            startOpenClaw()
        }
    }
}
