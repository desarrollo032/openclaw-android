package com.openclaw.android

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

private const val TAG = "OpenClawTermAct"

/**
 * OpenClawTerminalActivity — Optimizado para móvil.
 */
class OpenClawTerminalActivity : AppCompatActivity(), TerminalSessionClient {

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null
    private lateinit var manager: OpenClawTerminalManager
    private lateinit var statusDot: View
    private lateinit var titleView: TextView
    private lateinit var rootFrame: FrameLayout
    private var pendingInitialCommand: String? = null

    private var currentFontSizeSp = 40f
    private val MIN_FONT_SP = 40f
    private val MAX_FONT_SP = 56f

    companion object {
        private var activeActivity: WeakReference<OpenClawTerminalActivity>? = null

        fun launchWithCommand(context: Context, command: String) {
            val active = activeActivity?.get()
            if (active != null && !active.isFinishing && !active.isDestroyed) {
                active.runOnUiThread { active.writeCommand(command) }
                return
            }

            val intent = Intent(context, OpenClawTerminalActivity::class.java).apply {
                putExtra("initial_command", command)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivity = WeakReference(this)
        pendingInitialCommand = intent.getStringExtra("initial_command")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manager = OpenClawTerminalManager(this)
        setContentView(buildLayout())
        setupTerminalView()
        startSession()
    }

    override fun onResume() {
        super.onResume()
        activeActivity = WeakReference(this)
        terminalView.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Actualizar el intent de la actividad
        
        // Si recibimos un comando y ya hay una sesión, ejecutarlo
        intent.getStringExtra("initial_command")?.let { writeCommand(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeActivity?.get() === this) activeActivity = null
        terminalSession?.finishIfRunning(); terminalSession = null
    }

    private fun buildLayout(): View {
        rootFrame = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.terminal_bg_dark)) }

        // Toolbar (56dp)
        rootFrame.addView(buildToolbar())

        // TerminalView
        terminalView = TerminalView(this, null).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(8), dp(16), dp(64))
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
                topMargin = dp(56)
                bottomMargin = dp(56)
            }
        }
        rootFrame.addView(terminalView)

        // Barra de teclas especiales (48dp)
        rootFrame.addView(buildSpecialKeysBar())

        return rootFrame
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSpecialKeysBar(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(getColor(R.color.terminal_keys_bg))
            layoutParams = FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val keys = listOf(
            "TAB" to { send(9) },
            "ESC" to { send(27) },
            "CTRL" to { terminalView.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT)) },
            "ALT" to { terminalView.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT)) },
            "↑" to { arrow(KeyEvent.KEYCODE_DPAD_UP) },
            "↓" to { arrow(KeyEvent.KEYCODE_DPAD_DOWN) },
            "←" to { arrow(KeyEvent.KEYCODE_DPAD_LEFT) },
            "→" to { arrow(KeyEvent.KEYCODE_DPAD_RIGHT) },
            "/" to { send('/'.code) },
            "~" to { send('~'.code) },
            "-" to { send('-'.code) },
            "|" to { send('|'.code) },
            "_" to { send('_'.code) },
            "#" to { send('#'.code) },
            "@" to { send('@'.code) },
            "KBD" to { toggleKbd() }
        )

        keys.forEach { (label, action) ->
            bar.addView(TextView(this).apply {
                text = label; textSize = 15f; typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
                setTextColor(getColor(R.color.terminal_key_text)); gravity = Gravity.CENTER
                minWidth = dp(56); minHeight = dp(56)
                setPadding(dp(16), 0, dp(16), 0)
                background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.terminal_key_ripple)), null, null)
                setOnClickListener { action() }
            })
        }
        scroll.addView(bar)
        return scroll
    }

    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.terminal_toolbar_bg))
            setPadding(dp(16), 0, dp(8), 0)
            layoutParams = FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP)
        }

        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(getColor(R.color.terminal_status_online))
                setStroke(dp(1), getColor(R.color.terminal_status_stroke))
            }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) }
        }
        bar.addView(statusDot)

        titleView = TextView(this).apply {
            text = "Terminal"; textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        bar.addView(titleView)

        bar.addView(toolbarBtn("≡") { showToolbarMenu() })
        bar.addView(toolbarBtn("✕") { finish() })
        return bar
    }

    private fun toolbarBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; gravity = Gravity.CENTER
        setTextColor(getColor(R.color.terminal_toolbar_btn))
        minWidth = dp(48); minimumHeight = dp(48)
        background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.terminal_toolbar_ripple)), null, ColorDrawable(Color.WHITE))
        setOnClickListener { onClick() }
    }

    private fun showToolbarMenu() {
        val popup = PopupMenu(this, titleView, Gravity.END)
        popup.menu.apply {
            add(0, 1, 0, "Fuente +")
            add(0, 2, 1, "Fuente -")
            add(0, 3, 2, "Limpiar")
            add(0, 4, 3, "Reiniciar")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> changeFontSize(2f)
                2 -> changeFontSize(-2f)
                3 -> terminalSession?.write("clear\n")
                4 -> restartSession()
            }; true
        }
        popup.show()
    }

    private fun changeFontSize(delta: Float) {
        currentFontSizeSp = (currentFontSizeSp + delta).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        terminalView.setTextSize(currentFontSizeSp.roundToInt())
    }

    private fun send(byte: Int) { terminalSession?.write(byteArrayOf(byte.toByte()), 0, 1) }
    private fun arrow(code: Int) { terminalView.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code)) }

    private fun toggleKbd() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = terminalView.windowInsetsController
            val imeType = android.view.WindowInsets.Type.ime()
            if (terminalView.rootWindowInsets?.isVisible(imeType) == true) {
                controller?.hide(imeType)
            } else {
                controller?.show(imeType)
            }
        } else {
            @Suppress("DEPRECATION")
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }
    }

    private fun showKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = terminalView.windowInsetsController
            controller?.show(android.view.WindowInsets.Type.ime())
        } else {
            @Suppress("DEPRECATION")
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showErrorOverlay(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setupTerminalView() {
        terminalView.setTerminalViewClient(ViewClient())
        terminalView.setTextSize(currentFontSizeSp.roundToInt())
    }

    private fun startSession() {
        // Con toybox nativo no verificar busybox — siempre hay shell
        terminalSession = manager.createSession(this)
        if (terminalSession == null) {
            showErrorOverlay(
                "No se pudo iniciar el terminal.\n" +
                "Shell /system/bin/sh no disponible."
            )
            return
        }
        terminalView.attachSession(terminalSession)
        terminalView.requestFocus()
        showKeyboard()

        // Verificar toybox al iniciar (informativo)
        terminalSession?.write(
            "echo '=== OpenClaw Terminal ===' && " +
            "echo \"Shell: \$(which sh)\" && " +
            "echo \"Toybox: \$(toybox 2>/dev/null | " +
            "head -1 || echo 'usando system/bin')\" && " +
            "echo '========================'\n"
        )
    }

    private fun writeCommand(command: String) {
        val session = terminalSession
        if (session == null) {
            pendingInitialCommand = command
            return
        }
        terminalView.requestFocus()
        session.write("$command\n")
        refreshTerminal()
    }

    private fun restartSession() {
        terminalSession?.finishIfRunning()
        startSession()
    }

    private fun updateDot(running: Boolean) {
        (statusDot.background as? GradientDrawable)?.setColor(
            getColor(if (running) R.color.terminal_status_online else R.color.terminal_status_offline)
        )
    }

    override fun onSessionFinished(s: TerminalSession) {
        runOnUiThread { updateDot(false) }
    }

    override fun onTitleChanged(s: TerminalSession) {
        runOnUiThread { titleView.text = s.title ?: "Terminal" }
    }

    // Boilerplate for TerminalSessionClient
    override fun onTextChanged(s: TerminalSession) {
        refreshTerminal()
    }
    override fun onCopyTextToClipboard(s: TerminalSession, t: String) {
        copyTextToClipboard(t)
    }

    override fun onPasteTextFromClipboard(s: TerminalSession) {
        pasteTextFromClipboard()
    }
    override fun getTerminalCursorStyle() = 0
    override fun onBell(s: TerminalSession) {}
    override fun onColorsChanged(s: TerminalSession) {
        refreshTerminal()
    }
    override fun onTerminalCursorStateChange(b: Boolean) {
        refreshTerminal()
    }
    override fun logError(t: String?, m: String?) {}
    override fun logWarn(t: String?, m: String?) {}
    override fun logInfo(t: String?, m: String?) {}
    override fun logDebug(t: String?, m: String?) {}
    override fun logVerbose(t: String?, m: String?) {}
    override fun logStackTraceWithMessage(t: String?, m: String?, e: Exception?) {}
    override fun logStackTrace(t: String?, e: Exception?) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refreshTerminal() {
        if (!::terminalView.isInitialized) return
        terminalView.post {
            terminalView.onScreenUpdated()
            terminalView.postInvalidateOnAnimation()
        }
    }

    private fun copyTextToClipboard(text: String) {
        if (text.isBlank()) return
        runOnUiThread {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw Terminal", text))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteTextFromClipboard() {
        runOnUiThread {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                .orEmpty()
            if (text.isNotEmpty()) {
                terminalSession?.write(text)
                refreshTerminal()
            }
        }
    }

    private inner class ViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val newSize = (currentFontSizeSp * scale).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (Math.abs(newSize - currentFontSizeSp) > 0.5f) {
                currentFontSizeSp = newSize
                terminalView.setTextSize(newSize.roundToInt())
            }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
        override fun onLongPress(e: MotionEvent?) = false
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun copyModeChanged(c: Boolean) {}
        override fun onKeyDown(k: Int, e: KeyEvent?, s: TerminalSession?) = false
        override fun onKeyUp(k: Int, e: KeyEvent?) = false
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun onEmulatorSet() {}
        override fun isTerminalViewSelected() = true
        override fun onCodePoint(c: Int, ctrl: Boolean, s: TerminalSession?) = false
        override fun logError(t: String?, m: String?) {}
        override fun logWarn(t: String?, m: String?) {}
        override fun logInfo(t: String?, m: String?) {}
        override fun logDebug(t: String?, m: String?) {}
        override fun logVerbose(t: String?, m: String?) {}
        override fun logStackTraceWithMessage(t: String?, m: String?, e: Exception?) {}
        override fun logStackTrace(t: String?, e: Exception?) {}
    }
}
