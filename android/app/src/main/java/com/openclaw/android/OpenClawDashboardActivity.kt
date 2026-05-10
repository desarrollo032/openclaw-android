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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class OpenClawDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private var androidBridge: AndroidBridge? = null
    private var pendingFileCallbackId: String? = null
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }
    
    internal lateinit var filePicker: ActivityResultLauncher<String>

    companion object {
        private const val TAG = "Dashboard"
        private const val DASHBOARD_URL = "https://appassets.androidplatform.net/assets/www/index.html"
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
            uri?.let { handleFilePicked(it) }
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

    private fun handleFilePicked(uri: Uri) {
        pendingFileCallbackId?.let { callbackId ->
            dispatchNativeFilePicked(callbackId, uri, true)
            pendingFileCallbackId = null
            return
        }
        handleMigrationFilePicked(uri)
    }

    private fun handleMigrationFilePicked(uri: Uri) {
        // Obtener nombre real del archivo desde la URI
        var filename = "archivo.tar.gz"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                filename = cursor.getString(nameIndex)
            }
        }

        val size = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) { 0L }
        
        val sizeMB = size / 1024 / 1024
        
        // Mostrar confirmación visual nativa
        android.widget.Toast.makeText(this, "Archivo seleccionado: $filename (${sizeMB}MB)", android.widget.Toast.LENGTH_LONG).show()
        Log.i(TAG, "File picked: $filename, size: $sizeMB MB")

        androidBridge?.notifyReact("onMigrationFilePicked", 
            "{\"filename\":\"$filename\", \"sizeMB\":$sizeMB}")
    }

    private fun dispatchNativeFilePicked(callbackId: String, uri: Uri, success: Boolean) {
        val escapedUri = JSONObject.quote(uri.toString())
        val successJson = if (success) "true" else "false"
        binding.webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('native:file_picked_$callbackId', { detail: { uri: $escapedUri, success: $successJson } }));",
            null
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        androidBridge = AndroidBridge(this, binding.webView, lifecycleScope)
        binding.webView.addJavascriptInterface(androidBridge!!, "OpenClaw")
        
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                return request?.url?.let { assetLoader.shouldInterceptRequest(it) }
            }

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