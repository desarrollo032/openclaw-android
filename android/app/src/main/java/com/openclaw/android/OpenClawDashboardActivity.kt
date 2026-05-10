package com.openclaw.android

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.*
import androidx.activity.result.ActivityResultLauncher
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
    
    // Exponer filePicker como internal para el Bridge
    internal lateinit var filePicker: ActivityResultLauncher<String>

    companion object {
        private const val TAG = "Dashboard"
        private const val DASHBOARD_URL = "https://openclaw.local/index.html"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Registrar el filePicker
        filePicker = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { handleMigrationFilePicked(it) }
        }

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

    private fun handleMigrationFilePicked(uri: Uri) {
        val filename = uri.path?.substringAfterLast('/') ?: "file.tar.gz"
        val size = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) { 0L }
        
        androidBridge?.notifyReact("onMigrationFilePicked", 
            "{\"filename\":\"$filename\", \"sizeMB\":${size / 1024 / 1024}}")
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