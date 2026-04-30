package com.openclaw.android

import android.annotation.SuppressLint
import java.io.File
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openclaw.android.databinding.ActivityMainBinding
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_TEXT_SIZE = 32
        private const val MIN_TEXT_SIZE = 8
        private const val MAX_TEXT_SIZE = 32
        private const val KEYBOARD_SHOW_DELAY_MS = 200L
        private const val REQUEST_STORAGE_PERMISSIONS = 100
        private const val REQUEST_MANAGE_STORAGE = 101
        // Android 13+ (API 33): permiso para mostrar notificaciones del foreground service
        private const val REQUEST_POST_NOTIFICATIONS = 102
    }

    private lateinit var binding: ActivityMainBinding

    lateinit var sessionManager: TerminalSessionManager
    lateinit var bootstrapManager: BootstrapManager
    lateinit var eventBridge: EventBridge
    private lateinit var jsBridge: JsBridge

    private var currentTextSize = DEFAULT_TEXT_SIZE
    private var ctrlDown = false
    private var altDown = false
    private val terminalSessionClient = OpenClawSessionClient()
    private val terminalViewClient = OpenClawViewClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bootstrapManager = BootstrapManager(this)
        eventBridge = EventBridge(binding.webView)
        sessionManager = TerminalSessionManager(this, terminalSessionClient, eventBridge)
        jsBridge = JsBridge(this, sessionManager, bootstrapManager, eventBridge)

        setupTerminalView()
        setupWebView()
        setupExtraKeys()
        sessionManager.onSessionsChanged = { updateSessionTabs() }
        startService(Intent(this, OpenClawService::class.java))

        // Request storage permissions before any installation
        requestStoragePermissions()

        // Android 13+: solicitar permiso de notificaciones para el foreground service
        requestNotificationPermission()

        // Defer the install/launch decision until storage permissions are resolved.
        // On Android 11+ requestStoragePermissions() launches a Settings activity
        // and returns immediately — checkAndStartInstallation() will be called from
        // onStoragePermissionsGranted() / onActivityResult() once the user responds.
        // On Android 6-10 and when permissions are already granted, it is called
        // directly from onStoragePermissionsGranted() which runs synchronously.
    }

    /**
     * Evaluates the current install state and takes the appropriate action:
     *
     *  • Nothing installed at all  → auto-start the best available installer
     *    (payload > rootfs > bootstrap) so the user never has to tap a button.
     *  • Bootstrap installed, post-setup pending → run post-setup.sh in terminal.
     *  • Fully installed, OpenClaw missing      → run auto-install via TermuxIntegrationHelper.
     *  • Fully installed and ready              → show terminal / auto-start gateway.
     *
     * Called from onStoragePermissionsGranted() so storage is always available
     * before any file-system or shell operation runs.
     */
    private fun checkAndStartInstallation() {
        val rootfsManager = RootfsManager(this)
        val payloadManager = PayloadManager(this)

        val isInstalled = payloadManager.isReady() || rootfsManager.isInstalled() || bootstrapManager.isInstalled()
        val isOpenClawInstalled = payloadManager.isOpenClawInstalled() || rootfsManager.isOpenClawInstalled() || bootstrapManager.isOpenClawInstalled()

        AppLogger.i(
            TAG,
            "checkAndStartInstallation: installed=$isInstalled " +
                "(payload=${payloadManager.isReady()}, rootfs=${rootfsManager.isInstalled()}, " +
                "bootstrap=${bootstrapManager.isInstalled()}), openclaw=$isOpenClawInstalled",
        )

        // ── Sync www assets and apply script updates on APK upgrade ──────────
        if (isInstalled) {
            val prefs = getSharedPreferences("openclaw", 0)
            val savedVersionCode = prefs.getInt("versionCode", 0)
            val currentVersionCode = packageManager.getPackageInfo(packageName, 0).versionCode
            when {
                payloadManager.isReady() -> {
                    payloadManager.syncWwwFromAssets()
                    if (currentVersionCode > savedVersionCode) {
                        AppLogger.i(TAG, "APK upgrade: $savedVersionCode → $currentVersionCode (payload)")
                        payloadManager.applyScriptUpdate()
                        prefs.edit().putInt("versionCode", currentVersionCode).apply()
                    }
                }
                rootfsManager.isInstalled() -> {
                    rootfsManager.syncWwwFromAssets()
                    if (currentVersionCode > savedVersionCode) {
                        AppLogger.i(TAG, "APK upgrade: $savedVersionCode → $currentVersionCode (rootfs)")
                        rootfsManager.applyScriptUpdate()
                        prefs.edit().putInt("versionCode", currentVersionCode).apply()
                    }
                }
                else -> {
                    bootstrapManager.syncWwwFromAssets()
                    Thread { bootstrapManager.installOaCli() }.start()
                    if (currentVersionCode > savedVersionCode) {
                        AppLogger.i(TAG, "APK upgrade: $savedVersionCode → $currentVersionCode (bootstrap)")
                        bootstrapManager.applyScriptUpdate()
                        prefs.edit().putInt("versionCode", currentVersionCode).apply()
                    }
                }
            }
        }

        // ── Decide what to do based on install state ──────────────────────────
        when {
            // ── Case 1: Nothing installed → auto-start the best installer ────
            !isInstalled -> {
                AppLogger.i(TAG, "Nothing installed — starting automatic installation")
                showTerminal()
                val session = sessionManager.createSession()
                binding.terminalView.post {
                    autoStartInstallation(session, payloadManager, rootfsManager)
                }
            }

            // ── Case 2: Bootstrap installed, post-setup.sh still pending ─────
            bootstrapManager.isInstalled() && bootstrapManager.needsPostSetup() -> {
                AppLogger.i(TAG, "Bootstrap ready — running post-setup.sh automatically")
                showTerminal()
                val session = sessionManager.createSession()
                val script = bootstrapManager.postSetupScript.absolutePath
                binding.terminalView.post {
                    session.write("bash \"$script\"\n")
                }
            }

            // ── Case 3: Env installed but OpenClaw package missing ────────────
            isInstalled && !isOpenClawInstalled -> {
                AppLogger.i(TAG, "Environment ready — OpenClaw not installed, running auto-install")
                showTerminal()
                val session = sessionManager.createSession()
                binding.terminalView.post {
                    TermuxIntegrationHelper(this, bootstrapManager).installOpenClaw(session)
                }
            }

            // ── Case 4: Boot intent → auto-start gateway ──────────────────────
            intent?.getBooleanExtra("from_boot", false) == true -> {
                val startScript = when {
                    payloadManager.isReady() -> File(filesDir, "payload/run-openclaw.sh")
                    else -> File(filesDir, "home/openclaw-start.sh")
                }
                AppLogger.i(TAG, "Boot launch — auto-starting gateway: ${startScript.absolutePath}")
                showTerminal()
                val session = sessionManager.createSession()
                binding.terminalView.post {
                    session.write("\"${startScript.absolutePath}\"\n")
                }
            }

            // ── Case 5: Fully installed and ready → show dashboard ───────────
            else -> {
                AppLogger.i(TAG, "Fully installed — showing dashboard")
                showWebView()
            }
        }
    }

    /**
     * Selects and launches the best available installer automatically.
     *
     * Priority: payload (offline, bundled) > rootfs (pre-built tar) > bootstrap (network).
     * The user sees the terminal with live output — no button tap required.
     *
     * IMPORTANT: This runs while the terminal is visible. We do NOT delegate to
     * jsBridge.startPayloadInstall() / startRootfsInstall() here because those
     * methods emit progress events to the WebView (which is hidden at this point)
     * and show no output in the terminal. Instead we run the installation directly
     * in a coroutine and write progress lines to the terminal session, then switch
     * to the dashboard when done.
     */
    private fun autoStartInstallation(
        session: com.termux.terminal.TerminalSession,
        payloadManager: PayloadManager,
        rootfsManager: RootfsManager,
    ) {
        when {
            // ── Path 1: Payload (fully offline, bundled in APK) ───────────────
            payloadManager.hasPayloadAsset() -> {
                AppLogger.i(TAG, "Auto-install: payload path (offline)")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        payloadManager.install { progress, message ->
                            val pct = (progress * 100).toInt()
                            session.write("[$pct%] $message\r\n")
                            AppLogger.i(TAG, "[payload-install] $message")
                        }
                        payloadManager.syncWwwFromAssets()
                        session.write("\r\n✓ Installation complete!\r\n")
                        // Small delay so the user can read the final message
                        kotlinx.coroutines.delay(1200)
                        runOnUiThread { showWebView() }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Payload install failed: ${e.message}", e)
                        session.write("\r\n✗ Installation failed: ${e.message}\r\n")
                        session.write("Check logs and try reinstalling.\r\n")
                    }
                }
            }
            else -> {
                // Network bootstrap — run post-setup.sh which downloads everything
                AppLogger.i(TAG, "Auto-install: bootstrap + post-setup.sh path")
                val postSetup = bootstrapManager.postSetupScript
                if (postSetup.exists()) {
                    session.write("sh \"${postSetup.absolutePath}\"\n")
                } else {
                    // Bootstrap not yet extracted — trigger full bootstrap setup
                    jsBridge.startSetup()
                }
            }
        }
    }

    // --- Storage permissions ---

    /**
     * Solicita el permiso POST_NOTIFICATIONS en Android 13+ (API 33).
     *
     * Sin este permiso, el foreground service puede iniciarse pero su notificación
     * persistente no se mostrará, lo que en Android 12+ puede causar que el sistema
     * mate el servicio más agresivamente al no ver actividad visible.
     *
     * En versiones anteriores a Android 13, el permiso se concede automáticamente
     * al declarar FOREGROUND_SERVICE en el manifest.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                AppLogger.i(TAG, "Requesting POST_NOTIFICATIONS permission (Android 13+)")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_POST_NOTIFICATIONS,
                )
            }
        }
    }

    // --- Storage permissions ---

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: request MANAGE_EXTERNAL_STORAGE via Settings
            if (!Environment.isExternalStorageManager()) {
                AppLogger.i(TAG, "Requesting MANAGE_EXTERNAL_STORAGE permission")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                } catch (e: Exception) {
                    // Fallback: open general storage settings
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQUEST_MANAGE_STORAGE,
                    )
                }
            } else {
                onStoragePermissionsGranted()
            }
        } else {
            // Android 6-10: runtime permissions
            val permissions =
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_STORAGE_PERMISSIONS)
            } else {
                onStoragePermissionsGranted()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_STORAGE_PERMISSIONS -> {
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    AppLogger.i(TAG, "Storage permissions granted")
                    onStoragePermissionsGranted()
                } else {
                    AppLogger.w(TAG, "Storage permissions denied — termux-setup-storage may fail")
                    // Continue anyway; some features may be limited
                    onStoragePermissionsGranted()
                }
            }
            REQUEST_POST_NOTIFICATIONS -> {
                // Android 13+: el foreground service funciona con o sin este permiso,
                // pero la notificación persistente solo se muestra si se concede.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    AppLogger.i(TAG, "POST_NOTIFICATIONS permission granted — notification will be visible")
                } else {
                    AppLogger.w(TAG, "POST_NOTIFICATIONS denied — service runs but notification hidden")
                }
            }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                AppLogger.i(TAG, "MANAGE_EXTERNAL_STORAGE granted")
            } else {
                AppLogger.w(TAG, "MANAGE_EXTERNAL_STORAGE denied")
            }
            onStoragePermissionsGranted()
        }
    }

    /**
     * Called once storage permissions are resolved (granted or denied).
     * Runs termux-setup-storage in the background, then evaluates the install
     * state and starts the appropriate installer automatically.
     */
    private fun onStoragePermissionsGranted() {
        Thread {
            CommandRunner.runTermuxSetupStorage { line ->
                AppLogger.i(TAG, "setup-storage: $line")
            }
        }.start()
        // Always evaluate install state after permissions are settled.
        // This is the single entry point for the install/launch decision.
        checkAndStartInstallation()
    }

    // --- Terminal setup ---

    private fun setupTerminalView() {
        binding.terminalView.setTerminalViewClient(terminalViewClient)
        binding.terminalView.setTextSize(currentTextSize)
    }

    // --- WebView setup ---

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        binding.webView.apply {
            clearCache(true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(jsBridge, "OpenClaw")
            webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        super.onPageFinished(view, url)
                        AppLogger.i(TAG, "WebView page loaded: $url")
                        // Page loaded successfully
                    }
                }
            webChromeClient =
                object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            AppLogger.d("WebViewJS", "${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                        }
                        return true
                    }
                }
        }

        val wwwDir = bootstrapManager.wwwDir
        val url =
            if (wwwDir.resolve("index.html").exists()) {
                "file://${wwwDir.absolutePath}/index.html"
            } else {
                // Load bundled fallback setup page from assets
                "file:///android_asset/www/index.html"
            }
        AppLogger.i(TAG, "Loading WebView URL: $url")
        binding.webView.loadUrl(url)
    }

    fun reloadWebView() {
        binding.webView.reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        jsBridge.cancel()
    }

    // --- View switching ---

    fun showTerminal() {
        runOnUiThread {
            binding.webView.visibility = View.GONE
            binding.terminalContainer.visibility = View.VISIBLE
            binding.terminalView.requestFocus()
            updateSessionTabs()
            // Delay keyboard show — view must be focused and laid out first
            binding.terminalView.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.terminalView, InputMethodManager.SHOW_IMPLICIT)
            }, KEYBOARD_SHOW_DELAY_MS)
        }
    }

    fun showWebView() {
        runOnUiThread {
            binding.terminalContainer.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.terminalContainer.visibility == View.VISIBLE) {
            showWebView()
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // --- Extra Keys ---

    private val pressedAlpha = 0.5f
    private val normalAlpha = 1.0f

    @SuppressLint("ClickableViewAccessibility")
    private fun setupExtraKeys() {
        // Key code buttons — send key event on touch, never steal focus
        val keyMap =
            mapOf(
                R.id.btnEsc to KeyEvent.KEYCODE_ESCAPE,
                R.id.btnTab to KeyEvent.KEYCODE_TAB,
                R.id.btnHome to KeyEvent.KEYCODE_MOVE_HOME,
                R.id.btnEnd to KeyEvent.KEYCODE_MOVE_END,
                R.id.btnUp to KeyEvent.KEYCODE_DPAD_UP,
                R.id.btnDown to KeyEvent.KEYCODE_DPAD_DOWN,
                R.id.btnLeft to KeyEvent.KEYCODE_DPAD_LEFT,
                R.id.btnRight to KeyEvent.KEYCODE_DPAD_RIGHT,
            )
        for ((btnId, keyCode) in keyMap) {
            setupExtraKeyTouch(findViewById(btnId)) { sendExtraKey(keyCode) }
        }

        // Character keys
        setupExtraKeyTouch(findViewById(R.id.btnDash)) { sessionManager.activeSession?.write("-") }
        setupExtraKeyTouch(findViewById(R.id.btnPipe)) { sessionManager.activeSession?.write("|") }
        setupExtraKeyTouch(findViewById(R.id.btnPaste)) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text =
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(this)
                    ?.toString()
            if (!text.isNullOrEmpty()) sessionManager.activeSession?.write(text)
        }

        // Modifier toggles — stay pressed until next key or toggled off
        setupModifierTouch(findViewById(R.id.btnCtrl)) {
            ctrlDown = !ctrlDown
            ctrlDown
        }
        setupModifierTouch(findViewById(R.id.btnAlt)) {
            altDown = !altDown
            altDown
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupExtraKeyTouch(
        btn: Button,
        action: () -> Unit,
    ) {
        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.alpha = pressedAlpha
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = normalAlpha
                    if (event.action == MotionEvent.ACTION_UP) action()
                }
            }
            true // consume — never let focus leave TerminalView
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupModifierTouch(
        btn: Button,
        toggle: () -> Boolean,
    ) {
        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.alpha = pressedAlpha
                MotionEvent.ACTION_UP -> {
                    val active = toggle()
                    updateModifierButton(v as Button, active)
                    v.alpha = normalAlpha
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = normalAlpha
            }
            true
        }
    }

    private fun sendExtraKey(keyCode: Int) {
        var metaState = 0
        if (ctrlDown) metaState = metaState or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        if (altDown) metaState = metaState or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)

        val ev = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState)
        binding.terminalView.onKeyDown(keyCode, ev)

        // Auto-deactivate modifiers after use
        if (ctrlDown) {
            ctrlDown = false
            updateModifierButton(findViewById(R.id.btnCtrl), false)
        }
        if (altDown) {
            altDown = false
            updateModifierButton(findViewById(R.id.btnAlt), false)
        }
    }

    private fun updateModifierButton(
        button: Button,
        active: Boolean,
    ) {
        val bgColor = if (active) R.color.extraKeyActive else R.color.extraKeyDefault
        val txtColor = if (active) R.color.extraKeyActiveText else R.color.extraKeyText
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgColor))
        button.setTextColor(ContextCompat.getColor(this, txtColor))
    }

    // --- Session tab bar ---

    private fun updateSessionTabs() {
        val tabsLayout = binding.tabsLayout
        tabsLayout.removeAllViews()

        val sessions = sessionManager.getSessionsInfo()

        for (info in sessions) {
            val tabWrapper = createSessionTab(info)
            tabsLayout.addView(tabWrapper)
            if (info["active"] as Boolean) {
                binding.sessionTabBar.post {
                    binding.sessionTabBar.smoothScrollTo(tabWrapper.left, 0)
                }
            }
        }

        tabsLayout.addView(createAddButton())
    }

    private fun createSessionTab(info: Map<String, Any>): LinearLayout {
        val id = info["id"] as String
        val name = info["name"] as String
        val active = info["active"] as Boolean
        val finished = info["finished"] as Boolean

        val tabWrapper =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    ).apply {
                        marginEnd = resources.getDimensionPixelSize(R.dimen.tab_margin)
                    }
                val bgColor = if (active) R.color.tabActiveBackground else R.color.tabInactiveBackground
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, bgColor))
                isFocusable = false
                isFocusableInTouchMode = false
            }

        val tabContent = createTabContent(name, active, finished, id)
        val indicator = createTabIndicator(active)

        tabWrapper.addView(tabContent)
        tabWrapper.addView(indicator)
        tabWrapper.setOnClickListener {
            sessionManager.switchSession(id)
            binding.terminalView.requestFocus()
        }

        return tabWrapper
    }

    private fun createTabContent(
        name: String,
        active: Boolean,
        finished: Boolean,
        id: String,
    ): LinearLayout {
        val hPad = resources.getDimensionPixelSize(R.dimen.tab_padding_h)
        val vPad = resources.getDimensionPixelSize(R.dimen.tab_padding_v)
        val closePad = resources.getDimensionPixelSize(R.dimen.tab_close_size) / 4

        val tabContent =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(hPad, vPad, closePad, vPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    0,
                    1f,
                )
                isFocusable = false
                isFocusableInTouchMode = false
            }

        val nameView =
            TextView(this).apply {
                text = name
                textSize = resources.getDimension(R.dimen.tab_name_text_size) / resources.displayMetrics.scaledDensity
                val textColor = when {
                    finished -> R.color.tabTextFinished
                    active -> R.color.tabTextPrimary
                    else -> R.color.tabTextSecondary
                }
                setTextColor(ContextCompat.getColor(this@MainActivity, textColor))
                if (finished) setTypeface(typeface, Typeface.ITALIC)
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }

        val closeView =
            TextView(this).apply {
                text = getString(R.string.close_session)
                textSize = resources.getDimension(R.dimen.tab_close_text_size) / resources.displayMetrics.scaledDensity
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tabTextSecondary))
                setPadding(closePad, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                isFocusable = false
                isFocusableInTouchMode = false
                setOnClickListener { closeSessionFromTab(id) }
            }

        tabContent.addView(nameView)
        tabContent.addView(closeView)
        return tabContent
    }

    private fun createTabIndicator(active: Boolean): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.tab_indicator_height),
            )
            val color = if (active) R.color.tabAccent else android.R.color.transparent
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, color))
        }

    private fun createAddButton(): TextView =
        TextView(this).apply {
            text = getString(R.string.add_session)
            textSize = resources.getDimension(R.dimen.tab_add_text_size) / resources.displayMetrics.scaledDensity
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tabAddButton))
            val pad = resources.getDimensionPixelSize(R.dimen.tab_add_padding)
            setPadding(pad, 0, pad, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener {
                sessionManager.createSession()
                binding.terminalView.requestFocus()
            }
        }

    private fun closeSessionFromTab(handleId: String) {
        if (sessionManager.sessionCount <= 1) {
            // Create new session first, then close the old one
            sessionManager.createSession()
        }
        sessionManager.closeSession(handleId)
        binding.terminalView.requestFocus()
    }

    // --- Terminal session callbacks ---

    private inner class OpenClawSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            binding.terminalView.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            // Update tab bar when title changes
            runOnUiThread { updateSessionTabs() }
            // title changes propagated via EventBridge
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            sessionManager.onSessionFinished(finishedSession)
        }

        override fun onCopyTextToClipboard(
            session: TerminalSession,
            text: String,
        ) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text ?: return
            session?.write(text.toString())
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) = Unit

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun setTerminalShellPid(
            session: TerminalSession,
            pid: Int,
        ) = Unit

        override fun getTerminalCursorStyle(): Int = 0

        override fun logError(
            tag: String,
            message: String,
        ) {
            AppLogger.e(tag, message)
        }

        override fun logWarn(
            tag: String,
            message: String,
        ) {
            AppLogger.w(tag, message)
        }

        override fun logInfo(
            tag: String,
            message: String,
        ) {
            AppLogger.i(tag, message)
        }

        override fun logDebug(
            tag: String,
            message: String,
        ) {
            AppLogger.d(tag, message)
        }

        override fun logVerbose(
            tag: String,
            message: String,
        ) {
            AppLogger.v(tag, message)
        }

        override fun logStackTraceWithMessage(
            tag: String,
            message: String,
            e: Exception,
        ) {
            AppLogger.e(tag, message, e)
        }

        override fun logStackTrace(
            tag: String,
            e: Exception,
        ) {
            AppLogger.e(tag, "Exception", e)
        }
    }

    // --- Terminal view callbacks ---

    @Suppress("TooManyFunctions") // Interface implementation requires all methods
    private inner class OpenClawViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val currentSize = currentTextSize
            val newSize = if (scale > 1f) currentSize + 1 else currentSize - 1
            val clamped = newSize.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
            currentTextSize = clamped
            binding.terminalView.setTextSize(clamped)
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent) {
            // Toggle soft keyboard on tap (same as Termux)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false

        override fun shouldEnforceCharBasedInput(): Boolean = true

        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

        override fun isTerminalViewSelected(): Boolean = binding.terminalContainer.visibility == View.VISIBLE

        override fun copyModeChanged(copyMode: Boolean) = Unit

        override fun onKeyDown(
            keyCode: Int,
            e: KeyEvent,
            session: TerminalSession,
        ): Boolean = false

        override fun onKeyUp(
            keyCode: Int,
            e: KeyEvent,
        ): Boolean = false

        override fun onLongPress(event: MotionEvent): Boolean = false

        override fun readControlKey(): Boolean {
            val v = ctrlDown
            if (v) {
                ctrlDown = false
                runOnUiThread { updateModifierButton(findViewById(R.id.btnCtrl), false) }
            }
            return v
        }

        override fun readAltKey(): Boolean {
            val v = altDown
            if (v) {
                altDown = false
                runOnUiThread { updateModifierButton(findViewById(R.id.btnAlt), false) }
            }
            return v
        }

        override fun readShiftKey(): Boolean = false

        override fun readFnKey(): Boolean = false

        override fun onCodePoint(
            codePoint: Int,
            ctrlDown: Boolean,
            session: TerminalSession,
        ): Boolean = false

        override fun onEmulatorSet() = Unit

        override fun logError(
            tag: String,
            message: String,
        ) {
            AppLogger.e(tag, message)
        }

        override fun logWarn(
            tag: String,
            message: String,
        ) {
            AppLogger.w(tag, message)
        }

        override fun logInfo(
            tag: String,
            message: String,
        ) {
            AppLogger.i(tag, message)
        }

        override fun logDebug(
            tag: String,
            message: String,
        ) {
            AppLogger.d(tag, message)
        }

        override fun logVerbose(
            tag: String,
            message: String,
        ) {
            AppLogger.v(tag, message)
        }

        override fun logStackTraceWithMessage(
            tag: String,
            message: String,
            e: Exception,
        ) {
            AppLogger.e(tag, message, e)
        }

        override fun logStackTrace(
            tag: String,
            e: Exception,
        ) {
            AppLogger.e(tag, "Exception", e)
        }
    }
}
