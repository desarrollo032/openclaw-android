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
    private lateinit var btnSpace:       Button
    private lateinit var btnCtrlC:       Button

    private var process:     Process?     = null
    private var ioJob:       Job?         = null
    private val allNavKeys = mutableListOf<View>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If onboard was already completed (flag set), go straight to dashboard
        // UNLESS a specific interactive command was requested
        val interactiveCmd = intent.getStringExtra("interactive_cmd")
        if (interactiveCmd == null && OpenClawInstaller.isOnboardComplete(this)) {
            Log.i("OnboardActivity", "Onboard already complete — skipping to dashboard")
            launchDashboard()
            return
        }

        setContentView(buildLayout())
        startOnboard(interactiveCmd)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onDestroy() {
        ioJob?.cancel()
        process?.destroyForcibly()
        super.onDestroy()
    }

    // ── Onboard process ───────────────────────────────────────────────────────

    private fun startOnboard(customCmd: String? = null) {
        val cmdArgs = customCmd?.trim()?.split("\\s+".toRegex())?.filter { it.isNotEmpty() }
            ?: listOf("onboard")
        val displayCmd = customCmd ?: "openclaw onboard"

        setStatus("Iniciando $displayCmd...", Color.parseColor("#a0a0c0"))
        appendOutput("🦀 OpenClaw — $displayCmd\n")
        appendOutput("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")

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
                    listOf(
                        loader.absolutePath,
                        "--library-path", libs,
                        nodeExec.absolutePath,
                        "--disable-warning=ExperimentalWarning",
                        openclaw.absolutePath
                    ) + cmdArgs
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
                    allNavKeys.forEach { it.isEnabled = true }
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
        allNavKeys.forEach { it.isEnabled = false }

        if (code == 0) {
            appendOutput("\n✓ Configuración completada.\n")
            setStatus("✓ Listo — iniciando gateway...", Color.parseColor("#4ade80"))
            doneButton.visibility = View.GONE
            // Mark onboard as complete so MainActivity won't redirect here again
            OpenClawInstaller.markOnboardComplete(this)
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

        // ── Terminal keyboard ─────────────────────────────────────────────────
        // Modifier state: when active, next key press sends Ctrl+X or Alt+X
        var ctrlActive = false
        var altActive  = false

        val kbContainer = LinearLayout(this)
        kbContainer.orientation = LinearLayout.VERTICAL
        kbContainer.setBackgroundColor(Color.parseColor("#0a0a18"))
        kbContainer.setPadding(dp(4), dp(4), dp(4), dp(4))

        // ── Key factory ───────────────────────────────────────────────────────
        // weight: flex weight in the row. fixedDp: fixed width (0 = use weight)
        fun key(
            label: String,
            weight: Float = 1f,
            fixedDp: Int = 0,
            bg: String = "#1c1c2e",
            fg: String = "#e2e8f0",
            action: () -> Unit
        ): View {
            val btn = Button(this)
            btn.text = label
            btn.textSize = 11f
            btn.typeface = Typeface.DEFAULT_BOLD
            btn.setTextColor(Color.parseColor(fg))
            btn.isEnabled = false
            btn.setPadding(0, 0, 0, 0)
            val bgd = GradientDrawable()
            bgd.shape = GradientDrawable.RECTANGLE
            bgd.cornerRadius = dp(6).toFloat()
            bgd.setColor(Color.parseColor(bg))
            bgd.setStroke(dp(1), Color.parseColor("#2d2d4a"))
            btn.background = bgd
            val lp = if (fixedDp > 0)
                LinearLayout.LayoutParams(dp(fixedDp), dp(40))
            else
                LinearLayout.LayoutParams(0, dp(40), weight)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            btn.layoutParams = lp
            btn.setOnClickListener { action() }
            // Register for enable/disable
            btn.tag = "navkey"
            return btn
        }

        // Modifier toggle factory (stays highlighted when active)
        fun modKey(label: String, weight: Float = 1.4f): Button {
            val btn = Button(this)
            btn.text = label
            btn.textSize = 11f
            btn.typeface = Typeface.DEFAULT_BOLD
            btn.setTextColor(Color.parseColor("#fbbf24"))
            btn.isEnabled = false
            btn.setPadding(0, 0, 0, 0)
            val bgd = GradientDrawable()
            bgd.shape = GradientDrawable.RECTANGLE
            bgd.cornerRadius = dp(6).toFloat()
            bgd.setColor(Color.parseColor("#2d2200"))
            bgd.setStroke(dp(1), Color.parseColor("#78350f"))
            btn.background = bgd
            val lp = LinearLayout.LayoutParams(0, dp(40), weight)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            btn.layoutParams = lp
            btn.tag = "navkey"
            return btn
        }

        fun row(vararg views: View): LinearLayout {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.layoutParams = LinearLayout.LayoutParams(-1, -2)
            views.forEach { r.addView(it) }
            return r
        }

        // Helper: send raw bytes, applying Ctrl or Alt modifier if active
        fun sendKey(bytes: ByteArray, char: Char? = null) {
            val toSend: ByteArray = when {
                ctrlActive && char != null -> {
                    ctrlActive = false
                    byteArrayOf((char.code and 0x1F).toByte())
                }
                altActive && bytes.isNotEmpty() -> {
                    altActive = false
                    byteArrayOf(0x1B) + bytes
                }
                else -> bytes
            }
            sendRaw(toSend)
        }

        // Collect all nav keys for enable/disable (uses class-level allNavKeys)

        // ── Row 1: ESC  TAB  CTRL  ALT  |  HOME  END  PGUP  PGDN ────────────
        val ctrlBtn = modKey("CTRL")
        val altBtn  = modKey("ALT")

        ctrlBtn.setOnClickListener {
            ctrlActive = !ctrlActive
            altActive  = false
            val bgd = ctrlBtn.background as GradientDrawable
            bgd.setColor(Color.parseColor(if (ctrlActive) "#92400e" else "#2d2200"))
        }
        altBtn.setOnClickListener {
            altActive  = !altActive
            ctrlActive = false
            val bgd = altBtn.background as GradientDrawable
            bgd.setColor(Color.parseColor(if (altActive) "#92400e" else "#2d2200"))
        }

        val r1 = row(
            key("ESC",  bg="#3b1f1f", fg="#fca5a5") { sendKey(byteArrayOf(0x1B)) },
            key("TAB",  bg="#1e2d1e", fg="#86efac") { sendKey(byteArrayOf(0x09)) },
            ctrlBtn,
            altBtn,
            key("HOME", bg="#1c1c2e", fg="#c4b5fd") { sendKey(byteArrayOf(0x1B,0x5B,0x48)) },
            key("END",  bg="#1c1c2e", fg="#c4b5fd") { sendKey(byteArrayOf(0x1B,0x5B,0x46)) },
            key("PGUP", bg="#1c1c2e", fg="#c4b5fd") { sendKey(byteArrayOf(0x1B,0x5B,0x35,0x7E)) },
            key("PGDN", bg="#1c1c2e", fg="#c4b5fd") { sendKey(byteArrayOf(0x1B,0x5B,0x36,0x7E)) }
        )

        // ── Row 2: ← ↑ ↓ →  |  SPACE  BKSP  DEL ────────────────────────────
        btnLeft  = key("←", bg="#1e2a3e", fg="#93c5fd") { sendKey(byteArrayOf(0x1B,0x5B,0x44), 'D') } as Button
        btnUp    = key("↑", bg="#1e2a3e", fg="#93c5fd") { sendKey(byteArrayOf(0x1B,0x5B,0x41), 'A') } as Button
        btnDown  = key("↓", bg="#1e2a3e", fg="#93c5fd") { sendKey(byteArrayOf(0x1B,0x5B,0x42), 'B') } as Button
        btnRight = key("→", bg="#1e2a3e", fg="#93c5fd") { sendKey(byteArrayOf(0x1B,0x5B,0x43), 'C') } as Button

        val r2 = row(
            btnLeft, btnUp, btnDown, btnRight,
            key("SPACE", weight=1.5f, bg="#1e2a3e", fg="#93c5fd") { sendKey(byteArrayOf(0x20), ' ') },
            key("BKSP",  bg="#3b1f1f", fg="#fca5a5") { sendKey(byteArrayOf(0x7F)) },
            key("DEL",   bg="#3b1f1f", fg="#fca5a5") { sendKey(byteArrayOf(0x1B,0x5B,0x33,0x7E)) }
        )

        // ── Row 3: ENTER  |  Ctrl shortcuts ─────────────────────────────────
        btnEnter = key("↵ ENTER", weight=2f, bg="#14532d", fg="#86efac") { sendKey(byteArrayOf(0x0D)) } as Button
        btnCtrlC = key("^C", bg="#7f1d1d", fg="#fca5a5") { sendRaw(byteArrayOf(0x03)) } as Button

        val r3 = row(
            btnEnter,
            btnCtrlC,
            key("^D", bg="#3b1f1f", fg="#fca5a5") { sendRaw(byteArrayOf(0x04)) },
            key("^Z", bg="#2d2200", fg="#fbbf24") { sendRaw(byteArrayOf(0x1A)) },
            key("^L", bg="#1c1c2e", fg="#c4b5fd") { sendRaw(byteArrayOf(0x0C)) },
            key("^U", bg="#1c1c2e", fg="#c4b5fd") { sendRaw(byteArrayOf(0x15)) },
            key("^K", bg="#1c1c2e", fg="#c4b5fd") { sendRaw(byteArrayOf(0x0B)) },
            key("^W", bg="#1c1c2e", fg="#c4b5fd") { sendRaw(byteArrayOf(0x17)) }
        )

        // ── Row 4: Quick-answer buttons for Yes/No menus ─────────────────────
        // These send the text + Enter directly, which openclaw accepts for Yes/No prompts
        btnSpace = key("SPACE", bg="#1e2a3e", fg="#93c5fd") { sendRaw(byteArrayOf(0x20)) } as Button
        val r4 = row(
            key("✓ YES", weight=1.5f, bg="#14532d", fg="#86efac") {
                sendRaw("yes\r".toByteArray())
            },
            key("✗ NO",  weight=1.5f, bg="#7f1d1d", fg="#fca5a5") {
                sendRaw("no\r".toByteArray())
            },
            btnSpace,
            key("y", bg="#1e2a3e", fg="#93c5fd") { sendRaw("y\r".toByteArray()) },
            key("n", bg="#1e2a3e", fg="#93c5fd") { sendRaw("n\r".toByteArray()) },
            key("skip", bg="#1c1c2e", fg="#94a3b8") { sendRaw("skip\r".toByteArray()) },
            key("quit", bg="#2d2200", fg="#fbbf24") { sendRaw("quit\r".toByteArray()) },
            key("📋",  weight=1.2f, bg="#1e2d1e", fg="#86efac") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return@key
                sendRaw(clip.toByteArray(Charsets.UTF_8))
            }
        )

        // Collect all keys for enable/disable
        listOf(r1, r2, r3, r4).forEach { row ->
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i)
                if (v.tag == "navkey" || v is Button) allNavKeys.add(v)
            }
        }
        // Also add the modifier buttons explicitly
        allNavKeys.add(ctrlBtn)
        allNavKeys.add(altBtn)

        kbContainer.addView(r1)
        kbContainer.addView(r2)
        kbContainer.addView(r3)
        kbContainer.addView(r4)
        root.addView(kbContainer)

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
