package com.openclaw.android

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Manages multiple terminal sessions.
 * One TerminalView, many sessions — switch via attachSession().
 */
class TerminalSessionManager(
    private val activity: MainActivity,
    private val sessionClient: TerminalSessionClient,
    private val eventBridge: EventBridge,
) {
    companion object {
        private const val TAG = "SessionManager"
        private const val TRANSCRIPT_ROWS = 2000
    }

    private val sessions = mutableListOf<TerminalSession>()
    private var activeSessionIndex = -1
    private val finishedSessionIds = mutableSetOf<String>()
    var onSessionsChanged: (() -> Unit)? = null

    val activeSession: TerminalSession?
        get() = sessions.getOrNull(activeSessionIndex)

    /**
     * Creates a new terminal session using the app-local sandbox exclusively.
     *
     * Shell selection:
     *   1. filesDir/usr/bin/bash  — preferred (full-featured)
     *   2. filesDir/usr/bin/sh    — fallback
     *   3. /system/bin/sh         — last resort (always available on Android)
     *
     * bash is always launched with --norc --noprofile to prevent it from
     * sourcing /data/data/com.termux/files/usr/etc/bash.bashrc (the path
     * compiled into the Termux bootstrap bash binary). The full environment
     * is built explicitly by EnvironmentBuilder — no rc files are needed.
     */
    fun createSession(): TerminalSession {
        val env = EnvironmentBuilder.buildEnvironment(
            activity.filesDir,
            activity.packageName,
        ).toMutableMap()

        // Ensure HOME and TMPDIR directories exist
        val homeDir = java.io.File(env["HOME"] ?: activity.filesDir.resolve("home").absolutePath)
            .also { it.mkdirs() }
        env["TMPDIR"]?.let { java.io.File(it).mkdirs() }

        val prefix = env["PREFIX"] ?: activity.filesDir.resolve("usr").absolutePath
        val payloadManager = PayloadManager(activity)

        // Safety check: if the environment is not "ready", strip aggressive linker vars
        // to prevent native crashes when falling back to /system/bin/sh
        if (!payloadManager.isReady()) {
            AppLogger.w(TAG, "Incomplete environment detected. Entering Safe Mode (stripping linker vars).")
            env.remove("LD_PRELOAD")
            // We must keep $prefix/lib for Termux binaries (like bash) to work.
            // Only remove the glibc part.
            val glibcLib = java.io.File(prefix, "glibc/lib")
            if (!glibcLib.exists()) {
                env["LD_LIBRARY_PATH"] = "$prefix/lib"
            }
        }

        // Final check for LD_PRELOAD existence
        val ldPreload = env["LD_PRELOAD"]
        if (ldPreload != null && !java.io.File(ldPreload).exists()) {
            env.remove("LD_PRELOAD")
        }

        // Resolve shell from OUR prefix only — never from Termux or system paths
        // (except /system/bin/sh as absolute last resort).
        val shellBin = listOf(
            "$prefix/bin/bash",
            "$prefix/bin/sh",
            "/system/bin/sh",
        ).firstOrNull { java.io.File(it).exists() } ?: "/system/bin/sh"

        // If we must fall back to Android's system shell, we MUST strip Termux/glibc
        // linker variables. Android's /system/bin/sh is dynamically linked against Bionic.
        // If LD_LIBRARY_PATH points to $prefix/lib or LD_PRELOAD is set, the system linker
        // will crash immediately (segmentation fault) before the shell even starts.
        if (shellBin == "/system/bin/sh") {
            AppLogger.w(TAG, "Falling back to /system/bin/sh. Stripping incompatible linker vars.")
            env.remove("LD_PRELOAD")
            env.remove("LD_LIBRARY_PATH")
        }

        // Pass --norc --noprofile to bash so it does NOT source:
        //   - /etc/profile (--noprofile)
        //   - ~/.bashrc or the compiled-in TERMUX__PREFIX/etc/bash.bashrc (--norc)
        // This is the definitive fix for "bash.bashrc: Permission denied".
        //
        // IMPORTANT: argv[0] must be the process name (basename of the shell binary).
        // The JNI layer passes this array directly as execvp(cmd, argv), so argv[0]
        // is what the process sees as $0 / its own name (shown in the prompt).
        // Flags go in argv[1], argv[2], etc.
        val shellArgs: Array<String> = if (shellBin.endsWith("/bash")) {
            arrayOf("bash", "--norc", "--noprofile")
        } else {
            arrayOf("sh")
        }

        AppLogger.i(TAG, "Creating session: shell=$shellBin home=${homeDir.absolutePath} prefix=$prefix")

        val session = TerminalSession(
            shellBin,
            homeDir.absolutePath,
            shellArgs,
            env.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
            TRANSCRIPT_ROWS,
            sessionClient,
        )

        sessions.add(session)
        switchSession(sessions.size - 1)

        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "created"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }

        AppLogger.i(TAG, "Session created ${session.mHandle} (total: ${sessions.size})")
        return session
    }

    fun switchSession(index: Int) {
        if (index < 0 || index >= sessions.size) return
        activeSessionIndex = index
        val session = sessions[index]
        activity.runOnUiThread {
            val terminalView = activity.findViewById<com.termux.view.TerminalView>(R.id.terminalView)
            terminalView.attachSession(session)
            terminalView.invalidate()
        }
        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "switched"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }
    }

    fun switchSession(handleId: String) {
        val index = sessions.indexOfFirst { it.mHandle == handleId }
        if (index >= 0) switchSession(index)
    }

    fun getSessionById(handleId: String): TerminalSession? =
        sessions.find { it.mHandle == handleId }

    fun closeSession(handleId: String) {
        val index = sessions.indexOfFirst { it.mHandle == handleId }
        if (index < 0) return

        finishedSessionIds.remove(handleId)
        val session = sessions.removeAt(index)
        session.finishIfRunning()

        eventBridge.emit("session_changed", mapOf("id" to handleId, "action" to "closed"))

        if (sessions.isNotEmpty()) {
            switchSession(index.coerceAtMost(sessions.size - 1))
        } else {
            activeSessionIndex = -1
        }

        activity.runOnUiThread { onSessionsChanged?.invoke() }
        AppLogger.i(TAG, "Session closed $handleId (remaining: ${sessions.size})")
    }

    fun onSessionFinished(session: TerminalSession) {
        finishedSessionIds.add(session.mHandle)
        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "finished"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }
    }

    fun getSessionsInfo(): List<Map<String, Any>> =
        sessions.mapIndexed { index, session ->
            mapOf(
                "id" to session.mHandle,
                "name" to (session.title ?: "Session ${index + 1}"),
                "active" to (index == activeSessionIndex),
                "finished" to (session.mHandle in finishedSessionIds),
            )
        }

    fun isSessionFinished(handleId: String): Boolean = handleId in finishedSessionIds

    val sessionCount: Int get() = sessions.size
}
