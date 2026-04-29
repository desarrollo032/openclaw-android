package com.openclaw.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shell command execution via ProcessBuilder with bash -l (login shell).
 * Always uses bash -l -c to ensure full Termux environment is loaded.
 * Never uses Runtime.exec() or /usr/bin/node directly.
 */
object CommandRunner {
    private const val TAG = "CommandRunner"

    // Termux home and prefix — real paths used by the Termux bootstrap
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val OPENCLAW_DIR = "$TERMUX_HOME/.openclaw-android"
    const val OPENCLAW_BIN = "$OPENCLAW_DIR/bin"
    const val INSTALLED_MARKER = "$OPENCLAW_DIR/installed.json"
    const val WRAPPER_SCRIPT = "$TERMUX_HOME/openclaw-start.sh"

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /**
     * Build the environment map for Termux commands.
     * Detecta automáticamente si usar rutas de Termux o locales.
     */
    fun buildTermuxEnv(): Map<String, String> = EnvironmentBuilder.buildTermuxEnvironment()

    /**
     * Build a safe working directory, falling back to TERMUX_HOME or /data/local/tmp.
     */
    private fun safeWorkDir(workDir: File): File = when {
        workDir.exists() -> workDir
        File(TERMUX_HOME).exists() -> File(TERMUX_HOME)
        else -> File("/data/local/tmp").also { it.mkdirs() }
    }

    /**
     * Strip LD_PRELOAD if the referenced library doesn't exist (prevents crash).
     */
    private fun safeEnv(env: Map<String, String>): Map<String, String> =
        env.toMutableMap().apply {
            val ldPreload = get("LD_PRELOAD")
            if (ldPreload != null && !File(ldPreload).exists()) remove("LD_PRELOAD")
        }

    /**
     * Run a command synchronously using bash -l -c.
     * This ensures the full login environment is loaded.
     */
    fun runSync(
        command: String,
        env: Map<String, String> = buildTermuxEnv(),
        workDir: File = File(TERMUX_HOME),
        timeoutMs: Long = 5_000,
    ): CommandResult =
        try {
            val pb = ProcessBuilder("bash", "-l", "-c", command)
            pb.environment().clear()
            pb.environment().putAll(safeEnv(env))
            pb.directory(safeWorkDir(workDir))
            pb.redirectErrorStream(false)

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!exited) {
                process.destroyForcibly()
                CommandResult(-1, stdout, "Command timed out (${timeoutMs}ms)")
            } else {
                CommandResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "runSync failed: ${e.message}", e)
            CommandResult(-1, "", e.message ?: "Unknown error")
        }

    /**
     * Run a command asynchronously using bash -l -c, streaming output line-by-line.
     */
    suspend fun runStreaming(
        command: String,
        env: Map<String, String> = buildTermuxEnv(),
        workDir: File = File(TERMUX_HOME),
        onOutput: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder("bash", "-l", "-c", command)
            pb.environment().clear()
            pb.environment().putAll(safeEnv(env))
            pb.directory(safeWorkDir(workDir))
            pb.redirectErrorStream(true)

            val process = pb.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                onOutput(line)
            }
            process.waitFor()
        } catch (e: Exception) {
            AppLogger.e(TAG, "runStreaming failed: ${e.message}", e)
            onOutput("Error: ${e.message}")
        }
    }

    /**
     * Check if OpenClaw is installed by verifying the installed.json marker.
     */
    fun isOpenClawInstalled(): Boolean = File(INSTALLED_MARKER).exists()

    /**
     * Check if termux-setup-storage has already been run.
     * Heuristic: ~/storage symlink exists.
     */
    fun isStorageSetupDone(): Boolean = File("$TERMUX_HOME/storage").exists()

    /**
     * Run termux-setup-storage, auto-answering "y" to any prompt.
     * Must be called before any installation that needs external storage.
     */
    fun runTermuxSetupStorage(onOutput: (String) -> Unit = {}) {
        if (isStorageSetupDone()) {
            AppLogger.i(TAG, "termux-setup-storage already done, skipping")
            onOutput("Storage already configured.")
            return
        }
        // If Termux HOME doesn't exist, skip
        if (!File(TERMUX_HOME).exists()) {
            AppLogger.w(TAG, "Termux HOME directory not found, skipping termux-setup-storage")
            onOutput("Termux directory not found, skipping storage setup.")
            return
        }
        AppLogger.i(TAG, "Running termux-setup-storage...")
        try {
            val pb = ProcessBuilder("bash", "-l", "-c", "yes | termux-setup-storage")
            pb.environment().clear()
            pb.environment().putAll(safeEnv(buildTermuxEnv()))
            pb.directory(File(TERMUX_HOME))
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                AppLogger.i(TAG, "setup-storage: $line")
                onOutput(line)
            }
            process.waitFor(30, TimeUnit.SECONDS)
            AppLogger.i(TAG, "termux-setup-storage completed")
        } catch (e: Exception) {
            AppLogger.e(TAG, "termux-setup-storage failed: ${e.message}", e)
            onOutput("Error: ${e.message}")
        }
    }

    /**
     * Create the openclaw-start.sh wrapper script in Termux home.
     * Uses grun (glibc-runner) — never calls node directly.
     */
    fun createWrapperScript(): Boolean {
        val script = File(WRAPPER_SCRIPT)
        val content =
            """
            #!/data/data/com.termux/files/usr/bin/bash

            export HOME=$TERMUX_HOME
            export PATH=$OPENCLAW_BIN:$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:${'$'}PATH

            exec grun openclaw gateway --host 0.0.0.0
            """.trimIndent()
        return try {
            script.parentFile?.mkdirs()
            script.writeText(content)
            script.setExecutable(true)
            AppLogger.i(TAG, "Wrapper script created at $WRAPPER_SCRIPT")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create wrapper script: ${e.message}", e)
            false
        }
    }

    /**
     * Write the installed.json marker file.
     */
    fun writeInstalledMarker(): Boolean {
        val marker = File(INSTALLED_MARKER)
        return try {
            marker.parentFile?.mkdirs()
            marker.writeText(
                """{"installed":true,"path":"$OPENCLAW_DIR"}""",
            )
            AppLogger.i(TAG, "installed.json marker written")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write installed.json: ${e.message}", e)
            false
        }
    }

    /**
     * Launch the OpenClaw gateway using the wrapper script.
     * Returns the running Process so the caller can monitor it.
     */
    fun launchGateway(): Process? {
        createWrapperScript()
        return try {
            val pb = ProcessBuilder("bash", "-l", "-c", WRAPPER_SCRIPT)
            pb.environment().clear()
            pb.environment().putAll(buildTermuxEnv())
            pb.directory(File(TERMUX_HOME))
            pb.redirectErrorStream(true)
            val process = pb.start()
            AppLogger.i(TAG, "OpenClaw gateway launched via $WRAPPER_SCRIPT")
            process
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch gateway: ${e.message}", e)
            null
        }
    }
}
