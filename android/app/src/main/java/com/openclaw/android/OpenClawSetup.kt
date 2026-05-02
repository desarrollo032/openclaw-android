package com.openclaw.android

import android.content.Context
import java.io.File

class OpenClawSetup(private val context: Context) {

    // Directorio donde Termux embebido tiene su entorno (usado si fallamos al online)
    private val termuxHome = context.filesDir.resolve("termux")
    // Directorio donde extraemos el payload
    private val payloadDir = context.filesDir.resolve("openclaw-payload")

    fun setupOpenClaw() {
        if (isPayloadAvailable()) {
            installFromPayload()
        } else {
            installOnlineWithCurl()
        }
    }

    private fun isPayloadAvailable(): Boolean {
        // Verificar si el payload ya fue extraído previamente
        return payloadDir.resolve("run-openclaw.sh").exists() ||
               hasAsset("openclaw-payload.tar.gz")
    }

    private fun hasAsset(name: String): Boolean {
        return try {
            context.assets.open(name).use { it.available() > 0 }
        } catch (e: Exception) {
            false
        }
    }

    private fun installFromPayload() {
        if (!payloadDir.resolve("run-openclaw.sh").exists()) {
            AppLogger.i("OpenClawSetup", "Extracting payload from assets...")
            // Extraer el payload del asset usando PayloadExtractor existente si posible
            try {
                PayloadExtractor.extractTarGzAsset(context, "openclaw-payload.tar.gz", payloadDir)
            } catch (e: Exception) {
                AppLogger.e("OpenClawSetup", "Error extracting payload: ${e.message}")
            }
        }
        
        // Dar permisos de ejecución
        File(payloadDir, "run-openclaw.sh").setExecutable(true)
        File(payloadDir, "bin/node").setExecutable(true)
        val glibcNode = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")
        if(glibcNode.exists()) glibcNode.setExecutable(true)

        // Iniciar OpenClaw
        startOpenClaw(payloadDir)
    }

    private fun installOnlineWithCurl() {
        AppLogger.i("OpenClawSetup", "Starting online installation fallback")
        termuxHome.mkdirs()
        // Usar el Termux embebido para ejecutar el script de instalación
        val installScriptStream = try {
            context.assets.open("scripts/install-online.sh")
        } catch (e: Exception) {
            AppLogger.e("OpenClawSetup", "Missing install-online.sh asset")
            return
        }

        val scriptFile = File(termuxHome, "install.sh")
        scriptFile.writeBytes(installScriptStream.readBytes())
        scriptFile.setExecutable(true)

        val process = ProcessBuilder()
            .directory(termuxHome)
            .command("sh", scriptFile.absolutePath)
            .start()
        
        // Esperar a que termine
        val exitCode = process.waitFor()
        AppLogger.i("OpenClawSetup", "Online install exited with code $exitCode")

        migrateOnlineInstallToPayload()
    }

    private fun migrateOnlineInstallToPayload() {
        AppLogger.i("OpenClawSetup", "Migrating online install to payload dir...")
        // La implementación real dependería de dónde instaló el script online
        // Usualmente en Termux $HOME/.openclaw-android o $PREFIX.
    }

    private fun startOpenClaw(workingDir: File) {
        AppLogger.i("OpenClawSetup", "Starting OpenClaw from ${workingDir.absolutePath}")
        val processBuilder = ProcessBuilder()
        processBuilder.directory(workingDir)
        val env = processBuilder.environment()
        env["LD_LIBRARY_PATH"] = "${workingDir.absolutePath}/glibc/lib"
        env["PATH"] = "${workingDir.absolutePath}/bin:${workingDir.absolutePath}/glibc/bin:${System.getenv("PATH")}"
        env["OA_GLIBC"] = "1"
        env["CONTAINER"] = "1"

        processBuilder.command("sh", "./run-openclaw.sh", "start")
        try {
            processBuilder.start()
            AppLogger.i("OpenClawSetup", "OpenClaw started successfully")
        } catch (e: Exception) {
            AppLogger.e("OpenClawSetup", "Failed to start OpenClaw: ${e.message}")
        }
    }
}
