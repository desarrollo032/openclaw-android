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
import java.io.PrintWriter

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

    private var process:     Process?     = null
    private var stdinWriter: PrintWriter? = null
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
                // Use the loader copy in nativeLibraryDir — SELinux allows exec there
                val loader    = OpenClawInstaller.getLoaderPath(this@OnboardActivity)
                // node binary may be "node" or "node.real" depending on the payload build
                val nodeBin   = File(base, "node/bin")
                val nodeExec  = listOf("node.real", "node").map { File(nodeBin, it) }
                                    .firstOrNull { it.exists() }
                                    ?: File(nodeBin, "node.real") // fallback for error msg
                val libs      = File(base, "glibc/lib").absolutePath
                val openclaw  = File(base, "lib/node_modules/openclaw/openclaw.mjs")
                val tmpDir    = File(cacheDir, "tmp").apply { mkdirs() }
                val configDir = OpenClawInstaller.getConfigDir(this@OnboardActivity)

                // ── Diagnostic: log file state before exec ────────────────
                listOf(loader, nodeExec).forEach { f ->
                    Log.i(TAG, "PRE-EXEC ${f.name}: exists=${f.exists()} canExec=${f.canExecute()} canRead=${f.canRead()} path=${f.absolutePath}")
                    try {
                        val stat = android.system.Os.stat(f.absolutePath)
                        Log.i(TAG, "  stat mode=0${stat.st_mode.toString(8)} uid=${stat.st_uid}")
                    } catch (e: Exception) {
                        Log.w(TAG, "  stat failed: ${e.message}")
                    }
                }

                val pb = ProcessBuilder(
                    loader.absolutePath,
                    "--library-path", libs,
                    nodeExec.absolutePath,
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
                        put("PATH",            "${base.absolutePath}/node/bin:/system/bin")
                        put("TERM",            "xterm-256color")
                        put("COLUMNS",         "80")
                        put("LINES",           "40")
                        put("FORCE_COLOR",     "0")
                        put("NO_COLOR",        "1")
                    }
                }

                process = pb.start()
                stdinWriter = PrintWriter(process!!.outputStream, true)

                withContext(Dispatchers.Main) {
                    setStatus("Onboard en progreso...", Color.parseColor("#facc15"))
                    inputField.isEnabled = true
                    sendButton.isEnabled = true
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
        val writer = stdinWriter ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            writer.println(text)
            writer.flush()
        }
        appendOutput("> $text\n")
        inputField.text.clear()
    }

    private fun onOnboardFinished(code: Int) {
        inputField.isEnabled = false
        sendButton.isEnabled = false

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
        terminalOutput.append(text)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
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

        // ── Input row ─────────────────────────────────────────────────────────
        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity     = Gravity.CENTER_VERTICAL
        inputRow.setPadding(dp(12), dp(8), dp(12), dp(8))
        inputRow.setBackgroundColor(Color.parseColor("#0d0d1a"))

        val promptView = TextView(this)
        promptView.setText("›")
        promptView.textSize = 18f
        promptView.typeface = Typeface.DEFAULT_BOLD
        promptView.setTextColor(Color.parseColor("#6366f1"))
        val promptLp = LinearLayout.LayoutParams(-2, -2)
        promptLp.rightMargin = dp(8)
        promptView.layoutParams = promptLp
        inputRow.addView(promptView)

        inputField = EditText(this)
        inputField.hint = "Escribe tu respuesta..."
        inputField.setHintTextColor(Color.parseColor("#444466"))
        inputField.setTextColor(Color.WHITE)
        inputField.textSize = 14f
        inputField.typeface = Typeface.MONOSPACE
        inputField.background = null
        inputField.isEnabled = false
        inputField.imeOptions = EditorInfo.IME_ACTION_SEND
        inputField.setSingleLine(true)
        inputField.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        inputField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val txt = inputField.text.toString().trim()
                if (txt.isNotEmpty()) sendInput(txt)
                true
            } else false
        }
        inputRow.addView(inputField)

        sendButton = ImageButton(this)
        sendButton.setImageResource(android.R.drawable.ic_menu_send)
        sendButton.imageTintList = ColorStateList.valueOf(Color.parseColor("#6366f1"))
        sendButton.background = null
        sendButton.isEnabled = false
        sendButton.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        sendButton.setOnClickListener {
            val txt = inputField.text.toString().trim()
            if (txt.isNotEmpty()) sendInput(txt)
        }
        inputRow.addView(sendButton)

        root.addView(inputRow)
        return root
    }
}
