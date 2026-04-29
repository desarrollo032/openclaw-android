package com.openclaw.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.termux.terminal.TerminalSession
import java.io.File

class TermuxIntegrationHelper(private val context: Context, private val bootstrapManager: BootstrapManager) {

    private fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
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
            arrayOf("-c", "curl -sL myopenclawhub.com/install | bash")
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
            runFallbackScript(session)
        }
    }

    private fun showInstallTermuxDialog(session: TerminalSession) {
        AlertDialog.Builder(context)
            .setTitle("Termux no encontrado")
            .setMessage("Para una instalación óptima, se recomienda usar Termux. ¿Deseas instalarlo desde F-Droid?")
            .setPositiveButton("Instalar") { _, _ ->
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
                context.startActivity(browserIntent)
            }
            .setNegativeButton("Continuar sin Termux (Fallback)") { _, _ ->
                runFallbackScript(session)
            }
            .show()
    }

    private fun showAllowExternalAppsDialog(session: TerminalSession) {
        AlertDialog.Builder(context)
            .setTitle("Permiso Denegado")
            .setMessage("Para instalar OpenClaw, debes permitir que Termux reciba comandos externos.\n\n" +
                        "1. Abre Termux.\n" +
                        "2. Ejecuta: nano ~/.termux/termux.properties\n" +
                        "3. Cambia 'allow-external-apps = false' a 'true'.\n" +
                        "4. Reinicia Termux.")
            .setPositiveButton("Entendido") { _, _ -> }
            .setNegativeButton("Usar método alternativo") { _, _ ->
                runFallbackScript(session)
            }
            .show()
    }

    private fun runFallbackScript(session: TerminalSession) {
        val prefix = bootstrapManager.prefixDir.absolutePath
        val script = """
            echo "[*] Preparando entorno de instalación emulado..."

            export PREFIX="$prefix"
            export LD_LIBRARY_PATH="${'$'}PREFIX/lib"
            export PATH="${'$'}PREFIX/bin:${'$'}PATH"

            echo "[*] Creando jerarquía de carpetas dpkg/apt y archivos lock..."
            mkdir -p "${'$'}PREFIX/var/lib/apt/var/lib/dpkg"

            touch "${'$'}PREFIX/var/lib/dpkg/lock-frontend"
            touch "${'$'}PREFIX/var/lib/dpkg/lock"
            touch "${'$'}PREFIX/var/lib/apt/lists/lock"
            touch "${'$'}PREFIX/var/cache/apt/archives/lock"

            touch "${'$'}PREFIX/var/lib/apt/var/lib/dpkg/lock-frontend"
            touch "${'$'}PREFIX/var/lib/apt/var/lib/dpkg/lock"

            chmod 644 "${'$'}PREFIX/var/lib/dpkg/lock-frontend"
            chmod 644 "${'$'}PREFIX/var/lib/dpkg/lock"
            chmod 644 "${'$'}PREFIX/var/lib/apt/lists/lock"
            chmod 644 "${'$'}PREFIX/var/cache/apt/archives/lock"

            chmod 644 "${'$'}PREFIX/var/lib/apt/var/lib/dpkg/lock-frontend" 2>/dev/null
            chmod 644 "${'$'}PREFIX/var/lib/apt/var/lib/dpkg/lock" 2>/dev/null

            echo "[*] Entorno preparado. Iniciando descarga e instalación..."
            curl -sL myopenclawhub.com/install | bash
        """.trimIndent()

        session.write(script + "\n")
    }
}
