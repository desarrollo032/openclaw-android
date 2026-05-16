package com.openclaw.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.openclaw.android.databinding.ActivityDashboardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class OpenClawDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private var androidBridge: AndroidBridge? = null
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    internal lateinit var filePicker: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    companion object {
        private const val TAG = "Dashboard"
        private const val DASHBOARD_URL = "https://appassets.androidplatform.net/assets/www/index.html"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Registrar launcher para permisos de notificación
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.i(TAG, "Notification permission granted")
            } else {
                Log.i(TAG, "Notification permission denied")
            }
            OpenClawPreferences.isNotificationPermissionRequested = true
        }

        filePicker = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { handleFilePicked() }
        }

        setupWebView()
        binding.webView.loadUrl(DASHBOARD_URL)

        // Manejar botón atrás
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })

        // Verificar permisos de notificación al iniciar la actividad
        checkAndRequestNotificationPermission()

        lifecycleScope.launch {
            var lastToken: String = ""

            OpenClawGatewayService.state.collect { state ->
                val stateJson = JSONObject().apply { put("state", state.name) }.toString()
                androidBridge?.notifyReact("onGatewayStateChanged", stateJson)

                if (state == GatewayState.READY) {
                    androidBridge?.notifyReact("onGatewayReady", "{\\\"success\\\":true}")
                }

                val token = OpenClawGatewayService.currentToken
                if (token.isNotBlank() && token != lastToken) {
                    lastToken = token
                    androidBridge?.notifyReact("onTokenRefresh", JSONObject().apply { put("token", token) }.toString())
                }
            }
        }
    }

    /**
     * Verifica y solicita permiso de notificaciones si es necesario
     */
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.i(TAG, "Notification permission already granted")
            }
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                showNotificationPermissionRationale()
            }
            else -> {
                if (!OpenClawPreferences.isNotificationPermissionRequested) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    /**
     * Muestra un diálogo explicando por qué necesitamos el permiso de notificaciones
     */
    private fun showNotificationPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_permission_rationale)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    /**
     * Solicita iniciar el gateway, primero verificando permisos
     */
    internal fun startGatewayWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkAndRequestNotificationPermission()
        }
        OpenClawGatewayService.start(this)
    }

    private fun handleFilePicked() {
        // handlePickedFile ya no aplica con proot
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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

                // Enviar locale y tema al WebView al cargar la pagina
                val locale = resources.configuration.locales[0]?.toLanguageTag() ?: "en"
                val isDark = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                } else {
                    false
                }
                val configJson = JSONObject().apply {
                    put("locale", locale)
                    put("theme", if (isDark) "dark" else "light")
                }.toString()
                androidBridge?.notifyReact("onSystemConfig", configJson)
                androidBridge?.notifyReact("onLocaleChanged", JSONObject().apply { put("locale", locale) }.toString())
                androidBridge?.notifyReact("onThemeChanged", JSONObject().apply { put("theme", if (isDark) "dark" else "light") }.toString())
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e(TAG, "WebView Error: ${error?.description} at ${request?.url}")
            }
        }

        binding.webView.webChromeClient = WebChromeClient()
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("¿Salir?")
            .setMessage("El gateway seguirá corriendo en segundo plano.")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
