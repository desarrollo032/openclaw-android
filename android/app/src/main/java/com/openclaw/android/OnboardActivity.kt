package com.openclaw.android

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        startOnboard()
    }

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
                val loader    = File(base, "glibc/lib/ld-linux-aarch64.so.1")
                val nodeReal  = File(base, "node/bin/node.real")
                val libs      = File(base, "glibc/lib").absolutePath
                val openclaw  = File(base, "lib/node_modules/openclaw/openclaw.mjs")
                val tmpDir    = File(cacheDir, "tmp").apply { mkdirs() }
                val configDir = OpenClawInstaller.getConfigDir(this@OnboardActivity)

                val pb = ProcessBuilder(
                    loader.absolutePath,
                    "--library-path", libs,
                    nodeReal.absolutePath,
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
            appendOutput("Puedes continuar de todas formas o reintentar.\n")
            setStatus("Onboard finalizado (código $code)", Color.parseColor("#f87171"))
            doneButton.visibility = View.VISIBLE
            doneButton.text       = "Continuar de todas formas"
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
        statusBar.text = msg
        statusBar.setTextColor(color)
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080810"))
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            setBackgroundColor(Color.parseColor("#0d0d1a"))
        }
        header.addView(TextView(this).apply {
            text     = "🦀"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 10.dp() }
        })
        headerInfo.addView(TextView(this).apply {
            text     = "OpenClaw Onboard"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        statusBar = TextView(this).apply {
            text     = "Iniciando..."
            textSize = 11f
            setTextColor(Color.parseColor("#a0a0c0"))
        }
        headerInfo.addView(statusBar)
        header.addView(headerInfo)
        root.addView(header)

        // Terminal output
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.parseColor("#080810"))
        }
        terminalOutput = TextView(this).apply {
            text             = ""
            textSize         = 13f
            setTextColor(Color.parseColor("#c8c8e8"))
            typeface         = Typeface.MONOSPACE
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            movementMethod   = ScrollingMovementMethod()
            isTextSelectable = true
        }
        scrollView.addView(terminalOutput)
        root.addView(scrollView)

        // "Continue anyway" button
        doneButton = Button(this).apply {
            text      = "Continuar de todas formas"
            textSize  = 14f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape    = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#f59e0b"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, 52.dp())
            visibility   = View.GONE
        }
        root.addView(doneButton)

        // Input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setBackgroundColor(Color.parseColor("#0d0d1a"))
        }
        inputRow.addView(TextView(this).apply {
            text      = "›"
            textSize  = 18f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#6366f1"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp() }
        })
        inputField = EditText(this).apply {
            hint          = "Escribe tu respuesta..."
            setHintTextColor(Color.parseColor("#444466"))
            setTextColor(Color.WHITE)
            textSize      = 14f
            typeface      = Typeface.MONOSPACE
            background    = null
            isEnabled     = false
            imeOptions    = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            layoutParams  = LinearLayout.LayoutParams(0, -2, 1f)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    val txt = text.toString().trim()
                    if (txt.isNotEmpty()) sendInput(txt)
                    true
                } else false
            }
        }
        inputRow.addView(inputField)
        sendButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#6366f1"))
            background    = null
            isEnabled     = false
            layoutParams  = LinearLayout.LayoutParams(44.dp(), 44.dp())
            setOnClickListener {
                val txt = inputField.text.toString().trim()
                if (txt.isNotEmpty()) sendInput(txt)
            }
        }
        inputRow.addView(sendButton)
        root.addView(inputRow)

        return root
    }
}
