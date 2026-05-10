package com.openclaw.android

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.openclaw.android.databinding.ActivityDashboardBinding
import kotlinx.coroutines.launch

class OpenClawDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private var androidBridge: AndroidBridge? = null
    
    private var migrationCallback: ((String, Int) -> Unit)? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val filename = it.path?.substringAfterLast('/') ?: "file.tar.gz"
            val size = try {
                contentResolver.openFileDescriptor(it, "r")?.use { fd -> fd.statSize } ?: 0L
            } catch (e: Exception) { 0L }
            migrationCallback?.invoke(filename, (size / 1024 / 1024).toInt())
        }
    }

    companion object {
        private const val TAG = "Dashboard"
        private const val DASHBOARD_URL = "https://openclaw.local/index.html"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        binding.webView.loadUrl(DASHBOARD_URL)

        // Sincronizar estado del Gateway con la WebUI
        lifecycleScope.launch {
            OpenClawGatewayService.state.collect { state ->
                if (state == GatewayState.READY) {
                    androidBridge?.notifyReact("onGatewayReady", "{\"success\":true}")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        androidBridge = AndroidBridge(this, binding.webView, lifecycleScope)
        binding.webView.addJavascriptInterface(androidBridge!!, "AndroidBridge")
        
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url.startsWith("https://openclaw.local/")) {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
                return null
            }
        }
        
        binding.webView.webChromeClient = WebChromeClient()
    }

    fun pickMigrationFile(callback: (String, Int) -> Unit) {
        migrationCallback = callback
        filePickerLauncher.launch("*/*")
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
}