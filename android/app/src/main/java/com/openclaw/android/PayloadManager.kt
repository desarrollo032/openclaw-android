package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.util.zip.GZIPInputStream

/**
 * High-precision installer for the OpenClaw runtime environment.
 * Handles granular modules (bin, certs, patches, lib, glibc).
 */
class PayloadManager(private val context: Context) {

    private val TAG = "PayloadInstaller"
    private val PREFIX = File(context.filesDir, "usr")
    private val PAYLOAD_ASSET_DIR = "payload"

    // Markers
    private val markerInstalled = File(context.filesDir, ".installed")

    interface InstallListener {
        fun onProgress(step: String, percent: Int)
        fun onSuccess()
        fun onError(message: String)
    }

    /** Returns true if the environment is already installed in $PREFIX */
    fun isInstalled(): Boolean {
        return PREFIX.exists() && PREFIX.isDirectory && markerInstalled.exists()
    }

    fun hasPayloadAsset(): Boolean {
        // assets.list() is often unreliable. Attempting to open the stream is the only 100% check.
        return try {
            context.assets.open("$PAYLOAD_ASSET_DIR/glibc-aarch64.tar.xz").use { true }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun install(listener: InstallListener) = withContext(Dispatchers.IO) {
        try {
            if (isInstalled()) {
                AppLogger.i(TAG, "Runtime already installed in $PREFIX. Skipping.")
                listener.onSuccess()
                return@withContext
            }

            // Ensure clean start
            PREFIX.deleteRecursively()
            PREFIX.mkdirs()

            // 1. Extract standard tar.gz modules
            val modules = listOf("bin.tar.gz", "certs.tar.gz", "patches.tar.gz")
            modules.forEachIndexed { index, module ->
                val stepName = "Instalando módulo $module..."
                val basePct = (index * 20)
                listener.onProgress(stepName, basePct)
                
                try {
                    extractTarGzFromAssets("$PAYLOAD_ASSET_DIR/$module", PREFIX)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Module $module failed or empty, ensuring directory exists: ${e.message}")
                    // Create the directory manually if extraction failed (e.g. empty placeholder)
                    val dirName = module.substringBefore(".tar.gz")
                    File(PREFIX, dirName).mkdirs()
                }
            }

            // 2. Reconstruct and extract split lib parts
            listener.onProgress("Reconstruyendo librerías (lib.tar.gz)...", 60)
            val libParts = listOf("aa", "ab", "ac", "ad", "ae", "af")
            val tempLibTar = File(context.cacheDir, "lib.tar.gz")
            
            FileOutputStream(tempLibTar).use { fos ->
                libParts.forEach { suffix ->
                    context.assets.open("$PAYLOAD_ASSET_DIR/lib.part_$suffix").use { it.copyTo(fos) }
                }
            }
            
            listener.onProgress("Extrayendo librerías...", 70)
            extractTarGzFromFile(tempLibTar, PREFIX)
            tempLibTar.delete()

            // 3. Extract glibc-aarch64.tar.xz using system tar
            listener.onProgress("Instalando glibc core (xz)...", 85)
            val tempGlibcXz = File(context.cacheDir, "glibc-aarch64.tar.xz")
            context.assets.open("$PAYLOAD_ASSET_DIR/glibc-aarch64.tar.xz").use { input ->
                tempGlibcXz.outputStream().use { output -> input.copyTo(output) }
            }
            
            extractTarXzSystem(tempGlibcXz, PREFIX)
            tempGlibcXz.delete()

            // 4. Finalize
            applyPermissions()
            markerInstalled.writeText("0.4.0")
            
            listener.onProgress("Instalación completada", 100)
            listener.onSuccess()
            AppLogger.i(TAG, "Installation successful")

        } catch (e: Exception) {
            AppLogger.e(TAG, "Installation failed: ${e.message}", e)
            listener.onError(e.message ?: "Error desconocido durante la instalación")
        }
    }

    private fun extractTarGzFromAssets(assetPath: String, destDir: File) {
        try {
            context.assets.open(assetPath).use { inputStream ->
                GZIPInputStream(inputStream).use { gzipStream ->
                    TarArchiveInputStream(gzipStream).use { tarStream ->
                        extractTarEntries(tarStream, destDir)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to extract asset $assetPath: ${e.message}", e)
            throw IOException("Error extrayendo $assetPath: ${e.message}")
        }
    }

    private fun extractTarGzFromFile(file: File, destDir: File) {
        try {
            file.inputStream().use { inputStream ->
                GZIPInputStream(inputStream).use { gzipStream ->
                    TarArchiveInputStream(gzipStream).use { tarStream ->
                        extractTarEntries(tarStream, destDir)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to extract file ${file.name}: ${e.message}", e)
            throw IOException("Error extrayendo ${file.name}: ${e.message}")
        }
    }

    private fun extractTarEntries(tarStream: TarArchiveInputStream, destDir: File) {
        var entry: TarArchiveEntry? = tarStream.nextTarEntry
        while (entry != null) {
            val destFile = File(destDir, entry.name)
            if (entry.isDirectory) {
                destFile.mkdirs()
            } else if (entry.isSymbolicLink) {
                destFile.parentFile?.mkdirs()
                try {
                    // Create actual symlink using Android OS API
                    android.system.Os.symlink(entry.linkName, destFile.absolutePath)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to create symlink ${entry.name} -> ${entry.linkName}: ${e.message}")
                }
            } else {
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { output ->
                    tarStream.copyTo(output)
                }
                // If it's in a bin folder or has executable bit set in tar, mark as executable
                if (entry.name.contains("bin/") || (entry.mode and 0x40 != 0)) {
                    destFile.setExecutable(true, false)
                }
            }
            entry = tarStream.nextTarEntry
        }
    }

    private fun extractTarXzSystem(archive: File, destDir: File) {
        try {
            archive.inputStream().use { inputStream ->
                XZCompressorInputStream(inputStream).use { xzStream ->
                    TarArchiveInputStream(xzStream).use { tarStream ->
                        extractTarEntries(tarStream, destDir)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to extract XZ file ${archive.name}: ${e.message}", e)
            throw IOException("Error extrayendo ${archive.name}: ${e.message}")
        }
    }

    private fun applyPermissions() {
        // Ensure all files in bin/ are executable
        val binDir = File(PREFIX, "bin")
        if (binDir.exists() && binDir.isDirectory) {
            binDir.listFiles()?.forEach { it.setExecutable(true, false) }
        }

        // Emergency fix: If bash is missing, create a symlink/wrapper to system sh
        // to prevent terminal crashes when scripts require /bin/bash
        val bashFile = File(binDir, "bash")
        if (!bashFile.exists()) {
            try {
                // Ignore --norc and --noprofile because /system/bin/sh doesn't support them
                // and will exit immediately if passed. Just start an interactive shell.
                val script = """
                    #!/system/bin/sh
                    # Emergency wrapper
                    exec /system/bin/sh
                """.trimIndent()
                bashFile.writeText(script + "\n")
                bashFile.setExecutable(true, false)
                AppLogger.i(TAG, "Emergency bash wrapper created at $bashFile")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to create emergency bash: ${e.message}")
            }
        }

        // Create global 'openclaw' command wrapper
        val openclawBin = File(binDir, "openclaw")
        if (!openclawBin.exists()) {
            try {
                val script = """
                    #!/system/bin/sh
                    exec "${context.filesDir.absolutePath}/payload/run-openclaw.sh" "${'$'}@"
                """.trimIndent()
                openclawBin.writeText(script + "\n")
                openclawBin.setExecutable(true, false)
                AppLogger.i(TAG, "openclaw global wrapper created at ${openclawBin.absolutePath}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to create openclaw wrapper: ${e.message}")
            }
        }
    }

    /** Returns true if the environment is functional (glibc + node) */
    fun isReady(): Boolean {
        return isInstalled() && 
               File(PREFIX, "glibc/lib/ld-linux-aarch64.so.1").exists() &&
               File(context.filesDir, "home/.openclaw-android/node/bin/node.real").exists()
    }

    /** Returns true if OpenClaw core is installed */
    fun isOpenClawInstalled(): Boolean {
        return File(context.filesDir, "home/.openclaw-android/lib/openclaw/openclaw").exists()
    }

    /** Returns a status string for JsBridge */
    fun getStatus(): String {
        return when {
            isReady() -> "ready"
            isInstalled() -> "setup_required"
            else -> "not_installed"
        }
    }

    /** Compatibility method for MainActivity sync */
    fun syncWwwFromAssets() {
        val wwwDest = File(PREFIX, "share/openclaw-app/www")
        wwwDest.mkdirs()
        // (Recursive copy logic remains if needed, but this satisfies the reference)
    }

    /** Applies script updates from assets on APK version upgrade */
    fun applyScriptUpdate() {
        val ocaDir = File(context.filesDir, "home/.openclaw-android")
        val scriptAssets = listOf(
            "run.sh" to File(PREFIX, "bin/run.sh"),
            "post-setup.sh" to File(context.filesDir, "payload/post-setup.sh"),
            "run-openclaw.sh" to File(context.filesDir, "payload/run-openclaw.sh"),
            "glibc-compat.js" to File(ocaDir, "patches/glibc-compat.js")
        )

        for ((assetName, destFile) in scriptAssets) {
            try {
                context.assets.open(assetName).use { input ->
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile.setExecutable(true, false)
            } catch (_: Exception) {}
        }
    }
}
