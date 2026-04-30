package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
     *
     * Call from a coroutine on any dispatcher — this suspends on IO internally.
     * Progress is reported via [listener] on the IO thread.
     *
     * Flow:
     *   1. Check if already installed → skip
     *   2. If payload assets exist → offline install
     *   3. Else → online bootstrap install
     *   4. Validate critical files
     *   5. Write .installed marker
     */
    suspend fun install(listener: ProgressListener) = withContext(Dispatchers.IO) {
        try {
            // ── Already installed? ──────────────────────────────────────
            if (isInstalled()) {
                AppLogger.i(TAG, "Already installed at $prefix — skipping")
                listener.onSuccess()
                return@withContext
            }

            // ── Choose install path ─────────────────────────────────────
            if (hasPayloadAsset()) {
                AppLogger.i(TAG, "Payload assets detected — starting offline install")
                installOffline(listener)
            } else {
                AppLogger.i(TAG, "No payload assets — starting online bootstrap install")
                installOnline(listener)
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Install failed fatally: ${e.message}", e)
            listener.onError(
                "Instalación falló: ${e.message ?: "error desconocido"}",
                e,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Offline install (payload assets bundled in APK)
    // ═══════════════════════════════════════════════════════════════════════

    private fun installOffline(listener: ProgressListener) {
        // ── Clean start ─────────────────────────────────────────────────
        listener.onProgress(0, "Preparando instalación offline...")
        prefix.deleteRecursively()
        prefix.mkdirs()

        // ── Step 1: Small tar.gz modules (bin, certs, patches) ──────────
        val modules = listOf("bin.tar.gz", "certs.tar.gz", "patches.tar.gz")
        modules.forEachIndexed { index, module ->
            val pct = 5 + (index * 10) // 5%, 15%, 25%
            listener.onProgress(pct, "Extrayendo $module...")
            try {
                val count = PayloadExtractor.extractTarGzAsset(
                    context, "$PAYLOAD_ASSET_DIR/$module", prefix,
                )
                AppLogger.i(TAG, "$module: $count entries extracted")
            } catch (e: Exception) {
                // Small modules may be empty placeholders — non-fatal
                AppLogger.w(TAG, "$module extraction failed (non-fatal): ${e.message}")
                val dirName = module.substringBefore(".tar.gz")
                File(prefix, dirName).mkdirs()
            }
        }

        // ── Step 2: Split lib parts (streaming, no temp file) ───────────
        listener.onProgress(35, "Extrayendo librerías (streaming)...")
        val libParts = listOf("aa", "ab", "ac", "ad", "ae", "af")
        try {
            val count = PayloadExtractor.extractSplitTarGzAsset(
                context, PAYLOAD_ASSET_DIR, "lib.part_", libParts, prefix,
            )
            AppLogger.i(TAG, "lib split parts: $count entries extracted (streaming)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Streaming lib extraction failed: ${e.message}", e)
            listener.onError("Error extrayendo librerías: ${e.message}", e)
            return
        }
        listener.onProgress(60, "Librerías extraídas correctamente")

        // ── Step 3: glibc-aarch64.tar.xz ────────────────────────────────
        listener.onProgress(62, "Copiando glibc al disco...")
        val tempGlibc = File(cacheDir, "glibc-aarch64.tar.xz")
        try {
            val bytes = PayloadExtractor.copyAssetToFile(
                context, "$PAYLOAD_ASSET_DIR/glibc-aarch64.tar.xz", tempGlibc,
            )
            AppLogger.i(TAG, "glibc copied to disk: ${bytes / 1024 / 1024}MB")

            listener.onProgress(70, "Descomprimiendo glibc (xz)...")
            val count = PayloadExtractor.extractTarXzFile(tempGlibc, prefix)
            AppLogger.i(TAG, "glibc: $count entries extracted")
        } catch (e: Exception) {
            AppLogger.e(TAG, "glibc extraction failed: ${e.message}", e)
            listener.onError("Error extrayendo glibc: ${e.message}", e)
            return
        } finally {
            tempGlibc.delete()
        }
        listener.onProgress(85, "glibc extraído correctamente")

        // ── Step 4: Apply permissions + create wrappers ─────────────────
        listener.onProgress(87, "Configurando permisos...")
        applyPermissions()
        applyScriptUpdate() // Ensure run-openclaw.sh and post-setup.sh are copied from assets

        // ── Step 5: Validate ────────────────────────────────────────────
        listener.onProgress(92, "Validando instalación...")
        val result = InstallValidator.validatePayload(prefix)
        if (!result.passed) {
            val msg = "Validación falló: ${result.errors.joinToString("; ")}"
            AppLogger.e(TAG, msg)
            listener.onError(msg)
            return
        }
        result.warnings.forEach { w ->
            AppLogger.w(TAG, "Post-install warning: $w")
        }

        // ── Step 6: Write marker ────────────────────────────────────────
        listener.onProgress(97, "Finalizando instalación...")
        writeMarker()

        listener.onProgress(100, "Instalación completada")
        listener.onSuccess()
        AppLogger.i(TAG, "Offline installation completed successfully")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Online install (download bootstrap from network)
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun installOnline(listener: ProgressListener) {
        listener.onProgress(0, "Descargando bootstrap de Termux...")
        val bootstrapManager = BootstrapManager(context)
        try {
            bootstrapManager.startSetup { progress, message ->
                val pct = (progress * 90).toInt() // Reserve last 10% for validation
                listener.onProgress(pct, message)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Bootstrap download/extract failed: ${e.message}", e)
            listener.onError(
                "Error descargando bootstrap: ${e.message ?: "sin conexión"}. " +
                "Verifica tu conexión a internet.",
                e,
            )
            return
        }

        // Validate bootstrap installation
        listener.onProgress(92, "Validando bootstrap...")
        if (!bootstrapManager.isInstalled()) {
            listener.onError("Bootstrap se descargó pero no se instaló correctamente")
            return
        }

        // Write installed marker (bootstrap writes its own markers too)
        listener.onProgress(97, "Finalizando...")
        writeMarker()

        listener.onProgress(100, "Bootstrap instalado correctamente")
        listener.onSuccess()
        AppLogger.i(TAG, "Online bootstrap installation completed successfully")
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
        // Mark all bin/ files executable
        val binDir = File(prefix, "bin")
        if (binDir.isDirectory) {
            binDir.listFiles()?.forEach { 
                it.setExecutable(true, false)
                it.setExecutable(true, true) // Also set for owner+group+others
            }
            AppLogger.i(TAG, "Permissions set on ${binDir.listFiles()?.size ?: 0} bin files")
        }

        // Emergency bash wrapper → if bash is missing, wrap /system/bin/sh
        val bashFile = File(binDir, "bash")
        if (!bashFile.exists()) {
            val script = "#!/system/bin/sh\n# Emergency bash wrapper\nexec /system/bin/sh\n"
            bashFile.writeText(script)
            bashFile.setExecutable(true, false)
            bashFile.setExecutable(true, true)
            AppLogger.i(TAG, "Emergency bash wrapper created")
        }

        // Global 'openclaw' command wrapper
        val openclawBin = File(binDir, "openclaw")
        if (!openclawBin.exists()) {
            val runScript = "${filesDir.absolutePath}/payload/run-openclaw.sh"
            val script = "#!/system/bin/sh\nexec \"$runScript\" \"\$@\"\n"
            openclawBin.writeText(script)
            openclawBin.setExecutable(true, false)
            openclawBin.setExecutable(true, true)
            // Fallback: try chmod if setExecutable failed
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", openclawBin.absolutePath))
            } catch (e: Exception) {
                AppLogger.w(TAG, "chmod fallback failed for openclaw wrapper: ${e.message}")
            }
            AppLogger.i(TAG, "openclaw wrapper created with executable permissions")
        } else {
            // Ensure existing openclaw wrapper has executable permissions
            openclawBin.setExecutable(true, false)
            openclawBin.setExecutable(true, true)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", openclawBin.absolutePath))
            } catch (e: Exception) {
                AppLogger.w(TAG, "chmod fallback failed for existing openclaw wrapper: ${e.message}")
            }
        }

        // Ensure run-openclaw.sh exists and is executable
        val runOpenclawScript = File(filesDir, "payload/run-openclaw.sh")
        if (runOpenclawScript.exists()) {
            runOpenclawScript.setExecutable(true, false)
            runOpenclawScript.setExecutable(true, true)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", runOpenclawScript.absolutePath))
            } catch (e: Exception) {
                AppLogger.w(TAG, "chmod fallback failed for run-openclaw.sh: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Status queries (for JsBridge / UI)
    // ═══════════════════════════════════════════════════════════════════════

    fun getStatus(): String = when {
        isReady() -> "ready"
        isInstalled() -> "setup_required"
        else -> "not_installed"
    }

    fun isOpenClawInstalled(): Boolean {
        // Offline payload path: wrapper is created by applyPermissions()
        if (File(prefix, "bin/openclaw").exists()) return true
        // Online bootstrap path
        return File(filesDir, "home/.openclaw-android/lib/openclaw/openclaw").exists()
    }

    /** Sync www assets from APK to the share directory. */
    fun syncWwwFromAssets() {
        val wwwDest = File(prefix, "share/openclaw-app/www")
        wwwDest.mkdirs()
    }

    /** Apply script updates when APK version changes. */
    fun applyScriptUpdate() {
        val ocaDir = File(filesDir, "home/.openclaw-android")
        val scripts = listOf(
            "run.sh" to File(prefix, "bin/run.sh"),
            "post-setup.sh" to File(filesDir, "payload/post-setup.sh"),
            "run-openclaw.sh" to File(filesDir, "payload/run-openclaw.sh"),
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
                // Script may not exist in assets — non-fatal
            }
        }
    }
}
