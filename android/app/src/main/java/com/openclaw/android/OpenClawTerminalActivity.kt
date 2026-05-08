package com.openclaw.android

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.android.databinding.ActivityTerminalBinding
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private const val TAG = "OpenClawTermAct"

/**
 * OpenClawTerminalActivity
 *
 * Terminal PTY real usando:
 *  - libbusybox.so como shell ("sh -i") desde nativeLibraryDir
 *  - Environment idéntico al ProcessBuilder del gateway (OpenClawTerminalManager)
 *  - TerminalView de Termux: xterm-256color, ANSI colors, scroll táctil, Ctrl+C
 *
 * REGLAS CRÍTICAS cumplidas:
 *  - Shell via nativeLibraryDir (nunca filesDir/cacheDir para ELFs)
 *  - LD_PRELOAD omitido del environment
 *  - Working dir: context.getDir("payload", MODE_PRIVATE)
 *  - Log.d("OpenClawGW", line) para output del proceso (compatible con el tag del gateway)
 *  - Sin Runtime.exec() — la PTY usa TerminalSession internamente
 */
class OpenClawTerminalActivity : AppCompatActivity(), TerminalSessionClient {

    // ── ViewBinding ───────────────────────────────────────────────────────────
    private lateinit var binding: ActivityTerminalBinding

    // ── Terminal components ───────────────────────────────────────────────────
    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null
    private lateinit var manager: OpenClawTerminalManager

    // ── Special-keys toolbar ──────────────────────────────────────────────────
    private lateinit var specialKeysBar: LinearLayout

    // ── Font size (zoom state) ────────────────────────────────────────────────
    private var currentFontSizeSp = 13f

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantener pantalla encendida mientras la terminal esté activa
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        terminalView = binding.terminalView
        manager      = OpenClawTerminalManager(this)

        // Diagnósticos en logcat (solo debug)
        manager.logDiagnostics()

        // Barra de teclas especiales (construida programáticamente sobre el layout XML)
        specialKeysBar = buildSpecialKeysBar()
        (binding.root as? android.widget.FrameLayout)?.addView(specialKeysBar)

        // Configurar TerminalView
        setupTerminalView()

