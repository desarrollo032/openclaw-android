package com.openclaw.android

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.openclaw.android.databinding.ActivityTerminalBinding
import java.util.*

/**
 * TerminalActivity - Terminal interactivo usando librerías oficiales de Termux
 *
 * CARACTERÍSTICAS:
 * - Shell completa usando /system/bin/sh
 * - Soporte para comandos básicos: ls, cd, pwd, echo, cat, mkdir, etc.
 * - Entrada de teclado (físico y virtual)
 * - Scroll táctil en el terminal
 * - Configuración de colores y tamaño de fuente
 * - Manejo completo del ciclo de vida
 */
class TerminalActivity : AppCompatActivity(), TerminalSessionClient {

    companion object {
        private const val TAG = "TerminalActivity"
        // Shell por defecto en Android
        private const val DEFAULT_SHELL = "/system/bin/sh"
        // Directorio de trabajo inicial
        private const val INITIAL_WORKING_DIR = "/data/local/tmp"
    }

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configurar ventana completa para mejor experiencia de terminal
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        terminalView = binding.terminalView

        // Configurar el TerminalView con un cliente personalizado
        terminalView.setTerminalViewClient(TerminalViewClientImpl())

        // Configurar apariencia del terminal
        configureTerminalAppearance()

        // Crear y iniciar la sesión de terminal
        createTerminalSession()
    }

    /**
     * CONFIGURACIÓN DE APARIENCIA DEL TERMINAL
     * - Tamaño de fuente: 14sp (legible en móviles)
     * - Colores: Esquema oscuro estándar de terminal
     * - Padding: Para mejor visualización
     */
    private fun configureTerminalAppearance() {
        // Configurar tamaño de fuente (en SP)
        terminalView.textSize = 14f

        // Configurar colores del terminal (esquema estilo Termux)
        // Estos son los colores ANSI estándar
        val colorScheme = intArrayOf(
            0xFF000000.toInt(),  // 0: Negro (fondo)
            0xFFDD0000.toInt(),  // 1: Rojo
            0xFF00DD00.toInt(),  // 2: Verde
            0xFFDDDD00.toInt(),  // 3: Amarillo
            0xFF0000DD.toInt(),  // 4: Azul
            0xFFDD00DD.toInt(),  // 5: Magenta
            0xFF00DDDD.toInt(),  // 6: Cian
            0xFFDDDDDD.toInt(),  // 7: Blanco (texto)
            0xFF555555.toInt(),  // 8: Gris oscuro
            0xFFFF5555.toInt(),  // 9: Rojo claro
            0xFF55FF55.toInt(),  // 10: Verde claro
            0xFFFFFF55.toInt(),  // 11: Amarillo claro
            0xFF5555FF.toInt(),  // 12: Azul claro
            0xFFFF55FF.toInt(),  // 13: Magenta claro
            0xFF55FFFF.toInt(),  // 14: Cian claro
            0xFFFFFFFF.toInt()   // 15: Blanco brillante
        )

        // Aplicar colores al terminal
        terminalView.setTerminalColors(colorScheme)

        // Configurar padding para mejor visualización
        terminalView.setPadding(16, 16, 16, 16)
    }

    /**
     * CREAR SESIÓN DE TERMINAL
     * Inicializa una sesión con /system/bin/sh
     *
     * NOTA SOBRE ANDROID 11+ (API 30+):
     * Android 11 introdujo restricciones W^X (Write-Xor-Execute) que pueden
     * impedir la ejecución de binarios en ciertas ubicaciones.
     * /system/bin/sh generalmente funciona porque es parte del sistema.
     *
     * Si hay problemas, verificar que el directorio de trabajo exista y sea accesible.
     */
    private fun createTerminalSession() {
        try {
            // Verificar que el shell existe
            val shellPath = DEFAULT_SHELL

            // Crear directorio de trabajo si no existe
            val workingDir = INITIAL_WORKING_DIR
            val workDirFile = java.io.File(workingDir)
            if (!workDirFile.exists()) {
                workDirFile.mkdirs()
            }

            // Variables de entorno para la sesión
            val env = arrayOf(
                "TERM=xterm-256color",      // Tipo de terminal
                "HOME=$workingDir",         // Directorio home
                "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin", // PATH básico
                "LANG=en_US.UTF-8",         // Locale
                "COLORTERM=truecolor"        // Soporte de colores
            )

            // Crear la sesión de terminal
            // Constructor correcto: (executable, arguments, environment, workingDirectory, client)
            terminalSession = TerminalSession(
                shellPath,                    // Ejecutable del shell
                arrayOf(shellPath),           // Argumentos (el propio shell)
                env,                          // Variables de entorno
                workingDir,                   // Directorio de trabajo
                this                          // Cliente (callback)
            )

            // Adjuntar la sesión al TerminalView
            terminalView.attachSession(terminalSession)

            Log.i(TAG, "Terminal session created successfully with shell: $shellPath")

        } catch (e: Exception) {
            Log.e(TAG, "Error creating terminal session", e)
        }
    }

    // ── IMPLEMENTACIÓN DE TerminalSessionClient ───────────────────────────────
    // Estos callbacks reciben eventos de la sesión de terminal

    /**
     * Se llama cuando el texto del terminal cambia
     */
    override fun onTextChanged(changedSession: TerminalSession?) {
        // El TerminalView se actualiza automáticamente
    }

    /**
     * Se llama cuando la sesión termina
     */
    override fun onSessionFinished(finishedSession: TerminalSession?) {
        Log.i(TAG, "Terminal session finished")
        runOnUiThread {
            // Opcional: Mostrar mensaje de sesión terminada
            // O reiniciar la sesión automáticamente
        }
    }

    /**
     * Se llama cuando el usuario copia texto
     */
    override fun onCopyText(session: TerminalSession?, text: String?) {
        // El texto ya se copia al portapapeles automáticamente
        Log.d(TAG, "Text copied: ${text?.take(50)}")
    }

    /**
     * Se llama cuando se necesita pegar texto
     */
    override fun onPasteText(session: TerminalSession?): String? {
        // Retornar texto del portapapeles si es necesario
        return null
    }

    /**
     * Se llama en eventos de bell (sonido)
     */
    override fun onBell(session: TerminalSession?) {
        // Opcional: reproducir sonido o vibración
    }

    /**
     * Se llama cuando cambian los colores del terminal
     */
    override fun onColorsChanged(session: TerminalSession?) {
        // Los colores se actualizan automáticamente
    }

    /**
     * Se llama cuando cambia el estado del cursor
     */
    override fun onTerminalCursorStateChange(state: Boolean) {
        // state = true: cursor visible, false: cursor oculto
    }

    /**
     * Se llama cuando cambia el título de la sesión
     */
    override fun onSessionTitleChanged(changedSession: TerminalSession?) {
        val title = changedSession?.title ?: "Terminal"
        runOnUiThread {
            supportActionBar?.title = title
        }
    }

    /**
     * Se llama en eventos de scroll (trackpad)
     */
    override fun onTrackpadScrollDown() {
        terminalView.scrollDown()
    }

    /**
     * Se llama en eventos de scroll (trackpad)
     */
    override fun onTrackpadScrollUp() {
        terminalView.scrollUp()
    }

    /**
     * Se llama cuando se solicita selección de texto
     */
    override fun onRequestSelectingText() {
        terminalView.startSelectionMode()
    }

    // ── MANEJO DE TECLAS ─────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Delegar manejo de teclas al TerminalView
        // Esto permite que ENTER, BACKSPACE, flechas, etc. funcionen correctamente
        return if (terminalView.onKeyDown(keyCode, event)) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (terminalView.onKeyUp(keyCode, event)) {
            true
        } else {
            super.onKeyUp(keyCode, event)
        }
    }

    /**
     * MANEJO DEL BOTÓN ATRÁS
     * - Si hay texto seleccionado, cancela la selección
     * - Si no, comportamiento normal (salir de la actividad)
     */
    override fun onBackPressed() {
        if (terminalView.isSelectingText) {
            terminalView.stopSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    // ── CICLO DE VIDA ─────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Reanudar la sesión si es necesario
        terminalSession?.let { session ->
            // La sesión se reanuda automáticamente
        }
    }

    override fun onPause() {
        super.onPause()
        // Pausar la sesión si es necesario
    }

    override fun onDestroy() {
        super.onDestroy()
        // Terminar la sesión de terminal al destruir la actividad
        terminalSession?.finishIfRunning()
        terminalSession = null
    }

    // ── CLASE INTERNA PARA TerminalViewClient ────────────────────────────────

    /**
     * Implementación de TerminalViewClient para manejar eventos del TerminalView
     */
    private inner class TerminalViewClientImpl : TerminalViewClient {

        /**
         * Manejar gestos de escala (zoom)
         * Retorna la escala aplicada
         */
        override fun onScale(scale: Float): Float {
            // Permitir zoom cambiando el tamaño de fuente
            val newSize = (terminalView.textSize * scale).coerceIn(8f, 32f)
            terminalView.textSize = newSize
            return newSize / terminalView.textSize
        }

        /**
         * Manejar toque simple
         */
        override fun onSingleTapUp(event: android.view.MotionEvent?) {
            // Mostrar teclado virtual si es necesario
            terminalView.requestFocus()
        }

        /**
         * Determinar si el botón atrás debe mapearse a ESC
         */
        override fun shouldBackButtonBeMappedToEscape(): Boolean {
            return false // Comportamiento normal del botón atrás
        }

        /**
         * Cambios en el modo de copia
         */
        override fun copyModeChanged(copyMode: Boolean) {
            Log.d(TAG, "Copy mode changed: $copyMode")
        }

        /**
         * Manejar teclas presionadas en el TerminalView
         */
        override fun onKeyDown(keyCode: Int, event: KeyEvent?, session: TerminalSession?): Boolean {
            return false // Dejar que TerminalActivity maneje esto
        }

        /**
         * Manejar teclas liberadas en el TerminalView
         */
        override fun onKeyUp(keyCode: Int, event: KeyEvent?, session: TerminalSession?): Boolean {
            return false
        }

        /**
         * Manejar pulsación larga
         */
        override fun onLongPress(event: android.view.MotionEvent?): Boolean {
            // Iniciar modo selección en pulsación larga
            terminalView.startSelectionMode()
            return true
        }

        /**
         * Leer estado de teclas modificadoras
         */
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        /**
         * Determinar si se debe deshabilitar la entrada
         */
        override fun disableInput(): Boolean = false
    }
}
