package com.openclaw.android

import android.content.Context
import android.system.Os
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

private const val TAG = "OpenClawTermMgr"

/**
 * OpenClawTerminalManager
 *
 * Responsabilidades:
 * - Construir el entorno (environment array) idéntico al del ProcessBuilder del gateway.
 * - Priorizar shell nativo /system/bin/sh sobre BusyBox.
 * - Detectar y usar Toybox nativo del sistema Android 12+.
 * - Mantener BusyBox como fallback último recurso.
 * - Crear la TerminalSession con el shell adecuado.
 * - Gestionar el ciclo de vida de la sesión (start / finish / recreate).
 */
class OpenClawTerminalManager(private val context: Context) {

    private val nativeDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val payloadDir: File
        get() = context.getDir("payload", Context.MODE_PRIVATE)

    val busyboxFile: File
        get() = File(nativeDir, "libbusybox.so")

    // ── Detección de shell disponible ────────────────────────

    /**
     * Detecta el mejor shell disponible en este orden:
     * 1. /system/bin/sh (siempre disponible, compatible seccomp)
     * 2. libbusybox.so como último recurso
     */
    fun getShellPath(): String {
        // Opción 1 — sh nativo (SIEMPRE disponible en Android)
        val systemSh = File("/system/bin/sh")
        if (systemSh.exists() && systemSh.canExecute()) {
            Log.d(TAG, "Shell: /system/bin/sh (nativo)")
            return systemSh.absolutePath
        }

        // Opción 2 — busybox como último recurso
        val busybox = File(nativeDir, "libbusybox.so")
        if (busybox.exists() && busybox.canExecute()) {
            Log.w(TAG, "Shell: libbusybox.so (fallback — puede tener seccomp issues)")
            return busybox.absolutePath
        }

        // Nunca debería llegar aquí en Android
        throw IllegalStateException("No hay shell disponible en el dispositivo")
    }

    /**
     * Construye el PATH completo para la sesión terminal.
     * Prioriza binarios del payload, luego sistema Android.
     *
     * Comandos disponibles via /system/bin/ con toybox:
     * ls, cat, grep, find, cp, mv, rm, chmod, tar, echo,
     * env, which, mkdir, pwd, ps, kill, curl, wget, base64,
     * diff, du, df, head, tail, sort, uniq, wc, sed, awk,
     * date, hostname, id, whoami, uname, +200 más
     */
    private fun buildPath(): String {
        return listOf(
            "${payloadDir.absolutePath}/bin",  // openclaw, node symlinks
            nativeDir.absolutePath,            // libnode.so, libldlinux.so
            "/system/bin",                     // sh, toybox, ls, grep...
            "/system/xbin",                    // herramientas extra
            "/data/local/tmp"                  // herramientas temporales
        ).joinToString(":")
    }

    /**
     * Environment completo para la sesión terminal.
     * Idéntico al ProcessBuilder del gateway excepto
     * que NO incluye flags de Node.js.
     * NUNCA incluye LD_PRELOAD.
     */
    fun buildEnvironment(): Array<String> {
        val glibcLibs = File(payloadDir, "glibc/lib").absolutePath
        val libs = "${nativeDir.absolutePath}:${glibcLibs}"
        val rc = ensureShellRc()

        return arrayOf(
            "HOME=${payloadDir.absolutePath}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "PATH=${buildPath()}",
            "LD_LIBRARY_PATH=$libs",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "OPENCLAW_HOME=${context.filesDir.absolutePath}/.openclaw",
            "NODE_PATH=${payloadDir.absolutePath}/lib/node_modules",
            "SSL_CERT_FILE=${payloadDir.absolutePath}/etc/tls/cert.pem",
            "LANG=en_US.UTF-8",
            "LC_ALL=en_US.UTF-8",
            "ENV=${rc.absolutePath}",
            "OPENCLAW_TERMINAL_RC=${rc.absolutePath}",
            // Prompt corto — no ocupar toda la línea
            "PS1=$ ",
            // NO incluir LD_PRELOAD — crítico
        )
    }

