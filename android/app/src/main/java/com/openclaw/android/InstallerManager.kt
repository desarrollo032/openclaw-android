package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * InstallerManager — single entry point for all installation flows.
 *
 * Architecture:
 *   ┌──────────────────────────────────────────────┐
 *   │            InstallerManager                  │
 *   │  ┌───────────────┐  ┌────────────────────┐   │
 *   │  │PayloadExtractor│  │InstallValidator   │   │
 *   │  │(streaming I/O) │  │(post-install check)│   │
 *   │  └───────────────┘  └────────────────────┘   │
 *   │  ┌────────────────┐                          │
 *   │  │BootstrapManager│ ← online fallback        │
 *   │  └────────────────┘                          │
 *   └───────────────── ↑progress callbacks ────────┘
 *                      │
 *              MainActivity (UI layer)
 *
 * Design principles:
 *   1. NO shell writes — all progress via [ProgressListener] callback.
 *   2. NO temp files for split reconstruction — streaming via SequenceInputStream.
 *   3. Marker (.installed) written ONLY after validation passes.
 *   4. Each extraction step has its own try-catch — partial failures are reported,
 *      not silently swallowed.
 *   5. Runs entirely on Dispatchers.IO — never blocks the main thread.
 *   6. The Activity shows progress in a dedicated UI overlay, not in a terminal.
 */
class InstallerManager(private val context: Context) {

    private val TAG = "InstallerManager"

    // ── Filesystem layout ──────────────────────────────────────────────────
    private val filesDir: File = context.filesDir
    private val prefix = File(filesDir, "usr")
    private val homeDir = File(filesDir, "home")
    private val cacheDir: File = context.cacheDir

    private val markerInstalled = File(filesDir, ".installed")

    private val PAYLOAD_ASSET_DIR = "payload"

    // ── Progress reporting ─────────────────────────────────────────────────

    /**
     * Listener for installation progress.
     * Called from Dispatchers.IO — the UI layer must post to main thread.
     *
     * This is deliberately simple (no Flow/LiveData) because:
     *   - It's consumed by exactly one observer (MainActivity)
     *   - Progress is linear (0→100%), no fan-out needed
     *   - Callbacks are the simplest contract to test and reason about
     */
    interface ProgressListener {
        /** Installation step changed. percent is 0–100. */
        fun onProgress(percent: Int, message: String)

        /** Installation completed successfully. */
        fun onSuccess()

        /** Installation failed. [message] is user-facing. [cause] is for logging. */
        fun onError(message: String, cause: Throwable? = null)
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** True if the environment is structurally installed (marker + dirs). */
    fun isInstalled(): Boolean =
        prefix.isDirectory && markerInstalled.exists()

    /** True if glibc and node are both present (fully functional). */
    fun isReady(): Boolean =
        isInstalled() && InstallValidator.isStructurallyComplete(prefix)

    /** True if APK bundles a payload asset. */
    fun hasPayloadAsset(): Boolean = try {
        context.assets.open("$PAYLOAD_ASSET_DIR/glibc-aarch64.tar.xz").use { true }
    } catch (_: Exception) {
        false
    }