        // Crear sesión PTY
        startSession()
    }

    override fun onResume() {
        super.onResume()
        terminalView.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        // La sesión PTY continúa viva en background — solo pausamos el foco
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy — finishing PTY session")
        terminalSession?.finishIfRunning()
        terminalSession = null
    }

    // ── Terminal setup ────────────────────────────────────────────────────────

    private fun setupTerminalView() {
        terminalView.setTerminalViewClient(OpenClawTerminalViewClient())
        terminalView.textSize = currentFontSizeSp

        // Esquema de colores estilo Termux/xterm oscuro
        terminalView.setTerminalColors(buildColorScheme())

        // Padding interior mínimo para no cortar caracteres en los bordes
        terminalView.setPadding(6, 0, 6, 0)
    }

    private fun startSession() {
        if (!manager.isBusyboxAvailable()) {
            showErrorOverlay(
                "libbusybox.so no encontrado.\n\n" +
                "Asegúrate de que el APK incluye libbusybox.so en jniLibs/arm64-v8a/ " +
                "y que el payload está instalado."
            )
            return
        }

        terminalSession = manager.createSession(this)
        if (terminalSession == null) {
            showErrorOverlay("No se pudo crear la sesión de terminal.")
            return
        }

        terminalView.attachSession(terminalSession)
        terminalView.requestFocus()
        showKeyboard()

        Log.d(TAG, "PTY session attached — shell: ${manager.busyboxFile.absolutePath}")
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // BACK: si hay texto seleccionado, cancela selección; si no, minimiza terminal
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (terminalView.isSelectingText) {
                terminalView.stopSelectionMode()
                return true
            }
            // No finish() — el usuario puede volver con el botón del sistema
            moveTaskToBack(true)
            return true
        }
        return if (terminalView.onKeyDown(keyCode, event)) true
        else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (terminalView.onKeyUp(keyCode, event)) true
        else super.onKeyUp(keyCode, event)
    }

    // ── TerminalSessionClient callbacks ───────────────────────────────────────

    /** La pantalla necesita re-renderizarse */
    override fun onTextChanged(changedSession: TerminalSession?) {
        // TerminalView se invalida automáticamente cuando la sesión notifica cambios.
        // Sin runOnUiThread aquí — el callback ya llega en el hilo correcto.
    }

    /** La sesión terminó (shell salió) */
    override fun onSessionFinished(finishedSession: TerminalSession?) {
        Log.i(TAG, "Shell session finished (exit code: ${finishedSession?.exitStatus})")
        runOnUiThread {
            // Mostrar mensaje de "Process completed" y botón de reinicio
            showRestartOverlay()
        }
    }

    /** El usuario copió texto desde la terminal */
    override fun onCopyText(session: TerminalSession?, text: String?) {
        text?.let {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", it))
            Log.d(TAG, "Copied ${it.length} chars to clipboard")
        }
    }

    /** La terminal necesita texto del portapapeles */
    override fun onPasteText(session: TerminalSession?): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    /** Bell (BEL char 0x07) — vibración opcional */
    override fun onBell(session: TerminalSession?) {
        // Vibración deliberadamente omitida para no molestar al usuario
    }

    /** Los colores del terminal cambiaron via secuencias OSC */
    override fun onColorsChanged(session: TerminalSession?) {
        terminalView.invalidate()
    }

    /** El cursor se ocultó/mostró */
    override fun onTerminalCursorStateChange(state: Boolean) {
        // No acción necesaria — TerminalView lo maneja
    }

    /** El título de la ventana cambió (secuencia OSC 0/2) */
    override fun onSessionTitleChanged(changedSession: TerminalSession?) {
        val title = changedSession?.title?.takeIf { it.isNotBlank() } ?: "Terminal"
        runOnUiThread {
            supportActionBar?.title = title
        }
    }

    /** Scroll hacia abajo por trackpad/ratón externo */
    override fun onTrackpadScrollDown() {
        terminalView.scrollDown()
    }

    /** Scroll hacia arriba por trackpad/ratón externo */
    override fun onTrackpadScrollUp() {
        terminalView.scrollUp()
    }

    /** El usuario inició modo selección de texto */
    override fun onRequestSelectingText() {
        terminalView.startSelectionMode()
    }

    // ── Special keys toolbar ──────────────────────────────────────────────────

    /**
     * Barra horizontal en la parte inferior con teclas frecuentes:
     * [ESC] [TAB] [CTRL] [↑] [↓] [←] [→] [Ctrl+C] [/] [~] [|]
     *
     * Se construye programáticamente para evitar dependencia de layout XML adicional.
     * Se ancla al borde inferior sobre el TerminalView.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildSpecialKeysBar(): LinearLayout {
        val dp = resources.displayMetrics.density

        val bar = LinearLayout(this).apply {
            orientation  = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                (40 * dp).toInt(),
                android.view.Gravity.BOTTOM
            )
        }

        data class SpecialKey(val label: String, val action: () -> Unit)

        val keys = listOf(
            SpecialKey("ESC")   { sendEscape() },
            SpecialKey("TAB")   { sendTab() },
            SpecialKey("^C")    { sendCtrlC() },
            SpecialKey("^D")    { sendCtrlD() },
            SpecialKey("↑")     { sendArrow(KeyEvent.KEYCODE_DPAD_UP) },
            SpecialKey("↓")     { sendArrow(KeyEvent.KEYCODE_DPAD_DOWN) },
            SpecialKey("←")     { sendArrow(KeyEvent.KEYCODE_DPAD_LEFT) },
            SpecialKey("→")     { sendArrow(KeyEvent.KEYCODE_DPAD_RIGHT) },
            SpecialKey("/")     { sendText("/") },
            SpecialKey("~")     { sendText("~") },
            SpecialKey("|")     { sendText("|") },
            SpecialKey("KBD")   { toggleKeyboard() }
        )

        keys.forEach { key ->
            val btn = TextView(this).apply {
                text      = key.label
                textSize  = 11f
                typeface  = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#c0c0e0"))
                gravity   = android.view.Gravity.CENTER
                setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setOnClickListener { key.action() }
                // Feedback visual al pulsar
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> v.setBackgroundColor(Color.parseColor("#2d2d4e"))
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.TRANSPARENT)
                    }
                    false
                }
            }
            bar.addView(btn)

            // Separador vertical
            bar.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#2d2d4e"))
                layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
            })
        }

        return bar
    }

    // ── Key injection ─────────────────────────────────────────────────────────

    private fun sendEscape() {
        terminalSession?.write(byteArrayOf(27), 0, 1) // ESC = 0x1B
    }

    private fun sendTab() {
        terminalSession?.write(byteArrayOf(9), 0, 1) // TAB = 0x09
    }

    private fun sendCtrlC() {
        terminalSession?.write(byteArrayOf(3), 0, 1) // ETX = Ctrl+C
        Log.d(TAG, "Sent Ctrl+C")
    }

    private fun sendCtrlD() {
        terminalSession?.write(byteArrayOf(4), 0, 1) // EOT = Ctrl+D (EOF)
        Log.d(TAG, "Sent Ctrl+D")
    }

    private fun sendArrow(keyCode: Int) {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        terminalView.onKeyDown(keyCode, event)
    }

    private fun sendText(text: String) {
        terminalSession?.write(text.toByteArray(Charsets.UTF_8), 0, text.length)
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (currentFocus == null) {
            terminalView.requestFocus()
        }
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        terminalView.postDelayed({
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_FORCED)
        }, 200)
    }

    // ── Overlays ──────────────────────────────────────────────────────────────

    /**
     * Muestra un overlay de error cuando libbusybox.so no está disponible.
     */
    private fun showErrorOverlay(message: String) {
        val dp = resources.displayMetrics.density
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = android.view.Gravity.CENTER
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(Color.parseColor("#0d0d12"))
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
        }
        overlay.addView(TextView(this).apply {
            text      = "⚠ Error de terminal"
            textSize  = 18f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#f87171"))
            gravity   = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = (12 * dp).toInt()
            }
        })
        overlay.addView(TextView(this).apply {
            text     = message
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#a0a0c0"))
            gravity  = android.view.Gravity.CENTER
        })
        overlay.addView(Button(this).apply {
            text = "Cerrar"
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = (20 * dp).toInt()
                gravity   = android.view.Gravity.CENTER_HORIZONTAL
            }
        })
        (binding.root as? android.widget.FrameLayout)?.addView(overlay)
        Log.e(TAG, "Error overlay shown: $message")
    }

    /**
     * Overlay cuando la sesión de shell termina, con botón para reiniciarla.
     */
    private fun showRestartOverlay() {
        val dp      = resources.displayMetrics.density
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = android.view.Gravity.CENTER
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(Color.parseColor("#CC0d0d12")) // semi-transparente
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
        }
        overlay.addView(TextView(this).apply {
            text     = "Process completed"
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#a0a0c0"))
            gravity  = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = (16 * dp).toInt()
            }
        })

        val restartBtn = Button(this).apply {
            text = "Nueva sesión"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6366f1"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = (10 * dp).toInt()
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        val closeBtn = Button(this).apply {
            text = "Cerrar terminal"
            setTextColor(Color.parseColor("#a0a0c0"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }

        restartBtn.setOnClickListener {
            (binding.root as? android.widget.FrameLayout)?.removeView(overlay)
            terminalSession?.finishIfRunning()
            terminalSession = null
            startSession()
        }
        closeBtn.setOnClickListener { finish() }

        overlay.addView(restartBtn)
        overlay.addView(closeBtn)
        (binding.root as? android.widget.FrameLayout)?.addView(overlay)
    }

    // ── Color scheme ──────────────────────────────────────────────────────────

    /**
     * Esquema de 16 colores ANSI + foreground + background + cursor.
     * Estilo: Dracula-inspired oscuro, compatible con xterm-256color.
     *
     * TerminalView espera un array de exactamente 258 entradas:
     *   [0..7]   = colores normales ANSI (negro→blanco)
     *   [8..15]  = colores brillantes ANSI
     *   [256]    = foreground
     *   [257]    = background
     *   [258] si existe = cursor color (depende de versión de la lib)
     *
     * Usamos el overload setTerminalColors(int[]) con 16 entradas que
     * corresponde a la API de terminal-view de Termux (los índices 256/257
     * se configuran internamente por el emulador desde las secuencias OSC).
     */
    private fun buildColorScheme(): IntArray = intArrayOf(
        /* 0  negro         */ 0xFF1E1E2E.toInt(),
        /* 1  rojo          */ 0xFFFF5555.toInt(),
        /* 2  verde         */ 0xFF50FA7B.toInt(),
        /* 3  amarillo      */ 0xFFF1FA8C.toInt(),
        /* 4  azul          */ 0xFF6272A4.toInt(),
        /* 5  magenta       */ 0xFFFF79C6.toInt(),
        /* 6  cian          */ 0xFF8BE9FD.toInt(),
        /* 7  blanco        */ 0xFFF8F8F2.toInt(),
        /* 8  negro brillante  */ 0xFF44475A.toInt(),
        /* 9  rojo brillante   */ 0xFFFF6E6E.toInt(),
        /* 10 verde brillante  */ 0xFF69FF94.toInt(),
        /* 11 amarillo brillo  */ 0xFFFFFFA5.toInt(),
        /* 12 azul brillante   */ 0xFFD6ACFF.toInt(),
        /* 13 magenta brillo   */ 0xFFFF92DF.toInt(),
        /* 14 cian brillante   */ 0xFFA4FFFF.toInt(),
        /* 15 blanco brillante */ 0xFFFFFFFF.toInt()
    )

    // ── TerminalViewClient inner class ────────────────────────────────────────

    /**
     * Implementación de TerminalViewClient.
     * Maneja: zoom (pinch), tap único, pulsación larga, teclas modificadoras.
     */
    private inner class OpenClawTerminalViewClient : TerminalViewClient {

        /** Pinch-to-zoom: ajusta tamaño de fuente entre 8sp y 28sp */
        override fun onScale(scale: Float): Float {
            val newSize = (currentFontSizeSp * scale).coerceIn(8f, 28f)
            if (newSize != currentFontSizeSp) {
                currentFontSizeSp    = newSize
                terminalView.textSize = newSize
            }
            return scale
        }

        /** Tap simple: solicitar foco y mostrar teclado */
        override fun onSingleTapUp(e: MotionEvent?) {
            if (!terminalView.isSelectingText) {
                terminalView.requestFocus()
                showKeyboard()
            }
        }

        /** Pulsación larga: iniciar selección de texto */
        override fun onLongPress(e: MotionEvent?): Boolean {
            terminalView.startSelectionMode()
            return true
        }

        /** El botón atrás envía ESC en lugar de navegar */
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false

        /** Notificación de cambio de modo copia */
        override fun copyModeChanged(copyMode: Boolean) {
            Log.d(TAG, "Copy mode: $copyMode")
        }

        /** Teclas presionadas en el view (delegadas a la Activity) */
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean   = false

        // Teclas modificadoras (teclado físico externo)
        override fun readControlKey(): Boolean = false
        override fun readAltKey():     Boolean = false
        override fun readShiftKey():   Boolean = false
        override fun readFnKey():      Boolean = false

        /** Deshabilitar entrada cuando la sesión terminó */
        override fun disableInput(): Boolean = terminalSession?.isRunning != true
    }
}
