package com.openclaw.android

import android.content.Context
import android.util.Log
import com.openclaw.android.proot.OpenClawProot
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

private const val TAG = "OpenClawTermMgr"

/**
 * OpenClawTerminalManager — versión proot + Alpine.
 *
 * El terminal PTY ahora arranca `proot --rootfs=alpine-rootfs ... /bin/sh -i`,
 * es decir, da al usuario una shell Alpine real (busybox/ash) con `node`, `npm`,
 * `openclaw` ya disponibles en `/usr/bin` (instalados via `apk add`).
 *
 * La API pública conservada por compatibilidad con OpenClawTerminalActivity:
 *   - createSession(client) → TerminalSession?
 *   - getShellPath() → String   (ahora siempre = libproot.so)
 *   - buildEnvironment() → Array<String>
 *   - createBusyboxSymlinks() → Unit   (no-op; busybox vive en /bin del rootfs)
 *   - isBusyboxAvailable() / isBusyboxValid() → ahora reflejan Alpine
 *   - logDiagnostics() → log de estado proot/Alpine
 */
class OpenClawTerminalManager(private val context: Context) {

    private val proot: OpenClawProot by lazy { OpenClawProot(context) }

    // Compatibilidad con código viejo (legibilidad/diagnóstico)
    val busyboxFile: File
        get() = File(proot.rootfs, "bin/busybox")

    // ── Detección de shell disponible ────────────────────────────────────────

    /**
     * Ahora el "shell" del PTY es **siempre proot**. El sh real es
     * /bin/sh del rootfs Alpine, ejecutado vía proot.
     */
    fun getShellPath(): String {
        val prootBin = File(proot.proot)
        if (!prootBin.exists() || !prootBin.canExecute()) {
            throw IllegalStateException(
                "libproot.so no encontrado en ${prootBin.absolutePath}. " +
                "Reconstruye la APK con :app:fetchProot."
            )
        }
        return prootBin.absolutePath
    }

    /** PATH dentro del proot — devuelto sólo para diagnósticos. */
    fun buildPath(): String =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * Environment para la TerminalSession. El array que retorna
     * `OpenClawProot.buildShellEnv()` ya tiene todo lo necesario.
     */
    fun buildEnvironment(): Array<String> = proot.buildShellEnv()

    // ── Creación de sesión PTY ───────────────────────────────────────────────

    /**
     * Crea la sesión terminal usando proot como ejecutable y /bin/sh del
     * rootfs Alpine como shell final.
     *
     * TerminalSession signature (Termux):
     *   TerminalSession(shellPath, cwd, args, env, scrollbackLines, client)
     *
     * Los `args` aquí son TODOS los argumentos de proot (rootfs, binds, sh -i).
     */
    fun createSession(client: TerminalSessionClient): TerminalSession? {
        return try {
            if (!proot.isProotPresent()) {
                Log.e(TAG, "libproot.so no presente")
                return null
            }
            if (!proot.isAlpineInstalled()) {
                Log.e(TAG, "Alpine rootfs no instalado")
                return null
            }

            val shellExec = getShellPath()
            // CWD del lado HOST: el filesDir. Proot internamente cambiará a /data/home/.openclaw
            val cwdHost   = context.filesDir.absolutePath
            val args      = proot.buildShellCommand()
            val env       = proot.buildShellEnv()

            Log.d(TAG, "Iniciando sesión PTY proot")
            Log.d(TAG, "Exec: $shellExec")
            Log.d(TAG, "CWD host: $cwdHost")
            Log.d(TAG, "args[0..3]: ${args.take(4)}")

            TerminalSession(
                shellExec,
                cwdHost,
                args,
                env,
                4000,
                client
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creando sesión PTY: ${e.message}", e)
            null
        }
    }

    // ── Diagnósticos ─────────────────────────────────────────────────────────

    fun logDiagnostics() {
        Log.d(TAG, "=== DIAGNÓSTICO TERMINAL (proot+Alpine) ===")
        Log.d(TAG, "libproot.so present:   ${proot.isProotPresent()}")
        Log.d(TAG, "Alpine rootfs present: ${proot.isAlpineInstalled()}")
        Log.d(TAG, "openclaw installed:    ${proot.isOpenClawInstalled()}")
        Log.d(TAG, "rootfs path:           ${proot.rootfs.absolutePath}")
        Log.d(TAG, "PROOT_TMP_DIR:         ${proot.prootTmpDir.absolutePath}")
        Log.d(TAG, "OPENCLAW_HOME:         ${proot.openclawHome.absolutePath}")
        Log.d(TAG, "=== FIN DIAGNÓSTICO ===")
    }

    // ── No-ops / compatibilidad ──────────────────────────────────────────────

    /**
     * Con Alpine el shell viene en `/bin/busybox` dentro del rootfs y todos
     * los applets ya tienen symlinks instalados. No hace falta crearlos.
     */
    fun createBusyboxSymlinks() { /* no-op */ }

    /**
     * Disponibilidad de busybox = Alpine rootfs presente (busybox es el
     * proveedor de `/bin/sh` en Alpine minirootfs).
     */
    fun isBusyboxAvailable(): Boolean = proot.isAlpineInstalled()

    fun isBusyboxValid(): Boolean = isBusyboxAvailable()
}
