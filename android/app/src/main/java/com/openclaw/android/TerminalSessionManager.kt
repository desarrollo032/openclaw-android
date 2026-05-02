package com.openclaw.android

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

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
     */
    fun createSession(): TerminalSession {
        val env = EnvironmentBuilder.buildEnvironment(
            activity.filesDir,
            activity.packageName,
        ).toMutableMap()

        // Ensure directories exist with unified /data/user/0/... base
        val base = activity.filesDir.absolutePath
        val homeDir = File(base, "home").also { it.mkdirs() }
        val prefix = File(base, "usr").also { it.mkdirs() }
        val tmpDir = File(base, "tmp").also { it.mkdirs() }

        env["HOME"] = homeDir.absolutePath
        env["PREFIX"] = prefix.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath

        val payloadManager = PayloadManager(activity)

        // ── SAFE MODE: Determine if we can use Termux environment or must fall back ──
        val isEnvironmentReady = payloadManager.isReady()
        val hasGlibcLinker = File(prefix, "glibc/lib/ld-linux-aarch64.so.1").exists()
        val hasTermuxExec = File(prefix, "lib/libtermux-exec.so").exists()

        // Check if bash/sh are real binaries or emergency wrappers
        val bashFile = File(prefix, "bin/bash")
        val shFile = File(prefix, "bin/sh")

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
            AppLogger.w(TAG, "Safe Mode required: envReady=$isEnvironmentReady, glibc=$hasGlibcLinker, termuxExec=$hasTermuxExec")
            env.remove("LD_PRELOAD")
        }

        // Final safety: if LD_PRELOAD file doesn't exist, remove it
        val ldPreload = env["LD_PRELOAD"]
        if (ldPreload != null && !File(ldPreload).exists()) {
            env.remove("LD_PRELOAD")
        }

        var shellBin = if (mustUseSafeMode) {
            "/system/bin/sh"
        } else {
            listOf(
                File(prefix, "bin/bash").absolutePath,
                File(prefix, "bin/sh").absolutePath,
                "/system/bin/sh",
            ).firstOrNull { File(it).exists() } ?: "/system/bin/sh"
        }

        if (shellBin == "/system/bin/sh") {
            env.remove("LD_PRELOAD")
        }

        val shellArgs: Array<String> = if (shellBin.endsWith("/bash")) {
            arrayOf("bash", "--norc", "--noprofile")
        } else {
            arrayOf("sh")
        }

        AppLogger.i(TAG, "Creating session: shell=$shellBin home=${homeDir.absolutePath}")

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
