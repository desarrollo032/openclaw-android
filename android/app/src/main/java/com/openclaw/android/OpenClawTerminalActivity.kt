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
 * OpenClawTerminalActivity — Redesigned for mobile.
 *
 * Specs:
 *  - Font: 16sp default (14sp min, 26sp max)
 *  - Toolbar: 48dp, status dot + title + menu + close
 *  - Key bar: 52dp, 48dp min touch, 13sp mono, ripple, HorizontalScrollView
 *  - Gestures: tap→kbd, double-tap→toggle, long→popup, pinch→zoom(0.85), edge-swipe→close
 *  - windowSoftInputMode = adjustResize
 *  - FLAG_KEEP_SCREEN_ON
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

    // Edge swipe tracking
    private var edgeSwipeStartX = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manager = OpenClawTerminalManager(this)
        manager.logDiagnostics()
        setContentView(buildLayout())
        setupTerminalView()
        startSession()
    }

    override fun onResume() { super.onResume(); terminalView.requestFocus() }

    override fun onDestroy() {
        super.onDestroy()
        terminalSession?.finishIfRunning(); terminalSession = null
    }

    // ── Layout ───────────────────────────────────────────────────

    private fun buildLayout(): View {
        rootFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Toolbar (48dp)
        val toolbar = buildToolbar()
        rootFrame.addView(toolbar)

        // TerminalView
        terminalView = TerminalView(this, null).apply {
            id = R.id.terminal_view
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
                topMargin = dp(48)   // toolbar
                bottomMargin = dp(53) // key bar (52 + 1 separator)
            }
        }
        rootFrame.addView(terminalView)

        // Special keys bar (52dp, bottom)
        rootFrame.addView(buildSpecialKeysBar())

        return rootFrame
    }

    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0d0d1a"))
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP)
        }
        // Status dot
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#6b7280"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
        }
        bar.addView(statusDot)

        // Title
        titleView = TextView(this).apply {
            text = "OpenClaw Terminal"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        bar.addView(titleView)

        // Menu button [≡]
        bar.addView(toolbarBtn("≡") { showToolbarMenu() })
        // Close button [✕]
        bar.addView(toolbarBtn("✕") { finish() })

        return bar
    }

    private fun toolbarBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#a0a0c0"))
            minWidth = dp(44); minimumHeight = dp(44)
            setPadding(dp(8), 0, dp(8), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#333355")),
                null, ColorDrawable(Color.WHITE)
            )
            setOnClickListener { onClick() }
        }
    }

    // ── Toolbar Menu (BottomSheet simple) ─────────────────────────

    private fun showToolbarMenu() {
        val popup = PopupMenu(this, titleView, Gravity.END)
        popup.menu.apply {
            add(0, 1, 0, "Fuente +")
            add(0, 2, 1, "Fuente -")
            add(0, 3, 2, "Limpiar pantalla")
            add(0, 4, 3, "Nueva sesión")
            add(0, 5, 4, "Copiar todo al clipboard")
            add(0, 6, 5, "Compartir logs")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> changeFontSize(2f)
                2 -> changeFontSize(-2f)
                3 -> terminalSession?.write("clear\n")
                4 -> restartSession()
                5 -> copyAllToClipboard()
                6 -> shareSessionOutput()
            }; true
        }
        popup.show()
    }

    private fun changeFontSize(delta: Float) {
        currentFontSizeSp = (currentFontSizeSp + delta).coerceIn(MIN_FONT, MAX_FONT)
        terminalView.setTextSize(currentFontSizeSp.toInt())
    }

    private fun copyAllToClipboard() {
        val session = terminalSession ?: return
        val text = session.emulator?.screen?.getTranscriptText() ?: return
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("terminal_output", text))
        Toast.makeText(this, "Copiado al clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareSessionOutput() {
        val session = terminalSession ?: return
        val text = session.emulator?.screen?.getTranscriptText() ?: ""
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "OpenClaw Terminal Logs")
        }
        startActivity(android.content.Intent.createChooser(intent, "Compartir logs"))
    }

    // ── Special Keys Bar (52dp) ──────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSpecialKeysBar(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111128"))
            layoutParams = FrameLayout.LayoutParams(-1, dp(53), Gravity.BOTTOM) // 52 + 1 sep
        }
        // Top separator
        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#2d2d4e"))
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        })

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, dp(52))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }

        data class K(val label: String, val action: () -> Unit)
        val keys = listOf(
            K("TAB") { send(9) }, K("ESC") { send(27) },
            K("^C") { send(3) }, K("^D") { send(4) },
            K("↑") { arrow(KeyEvent.KEYCODE_DPAD_UP) },
            K("↓") { arrow(KeyEvent.KEYCODE_DPAD_DOWN) },
            K("←") { arrow(KeyEvent.KEYCODE_DPAD_LEFT) },
            K("→") { arrow(KeyEvent.KEYCODE_DPAD_RIGHT) },
            K("/") { text("/") }, K("~") { text("~") },
            K("-") { text("-") }, K("|") { text("|") },
            K("_") { text("_") }, K("#") { text("#") },
            K("@") { text("@") }, K("KBD") { toggleKbd() }
        )
        keys.forEachIndexed { i, k ->
            bar.addView(TextView(this).apply {
                text = k.label; textSize = 13f; typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#c8c8e8")); gravity = Gravity.CENTER
                minWidth = dp(48); setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(-2, -1)
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#333355")),
                    null, ColorDrawable(Color.WHITE)
                )
                setOnClickListener { k.action() }
            })
            // Separator between keys (not after last)
            if (i < keys.size - 1) {
                bar.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#2d2d4e"))
                    layoutParams = LinearLayout.LayoutParams(1, -1).apply {
                        topMargin = dp(10); bottomMargin = dp(10)
                    }
                })
            }
        }
        scroll.addView(bar)
        container.addView(scroll)
        return container
    }

    // ── Key injection ────────────────────────────────────────────

    private fun send(byte: Int) { terminalSession?.write(byteArrayOf(byte.toByte()), 0, 1) }
    private fun arrow(code: Int) { terminalView.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code)) }
    private fun text(s: String) { terminalSession?.write(s.toByteArray(), 0, s.length) }

    private fun toggleKbd() {
        if (currentFocus == null) terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        @Suppress("DEPRECATION")
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun showKbd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.ime())
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ── Terminal setup ────────────────────────────────────────────

    private fun setupTerminalView() {
        terminalView.setTerminalViewClient(ViewClient())
        terminalView.setTextSize(currentFontSizeSp.toInt())
        terminalView.setPadding(dp(12), 0, dp(12), 0)
    }

    private fun startSession() {
        if (!manager.isBusyboxAvailable()) {
            showOverlay("⚠ libbusybox.so no encontrado.\n\nAsegúrate de que el APK incluye el binario.", true)
            return
        }
        terminalSession = manager.createSession(this)
        if (terminalSession == null) {
            showOverlay("⚠ No se pudo crear la sesión de terminal.", true)
            return
        }
        terminalView.attachSession(terminalSession)
        updateDot(true)
        intent.getStringExtra("initial_command")?.let { cmd ->
            terminalSession?.write("$cmd\n"); intent.removeExtra("initial_command")
        }
        terminalView.requestFocus()
        terminalView.postDelayed({ terminalView.requestFocus(); showKbd() }, 300)
    }

    private fun restartSession() {
        terminalSession?.finishIfRunning(); terminalSession = null
        // Remove any overlay
        for (i in rootFrame.childCount - 1 downTo 0) {
            val v = rootFrame.getChildAt(i)
            if (v.tag == "overlay") rootFrame.removeViewAt(i)
        }
        startSession()
    }

    private fun updateDot(running: Boolean) {
        (statusDot.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (running) "#4ade80" else "#f87171")
        )
    }

    // ── Context menu (long press) ────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    private fun showContextMenu(x: Float, y: Float) {
        val popup = PopupMenu(this, terminalView, Gravity.NO_GRAVITY)
        popup.menu.apply {
            add(0, 1, 0, "Pegar")
            add(0, 2, 1, "Seleccionar todo")
            add(0, 0, 2, "────────────").isEnabled = false
            add(0, 3, 3, "Limpiar pantalla")
            add(0, 4, 4, "Nueva sesión")
            add(0, 0, 5, "────────────").isEnabled = false
            add(0, 5, 6, "Cerrar terminal")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { val c = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    c.primaryClip?.getItemAt(0)?.text?.toString()?.let { terminalSession?.write(it) } }
                3 -> terminalSession?.write("clear\n")
                4 -> restartSession()
                5 -> finish()
            }; true
        }
        popup.show()
    }

    // ── Overlay (error / process completed) ──────────────────────

    private fun showOverlay(msg: String, isError: Boolean) {
        val overlay = LinearLayout(this).apply {
            tag = "overlay"
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.parseColor(if (isError) "#0d0d12" else "#CC0d0d12"))
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(48) }
        }
        overlay.addView(TextView(this).apply {
            text = msg; textSize = 14f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(if (isError) "#f87171" else "#a0a0c0"))
            gravity = Gravity.CENTER
        })
        if (!isError) {
            overlay.addView(Button(this).apply {
                text = "Nueva sesión"; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#6366f1"))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    topMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL
                }
                setOnClickListener { restartSession() }
            })
        }
        overlay.addView(Button(this).apply {
            text = "Cerrar terminal"; setTextColor(Color.parseColor("#a0a0c0"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { finish() }
        })
        rootFrame.addView(overlay)
    }

    // ── Key handling ──────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { moveTaskToBack(true); return true }
        return if (terminalView.onKeyDown(keyCode, event)) true else super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (terminalView.onKeyUp(keyCode, event)) true else super.onKeyUp(keyCode, event)
    }

    // ── TerminalSessionClient ────────────────────────────────────

    override fun onTextChanged(s: TerminalSession) {}
    override fun onSessionFinished(s: TerminalSession) {
        runOnUiThread { updateDot(false); showOverlay("Process completed (exit ${s.exitStatus})", false) }
    }
    override fun onCopyTextToClipboard(s: TerminalSession, t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("terminal", t))
    }
    override fun onPasteTextFromClipboard(s: TerminalSession) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString()?.let { s.write(it) }
    }
    override fun getTerminalCursorStyle() = 0
    override fun onBell(s: TerminalSession) {}
    override fun onColorsChanged(s: TerminalSession) { terminalView.invalidate() }
    override fun onTerminalCursorStateChange(b: Boolean) {}
    override fun onTitleChanged(s: TerminalSession) {
        runOnUiThread { titleView.text = s.title.takeIf { it.isNotBlank() } ?: "OpenClaw Terminal" }
    }
    override fun logError(t: String?, m: String?) { Log.e("PTY", m ?: "") }
    override fun logWarn(t: String?, m: String?) { Log.w("PTY", m ?: "") }
    override fun logInfo(t: String?, m: String?) { Log.i("PTY", m ?: "") }
    override fun logDebug(t: String?, m: String?) { Log.d("PTY", m ?: "") }
    override fun logVerbose(t: String?, m: String?) {}
    override fun logStackTraceWithMessage(t: String?, m: String?, e: Exception?) {}
    override fun logStackTrace(t: String?, e: Exception?) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ── TerminalViewClient ───────────────────────────────────────

    private var lastTapTime = 0L

    private inner class ViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val adj = if (scale < 1f) 1f - (1f - scale) * 0.85f else 1f + (scale - 1f) * 0.85f
            val ns = (currentFontSizeSp * adj).coerceIn(MIN_FONT, MAX_FONT)
            if (ns != currentFontSizeSp) { currentFontSizeSp = ns; terminalView.setTextSize(ns.toInt()) }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 300) { toggleKbd(); lastTapTime = 0 }
            else { terminalView.requestFocus(); showKbd(); lastTapTime = now }
        }
        override fun onLongPress(e: MotionEvent?): Boolean {
            e?.let { showContextMenu(it.x, it.y) }; return true
        }
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
        override fun logError(t: String?, m: String?) { Log.e("PTY-V", m ?: "") }
        override fun logWarn(t: String?, m: String?) { Log.w("PTY-V", m ?: "") }
        override fun logInfo(t: String?, m: String?) {}
        override fun logDebug(t: String?, m: String?) {}
        override fun logVerbose(t: String?, m: String?) {}
        override fun logStackTraceWithMessage(t: String?, m: String?, e: Exception?) {}
        override fun logStackTrace(t: String?, e: Exception?) {}
    }

    // Edge swipe detection
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val screenW = resources.displayMetrics.widthPixels
        val edgeZone = dp(100)
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                edgeSwipeStartX = if (ev.x > screenW - edgeZone) ev.x else -1f
            }
            MotionEvent.ACTION_UP -> {
                if (edgeSwipeStartX > 0 && edgeSwipeStartX - ev.x > dp(100)) {
                    finish(); return true
                }
                edgeSwipeStartX = -1f
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
