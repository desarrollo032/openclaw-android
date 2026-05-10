package com.openclaw.android

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

    private var currentFontSizeSp = 16f
    private val MIN_FONT = 14f
    private val MAX_FONT = 26f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manager = OpenClawTerminalManager(this)
        setContentView(buildLayout())
        setupTerminalView()
        startSession()
    }

    override fun onResume() { super.onResume(); terminalView.requestFocus() }

    override fun onDestroy() {
        super.onDestroy()
        terminalSession?.finishIfRunning(); terminalSession = null
    }

    private fun buildLayout(): View {
        rootFrame = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0a0a0f")) }

        // Toolbar (56dp)
        rootFrame.addView(buildToolbar())

        // TerminalView
        terminalView = TerminalView(this, null).apply {
            id = R.id.terminal_view
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(8), dp(12), dp(12))
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
                topMargin = dp(56)
                bottomMargin = dp(48)
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
            setBackgroundColor(Color.parseColor("#0f172a"))
            layoutParams = FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val keys = listOf(
            "TAB" to { send(9) },
            "ESC" to { send(27) },
            "CTRL" to { terminalView.toggleControlKeyDown() },
            "ALT" to { terminalView.toggleAltKeyDown() },
            "↑" to { arrow(KeyEvent.KEYCODE_DPAD_UP) },
            "↓" to { arrow(KeyEvent.KEYCODE_DPAD_DOWN) },
            "←" to { arrow(KeyEvent.KEYCODE_DPAD_LEFT) },
            "→" to { arrow(KeyEvent.KEYCODE_DPAD_RIGHT) },
            "HOME" to { send(1) },
            "END" to { send(5) },
            "KBD" to { toggleKbd() }
        )

        keys.forEach { (label, action) ->
            bar.addView(TextView(this).apply {
                text = label; textSize = 13f; typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
                setTextColor(Color.parseColor("#cbd5e1")); gravity = Gravity.CENTER
                minWidth = dp(52); minHeight = dp(48)
                setPadding(dp(8), 0, dp(8), 0)
                background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#1e293b")), null, null)
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
            setBackgroundColor(Color.parseColor("#0a0a14"))
            setPadding(dp(16), 0, dp(8), 0)
            layoutParams = FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP)
        }

        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4ade80"))
                setStroke(dp(1), Color.parseColor("#22c55e"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) }
        }
        bar.addView(statusDot)

        titleView = TextView(this).apply {
            text = "Terminal"; textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        setTextColor(Color.parseColor("#a0a0c0"))
        minWidth = dp(48); minimumHeight = dp(48)
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#333355")), null, ColorDrawable(Color.WHITE))
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
        currentFontSizeSp = (currentFontSizeSp + delta).coerceIn(MIN_FONT, MAX_FONT)
        terminalView.setTextSize(currentFontSizeSp.toInt())
    }

    private fun send(byte: Int) { terminalSession?.write(byteArrayOf(byte.toByte()), 0, 1) }
    private fun arrow(code: Int) { terminalView.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code)) }

    private fun toggleKbd() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun setupTerminalView() {
        terminalView.setTerminalViewClient(ViewClient())
        terminalView.setTextSize(currentFontSizeSp.toInt())
    }

    private fun startSession() {
        terminalSession = manager.createSession(this)
        terminalSession?.let {
            terminalView.attachSession(it)
            updateDot(true)
            intent.getStringExtra("initial_command")?.let { cmd ->
                it.write("$cmd\n")
            }
        }
        terminalView.requestFocus()
    }

    private fun restartSession() {
        terminalSession?.finishIfRunning()
        startSession()
    }

    private fun updateDot(running: Boolean) {
        (statusDot.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (running) "#4ade80" else "#f87171")
        )
    }

    override fun onSessionFinished(s: TerminalSession) {
        runOnUiThread { updateDot(false) }
    }

    override fun onTitleChanged(s: TerminalSession) {
        runOnUiThread { titleView.text = s.title ?: "Terminal" }
    }

    // Boilerplate for TerminalSessionClient
    override fun onTextChanged(s: TerminalSession) {}
    override fun onCopyTextToClipboard(s: TerminalSession, t: String) {}
    override fun onPasteTextFromClipboard(s: TerminalSession) {}
    override fun getTerminalCursorStyle() = 0
    override fun onBell(s: TerminalSession) {}
    override fun onColorsChanged(s: TerminalSession) {}
    override fun onTerminalCursorStateChange(b: Boolean) {}
    override fun logError(t: String?, m: String?) {}
    override fun logWarn(t: String?, m: String?) {}
    override fun logInfo(t: String?, m: String?) {}
    override fun logDebug(t: String?, m: String?) {}
    override fun logVerbose(t: String?, m: String?) {}
    override fun logStackTraceWithMessage(t: String?, m: String?, e: Exception?) {}
    override fun logStackTrace(t: String?, e: Exception?) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class ViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            currentFontSizeSp = (currentFontSizeSp * scale).coerceIn(MIN_FONT, MAX_FONT)
            terminalView.setTextSize(currentFontSizeSp.toInt())
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
