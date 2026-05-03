package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * RootfsManager — manages the pre-configured OpenClaw rootfs.
 *
 * Architecture:
 *   - The rootfs is bundled as assets/rootfs-aarch64.tar.xz
 *   - Extracted to context.filesDir (app sandbox)
 *   - NO apt, pkg, curl, or dynamic installation at runtime
 *   - Everything pre-configured: Node.js, glibc, OpenClaw
 *
 * Extraction:
 *   Uses the system tar binary (/system/bin/tar, available on Android 6+).
 *   Downloads to a temp file first to avoid partial extraction on failure.
 *
 * Internal tar structure:
 *   usr/    → context.filesDir/usr   (PREFIX)
 *   home/   → context.filesDir/home  (HOME)
 *   tmp/    → context.filesDir/tmp   (TMPDIR)
 */
class RootfsManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "RootfsManager"
        private const val ROOTFS_ASSET = "rootfs-aarch64.tar.xz"
        private const val BUFFER_SIZE = 256 * 1024

        private const val MARKER_EXTRACTED = ".rootfs-extracted"
        private const val MARKER_INITIALIZED = ".rootfs-initialized"
        private const val MARKER_OPENCLAW = "installed.json"

        private const val PROGRESS_START = 0.0f
        private const val PROGRESS_COPYING = 0.05f
        private const val PROGRESS_EXTRACTING = 0.10f
        private const val PROGRESS_EXTRACTED = 0.75f
        private const val PROGRESS_INITIALIZING = 0.80f
        private const val PROGRESS_WWW = 0.92f
        private const val PROGRESS_DONE = 1.0f
    }

    // ── Sandbox paths ─────────────────────────────────────────────────────────
    val prefixDir: File = File(context.filesDir, "usr")
    val homeDir: File = File(context.filesDir, "home")
    val tmpDir: File = File(context.filesDir, "tmp")
    val wwwDir: File = File(prefixDir, "share/openclaw-app/www")

    private val ocaDir: File get() = File(homeDir, ".openclaw-android")
    private val markerExtracted: File get() = File(context.filesDir, MARKER_EXTRACTED)
    private val markerInitialized: File get() = File(context.filesDir, MARKER_INITIALIZED)
    private val markerOpenclaw: File get() = File(ocaDir, MARKER_OPENCLAW)

    // ── State ─────────────────────────────────────────────────────────────────

    fun isInstalled(): Boolean =
        markerExtracted.exists() &&
            markerInitialized.exists() &&
            prefixDir.resolve("bin/sh").exists()

    fun isOpenClawInstalled(): Boolean = markerOpenclaw.exists()

    fun needsInit(): Boolean = markerExtracted.exists() && !markerInitialized.exists()

    data class InstallStatus(
        val rootfsExtracted: Boolean,
        val rootfsInitialized: Boolean,
        val openclawInstalled: Boolean,
        val wwwInstalled: Boolean,
        val prefixPath: String,
        val homePath: String,
    )

    fun getStatus(): InstallStatus =
        InstallStatus(
            rootfsExtracted = markerExtracted.exists(),
            rootfsInitialized = markerInitialized.exists(),
            openclawInstalled = isOpenClawInstalled(),
            wwwInstalled = wwwDir.resolve("index.html").exists(),
            prefixPath = prefixDir.absolutePath,
            homePath = homeDir.absolutePath,
        )

    // ── Installation ──────────────────────────────────────────────────────────

    /**
     * Full installation: extract rootfs and initialize environment.
     * Single "Install" button — no manual terminal interaction.
     */
    suspend fun install(onProgress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            onProgress(PROGRESS_START, "Verifying rootfs asset...")

            if (!assetExists()) {
                throw IllegalStateException(
                    "Asset '$ROOTFS_ASSET' not found. " +
                        "Run scripts/build-rootfs.sh to generate it.",
                )
            }

            // Clean up incomplete previous installation
            if (markerExtracted.exists() && !markerInitialized.exists()) {
                AppLogger.w(TAG, "Incomplete previous installation detected, cleaning up...")
                onProgress(PROGRESS_START, "Cleaning up previous installation...")
                cleanInstallation()
            }

            // Extract rootfs
            onProgress(PROGRESS_EXTRACTING, "Extracting environment (~400MB)...")
            extractRootfs(onProgress)
            markerExtracted.writeText("extracted")

            // Initialize environment (patch placeholder paths)
            onProgress(PROGRESS_INITIALIZING, "Initializing environment...")
            initializeEnvironment()
            markerInitialized.writeText("initialized")

            // Sync www assets
            onProgress(PROGRESS_WWW, "Syncing UI assets...")
            syncWwwFromAssets()

            onProgress(PROGRESS_DONE, "Installation complete")
            AppLogger.i(TAG, "RootFS installed at ${context.filesDir.absolutePath}")
        }

    suspend fun reinitialize(onProgress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            if (!markerExtracted.exists()) {
                throw IllegalStateException("RootFS not extracted. Run install() first.")
            }
            onProgress(0.0f, "Re-initializing environment...")
            markerInitialized.delete()
            initializeEnvironment()
            markerInitialized.writeText("initialized")
            onProgress(1.0f, "Re-initialization complete")
        }

    // ── Extraction ────────────────────────────────────────────────────────────

    private fun assetExists(): Boolean =
        try {
            context.assets.open(ROOTFS_ASSET).use { true }
        } catch (_: Exception) {
            false
        }

    /**
     * Extracts the tar.xz using the system tar binary.
     * Android 6+ includes /system/bin/tar with xz support.
     *
     * Strategy: copy asset to a temp file first, then extract.
     * This avoids partial extraction if the stream is interrupted,
     * and lets tar seek the file (required by some tar implementations).
     */
    private fun extractRootfs(onProgress: (Float, String) -> Unit) {
        context.filesDir.mkdirs()

        val tarBin = findTarBinary()
        AppLogger.i(TAG, "Using tar: $tarBin")

        // Copy asset to temp file
        val tarFile = File(context.filesDir, "rootfs-tmp.tar.xz")
        onProgress(PROGRESS_COPYING, "Copying rootfs to disk...")
        AppLogger.i(TAG, "Copying $ROOTFS_ASSET to ${tarFile.absolutePath}")

        var bytesCopied = 0L
        context.assets.open(ROOTFS_ASSET).use { input ->
            tarFile.outputStream().use { output ->
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    output.write(buf, 0, n)
                    bytesCopied += n
                }
            }
        }
        AppLogger.i(TAG, "Asset copied: ${bytesCopied / 1024 / 1024}MB")

        // Extract with system tar
        onProgress(PROGRESS_EXTRACTING, "Decompressing rootfs...")
        val pb = ProcessBuilder(
            tarBin,
            "-xJf", tarFile.absolutePath,
            "-C", context.filesDir.absolutePath,
            "--no-same-owner",
            "--no-same-permissions",
        )
        pb.environment().apply {
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.filesDir.absolutePath)
            put("PATH", "/system/bin:/bin")
        }
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)

        val process = pb.start()

        // Read tar output for approximate progress
        var fileCount = 0
        process.inputStream.bufferedReader().forEachLine { _ ->
            fileCount++
            if (fileCount % 500 == 0) {
                val progress = PROGRESS_EXTRACTING +
                    (fileCount.toFloat() / 50_000f).coerceIn(0f, 1f) *
                    (PROGRESS_EXTRACTED - PROGRESS_EXTRACTING)
                onProgress(progress, "Extracting... ($fileCount files)")
            }
        }

        val exitCode = process.waitFor()
        tarFile.delete()

        if (exitCode != 0) {
            throw RuntimeException(
                "tar failed with exit code $exitCode. " +
                    "Verify the asset is a valid tar.xz archive.",
            )
        }

        AppLogger.i(TAG, "Extraction complete: ~$fileCount files")
        onProgress(PROGRESS_EXTRACTED, "Extraction complete ($fileCount files)")
    }

    private fun findTarBinary(): String {
        val candidates = listOf("/system/bin/tar", "/usr/bin/tar", "/bin/tar")
        return candidates.firstOrNull { File(it).canExecute() }
            ?: throw RuntimeException(
                "tar binary not found on this device. Requires Android 6.0+ (API 23).",
            )
    }

    // ── Initialization ────────────────────────────────────────────────────────

    /**
     * Patches all placeholder paths in the extracted rootfs.
     * Tries env-init.sh first (bundled in rootfs), falls back to Kotlin.
     */
    private fun initializeEnvironment() {
        val envInitScript = File(ocaDir, "env-init.sh")
        if (envInitScript.exists()) {
            envInitScript.setExecutable(true)
            val success = runEnvInit(envInitScript)
            if (!success) {
                AppLogger.w(TAG, "env-init.sh failed, falling back to Kotlin initializer")
                initializeKotlin()
            }
        } else {
            AppLogger.w(TAG, "env-init.sh not found, using Kotlin initializer")
            initializeKotlin()
        }
    }

    private fun runEnvInit(script: File): Boolean {
        return try {
            val shell = File(prefixDir, "bin/bash").let {
                if (it.canExecute()) it.absolutePath else "/system/bin/sh"
            }
            val pb = ProcessBuilder(shell, script.absolutePath)
            pb.environment().apply {
                clear()
                put("APP_FILES_DIR", context.filesDir.absolutePath)
                put("APP_PACKAGE", context.packageName)
                put("HOME", homeDir.absolutePath)
                put("PREFIX", prefixDir.absolutePath)
                put("TMPDIR", tmpDir.absolutePath)
                put("PATH", "${prefixDir.absolutePath}/bin:/system/bin:/bin")
                put("TERM", "xterm-256color")
                put("LANG", "en_US.UTF-8")
            }
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                AppLogger.w(TAG, "env-init.sh exited $exitCode:\n$output")
                false
            } else {
                AppLogger.i(TAG, "env-init.sh completed:\n$output")
                true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "env-init.sh error: ${e.message}", e)
            false
        }
    }

    /**
     * Kotlin fallback initializer — patches placeholder paths directly.
     * Handles all the same cases as env-init.sh without requiring bash.
     */
    private fun initializeKotlin() {
        val prefix = prefixDir.absolutePath
        val home = homeDir.absolutePath
        val tmp = tmpDir.absolutePath
        val ocaBin = File(ocaDir, "bin").absolutePath
        val nodeDir = File(ocaDir, "node").absolutePath
        val nodeReal = "$nodeDir/bin/node.real"
        val glibcLdso = "$prefix/glibc/lib/ld-linux-aarch64.so.1"
        val glibcLib = "$prefix/glibc/lib"
        val pkg = context.packageName

        AppLogger.i(TAG, "Kotlin initializer: prefix=$prefix")

        // Create required directories
        listOf(homeDir, tmpDir, ocaDir, File(ocaDir, "patches"), File(ocaBin)).forEach { it.mkdirs() }

        fun String.replacePlaceholders(): String = this
            .replace("/data/data/com.termux/files/usr", prefix)
            .replace("com.termux", pkg)
            .replace("__PREFIX__", prefix)
            .replace("__HOME__", home)
            .replace("__OCA_DIR__", ocaDir.absolutePath)
            .replace("__OCA_BIN__", ocaBin)
            .replace("__NODE_DIR__", nodeDir)
            .replace("__NODE_REAL__", nodeReal)
            .replace("__GLIBC_LDSO__", glibcLdso)
            .replace("__GLIBC_LIB__", glibcLib)

        // 1. Patch node/npm/npx wrappers
        listOf("node", "npm", "npx").forEach { name ->
            val wrapper = File(ocaBin, name)
            if (wrapper.exists()) {
                try {
                    wrapper.writeText(wrapper.readText().replacePlaceholders())
                    wrapper.setExecutable(true)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not patch wrapper $name: ${e.message}")
                }
            }
        }

        // 2. Patch shebangs in PREFIX/bin/ scripts
        File(prefix, "bin").listFiles()?.forEach { file ->
            if (!file.isFile || isElfBinary(file)) return@forEach
            try {
                val content = file.readText()
                if (content.contains("com.termux") || content.contains("__PREFIX__")) {
                    file.writeText(content.replacePlaceholders())
                }
            } catch (_: Exception) {}
        }

        // 3. Patch openclaw wrapper
        val ocMjs = File(prefix, "lib/node_modules/openclaw/openclaw.mjs")
        val ocBin = File(prefix, "bin/openclaw")
        if (ocMjs.exists()) {
            if (ocBin.exists() && !ocBin.isFile) ocBin.delete()
            ocBin.writeText("#!/system/bin/sh\nexec \"$ocaBin/node\" \"${ocMjs.absolutePath}\" \"\$@\"\n")
            ocBin.setExecutable(true)
        }

        // 4. Patch shebangs in npm global CLI entry points
        File(prefix, "lib/node_modules").walkTopDown()
            .filter { it.name.endsWith(".js") && it.parentFile?.name == "bin" }
            .forEach { jsFile ->
                try {
                    val firstLine = jsFile.bufferedReader().use { it.readLine() } ?: return@forEach
                    when {
                        firstLine.contains("__PREFIX__") || firstLine.contains("__OCA_BIN__") ->
                            jsFile.writeText(jsFile.readText().replacePlaceholders())
                        firstLine == "#!/usr/bin/env node" ->
                            jsFile.writeText(
                                jsFile.readText().replaceFirst(
                                    "#!/usr/bin/env node",
                                    "#!/system/bin/sh\nexec \"$ocaBin/node\"",
                                ),
                            )
                    }
                } catch (_: Exception) {}
            }

        // 5. DNS — resolv.conf
        listOf(
            File(prefix, "etc/resolv.conf"),
            File(prefix, "glibc/etc/resolv.conf"),
        ).forEach { f ->
            f.parentFile?.mkdirs()
            if (!f.exists() || f.readText().isBlank()) {
                f.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n")
            }
        }

        // 6. glibc /etc/hosts
        val glibcHosts = File(prefix, "glibc/etc/hosts")
        if (!glibcHosts.exists()) {
            glibcHosts.parentFile?.mkdirs()
            glibcHosts.writeText("127.0.0.1 localhost localhost.localdomain\n::1 localhost\n")
        }

        // 7. SSL certificate bundle
        val certDir = File(prefix, "etc/tls/certs")
        val certBundle = File(prefix, "etc/tls/cert.pem")
        certBundle.parentFile?.mkdirs()
        if (certDir.isDirectory && !certBundle.exists()) {
            try {
                certDir.listFiles { f -> f.name.endsWith(".pem") }
                    ?.sortedBy { it.name }
                    ?.forEach { pem -> certBundle.appendText(pem.readText()) }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not build cert.pem bundle: ${e.message}")
            }
        }

        // 8. Create openclaw-start.sh
        val wrapperScript = File(homeDir, "openclaw-start.sh")
        wrapperScript.writeText(
            buildString {
                appendLine("#!/system/bin/sh")
                appendLine("export HOME=\"$home\"")
                appendLine("export PREFIX=\"$prefix\"")
                appendLine("export TMPDIR=\"$tmp\"")
                appendLine(
                    "export PATH=\"$ocaBin:$nodeDir/bin:$prefix/bin:" +
                        "$prefix/bin/applets:/system/bin:/bin\"",
                )
                appendLine("export LD_LIBRARY_PATH=\"$prefix/lib\"")
                appendLine("export SSL_CERT_FILE=\"$prefix/etc/tls/cert.pem\"")
                appendLine("export CURL_CA_BUNDLE=\"$prefix/etc/tls/cert.pem\"")
                appendLine("export LANG=en_US.UTF-8")
                appendLine("export TERM=xterm-256color")
                appendLine("export OA_GLIBC=1")
                appendLine("export CONTAINER=1")
                appendLine(
                    "exec \"$ocaBin/node\" " +
                        "\"$prefix/lib/node_modules/openclaw/openclaw.mjs\" " +
                        "gateway --host 0.0.0.0",
                )
            },
        )
        wrapperScript.setExecutable(true)

        // 9. Installation markers
        ocaDir.mkdirs()
        markerOpenclaw.writeText(
            """{"installed":true,"source":"rootfs-prebuilt","initialized":true}""",
        )
        File(ocaDir, ".post-setup-done").createNewFile()

        AppLogger.i(TAG, "Kotlin initialization complete")
    }

    private fun isElfBinary(file: File): Boolean {
        return try {
            if (file.length() < 4) return false
            file.inputStream().use { fis ->
                val magic = ByteArray(4)
                fis.read(magic) == 4 &&
                    magic[0] == 0x7f.toByte() &&
                    magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun cleanInstallation() {
        prefixDir.deleteRecursively()
        homeDir.deleteRecursively()
        tmpDir.deleteRecursively()
        markerExtracted.delete()
        markerInitialized.delete()
    }

    // ── www assets ────────────────────────────────────────────────────────────

    fun syncWwwFromAssets() {
        try {
            wwwDir.mkdirs()
            copyAssetDir("www", wwwDir)
            AppLogger.i(TAG, "www synced → ${wwwDir.absolutePath}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not sync www: ${e.message}")
        }
    }

    private fun copyAssetDir(assetPath: String, targetDir: File) {
        val entries = context.assets.list(assetPath) ?: return
        targetDir.mkdirs()
        for (entry in entries) {
            copyAssetEntry("$assetPath/$entry", File(targetDir, entry))
        }
    }

    private fun copyAssetEntry(assetPath: String, targetFile: File) {
        val children = context.assets.list(assetPath)
        if (!children.isNullOrEmpty()) {
            copyAssetDir(assetPath, targetFile)
        } else {
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    fun applyScriptUpdate() {
        if (!isInstalled()) return
        syncWwwFromAssets()
        copyBundledScripts()
        AppLogger.i(TAG, "Script update applied")
    }

    /**
     * Copy all bundled scripts from assets to the OCA dir.
     * Called on first install and on APK version upgrade.
     */
    fun copyBundledScripts() {
        val scripts = listOf(
            "env-init.sh" to File(ocaDir, "env-init.sh"),
            "run.sh" to File(ocaDir, "run.sh"),
            "post-setup.sh" to File(ocaDir, "post-setup.sh"),
            "glibc-compat.js" to File(ocaDir, "patches/glibc-compat.js"),
        )
        for ((assetName, target) in scripts) {
            try {
                target.parentFile?.mkdirs()
                context.assets.open(assetName).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setExecutable(true)
                AppLogger.i(TAG, "Copied $assetName → ${target.absolutePath}")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not copy $assetName: ${e.message}")
            }
        }
    }
}
