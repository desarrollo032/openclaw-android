package com.openclaw.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.termux.terminal.TerminalSession
import java.io.File

/**
 * Helper para integrar con Termux o ejecutar el instalador de OpenClaw
 * directamente en el sandbox de la app cuando Termux no está disponible.
 *
 * Flujo:
 * 1. Si Termux está instalado y tiene permisos → lanza via RUN_COMMAND intent
 * 2. Si Termux no está instalado → ofrece instalarlo o usar el fallback
 * 3. Fallback: usa TerminalManager para ejecutar install.sh con el entorno correcto
 */
class TermuxIntegrationHelper(
    private val context: Context,
    private val bootstrapManager: BootstrapManager,
) {
    private fun isTermuxInstalled(): Boolean =
        try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    fun installOpenClaw(session: TerminalSession) {
        if (!isTermuxInstalled()) {
            showInstallTermuxDialog(session)
            return
        }

        val intent = Intent("com.termux.RUN_COMMAND")
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        intent.putExtra(
            "com.termux.RUN_COMMAND_ARGUMENTS",
            arrayOf("-c", "curl -sL myopenclawhub.com/install | bash"),
        )
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "1")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: SecurityException) {
            showAllowExternalAppsDialog(session)
        } catch (e: Exception) {
            e.printStackTrace()
            runFallbackInstall(session)
        }
    }

    private fun showInstallTermuxDialog(session: TerminalSession) {
        AlertDialog.Builder(context)
            .setTitle("Termux not found")
            .setMessage(
                "For optimal installation, Termux is recommended. " +
                    "Would you like to install it from F-Droid?",
            )
            .setPositiveButton("Install Termux") { _, _ ->
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/com.termux/"),
                )
                context.startActivity(browserIntent)
            }
            .setNegativeButton("Continue without Termux") { _, _ ->
                runFallbackInstall(session)
            }
            .show()
    }

    private fun showAllowExternalAppsDialog(session: TerminalSession) {
        AlertDialog.Builder(context)
            .setTitle("Permission Denied")
            .setMessage(
                "To install OpenClaw, you must allow Termux to receive external commands.\n\n" +
                    "1. Open Termux.\n" +
                    "2. Run: nano ~/.termux/termux.properties\n" +
                    "3. Change 'allow-external-apps = false' to 'true'.\n" +
                    "4. Restart Termux.",
            )
            .setPositiveButton("Got it") { _, _ -> }
            .setNegativeButton("Use alternative method") { _, _ ->
                runFallbackInstall(session)
            }
            .show()
    }

    /**
     * Fallback: ejecuta install.sh directamente en el sandbox de la app.
     *
     * Usa TerminalManager para inyectar las variables de entorno correctas
     * ANTES de ejecutar el script. Esto resuelve el error:
     *   "E: Could not open lock file /var/lib/dpkg/lock-frontend"
     *
     * El error ocurre porque apt/dpkg busca sus lock files en rutas absolutas
     * hardcodeadas para Termux. Con PREFIX apuntando al sandbox de la app,
     * los lock files se crean en la ruta correcta y accesible.
     *
     * El script install.sh se busca en:
     *   filesDir/install/install.sh
     */
    private fun runFallbackInstall(session: TerminalSession) {
        val filesDir = (context as? MainActivity)?.filesDir ?: context.filesDir
        val terminalManager = TerminalManager(context, filesDir)

        // Verificar si existe install.sh en el directorio de instalación
        val installDir = filesDir.resolve("install")
        val installScript = installDir.resolve("install.sh")

        if (installScript.exists()) {
            // Usar TerminalManager para ejecutar con el entorno correcto
            terminalManager.runInstallScript(session, "install")
        } else {
            // install.sh no está disponible localmente — descargar via curl
            // con el entorno correcto ya configurado
            terminalManager.runCommandInSession(
                session,
                "curl -sL myopenclawhub.com/install | bash",
                injectEnv = true,
            )
        }
    }
}
