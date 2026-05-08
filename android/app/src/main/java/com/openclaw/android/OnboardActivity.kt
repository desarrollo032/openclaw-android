package com.openclaw.android

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File

private const val TAG = "OnboardActivity"

/**
 * Runs `openclaw onboard` as an interactive terminal session.
 * When onboard exits with code 0, starts the gateway and opens the dashboard.
 */
class OnboardActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var inputField:     EditText
    private lateinit var sendButton:     ImageButton
    private lateinit var doneButton:     Button
    private lateinit var scrollView:     ScrollView
    private lateinit var statusBar:      TextView
    private lateinit var btnUp:          Button
    private lateinit var btnDown:        Button
    private lateinit var btnLeft:        Button
    private lateinit var btnRight:       Button
    private lateinit var btnEnter:       Button
    private lateinit var btnCtrlC:       Button

    private var process:     Process?     = null
    private var ioJob:       Job?         = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        startOnboard()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onDestroy() {
        ioJob?.cancel()
        process?.destroyForcibly()
        super.onDestroy()
    }

    // ── Onboard process ───────────────────────────────────────────────────────

    private fun startOnboard() {
        setStatus("Iniciando openclaw onboard...", Color.parseColor("#a0a0c0"))
        appendOutput("🦀 OpenClaw Onboard\n")
        appendOutput("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        appendOutput("Responde las preguntas del asistente de configuración.\n\n")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val base      = OpenClawInstaller.getPayloadDir(this@OnboardActivity)
                val nativeDir = File(applicationInfo.nativeLibraryDir)
                val loader    = File(nativeDir, "libldlinux.so")
                val nodeExec  = File(nativeDir, "libnode.so")
                val glibcLibs = File(base, "glibc/lib").absolutePath
                val libs      = "${nativeDir.absolutePath}:${glibcLibs}"
                val openclaw  = File(base, "lib/node_modules/openclaw/openclaw.mjs")
                val tmpDir    = File(cacheDir, "tmp").apply { mkdirs() }
                val configDir = OpenClawInstaller.getConfigDir(this@OnboardActivity)

                // ── Diagnostic: log file state before exec ────────────────
                listOf(loader, nodeExec).forEach { f ->
                    Log.i(TAG, "PRE-EXEC ${f.name}: exists=${f.exists()} canExec=${f.canExecute()} path=${f.absolutePath}")
                }

                val pb = ProcessBuilder(
                    loader.absolutePath,
                    "--library-path", libs,
                    nodeExec.absolutePath,
                    "--disable-warning=ExperimentalWarning",
                    openclaw.absolutePath,
                    "onboard"
                ).apply {
                    directory(base)
                    redirectErrorStream(true)
                    environment().apply {
                        remove("LD_PRELOAD")
                        put("LD_LIBRARY_PATH", libs)
                        put("OA_GLIBC",        "1")
                        put("CONTAINER",       "1")
                        put("TMPDIR",          tmpDir.absolutePath)
                        put("HOME",            base.absolutePath)
                        put("NODE_PATH",       "${base.absolutePath}/lib/node_modules")
                        put("OPENCLAW_HOME",   configDir.absolutePath)
                        put("SSL_CERT_FILE",   "${base.absolutePath}/etc/tls/cert.pem")
                        put("PATH",            "${base.absolutePath}/bin:/system/bin")
                        put("TERM",            "xterm-256color")
                        put("COLUMNS",         "80")
                        put("LINES",           "40")
                        put("FORCE_COLOR",     "0")
                        put("NO_COLOR",        "1")
                        // Prevent openclaw.mjs from respawning with execArgv flags
                        // that the ld-linux loader doesn't understand
                        put("NODE_NO_WARNINGS",                          "1")
                        put("OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED", "1")
                        put("OPENCLAW_SOURCE_COMPILE_CACHE_RESPAWNED",   "1")
                        put("NODE_DISABLE_COMPILE_CACHE",                "1")
                    }
                }

                process = pb.start()

                withContext(Dispatchers.Main) {
                    setStatus("Onboard en progreso...", Color.parseColor("#facc15"))
                    inputField.isEnabled = true
                    sendButton.isEnabled = true
                    btnUp.isEnabled    = true
                    btnDown.isEnabled  = true
                    btnLeft.isEnabled  = true
                    btnRight.isEnabled = true
                    btnEnter.isEnabled = true
                    btnCtrlC.isEnabled = true
                    // Update button backgrounds to active state
                    listOf(btnUp, btnDown, btnLeft, btnRight).forEach { btn ->
                        (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#1e3a5f"))
                    }
                    (btnEnter.background as? GradientDrawable)?.setColor(Color.parseColor("#166534"))
                    (btnCtrlC.background as? GradientDrawable)?.setColor(Color.parseColor("#991b1b"))
                }

                ioJob = launch(Dispatchers.IO) {
                    process!!.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, line)
                        runOnUiThread { appendOutput("$line\n") }
                    }
                }

                val exitCode = process!!.waitFor()
                Log.i(TAG, "onboard exited: $exitCode")
                withContext(Dispatchers.Main) { onOnboardFinished(exitCode) }

            } catch (e: Exception) {
                Log.e(TAG, "onboard failed", e)
                withContext(Dispatchers.Main) {
                    appendOutput("\n⚠ Error: ${e.message}\n")
                    onOnboardFinished(-1)
                }
            }
        }
    }

    private fun sendInput(text: String) {
        if (text.isEmpty()) return
        val proc = process ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Send text bytes + CR (0x0D) — no println to avoid \r\n corruption
                val bytes = text.toByteArray(Charsets.UTF_8) + byteArrayOf(0x0D)
                proc.outputStream.write(bytes)
                proc.outputStream.flush()
            } catch (e: Exception) {
                Log.w(TAG, "sendInput failed: ${e.message}")
            }
        }
        runOnUiThread {
            appendOutput("> $text\n")
            inputField.text.clear()
        }
    }

    /** Send raw bytes directly to the process stdin (for escape sequences). */
    private fun sendRaw(bytes: ByteArray) {
        val proc = process ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                proc.outputStream.write(bytes)
                proc.outputStream.flush()
            } catch (e: Exception) {
                Log.w(TAG, "sendRaw failed: ${e.message}")
            }
        }
    }

    private fun onOnboardFinished(code: Int) {
        inputField.isEnabled = false
        sendButton.isEnabled = false
        btnUp.isEnabled    = false
        btnDown.isEnabled  = false
        btnLeft.isEnabled  = false
        btnRight.isEnabled = false
        btnEnter.isEnabled = false
        btnCtrlC.isEnabled = false

        if (code == 0) {
            appendOutput("\n✓ Configuración completada.\n")
            setStatus("✓ Listo — iniciando gateway...", Color.parseColor("#4ade80"))
            doneButton.visibility = View.GONE
            lifecycleScope.launch {
                delay(1_500)
                launchDashboard()
            }
        } else {
            appendOutput("\n⚠ Onboard terminó con código $code.\n")
            appendOutput("Puedes ir al dashboard y configurar desde allí.\n")
            setStatus("Onboard finalizado (código $code)", Color.parseColor("#f87171"))
            doneButton.visibility = View.VISIBLE
            doneButton.setText("Ir al dashboard")
            doneButton.setOnClickListener { launchDashboard() }
        }
    }

    private fun launchDashboard() {
        OpenClawGatewayService.start(this)
        startActivity(
            Intent(this, OpenClawDashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun appendOutput(text: String) {
        // Strip ANSI/VT100 escape sequences so the terminal stays readable
        val clean = text
            .replace(Regex("\u001B\\[\\?[0-9;]*[hlH]"), "")  // private mode: [?25l [?25h etc
            .replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")  // CSI sequences (cursor, color)
            .replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "") // OSC
            .replace(Regex("\u001B[()][AB012]"), "")          // charset designations
            .replace(Regex("\u001B[=>]"), "")                 // keypad mode
            .replace(Regex("\u001B[NOM]"), "")                // SS2/SS3/RI
            .replace(Regex("\r\n"), "\n")                     // CRLF → LF
            .replace(Regex("\r"), "")                         // bare CR
        if (clean.isNotEmpty()) {
            terminalOutput.append(clean)
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setStatus(msg: String, color: Int) {
        statusBar.setText(msg)
        statusBar.setTextColor(color)
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // ── Root ──────────────────────────────────────────────────────────────
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#080810"))
        root.layoutParams = FrameLayout.LayoutParams(-1, -1)

        // ── Header ────────────────────────────────────────────────────────────
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity     = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16), dp(12), dp(16), dp(12))
        header.setBackgroundColor(Color.parseColor("#0d0d1a"))

        val logoView = TextView(this)
        logoView.setText("🦀")
        logoView.textSize = 20f
        val logoLp = LinearLayout.LayoutParams(-2, -2)
        logoLp.rightMargin = dp(10)
        logoView.layoutParams = logoLp
        header.addView(logoView)

        val headerInfo = LinearLayout(this)
        headerInfo.orientation = LinearLayout.VERTICAL
        headerInfo.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

        val titleView = TextView(this)
        titleView.setText("OpenClaw Onboard")
        titleView.textSize = 15f
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.setTextColor(Color.WHITE)
        headerInfo.addView(titleView)

        statusBar = TextView(this)
        statusBar.setText("Iniciando...")
        statusBar.textSize = 11f
        statusBar.setTextColor(Color.parseColor("#a0a0c0"))
        headerInfo.addView(statusBar)

        header.addView(headerInfo)
        root.addView(header)

        // ── Terminal output ───────────────────────────────────────────────────
        scrollView = ScrollView(this)
        scrollView.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        scrollView.setBackgroundColor(Color.parseColor("#080810"))

        terminalOutput = TextView(this)
        terminalOutput.setText("")
        terminalOutput.textSize = 13f
        terminalOutput.setTextColor(Color.parseColor("#c8c8e8"))
        terminalOutput.typeface = Typeface.MONOSPACE
        terminalOutput.setPadding(dp(14), dp(12), dp(14), dp(12))
        terminalOutput.movementMethod = ScrollingMovementMethod()
        terminalOutput.setTextIsSelectable(true)

        scrollView.addView(terminalOutput)
        root.addView(scrollView)

        // ── "Continue anyway" button ──────────────────────────────────────────
        doneButton = Button(this)
        doneButton.setText("Ir al dashboard")
        doneButton.textSize = 14f
        doneButton.typeface = Typeface.DEFAULT_BOLD
        doneButton.setTextColor(Color.WHITE)
        val doneBg = GradientDrawable()
        doneBg.shape = GradientDrawable.RECTANGLE
        doneBg.setColor(Color.parseColor("#f59e0b"))
        doneButton.background = doneBg
        doneButton.layoutParams = LinearLayout.LayoutParams(-1, dp(52))
        doneButton.visibility = View.GONE
        root.addView(doneButton)

        // ── Nav keys (2 rows: arrows + action buttons) ───────────────────────
        val navContainer = LinearLayout(this)
        navContainer.orientation = LinearLayout.VERTICAL
        navContainer.setPadding(dp(8), dp(6), dp(8), dp(4))
        navContainer.setBackgroundColor(Color.parseColor("#0d0d1a"))

        fun makeNavBtn(label: String, bgColor: String, fgColor: String): Button {
            val btn = Button(this)
            btn.text = label
            btn.textSize = 15f
            btn.typeface = Typeface.DEFAULT_BOLD
            btn.setTextColor(Color.parseColor(fgColor))
            btn.isEnabled = false
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.RECTANGLE
            bg.cornerRadius = dp(10).toFloat()
            bg.setColor(Color.parseColor(bgColor))
            btn.background = bg
            val lp = LinearLayout.LayoutParams(0, dp(50), 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            btn.layoutParams = lp
            return btn
        }

        // Row 1: ← ↑ ↓ →  (blue-grey tone)
        val arrowRow = LinearLayout(this)
        arrowRow.orientation = LinearLayout.HORIZONTAL
        arrowRow.layoutParams = LinearLayout.LayoutParams(-1, -2)

        btnLeft  = makeNavBtn("⇤ Tab", "#2d3a5e", "#93c5fd")
        btnUp    = makeNavBtn("↑", "#2d3a5e", "#93c5fd")
        btnDown  = makeNavBtn("↓", "#2d3a5e", "#93c5fd")
        btnRight = makeNavBtn("Tab ⇥", "#2d3a5e", "#93c5fd")

        btnLeft.setOnClickListener  { sendRaw(byteArrayOf(0x1B, 0x5B, 0x5A)) } // Shift+Tab (ESC[Z)
        btnUp.setOnClickListener    { sendRaw(byteArrayOf(0x1B, 0x5B, 0x41)) } // ESC[A up
        btnDown.setOnClickListener  { sendRaw(byteArrayOf(0x1B, 0x5B, 0x42)) } // ESC[B down
        btnRight.setOnClickListener { sendRaw(byteArrayOf(0x09)) }              // Tab

        arrowRow.addView(btnLeft)
        arrowRow.addView(btnUp)
        arrowRow.addView(btnDown)
        arrowRow.addView(btnRight)

        // Row 2: ↵ Enter (green)  +  ✕ Ctrl+C (red)
        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.layoutParams = LinearLayout.LayoutParams(-1, -2)

        btnEnter = makeNavBtn("↵  ENTER", "#14532d", "#86efac")
        btnCtrlC = makeNavBtn("✕  CTRL+C", "#7f1d1d", "#fca5a5")

        btnEnter.setOnClickListener { sendRaw(byteArrayOf(0x0D)) }
        btnCtrlC.setOnClickListener { sendRaw(byteArrayOf(0x03)) }

        actionRow.addView(btnEnter)
        actionRow.addView(btnCtrlC)

        navContainer.addView(arrowRow)
        navContainer.addView(actionRow)
        root.addView(navContainer)

        // ── Input row ─────────────────────────────────────────────────────────
        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.VERTICAL
        inputRow.setPadding(dp(10), dp(6), dp(10), dp(10))
        inputRow.setBackgroundColor(Color.parseColor("#0d0d1a"))

        // Input field container (border + field side by side)
        val fieldRow = LinearLayout(this)
        fieldRow.orientation = LinearLayout.HORIZONTAL
        fieldRow.gravity = Gravity.CENTER_VERTICAL
        val fieldBg = GradientDrawable()
        fieldBg.shape = GradientDrawable.RECTANGLE
        fieldBg.cornerRadius = dp(10).toFloat()
        fieldBg.setColor(Color.parseColor("#12122a"))
        fieldBg.setStroke(dp(1), Color.parseColor("#3d3d6e"))
        fieldRow.background = fieldBg
        fieldRow.setPadding(dp(10), dp(4), dp(4), dp(4))
        fieldRow.layoutParams = LinearLayout.LayoutParams(-1, -2).also {
            it.bottomMargin = dp(6)
        }

        inputField = EditText(this)
        inputField.hint = "Escribe o pega tu respuesta..."
        inputField.setHintTextColor(Color.parseColor("#444466"))
        inputField.setTextColor(Color.WHITE)
        inputField.textSize = 14f
        inputField.typeface = Typeface.MONOSPACE
        inputField.background = null
        inputField.isEnabled = false
        // Multiline so paste works properly; Enter key sends
        inputField.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                               android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                               android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        inputField.imeOptions = EditorInfo.IME_ACTION_NONE
        inputField.isSingleLine = false
        inputField.maxLines = 4
        inputField.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        // Intercept Enter key to send
        inputField.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                val txt = inputField.text.toString()
                if (txt.isNotEmpty()) sendInput(txt)
                true
            } else false
        }
        fieldRow.addView(inputField)

        sendButton = ImageButton(this)
        sendButton.setImageResource(android.R.drawable.ic_menu_send)
        sendButton.imageTintList = ColorStateList.valueOf(Color.parseColor("#6366f1"))
        sendButton.background = null
        sendButton.isEnabled = false
        sendButton.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        sendButton.setOnClickListener {
            val txt = inputField.text.toString()
            if (txt.isNotEmpty()) sendInput(txt)
        }
        fieldRow.addView(sendButton)
        inputRow.addView(fieldRow)

        // Quick-action buttons row: Paste | Clear
        val quickRow = LinearLayout(this)
        quickRow.orientation = LinearLayout.HORIZONTAL
        quickRow.layoutParams = LinearLayout.LayoutParams(-1, -2)

        fun quickBtn(label: String, color: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.textSize = 12f
            b.typeface = Typeface.DEFAULT_BOLD
            b.setTextColor(Color.parseColor(color))
            b.background = null
            val lp = LinearLayout.LayoutParams(-2, dp(36))
            lp.rightMargin = dp(8)
            b.layoutParams = lp
            b.setOnClickListener { action() }
            return b
        }

        quickRow.addView(quickBtn("📋 Pegar", "#a5b4fc") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return@quickBtn
            inputField.setText(inputField.text.toString() + clip)
            inputField.setSelection(inputField.text.length)
        })
        quickRow.addView(quickBtn("✕ Limpiar", "#f87171") {
            inputField.text.clear()
        })

        inputRow.addView(quickRow)
        root.addView(inputRow)
        return root
    }
}
