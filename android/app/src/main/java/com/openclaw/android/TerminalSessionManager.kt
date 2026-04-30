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

        // ── SAFE MODE: Determine if we can use Termux environment or must fall back ──
        val isEnvironmentReady = payloadManager.isReady()
        val hasGlibcLinker = java.io.File(prefix, "glibc/lib/ld-linux-aarch64.so.1").exists()
        val hasTermuxExec = java.io.File("$prefix/lib/libtermux-exec.so").exists()

        // Check if bash/sh are real binaries or emergency wrappers
        val bashFile = java.io.File("$prefix/bin/bash")
        val shFile = java.io.File("$prefix/bin/sh")
        
        val isBashWrapper = try {
            bashFile.exists() && 
            bashFile.length() < 200 && 
            bashFile.readText().contains("# Emergency bash wrapper")
        } catch (e: Exception) {
            false
        }
        
        val isShWrapper = try {
            shFile.exists() && 
            shFile.length() < 200 && 
            shFile.readText().contains("# Emergency")
        } catch (e: Exception) {
            false
        }

        // Determine if we MUST use safe mode (system shell only)
        val mustUseSafeMode = !isEnvironmentReady || 
                              (!hasGlibcLinker && !hasTermuxExec) ||
                              (bashFile.exists() && isBashWrapper) ||
                              (shFile.exists() && isShWrapper)

        if (mustUseSafeMode) {
            AppLogger.w(TAG, "Safe Mode required: envReady=$isEnvironmentReady, glibc=$hasGlibcLinker, termuxExec=$hasTermuxExec, bashWrapper=$isBashWrapper, shWrapper=$isShWrapper")
            // Strip ALL Termux/glibc linker variables for system shell
            env.remove("LD_PRELOAD")
            env.remove("LD_LIBRARY_PATH")
        } else {
            // Full Termux environment is available and ready
            AppLogger.i(TAG, "Full Termux environment available")
            // Keep all environment variables as-is
        }

        // Final safety: if LD_PRELOAD file doesn't exist, remove it
        val ldPreload = env["LD_PRELOAD"]
        if (ldPreload != null && !java.io.File(ldPreload).exists()) {
            env.remove("LD_PRELOAD")
        }

        // Resolve shell from OUR prefix only — never from Termux or system paths
        // (except /system/bin/sh as absolute last resort).
        var shellBin = if (mustUseSafeMode) {
            "/system/bin/sh"
        } else {
            listOf(
                "$prefix/bin/bash",
                "$prefix/bin/sh",
                "/system/bin/sh",
            ).firstOrNull { java.io.File(it).exists() } ?: "/system/bin/sh"
        }

        // Double-check: if we somehow selected a wrapper in non-safe mode, correct it
        if (!mustUseSafeMode && shellBin == "$prefix/bin/bash") {
            try {
                val f = java.io.File(shellBin)
                if (f.length() < 200 && f.readText().contains("# Emergency bash wrapper")) {
                    AppLogger.w(TAG, "Emergency bash wrapper detected post-selection. Switching to Safe Mode.")
                    shellBin = "/system/bin/sh"
                    env.remove("LD_PRELOAD")
                    env.remove("LD_LIBRARY_PATH")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error checking bash wrapper post-selection: ${e.message}")
            }
        }

        // Final safeguard: if using /system/bin/sh, ensure no incompatible linker vars
        if (shellBin == "/system/bin/sh") {
            env.remove("LD_PRELOAD")
            env.remove("LD_LIBRARY_PATH")
            AppLogger.w(TAG, "Using /system/bin/sh with clean environment")
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
