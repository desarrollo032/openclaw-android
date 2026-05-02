package com.openclaw.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession
import java.io.File
import java.util.regex.Regex

/**
 * TerminalManager — orchestrates script execution inside an existing terminal
 * session with the correct environment for install.sh to work without lock-file
 * errors.
 *
 * Root cause of the lock-file error:
 *   "E: Could not open lock file /var/lib/dpkg/lock-frontend" happens because:
 *   1. apt/dpkg look for lock files at ABSOLUTE paths hardcoded for Termux:
 *      /data/data/com.termux/files/usr/var/lib/dpkg/lock-frontend
 *   2. When the app runs as com.openclaw.android.debug, the real PREFIX is:
 *      /data/data/com.openclaw.android.debug/files/usr
 *   3. Without the correct env vars (HOME, PREFIX, PATH), bash cannot find
 *      binaries or working directories, and apt fails trying to open lock files
 *      at paths that don't exist or lack permissions.
 *
 * Solution:
 *   This manager writes a sourced env file to TMPDIR and sources it from the
 *   shell session. This is more reliable than injecting raw export lines because
 *   it avoids the race condition where the shell hasn't finished initializing
 *   when the first bytes arrive.
 *
 * Usage:
 *   val manager = TerminalManager(context, filesDir)
 *   manager.runInstallScript(session)
 *   manager.runCommandInSession(session, "pkg update && pkg install git")
 */
