package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * PayloadManager — manages the self-contained OpenClaw payload.
 *
 * Architecture:
 *   - Payload is bundled as assets/payload/ (directory of files)
 *   - Extracted to context.filesDir/payload/ on first run
 *   - post-setup.sh runs offline to configure glibc, certs, Node.js, OpenClaw
 *   - NO network access required after APK installation
 *   - NO Termux dependency at runtime
 *
 * Payload structure (assets/payload/):
 *   glibc-aarch64.tar.xz   — glibc runtime (ld.so, libc, libstdc++, etc.)
 *   certs/cert.pem          — CA certificate bundle
 *   bin/node                — glibc-wrapped node launcher (placeholder paths)
 *   bin/npm                 — npm wrapper
 *   bin/npx                 — npx wrapper
 *   lib/node/bin/node.real  — actual Node.js ELF binary
 *   lib/node/lib/           — node_modules (npm, npx)
 *   lib/openclaw/           — OpenClaw package
 *   patches/glibc-compat.js — Node.js Android compatibility shim
 *   post-setup.sh           — offline setup script
 *   run-openclaw.sh         — runtime launcher
 *   PAYLOAD_CHECKSUM.sha256 — integrity manifest
 *
 * Sandbox layout (all under context.filesDir):
 *   payload/    — extracted payload (source of truth)
 *   usr/        — PREFIX (glibc, certs, bin, lib)
 *   home/       — HOME (.openclaw-android/bin, node, patches)
 *   tmp/        — TMPDIR
 */
class PayloadManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "PayloadManager"
        private const val PAYLOAD_ASSET_DIR = "payload"
        private const val BUFFER_SIZE = 256 * 1024

        // Markers
        private const val MARKER_EXTRACTED = ".payload-extracted"
        private const val MARKER_SETUP_DONE = ".post-setup-done"

        // Progress milestones
        private const val PROGRESS_START = 0.0f
        private const val PROGRESS_LISTING = 0.02f
        private const val PROGRESS_EXTRACTING = 0.05f
        private const val PROGRESS_EXTRACTED = 0.50f
        private const val PROGRESS_SETUP_START = 0.55f
        private const val PROGRESS_SETUP_DONE = 0.95f
        private const val PROGRESS_DONE = 1.0f
    }

    // ── Sandbox paths ─────────────────────────────────────────────────────────
    val payloadDir: File = File(context.filesDir, "payload")
    val prefixDir: File = File(context.filesDir, "usr")
    val homeDir: File = File(context.filesDir, "home")
    val tmpDir: File = File(context.filesDir, "tmp")

    private val ocaDir: File get() = File(homeDir, ".openclaw-android")
    private val markerExtracted: File get() = File(context.filesDir, MARKER_EXTRACTED)
    private val markerSetupDone: File get() = File(ocaDir, MARKER_SETUP_DONE)
    private val markerInstalled: File get() = File(ocaDir, "installed.json")

    // ── State queries ─────────────────────────────────────────────────────────

    /** True if the payload has been extracted to filesDir/payload/ */
    fun isPayloadExtracted(): Boolean =
        markerExtracted.exists() && payloadDir.resolve("glibc-aarch64.tar.xz").exists()

    /** True if post-setup.sh has completed successfully */
    fun isSetupDone(): Boolean = markerSetupDone.exists()

    /** True if OpenClaw is fully installed and ready to run */
    fun isReady(): Boolean =
        isSetupDone() &&
            prefixDir.resolve("glibc/lib/ld-linux-aarch64.so.1").exists() &&
            ocaDir.resolve("node/bin/node.real").exists()

    /** True if OpenClaw package is installed */
    fun isOpenClawInstalled(): Boolean = markerInstalled.exists()

    data class PayloadStatus(
        val payloadExtracted: Boolean,
        val setupDone: Boolean,
        val glibcReady: Boolean,
        val nodeReady: Boolean,
        val openclawReady: Boolean,
        val certsReady: Boolean,
        val prefixPath: String,
        val homePath: String,
    )

    fun getStatus(): PayloadStatus =
        PayloadStatus(
            payloadExtracted = isPayloadExtracted(),
            setupDone = isSetupDone(),
            glibcReady = prefixDir.resolve("glibc/lib/ld-linux-aarch64.so.1").exists(),
            nodeReady = ocaDir.resolve("node/bin/node.real").exists(),
            openclawReady = isOpenClawInstalled(),
            certsReady = prefixDir.resolve("etc/tls/cert.pem").let { it.exists() && it.length() > 0 },
            prefixPath = prefixDir.absolutePath,
            homePath = homeDir.absolutePath,
        )

    // ── Payload asset check ───────────────────────────────────────────────────

    /** Returns true if assets/payload/ exists and contains the glibc archive */
    fun hasPayloadAsset(): Boolean =
        try {
            context.assets.list(PAYLOAD_ASSET_DIR)?.contains("glibc-aarch64.tar.xz") == true
        } catch (_: Exception) {
            false
        }

    // ── Full installation ─────────────────────────────────────────────────────

    /**
     * Full installation flow:
     *   1. Extract payload assets → filesDir/payload/
     *   2. Verify checksums
     *   3. Run post-setup.sh (offline, configures glibc/certs/node/openclaw)
     */
    suspend fun install(onProgress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            onProgress(PROGRESS_START, "Checking payload...")

            if (!hasPayloadAsset()) {
                throw IllegalStateException(
                    "Payload asset not found in APK. " +
                        "Run build-payload.sh and rebuild the APK.",
                )
            }

            // Step 1: Extract payload
            if (!isPayloadExtracted()) {
                onProgress(PROGRESS_LISTING, "Listing payload assets...")
                val assets = listPayloadAssets()
                AppLogger.i(TAG, "Payload assets: ${assets.size} files")

                onProgress(PROGRESS_EXTRACTING, "Extracting payload (${assets.size} files)...")
                extractPayloadAssets(assets, onProgress)
                markerExtracted.writeText("extracted")
                AppLogger.i(TAG, "Payload extracted to ${payloadDir.absolutePath}")
            } else {
                AppLogger.i(TAG, "Payload already extracted — skipping")
            }

            onProgress(PROGRESS_EXTRACTED, "Verifying payload integrity...")
            verifyChecksums()

            // Step 2: Run post-setup.sh
            if (!isSetupDone()) {
                onProgress(PROGRESS_SETUP_START, "Running offline setup...")
                runPostSetup(onProgress)
            } else {
                AppLogger.i(TAG, "Post-setup already done — skipping")
            }

            onProgress(PROGRESS_DONE, "Installation complete")
        }

    // ── Asset extraction ──────────────────────────────────────────────────────

    private fun listPayloadAssets(): List<String> {
        val result = mutableListOf<String>()
        listAssetsRecursive(PAYLOAD_ASSET_DIR, "", result)
        return result
    }

    private fun listAssetsRecursive(
        assetDir: String,
        relativePath: String,
        result: MutableList<String>,
    ) {
        val entries = context.assets.list(assetDir) ?: return
        for (entry in entries) {
            val assetPath = "$assetDir/$entry"
            val relPath = if (relativePath.isEmpty()) entry else "$relativePath/$entry"
            val children = context.assets.list(assetPath)
            if (children != null && children.isNotEmpty()) {
                // Directory — recurse
                listAssetsRecursive(assetPath, relPath, result)
            } else {
                // File
                result.add(relPath)
            }
        }
    }

    private fun extractPayloadAssets(
        assets: List<String>,
        onProgress: (Float, String) -> Unit,
    ) {
        payloadDir.mkdirs()
        val total = assets.size
        var done = 0

        for (relPath in assets) {
            val assetPath = "$PAYLOAD_ASSET_DIR/$relPath"
            val destFile = File(payloadDir, relPath)

            destFile.parentFile?.mkdirs()

            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    copyStream(input, output)
                }
            }

            // Set executable bit on scripts and binaries
            if (shouldBeExecutable(relPath)) {
                destFile.setExecutable(true, false)
            }

            done++
            val progress = PROGRESS_EXTRACTING + (PROGRESS_EXTRACTED - PROGRESS_EXTRACTING) * done / total
            if (done % 10 == 0 || done == total) {
                onProgress(progress, "Extracting: $relPath ($done/$total)")
            }
        }

        AppLogger.i(TAG, "Extracted $total payload files to ${payloadDir.absolutePath}")
    }

    private fun shouldBeExecutable(relPath: String): Boolean {
        val name = File(relPath).name
        return name.endsWith(".sh") ||
            name.endsWith(".real") ||
            relPath.startsWith("bin/") ||
            name == "node" ||
            name == "npm" ||
            name == "npx" ||
            name == "ld-linux-aarch64.so.1"
    }

    private fun copyStream(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
    }

    // ── Checksum verification ─────────────────────────────────────────────────

    /**
     * Verifies payload integrity against PAYLOAD_CHECKSUM.sha256.
     * Non-fatal: logs warnings but does not abort installation.
     */
    private fun verifyChecksums() {
        val checksumFile = File(payloadDir, "PAYLOAD_CHECKSUM.sha256")
        if (!checksumFile.exists()) {
            AppLogger.w(TAG, "No checksum file found — skipping integrity check")
            return
        }

        var verified = 0
        var failed = 0

        checksumFile.forEachLine { line ->
            val parts = line.trim().split("  ", limit = 2)
            if (parts.size != 2) return@forEachLine

            val expectedHash = parts[0]
            val relPath = parts[1]
            val file = File(payloadDir, relPath)

            if (!file.exists()) {
                AppLogger.w(TAG, "Checksum: missing file $relPath")
                failed++
                return@forEachLine
            }

            val actualHash = sha256(file)
            if (actualHash == expectedHash) {
                verified++
            } else {
                AppLogger.w(TAG, "Checksum MISMATCH: $relPath (expected=$expectedHash, got=$actualHash)")
                failed++
            }
        }

        AppLogger.i(TAG, "Checksum verification: $verified OK, $failed failed")
        if (failed > 0) {
            AppLogger.w(TAG, "Some files failed checksum — payload may be incomplete")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Post-setup execution ──────────────────────────────────────────────────

    /**
     * Runs post-setup.sh from the extracted payload.
     * The script is fully offline — no network access required.
     */
    private suspend fun runPostSetup(onProgress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            val postSetupScript = File(payloadDir, "post-setup.sh")
            if (!postSetupScript.exists()) {
                // Fall back to the one in assets (copied by extractPayloadAssets)
                throw IllegalStateException("post-setup.sh not found in payload: ${postSetupScript.absolutePath}")
            }

            postSetupScript.setExecutable(true, false)

            val env = buildPostSetupEnv()
            AppLogger.i(TAG, "Running post-setup.sh with env: $env")

            onProgress(PROGRESS_SETUP_START, "Setting up glibc...")

            val result = CommandRunner.runStreaming(
                command = postSetupScript.absolutePath,
                env = env,
                workDir = payloadDir,
                onOutput = { line ->
                    AppLogger.i(TAG, "[post-setup] $line")
                    // Parse progress from log lines
                    val progress = when {
                        line.contains("Step 1/5") -> PROGRESS_SETUP_START + 0.05f
                        line.contains("Step 2/5") -> PROGRESS_SETUP_START + 0.10f
                        line.contains("Step 3/5") -> PROGRESS_SETUP_START + 0.20f
                        line.contains("Step 4/5") -> PROGRESS_SETUP_START + 0.30f
                        line.contains("Step 5/5") -> PROGRESS_SETUP_START + 0.35f
                        line.contains("Validating runtime") -> PROGRESS_SETUP_START + 0.38f
                        else -> null
                    }
                    if (progress != null) {
                        onProgress(progress, line.trim())
                    }
                },
            )

            if (result.exitCode != 0) {
                AppLogger.e(TAG, "post-setup.sh failed (exit ${result.exitCode}): ${result.stderr}")
                throw RuntimeException(
                    "Post-setup failed (exit ${result.exitCode}).\n" +
                        "stderr: ${result.stderr.take(500)}",
                )
            }

            onProgress(PROGRESS_SETUP_DONE, "Setup complete")
            AppLogger.i(TAG, "post-setup.sh completed successfully")
        }

    private fun buildPostSetupEnv(): Map<String, String> {
        val filesDir = context.filesDir
        val prefix = prefixDir.absolutePath
        val home = homeDir.absolutePath
        val tmp = tmpDir.also { it.mkdirs() }.absolutePath

        return buildMap {
            put("APP_FILES_DIR", filesDir.absolutePath)
            put("APP_PACKAGE", context.packageName)
            put("PREFIX", prefix)
            put("HOME", home)
            put("TMPDIR", tmp)
            put("PAYLOAD_DIR", payloadDir.absolutePath)

            // PATH: system shell tools + any existing prefix bins
            put("PATH", "$prefix/bin:/system/bin:/bin")

            // Minimal LD_LIBRARY_PATH — glibc will be set up by post-setup.sh
            put("LD_LIBRARY_PATH", "$prefix/glibc/lib:$prefix/lib")

            // SSL — may be empty until post-setup.sh installs certs
            put("SSL_CERT_FILE", "$prefix/etc/tls/cert.pem")
            put("CURL_CA_BUNDLE", "$prefix/etc/tls/cert.pem")

            // Android system info
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            // Locale
            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")
        }
    }

    // ── Sync www assets ───────────────────────────────────────────────────────

    /**
     * Syncs the www/ assets from APK to the prefix www directory.
     * Called on every app launch to pick up UI updates.
     */
    fun syncWwwFromAssets() {
        val wwwDest = prefixDir.resolve("share/openclaw-app/www")
        wwwDest.mkdirs()

        try {
            val assets = context.assets.list("www") ?: return
            syncAssetsDir("www", wwwDest)
            AppLogger.i(TAG, "www assets synced to ${wwwDest.absolutePath}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "www sync failed: ${e.message}")
        }
    }

    private fun syncAssetsDir(assetDir: String, destDir: File) {
        destDir.mkdirs()
        val entries = context.assets.list(assetDir) ?: return
        for (entry in entries) {
            val assetPath = "$assetDir/$entry"
            val destFile = File(destDir, entry)
            val children = context.assets.list(assetPath)
            if (children != null && children.isNotEmpty()) {
                syncAssetsDir(assetPath, destFile)
            } else {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        copyStream(input, output)
                    }
                }
            }
        }
    }

    // ── Script update ─────────────────────────────────────────────────────────

    /**
     * Applies script updates from assets on APK version upgrade.
     * Replaces run.sh, post-setup.sh, run-openclaw.sh, glibc-compat.js.
     */
    fun applyScriptUpdate() {
        val scriptAssets = listOf(
            "run.sh" to prefixDir.resolve("bin/run.sh"),
            "post-setup.sh" to payloadDir.resolve("post-setup.sh"),
            "run-openclaw.sh" to payloadDir.resolve("run-openclaw.sh"),
            "glibc-compat.js" to ocaDir.resolve("patches/glibc-compat.js"),
        )

        for ((assetName, destFile) in scriptAssets) {
            try {
                context.assets.open(assetName).use { input ->
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { output ->
                        copyStream(input, output)
                    }
                    destFile.setExecutable(true, false)
                }
                AppLogger.i(TAG, "Script updated: $assetName → ${destFile.absolutePath}")
            } catch (_: Exception) {
                // Asset may not exist — non-fatal
            }
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    /**
     * Resets the installation state so install() will run again.
     * Does NOT delete the payload archive (avoids re-extraction).
     */
    fun resetSetupMarker() {
        markerSetupDone.delete()
        AppLogger.i(TAG, "Post-setup marker cleared — will re-run on next install()")
    }

    /** Full reset: deletes all extracted files and markers. */
    fun fullReset() {
        markerExtracted.delete()
        markerSetupDone.delete()
        prefixDir.deleteRecursively()
        homeDir.deleteRecursively()
        tmpDir.deleteRecursively()
        AppLogger.i(TAG, "Full reset complete")
    }
}