    private fun getNodeCompileCacheDir(): File {
        val cacheDir = File(context.cacheDir, "openclaw-compile-cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }

    private fun ensureShellRc(): File {
        val payload = payloadDir.absolutePath
        val native = nativeDir.absolutePath
        val binDir = "$payload/bin"
        val libs = "$native:$payload/glibc/lib"
        val loader = "$native/libldlinux.so"
        val node = "$native/libnode.so"
        val openclawScript = "$payload/lib/node_modules/openclaw/openclaw.mjs"
        val npmScript = "$payload/lib/node_modules/npm/bin/npm-cli.js"
        val rc = File(context.filesDir, "openclaw-terminal.rc")
        rc.writeText(
                """
            PS1='${'$'} '

            # Priorizar ABSOLUTAMENTE los wrappers oficiales en $binDir
            export PATH="$binDir:${'$'}{PATH}"

            # Evitar ejecutar archivos .js/.mjs directamente por error
            # Funciones como fallback (se usan solo si los wrappers no existen)
            node() {
              unset LD_PRELOAD
              exec "$loader" --library-path "$libs" "$node" "${'$'}@"
            }
            openclaw() {
              unset LD_PRELOAD
              exec "$loader" --library-path "$libs" "$node" "$openclawScript" "${'$'}@"
            }
            npm() {
              if [ -f "$npmScript" ]; then
                unset LD_PRELOAD
                exec "$loader" --library-path "$libs" "$node" "$npmScript" "${'$'}@"
              else
                echo "npm: no incluido"
                return 127
              fi
            }
            export PS1
        """.trimIndent()
        )
        return rc
    }

    // ── Creación de sesión PTY ────────────────────────────────

    /**
     * Crea la sesión terminal con Toybox/sh nativo.
     * Retorna null si no hay shell disponible.
     */
    fun createSession(client: TerminalSessionClient): TerminalSession? {
        return try {
            val shell = getShellPath()
            val env = buildEnvironment()
            val cwd = payloadDir.absolutePath

            Log.d(TAG, "Iniciando sesión PTY")
            Log.d(TAG, "Shell: $shell")
            Log.d(TAG, "CWD: $cwd")
            Log.d(TAG, "PATH: ${buildPath()}")

            TerminalSession(
                shell,
                cwd,
                arrayOf("sh", "-i"),  // modo interactivo
                env,
                4000,                 // scrollback lines
                client
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creando sesión: ${e.message}")
            null
        }
    }

    // ── Diagnósticos ─────────────────────────────────────────

    /**
     * Log completo del entorno disponible.
     * Llamar en onCreate() de OpenClawTerminalActivity para debug.
     */
    fun logDiagnostics() {
        Log.d(TAG, "=== DIAGNÓSTICO TERMINAL ===")

        // Verificar shell
        listOf("/system/bin/sh", "/system/bin/bash").forEach { path ->
            val f = File(path)
            Log.d(TAG,
                "$path — exists:${f.exists()} canExec:${f.canExecute()}")
        }

        // Verificar toybox
        val toybox = File("/system/bin/toybox")
        Log.d(TAG,
            "toybox — exists:${toybox.exists()} canExec:${toybox.canExecute()}")

        // Listar applets de toybox disponibles
        if (toybox.exists()) {
            try {
                val applets = ProcessBuilder("/system/bin/toybox")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader().readText()
                Log.d(TAG, "Toybox applets: $applets")
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo listar toybox: ${e.message}")
            }
        }

        // Verificar busybox (fallback)
        val busybox = File(nativeDir, "libbusybox.so")
        Log.d(TAG,
            "libbusybox.so — exists:${busybox.exists()} " +
            "canExec:${busybox.canExecute()} size:${busybox.length()}B")

        // Verificar payload
        Log.d(TAG,
            "payloadDir — exists:${payloadDir.exists()} " +
            "path:${payloadDir.absolutePath}")

        Log.d(TAG, "=== FIN DIAGNÓSTICO ===")
    }

    // ── Verificación de busybox (para info solamente) ─────────

    /**
     * Verifica si busybox está disponible Y compatible con seccomp.
     * Solo para diagnóstico — el shell principal es /system/bin/sh.
     */
    fun isBusyboxValid(): Boolean {
        val busybox = File(nativeDir, "libbusybox.so")
        if (!busybox.exists() || !busybox.canExecute()) return false
        return try {
            val process = ProcessBuilder(
                busybox.absolutePath, "echo", "test"
            ).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            // "Bad system call" indica seccomp bloqueando
            !out.contains("Bad system call") && code == 0
        } catch (e: Exception) { false }
    }

    // ── Symlinks de comandos (opcional con toybox) ────────────

    /**
     * Con toybox nativo NO se necesitan symlinks.
     * /system/bin/ ya tiene todos los comandos necesarios.
     * Este método se mantiene por compatibilidad pero no hace nada
     * si el sistema ya tiene los comandos disponibles.
     */
    fun createBusyboxSymlinks() {
        val toybox = File("/system/bin/toybox")
        if (toybox.exists()) {
            Log.d(TAG,
                "Toybox disponible — symlinks no necesarios")
            return
        }
        // Solo crear symlinks si NO hay toybox (caso muy raro)
        val busybox = File(nativeDir, "libbusybox.so")
        if (!busybox.exists()) return
        val binDir = File(payloadDir, "bin")
        binDir.mkdirs()
        listOf("sh","ls","cat","grep","find","tar",
               "chmod","mkdir","cp","mv","rm","echo",
               "env","which","ps","kill").forEach { cmd ->
            val link = File(binDir, cmd)
            if (!link.exists()) {
                try {
                    Os.symlink(busybox.absolutePath, link.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG,
                        "Symlink $cmd failed: ${e.message}")
                }
            }
        }
    }

    // ── Propiedades públicas ──────────────────────────────────

    fun isBusyboxAvailable(): Boolean =
        busyboxFile.exists() && busyboxFile.canExecute()
}