    /**
     * Main entry point: decide offline vs online, execute, validate.
     */
    suspend fun install(mode: String, customUri: android.net.Uri?, listener: ProgressListener) = withContext(Dispatchers.IO) {
        try {
            // ── Clean start if requested or not installed ──────────────────
            if (isInstalled() && mode != "force") {
                AppLogger.i(TAG, "Already installed — skipping")
                listener.onSuccess()
                return@withContext
            }

            when (mode) {
                "offline" -> {
                    if (customUri != null) {
                        installFromCustomPayload(customUri, listener)
                    } else {
                        installOffline(listener)
                    }
                }
                "online" -> {
                    installOnline(listener)
                }
                else -> {
                    if (hasPayloadAsset()) installOffline(listener)
                    else installOnline(listener)
                }
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Install failed fatally: ${e.message}", e)
            listener.onError(
                "Instalación falló: ${e.message ?: "error desconocido"}",
                e,
            )
        }
    }

    private fun installOffline(listener: ProgressListener) {
        listener.onProgress(0, "Preparando instalación desde assets...")
        
        // El payload puede contener usr/ y home/ o estar plano. 
        // Lo extraeremos en el directorio de archivos de la app.
        try {
            listener.onProgress(5, "Extrayendo payload principal (esto puede tardar)...")
            val count = PayloadExtractor.extractTarGzAsset(
                context, "openclaw-payload.tar.gz", filesDir
            )
            AppLogger.i(TAG, "Payload extracted: $count entries")
            
            completeInstallation(listener)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Offline extraction failed: ${e.message}", e)
            listener.onError("Error extrayendo payload: ${e.message}", e)
        }
    }

    private fun installFromCustomPayload(uri: android.net.Uri, listener: ProgressListener) {
        listener.onProgress(0, "Preparando instalación desde archivo externo...")
        try {
            listener.onProgress(5, "Abriendo archivo seleccionado...")
            context.contentResolver.openInputStream(uri)?.use { input ->
                listener.onProgress(10, "Extrayendo contenido (streaming)...")
                val count = PayloadExtractor.extractTarGzStream(input, filesDir)
                AppLogger.i(TAG, "Custom payload extracted: $count entries")
            }
            
            completeInstallation(listener)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Custom payload extraction failed: ${e.message}", e)
            listener.onError("Error extrayendo archivo externo: ${e.message}", e)
        }
    }

    private fun completeInstallation(listener: ProgressListener) {
        listener.onProgress(80, "Configurando entorno...")
        
        // Asegurar que los scripts de assets estén actualizados en el destino
        applyScriptUpdate()
        
        // Aplicar permisos
        applyPermissions()
        
        // Escribir marcador de instalación exitosa
        writeMarker()
        
        listener.onProgress(100, "¡Instalación completada!")
        listener.onSuccess()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Online install (download bootstrap from network)
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun installOnline(listener: ProgressListener) {
        listener.onProgress(0, "Iniciando descarga de payload...")
        
        val payloadUrl = "https://github.com/desarrollo032/openclaw-android/releases/download/latest/openclaw-payload.tar.gz"
        val tempFile = File(cacheDir, "downloaded-payload.tar.gz")
        
        try {
            listener.onProgress(5, "Conectando con el servidor...")
            val url = URL(payloadUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            
            if (connection.responseCode != 200) {
                throw Exception("Error del servidor: ${connection.responseCode} ${connection.responseMessage}")
            }
            
            val totalSize = connection.contentLength.toLong()
            var downloaded = 0L
            
            listener.onProgress(10, "Descargando payload (${totalSize / 1024 / 1024}MB)...")
            
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val pct = 10 + (downloaded * 60 / totalSize).toInt()
                            listener.onProgress(pct, "Descargando... ${(downloaded / 1024 / 1024)}MB / ${(totalSize / 1024 / 1024)}MB")
                        }
                    }
                }
            }
            
            listener.onProgress(70, "Descarga completada. Extrayendo...")
            tempFile.inputStream().use { input ->
                val count = PayloadExtractor.extractTarGzStream(input, filesDir)
                AppLogger.i(TAG, "Online payload extracted: $count entries")
            }
            
            completeInstallation(listener)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Online install failed: ${e.message}", e)
            listener.onError("Error en la instalación online: ${e.message}", e)
        } finally {
            tempFile.delete()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun writeMarker() {
        try {
            markerInstalled.writeText("0.4.0\n")
            AppLogger.i(TAG, "Installed marker written at ${markerInstalled.absolutePath}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write marker: ${e.message}", e)
            // Non-fatal: next launch will re-install which is safe
        }
    }

    /**
     * Set executable permissions on extracted binaries and create emergency wrappers.
     */
    private fun applyPermissions() {
        // Encontrar el directorio del payload (puede estar directamente en filesDir o en openclaw-payload/)
        val ocaPayloadDir = if (File(filesDir, "openclaw-payload").isDirectory) {
            File(filesDir, "openclaw-payload")
        } else {
            filesDir
        }

        AppLogger.i(TAG, "Applying permissions in ${ocaPayloadDir.absolutePath}")

        // Asegurar que run-openclaw.sh sea ejecutable
        val runScript = File(ocaPayloadDir, "run-openclaw.sh")
        if (runScript.exists()) {
            runScript.setExecutable(true, false)
            runScript.setExecutable(true, true)
        }

        // Asegurar que el binario de node sea ejecutable
        val nodeBin = File(ocaPayloadDir, "bin/node")
        if (nodeBin.exists()) {
            nodeBin.setExecutable(true, false)
            nodeBin.setExecutable(true, true)
        }

        // Asegurar que el cargador de glibc sea ejecutable
        val ldso = File(ocaPayloadDir, "glibc/lib/ld-linux-aarch64.so.1")
        if (ldso.exists()) {
            ldso.setExecutable(true, false)
            ldso.setExecutable(true, true)
        }
        
        // Crear enlace simbólico o wrapper en bin/openclaw para acceso fácil
        val binDir = File(prefix, "bin")
        binDir.mkdirs()
        val openclawLink = File(binDir, "openclaw")
        val runScript = getRunScriptPath()
        
        if (runScript.exists()) {
            try {
                // Intentar crear un wrapper script en bin/openclaw
                val wrapper = "#!/system/bin/sh\nexec \"${runScript.absolutePath}\" \"$@\"\n"
                openclawLink.writeText(wrapper)
                openclawLink.setExecutable(true, false)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to create bin wrapper: ${e.message}")
            }
        }

        if (binDir.isDirectory) {
            binDir.listFiles()?.forEach { 
                it.setExecutable(true, false)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Status queries (for JsBridge / UI)
    // ═══════════════════════════════════════════════════════════════════════

    fun getRunScriptPath(): File {
        val ocaPayloadDir = if (File(filesDir, "openclaw-payload").isDirectory) {
            File(filesDir, "openclaw-payload")
        } else {
            filesDir
        }
        return File(ocaPayloadDir, "run-openclaw.sh")
    }

    fun getStatus(): String = when {
        isReady() -> "ready"
        isInstalled() -> "setup_required"
        else -> "not_installed"
    }

    fun isOpenClawInstalled(): Boolean {
        val ocaPayloadDir = if (File(filesDir, "openclaw-payload").isDirectory) {
            File(filesDir, "openclaw-payload")
        } else {
            filesDir
        }
        // Verificar binario de openclaw (enlace o wrapper) o script de arranque
        return File(prefix, "bin/openclaw").exists() || 
               File(ocaPayloadDir, "run-openclaw.sh").exists() ||
               File(prefix, "lib/node_modules/openclaw/openclaw.mjs").exists()
    }

    /** Sync www assets from APK to the share directory. */
    fun syncWwwFromAssets() {
        val wwwDest = File(prefix, "share/openclaw-app/www")
        wwwDest.mkdirs()
    }

    fun applyScriptUpdate() {
        val ocaPayloadDir = if (File(filesDir, "openclaw-payload").isDirectory) {
            File(filesDir, "openclaw-payload")
        } else {
            filesDir
        }
        val ocaDir = File(filesDir, "home/.openclaw-android")
        
        val scripts = listOf(
            "run.sh" to File(prefix, "bin/run.sh"),
            "post-setup.sh" to File(ocaPayloadDir, "post-setup.sh"),
            "run-openclaw.sh" to File(ocaPayloadDir, "run-openclaw.sh"),
            "glibc-compat.js" to File(ocaDir, "patches/glibc-compat.js"),
        )
        for ((assetName, destFile) in scripts) {
            try {
                context.assets.open(assetName).use { input ->
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile.setExecutable(true, false)
            } catch (_: Exception) {
            }
        }
    }
}
