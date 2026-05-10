package com.openclaw.android

import android.content.Context
import android.util.Log
import android.system.Os
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

private const val TAG = "OpenClawTermMgr"

/**
 * OpenClawTerminalManager
 *
 * Responsabilidades:
 *  - Construir el entorno (environment array) idéntico al del ProcessBuilder del gateway.
 *  - Verificar que libbusybox.so exista en nativeLibraryDir antes de crear sesión.
 *  - Validar que libbusybox.so responde a --list y tiene applet "sh".
 *  - Fallback a /system/bin/sh si BusyBox no es válido.
 *  - Crear la TerminalSession con el shell adecuado.
 *  - Gestionar el ciclo de vida de la sesión (start / finish / recreate).
 */
class OpenClawTerminalManager(private val context: Context) {

    private val nativeDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val payloadDir: File
        get() = OpenClawInstaller.getPayloadDir(context)

    private val configDir: File
        get() = OpenClawInstaller.getConfigDir(context)

    val busyboxFile: File
        get() = File(nativeDir, "libbusybox.so")

    fun isBusyboxValid(): Boolean {
        val busybox = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        if (!busybox.exists() || !busybox.canExecute()) return false
        return try {
            val result = ProcessBuilder(busybox.absolutePath, "--list")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText()
            result.contains("sh")
        } catch (e: Exception) { false }
    }

    fun createBusyboxSymlinks() {
        val busybox = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        val binDir = File(context.getDir("payload", Context.MODE_PRIVATE), "bin")
        binDir.mkdirs()
        listOf("sh","ls","cat","grep","find","tar","chmod",
               "mkdir","cp","mv","rm","echo","env","which").forEach { cmd ->
            val link = File(binDir, cmd)
            if (!link.exists()) {
                try { Os.symlink(busybox.absolutePath, link.absolutePath) }
                catch (e: Exception) {
                    Log.w("TerminalManager", "Symlink $cmd failed: ${e.message}")
                }
            }
        }
    }

    fun getShellPath(): String {
        return if (isBusyboxValid()) {
            busyboxFile.absolutePath
        } else {
            Log.w(TAG, "BusyBox invalid or unavailable → falling back to /system/bin/sh")
            "/system/bin/sh"
        }
    }

    fun buildEnvironment(): Array<String> {
        val native    = nativeDir.absolutePath
        val payload   = payloadDir.absolutePath
        val glibcLib  = "$payload/glibc/lib"
        val tmpDir    = context.cacheDir.absolutePath
        val ocHome    = File(context.filesDir, ".openclaw").absolutePath
        val shellRc   = ensureShellRc()

        val path = "$payload/bin:$native:/system/bin"
        val ldLibPath = "$native:$glibcLib"

        return arrayOf(
            "TERM=xterm-256color",
            "HOME=$payload",
            "PATH=$path",
            "LD_LIBRARY_PATH=$ldLibPath",
            "TMPDIR=$tmpDir",
            "OPENCLAW_HOME=$ocHome",
            "ENV=${shellRc.absolutePath}",
            "PS1=$ ",
            "NODE_PATH=$payload/lib/node_modules",
            "SSL_CERT_FILE=$payload/etc/tls/cert.pem",
            "LANG=en_US.UTF-8",
            "COLORTERM=truecolor",
            "OA_GLIBC=1",
            "CONTAINER=1"
        )
    }

    private fun ensureShellRc(): File {
        val payload = payloadDir.absolutePath
        val rc = File(context.filesDir, "openclaw-terminal.rc")
        rc.writeText("""
            PS1='$ '
            openclaw() { sh "$payload/bin/openclaw" "$@"; }
            node() { sh "$payload/bin/node" "$@"; }
            npm() { sh "$payload/bin/npm" "$@"; }
            export PS1
        """.trimIndent())
        return rc
    }

    fun createSession(client: TerminalSessionClient): TerminalSession? {
        val shell = getShellPath()
        val workingDir = if (payloadDir.exists()) payloadDir.absolutePath
                         else context.cacheDir.absolutePath
        val env = buildEnvironment()
        val args = arrayOf("sh", "-i")

        return try {
            TerminalSession(
                shell,
                workingDir,
                args,
                env,
                4000,
                client
            )
        } catch (e: Exception) {
            Log.e(TAG, "TerminalSession creation failed", e)
            null
        }
    }

    fun logDiagnostics() {
        Log.d(TAG, "=== OpenClawTerminalManager Diagnostics ===")
        Log.d(TAG, "nativeLibraryDir : ${nativeDir.absolutePath}")
        Log.d(TAG, "payloadDir       : ${payloadDir.absolutePath} (exists=${payloadDir.exists()})")
        Log.d(TAG, "libbusybox.so    : ${busyboxFile.absolutePath} (exists=${busyboxFile.exists()})")
        Log.d(TAG, "busybox valid    : ${isBusyboxValid()}")
        Log.d(TAG, "shell path       : ${getShellPath()}")
        Log.d(TAG, "==========================================")
    }
}
