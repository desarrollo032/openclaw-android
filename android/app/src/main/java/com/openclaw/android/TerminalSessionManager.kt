package com.openclaw.android

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Gestión de sesiones de terminal múltiples.
 * Usa TerminalView.attachSession() para cambiar sesiones — un TerminalView, muchas sesiones.
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
     * Crea una nueva sesión de terminal. Devuelve el handle de la sesión.
     * Detecta automáticamente si usar rutas de Termux o locales de la app.
     */
    fun createSession(): TerminalSession {
        // Construir entorno con rutas reales disponibles (Termux o local de la app)
        val env = EnvironmentBuilder.buildEnvironment(activity.filesDir).toMutableMap()

        // Asegurar que HOME y TMPDIR existen
        val homeDir = java.io.File(env["HOME"] ?: CommandRunner.TERMUX_HOME).also { it.mkdirs() }
        env["TMPDIR"]?.let { java.io.File(it).mkdirs() }

        // Eliminar LD_PRELOAD si la librería no existe (evita crash "library not found")
        val ldPreload = env["LD_PRELOAD"]
        if (ldPreload != null && !java.io.File(ldPreload).exists()) {
            env.remove("LD_PRELOAD")
            AppLogger.w(TAG, "LD_PRELOAD eliminado: $ldPreload no existe")
        }

        val prefix = env["PREFIX"] ?: CommandRunner.TERMUX_PREFIX

        // Seleccionar el mejor shell disponible
        val shell = listOf(
            "$prefix/bin/bash",
            "$prefix/bin/sh",
            "/system/bin/sh",
        ).firstOrNull { java.io.File(it).exists() } ?: "/system/bin/sh"

        AppLogger.i(TAG, "Iniciando sesión: shell=$shell, home=${homeDir.absolutePath}, prefix=$prefix")

        val session = TerminalSession(
            shell,
            homeDir.absolutePath,
            arrayOf<String>(),
            env.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
            TRANSCRIPT_ROWS,
            sessionClient,
        )

        sessions.add(session)
        switchSession(sessions.size - 1)

        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "created"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }

        AppLogger.i(TAG, "Sesión creada ${session.mHandle} (total: ${sessions.size})")
        return session
    }

    /**
     * Cambia a la sesión por índice.
     */
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

    /**
     * Cambia a la sesión por handle ID.
     */
    fun switchSession(handleId: String) {
        val index = sessions.indexOfFirst { it.mHandle == handleId }
        if (index >= 0) switchSession(index)
    }

    /**
     * Busca una sesión por handle ID.
     */
    fun getSessionById(handleId: String): TerminalSession? = sessions.find { it.mHandle == handleId }

    /**
     * Cierra una sesión por handle ID.
     */
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
        AppLogger.i(TAG, "Sesión cerrada $handleId (restantes: ${sessions.size})")
    }

    /**
     * Llamado cuando el proceso de una sesión termina.
     */
    fun onSessionFinished(session: TerminalSession) {
        finishedSessionIds.add(session.mHandle)
        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "finished"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }
    }

    /**
     * Devuelve info de todas las sesiones para JsBridge.
     */
    fun getSessionsInfo(): List<Map<String, Any>> =
        sessions.mapIndexed { index, session ->
            mapOf(
                "id" to session.mHandle,
                "name" to (session.title ?: "Sesión ${index + 1}"),
                "active" to (index == activeSessionIndex),
                "finished" to (session.mHandle in finishedSessionIds),
            )
        }

    fun isSessionFinished(handleId: String): Boolean = handleId in finishedSessionIds

    val sessionCount: Int get() = sessions.size
}
