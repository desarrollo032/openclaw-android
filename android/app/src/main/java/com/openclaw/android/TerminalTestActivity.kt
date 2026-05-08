package com.openclaw.android

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.io.File

class TerminalTestActivity : AppCompatActivity(), TerminalSessionClient {

    private lateinit var terminalView: TerminalView
    private lateinit var session: TerminalSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Layout programático — sin XML para aislar el test
        terminalView = TerminalView(this, null)
        terminalView.setBackgroundColor(Color.BLACK)
        setContentView(terminalView)

        val nativeDir = File(applicationInfo.nativeLibraryDir)
        val payloadDir = getDir("payload", Context.MODE_PRIVATE)
        val busybox = File(nativeDir, "libbusybox.so")

        // Verificar que el archivo existe y es ejecutable
        Log.d("TerminalTest", "BusyBox exists: ${busybox.exists()}")
        Log.d("TerminalTest", "BusyBox canExecute: ${busybox.canExecute()}")
        Log.d("TerminalTest", "BusyBox size: ${busybox.length()} bytes")

        val env = arrayOf(
            "HOME=${payloadDir.absolutePath}",
            "TERM=xterm-256color",
            "PATH=${payloadDir.absolutePath}/bin:${nativeDir.absolutePath}:/system/bin",
            "LD_LIBRARY_PATH=${nativeDir.absolutePath}:${payloadDir.absolutePath}/glibc/lib",
            "TMPDIR=${cacheDir.absolutePath}",
            "OPENCLAW_HOME=${filesDir.absolutePath}/.openclaw",
            "NODE_PATH=${payloadDir.absolutePath}/lib/node_modules"
            // SIN LD_PRELOAD
        )

        session = TerminalSession(
            busybox.absolutePath,
            payloadDir.absolutePath,
            arrayOf("sh"),
            env,
            4000,
            this
        )

        terminalView.attachSession(session)
        
        // Suite de comandos de verificación
        val tests = listOf(
            "echo '=== TEST ENTORNO ==='",
            "echo HOME=\$HOME",
            "echo PATH=\$PATH",
            "echo TMPDIR=\$TMPDIR",
            "echo '=== TEST FILESYSTEM ==='",
            "ls \$HOME",
            "ls \$HOME/bin 2>/dev/null || echo 'bin/ vacio - normal si payload no extraido'",
            "ls \$HOME/glibc/lib 2>/dev/null || echo 'glibc/ vacio - normal si payload no extraido'",
            "echo '=== TEST BINARIOS ==='",
            "which sh",
            "ls -la ${nativeDir.absolutePath}/",
            "echo '=== TODOS LOS TESTS COMPLETADOS ==='"
        )

        tests.forEach { cmd -> session.write("$cmd\n") }
    }

    // Implementar TODOS los callbacks de TerminalSessionClient
    override fun onTextChanged(changedSession: TerminalSession) {
        runOnUiThread { terminalView.onScreenUpdated() }
    }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        Log.d("TerminalTest", "Sesión terminó — exitCode: ${finishedSession.exitStatus}")
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun logError(tag: String?, message: String?) { Log.e("PTY", message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w("PTY", message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i("PTY", message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d("PTY", message ?: "") }
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