class TerminalManager(
    private val context: Context,
    private val filesDir: File,
) {
    companion object {
        private const val TAG = "TerminalManager"

        // Time to wait for the shell process to initialize before writing.
        // 600ms covers slow devices; the env file approach makes this less critical.
        private const val SHELL_INIT_DELAY_MS = 600L

        // Extra delay between sourcing the env file and running the main command.
        private const val ENV_APPLY_DELAY_MS = 250L

        // Name of the env file written to TMPDIR
        private const val ENV_FILE_NAME = "oca-env.sh"
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Builds the environment variable block as a shell-sourceable string.
     *
     * Critical variables:
     * - PREFIX: apt/dpkg derive ALL their paths from this. With the correct
     *   PREFIX, lock files are created at $PREFIX/var/lib/dpkg/lock-frontend
     *   (accessible) instead of /data/data/com.termux/... (inaccessible).
     * - DPKG_ADMINDIR / DPKG_ROOT: explicit overrides so dpkg never falls back
     *   to its compiled-in Termux paths.
     * - APT_CONFIG: points apt to our apt.conf with absolute Dir::State::status,
     *   which prevents the duplicated-path bug.
     */
    fun buildEnvBlock(): String {
        // Normalize /data/user/0/<pkg>/files -> /data/data/<pkg>/files
        // Bootstrap binaries have /data/data/... hardcoded in their ELF strings.
        val normalizedFilesDir = normalizeFilesDir(filesDir)

        val prefix = normalizedFilesDir.resolve("usr").absolutePath
        val home = normalizedFilesDir.resolve("home").absolutePath
        val tmpDir = normalizedFilesDir.resolve("tmp").absolutePath
        val ocaBin = "$home/.openclaw-android/bin"
        val nodeDir = "$home/.openclaw-android/node"
        val glibcLib = "$prefix/glibc/lib"
        val certBundle = "$prefix/etc/tls/cert.pem"

        File(home).mkdirs()
        File(tmpDir).mkdirs()

        return buildString {
            appendLine("# --- OpenClaw Environment Setup ---")
            appendLine("export HOME=\"$home\"")
            appendLine("export PREFIX=\"$prefix\"")
            appendLine("export TMPDIR=\"$tmpDir\"")
            appendLine("export TERMUX__PREFIX=\"$prefix\"")
            appendLine("export TERMUX_PREFIX=\"$prefix\"")
            appendLine("export TERMUX__ROOTFS=\"${File(prefix).parent}\"")
            appendLine("export PATH=\"$ocaBin:$nodeDir/bin:$prefix/bin:$prefix/bin/applets:/system/bin:/bin\"")
            appendLine("export NPM_CONFIG_PREFIX=\"$prefix\"")
            appendLine("export npm_config_prefix=\"$prefix\"")
            appendLine("export LD_LIBRARY_PATH=\"$prefix/lib:$glibcLib\"")
            // dpkg/apt explicit overrides — prevent fallback to compiled-in Termux paths
            appendLine("export DPKG_ADMINDIR=\"$prefix/var/lib/dpkg\"")
            appendLine("export DPKG_ROOT=\"$prefix\"")
            appendLine("export APT_CONFIG=\"$prefix/etc/apt/apt.conf\"")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("export SSL_CERT_FILE=\"$certBundle\"")
            appendLine("export CURL_CA_BUNDLE=\"$certBundle\"")
            appendLine("export GIT_SSL_CAINFO=\"$certBundle\"")
            appendLine("export RESOLV_CONF=\"$prefix/etc/resolv.conf\"")
            appendLine("export GIT_CONFIG_NOSYSTEM=1")
            appendLine("export LANG=en_US.UTF-8")
            appendLine("export TERM=xterm-256color")
            appendLine("export ANDROID_DATA=/data")
            appendLine("export ANDROID_ROOT=/system")
            appendLine("export OA_GLIBC=1")
            appendLine("export CONTAINER=1")
            appendLine("export BASH_ENV=/dev/null")
            appendLine("export ENV=/dev/null")
            appendLine("export APP_FILES_DIR=\"${normalizedFilesDir.absolutePath}\"")
            appendLine("export APP_PACKAGE=\"${context.packageName}\"")
            appendLine("unset LD_PRELOAD")
            appendLine("# --- End Environment Setup ---")
        }
    }

    /**
     * Writes the env block to a file in TMPDIR and returns the path.
     * Sourcing a file is more reliable than injecting raw text because the
     * shell processes it as a single atomic read rather than byte-by-byte input.
     */
    private fun writeEnvFile(): File {
        val tmpDir = normalizeFilesDir(filesDir).resolve("tmp").also { it.mkdirs() }
        val envFile = tmpDir.resolve(ENV_FILE_NAME)
        envFile.writeText(buildEnvBlock())
        envFile.setReadable(true)
        return envFile
    }

    /**
     * Runs install.sh inside an existing terminal session.
     *
     * The script is looked up at filesDir/<installSubdir>/install.sh.
     * Before running it, all required env vars are sourced from a temp file
     * so that apt/dpkg find their directories at the correct sandbox paths.
     *
     * @param session       Terminal session to write commands into.
     * @param installSubdir Subdirectory relative to filesDir containing install.sh.
     *                      Defaults to "install" -> filesDir/install/install.sh
     * @param onReady       Optional callback invoked after the command is sent.
     */
    fun runInstallScript(
        session: TerminalSession,
        installSubdir: String = "install",
        onReady: (() -> Unit)? = null,
    ) {
        val installDir = filesDir.resolve(installSubdir)
        val installScript = installDir.resolve("install.sh")

        if (!installScript.exists()) {
            AppLogger.e(TAG, "install.sh not found at: ${installScript.absolutePath}")
            session.write("echo '[ERROR] install.sh not found at: ${installScript.absolutePath}'\n")
            return
        }

        installScript.setExecutable(true)
        AppLogger.i(TAG, "Running install.sh from: ${installScript.absolutePath}")

        handler.postDelayed({
            val envFile = writeEnvFile()
            // Source the env file, then run install.sh in its own directory
            session.write(". \"${envFile.absolutePath}\"\n")

            handler.postDelayed({
                session.write("cd \"${installDir.absolutePath}\" && bash install.sh\n")
                onReady?.invoke()
                AppLogger.i(TAG, "install.sh command sent to terminal session")
            }, ENV_APPLY_DELAY_MS)
        }, SHELL_INIT_DELAY_MS)
    }

    /**
     * Runs an arbitrary command in the session with the correct environment.
     *
     * @param session    Terminal session.
     * @param command    Command to run (newline appended automatically).
     * @param injectEnv  If true (default), sources the env file before the command.
     */
    fun runCommandInSession(
        session: TerminalSession,
        command: String,
        injectEnv: Boolean = true,
    ) {
        handler.postDelayed({
            if (injectEnv) {
                val envFile = writeEnvFile()
                session.write(". \"${envFile.absolutePath}\"\n")
                handler.postDelayed({
                    session.write("$command\n")
                }, ENV_APPLY_DELAY_MS)
            } else {
                session.write("$command\n")
            }
        }, SHELL_INIT_DELAY_MS)
    }

    /**
     * Writes diagnostic information to the terminal session.
     * Useful for debugging environment issues.
     */
    fun runDiagnostics(session: TerminalSession) {
        handler.postDelayed({
            val envFile = writeEnvFile()
            session.write(". \"${envFile.absolutePath}\"\n")
            handler.postDelayed({
                val diagScript = buildString {
                    appendLine("echo '=== OpenClaw Environment Diagnostics ==='")
                    appendLine("echo \"HOME: \$HOME\"")
                    appendLine("echo \"PREFIX: \$PREFIX\"")
                    appendLine("echo \"PATH: \$PATH\"")
                    appendLine("echo \"TMPDIR: \$TMPDIR\"")
                    appendLine("echo \"DPKG_ADMINDIR: \$DPKG_ADMINDIR\"")
                    appendLine("echo \"APT_CONFIG: \$APT_CONFIG\"")
                    appendLine("echo ''")
                    appendLine("echo '=== Directory Check ==='")
                    appendLine("ls \"\$PREFIX/bin/bash\" 2>/dev/null && echo 'bash: OK' || echo 'bash: NOT FOUND'")
                    appendLine("ls \"\$PREFIX/bin/apt\" 2>/dev/null && echo 'apt: OK' || echo 'apt: NOT FOUND'")
                    appendLine("ls \"\$PREFIX/var/lib/dpkg\" 2>/dev/null && echo 'dpkg dir: OK' || echo 'dpkg dir: NOT FOUND'")
                    appendLine("echo ''")
                    appendLine("echo '=== Lock Files ==='")
                    appendLine("ls -la \"\$PREFIX/var/lib/dpkg/lock-frontend\" 2>/dev/null || echo 'lock-frontend: not created yet (OK)'")
                    appendLine("echo '=== End Diagnostics ==='")
                }
                session.write(diagScript)
            }, ENV_APPLY_DELAY_MS)
        }, SHELL_INIT_DELAY_MS)
    }

    /**
     * Normalizes /data/user/0/<pkg>/files to /data/data/<pkg>/files.
     *
     * Android 7+ with multi-user support exposes context.filesDir as
     * /data/user/0/<pkg>/files (a bind-mount alias). Bootstrap binaries compiled
     * for Termux have /data/data/... hardcoded in ELF strings and config lookups
     * via open()/opendir() — calls NOT intercepted by libtermux-exec.so.
     * Using the canonical path avoids "No such file or directory" errors in dpkg,
     * bash, and apt when they try to open their config directories.
     */
    private fun normalizeFilesDir(dir: File): File {
        val path = dir.absolutePath
        val normalized = path.replaceFirst(
            Regex("^/data/user/\\d+/"),
            "/data/data/",
        )
        return if (normalized != path) File(normalized) else dir
    }
}
