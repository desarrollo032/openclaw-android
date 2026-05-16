package com.openclaw.android

import android.content.Context
import android.util.Log
import com.openclaw.android.proot.OpenClawProot
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

private const val TAG = "OpenClawTermMgr"

/**
 * OpenClawTerminalManager — Terminal PTY basado en proot + Alpine Linux.
 *
 * Usa proot → Alpine rootfs → /bin/sh interactivo.
 *
 * La sesión terminal corre dentro del entorno Alpine completo, con
 * Node.js, npm y openclaw disponibles directamente.
 */
class OpenClawTerminalManager(private val context: Context) {

    private val prootHelper: OpenClawProot by lazy { OpenClawProot(context) }

    // ── Creación de sesión PTY ────────────────────────────────────────────────

    /**
     * Crea una sesión terminal dentro del entorno proot + Alpine.
     *
     * El TerminalSession recibe:
     *   - executable    = libproot.so (desde nativeLibraryDir)
     *   - initialDir    = /data/home/.openclaw (dentro del proot)
     *   - arguments     = --rootfs=... --bind=... --change-id=0:0 --cwd=... /bin/sh
     *   - environment   = KEY=VALUE array (PROOT_TMP_DIR, PATH, etc.)
     *
     * Retorna null si proot no está presente o Alpine no está instalado.
     */
    fun createSession(client: TerminalSessionClient): TerminalSession? {
        return try {
            val proot = prootHelper

            if (!proot.isProotPresent()) {
                Log.e(TAG, "libproot.so no encontrado en ${proot.nativeDir}")
                return null
            }

            val shellCmd = proot.buildShellCommand()
            val shellEnv = proot.buildShellEnv()
            val cwd = "/data/home/.openclaw"

            Log.d(TAG, "Iniciando sesión PTY con proot + Alpine")
            Log.d(TAG, "Ejecutable: ${proot.proot}")
            Log.d(TAG, "CWD: $cwd")

            TerminalSession(
                proot.proot,              // ejecutable = libproot.so
                cwd,                       // directorio inicial dentro del proot
                shellCmd,                  // argumentos completos (rootfs, binds, sh)
                shellEnv,                  // environment KEY=VALUE
                4000,                      // scrollback lines
                client
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creando sesión terminal: ${e.message}")
            null
        }
    }

    // ── Diagnósticos ─────────────────────────────────────────────────────────

    /**
     * Log completo del entorno disponible.
     * Llamar en onCreate() de OpenClawTerminalActivity para debug.
     */
    fun logDiagnostics() {
        val proot = prootHelper
        Log.d(TAG, "=== DIAGNÓSTICO TERMINAL (proot + Alpine) ===")
        Log.d(TAG, "proot:   ${proot.proot} — exists:${File(proot.proot).exists()}")
        Log.d(TAG, "rootfs:  ${proot.rootfs.absolutePath} — installed:${proot.isAlpineInstalled()}")
        Log.d(TAG, "openclaw installed: ${proot.isOpenClawInstalled()}")
        Log.d(TAG, "prootTmpDir: ${proot.prootTmpDir.absolutePath}")
        Log.d(TAG, "openclawHome: ${proot.openclawHome.absolutePath}")
        Log.d(TAG, "=== FIN DIAGNÓSTICO ===")
    }

    // ── Propiedades de compatibilidad ─────────────────────────────────────────

    /** ¿Está instalado Alpine? */
    fun isAlpineReady(): Boolean = prootHelper.isAlpineInstalled()

    /** ¿Está instalado openclaw? */
    fun isOpenClawReady(): Boolean = prootHelper.isOpenClawInstalled()
}
