package com.openclaw.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.termux.terminal.TerminalSession
import java.io.File

/**
 * Handles OpenClaw installation from within the app terminal.
 *
 * Install strategy (in priority order):
 *
 * 1. Payload path (preferred, fully offline): if PayloadManager.isReady() is
 *    true, OpenClaw is already installed — nothing to do.
 *
 * 2. App-sandbox install (no Termux required): copies install scripts from
 *    assets to filesDir/install/, injects the correct environment via
 *    TerminalManager, and runs install.sh locally. This is the primary path
 *    for devices without Termux.
 *
 * 3. Termux RUN_COMMAND intent (optional enhancement): if Termux is installed
 *    AND the user has enabled allow-external-apps, we can delegate to Termux.
 *    However, the install script must still target the APP sandbox (not Termux
 *    home), so we pass the correct PREFIX/HOME env vars via the intent.
 *
 * Root cause of the original lock-file error:
 *   install.sh was written for a standard Termux environment where
 *   PREFIX=/data/data/com.termux/files/usr. When run inside this app
 *   (package com.openclaw.android.debug), apt/dpkg look for lock files at
 *   the Termux path which is inaccessible. The fix is to always set PREFIX,
 *   HOME, and DPKG_ADMINDIR to paths inside the app sandbox BEFORE running
 *   any apt/dpkg command.
 */
