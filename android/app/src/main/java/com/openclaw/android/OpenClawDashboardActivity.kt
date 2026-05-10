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
import com.openclaw.android.databinding.ActivityDashboardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OpenClawDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private var androidBridge: AndroidBridge? = null
    
    internal lateinit var filePicker: ActivityResultLauncher<String>

    companion object {
        private const val TAG = "Dashboard"
        // Ruta directa a los assets compilados de React
        private const val DASHBOARD_URL = "file:///android_asset/www/index.html"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Asegurar que los scripts y wrappers existan cada vez que abrimos la app
        val payloadDir = OpenClawInstaller.getPayloadDir(this)
        if (payloadDir.exists()) {
            lifecycleScope.launch(Dispatchers.IO) {
                OpenClawInstaller.deployScripts(this@OpenClawDashboardActivity, payloadDir)
                OpenClawInstaller.fixPermissions(payloadDir)
                Log.i("Dashboard", "Comandos sincronizados correctamente")
            }
        }

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filePicker = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { handleMigrationFilePicked(it) }
        }

        setupWebView()
        binding.webView.clearCache(true)
        binding.webView.loadUrl(DASHBOARD_URL)

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
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            // Habilitar acceso a archivos para que React cargue sus assets locales
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        androidBridge = AndroidBridge(this, binding.webView, lifecycleScope)
        binding.webView.addJavascriptInterface(androidBridge!!, "OpenClaw")
        
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Dashboard loaded: $url")
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e(TAG, "WebView Error: ${error?.description} at ${request?.url}")
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