class TermuxIntegrationHelper(
    private val context: Context,
    private val bootstrapManager: BootstrapManager,
) {
    companion object {
        private const val TAG = "TermuxIntegrationHelper"
        private const val SHELL_INIT_DELAY_MS = 600L
        private const val ENV_APPLY_DELAY_MS = 300L
        private const val INSTALL_SUBDIR = "install"
        private const val INSTALL_SCRIPT_NAME = "install.sh"
    }

    private val filesDir: File get() = (context as? MainActivity)?.filesDir ?: context.filesDir

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Starts the OpenClaw installation in the given terminal session.
     *
     * Checks whether Termux is available and has external-apps permission,
     * then chooses the best install path. Falls back to the app-sandbox
     * install if Termux is unavailable or permission is denied.
     */
    fun installOpenClaw(session: TerminalSession) {
        AppLogger.i(TAG, "installOpenClaw() called")

        // Ensure install scripts are available in the sandbox before anything else
        ensureInstallScripts()

        if (isTermuxInstalled()) {
            tryTermuxIntent(session)
        } else {
            runSandboxInstall(session)
        }
    }

    // ── Termux path (Solution A) ──────────────────────────────────────────────

    private fun isTermuxInstalled(): Boolean =
        try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * Attempts to launch the installer via Termux RUN_COMMAND intent.
     *
     * IMPORTANT: even when using Termux as the shell host, we pass the app
     * sandbox paths (PREFIX, HOME) so that apt/dpkg write their lock files
     * inside the app's private storage — not inside Termux's storage.
     *
     * The intent opens a new Termux session visible to the user, which is
     * preferable for long-running installs.
     */
    private fun tryTermuxIntent(session: TerminalSession) {
        val env = EnvironmentBuilder.buildEnvironment(filesDir, context.packageName)
        val prefix = env["PREFIX"] ?: filesDir.resolve("usr").absolutePath
        val home = env["HOME"] ?: filesDir.resolve("home").absolutePath
        val installScript = filesDir.resolve("$INSTALL_SUBDIR/$INSTALL_SCRIPT_NAME")

        // Build the command: set env vars inline, then run install.sh
        val command = buildString {
            append("export PREFIX=\"$prefix\"; ")
            append("export HOME=\"$home\"; ")
            append("export TMPDIR=\"${env["TMPDIR"] ?: filesDir.resolve("tmp").absolutePath}\"; ")
            append("export DPKG_ADMINDIR=\"$prefix/var/lib/dpkg\"; ")
            append("export DPKG_ROOT=\"$prefix\"; ")
            append("export APT_CONFIG=\"$prefix/etc/apt/apt.conf\"; ")
            append("export PATH=\"$prefix/bin:$prefix/bin/applets:/system/bin:/bin\"; ")
            append("export TERMUX__PREFIX=\"$prefix\"; ")
            append("export TERMUX_PREFIX=\"$prefix\"; ")
            append("unset LD_PRELOAD; ")
            if (installScript.exists()) {
                append("bash \"${installScript.absolutePath}\"")
            } else {
                append("curl -sL myopenclawhub.com/install | bash")
            }
        }

        val termuxBash = "/data/data/com.termux/files/usr/bin/bash"
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", termuxBash)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            // Use the APP home dir as workdir, not Termux home
            putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "1")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            AppLogger.i(TAG, "Termux RUN_COMMAND intent sent")
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "Termux RUN_COMMAND denied (allow-external-apps not set): ${e.message}")
            showAllowExternalAppsDialog(session)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Termux intent failed, falling back to sandbox install: ${e.message}")
            runSandboxInstall(session)
        }
    }

    // ── App-sandbox path (Solution B) ────────────────────────────────────────

    /**
     * Runs the installer entirely within the app sandbox.
     *
     * This is the definitive fix for the lock-file error. The sequence is:
     *
     * 1. Write all required env vars to the shell session (PREFIX, HOME,
     *    DPKG_ADMINDIR, APT_CONFIG, etc.) pointing to the app sandbox.
     * 2. Wait for the shell to process the exports.
     * 3. Run install.sh (local copy from assets) or fall back to curl.
     *
     * With PREFIX set to filesDir/usr, apt/dpkg create their lock files at:
     *   filesDir/usr/var/lib/dpkg/lock-frontend  ← accessible by the app
     * instead of:
     *   /data/data/com.termux/files/usr/var/lib/dpkg/lock-frontend  ← inaccessible
     */
    private fun runSandboxInstall(session: TerminalSession) {
        AppLogger.i(TAG, "Running sandbox install")
        val terminalManager = TerminalManager(context, filesDir)
        val installScript = filesDir.resolve("$INSTALL_SUBDIR/$INSTALL_SCRIPT_NAME")

        if (installScript.exists()) {
            AppLogger.i(TAG, "Running local install.sh: ${installScript.absolutePath}")
            terminalManager.runInstallScript(session, INSTALL_SUBDIR)
        } else {
            // No local script — run curl with the correct environment injected first.
            // The env vars ensure that even a freshly downloaded install.sh will
            // find the correct PREFIX and not fail on lock files.
            AppLogger.i(TAG, "No local install.sh — running curl with injected env")
            terminalManager.runCommandInSession(
                session,
                "curl -sL myopenclawhub.com/install | bash",
                injectEnv = true,
            )
        }
    }

    // ── Asset management ──────────────────────────────────────────────────────

    /**
     * Copies install scripts from assets/install/ to filesDir/install/.
     *
     * Called before every install attempt so the scripts are always up to date
     * with the bundled APK version. Safe to call multiple times (idempotent).
     *
     * Assets expected at: assets/install/install.sh (and any helper scripts)
     */
    fun ensureInstallScripts() {
        val installDir = filesDir.resolve(INSTALL_SUBDIR)
        installDir.mkdirs()

        try {
            val assetFiles = context.assets.list(INSTALL_SUBDIR) ?: emptyArray()
            if (assetFiles.isEmpty()) {
                AppLogger.w(TAG, "No files found in assets/$INSTALL_SUBDIR/")
                return
            }
            for (assetName in assetFiles) {
                val dest = installDir.resolve(assetName)
                context.assets.open("$INSTALL_SUBDIR/$assetName").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.setExecutable(true)
                AppLogger.i(TAG, "Copied assets/$INSTALL_SUBDIR/$assetName → ${dest.absolutePath}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not copy install scripts from assets: ${e.message}")
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showAllowExternalAppsDialog(session: TerminalSession) {
        (context as? MainActivity)?.runOnUiThread {
            AlertDialog.Builder(context)
                .setTitle("Termux Permission Required")
                .setMessage(
                    "To use Termux for installation, enable external app access:\n\n" +
                        "1. Open Termux\n" +
                        "2. Run: echo 'allow-external-apps = true' >> ~/.termux/termux.properties\n" +
                        "3. Restart Termux\n\n" +
                        "Or tap 'Install without Termux' to use the built-in installer.",
                )
                .setPositiveButton("Install without Termux") { _, _ ->
                    runSandboxInstall(session)
                }
                .setNegativeButton("Cancel") { _, _ -> }
                .show()
        } ?: runSandboxInstall(session)
    }
}
