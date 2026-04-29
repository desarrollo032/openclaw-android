package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages Termux bootstrap download, extraction, and configuration.
 * Phase 0: extracts from assets. Phase 1+: downloads from network.
 * Based on AnyClaw BootstrapInstaller.kt pattern (§2.2.1).
 */
class BootstrapManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "BootstrapManager"
        private const val PROGRESS_PREPARING = 0.05f
        private const val PROGRESS_DOWNLOADING = 0.10f
        private const val PROGRESS_EXTRACTING = 0.30f
        private const val PROGRESS_CONFIGURING = 0.60f
        private const val ELF_MAGIC_SIZE = 4
        private val ELF_SIGNATURE = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        private const val SYMLINK_SEPARATOR = "←"
        private const val SYMLINK_PARTS_COUNT = 2
    }

    val prefixDir = File(context.filesDir, "usr")
    val homeDir = File(context.filesDir, "home")
    val tmpDir = File(context.filesDir, "tmp")
    val wwwDir = File(prefixDir, "share/openclaw-app/www")
    private val stagingDir = File(context.filesDir, "usr-staging")

    // Real Termux paths for detection — independent of app package name
    private val termuxHome = File(CommandRunner.TERMUX_HOME)
    private val termuxPrefix = File(CommandRunner.TERMUX_PREFIX)
    private val openclawDir = File(CommandRunner.OPENCLAW_DIR)

    /**
     * Bootstrap is installed if the app-local sh binary exists.
     * Only checks the app sandbox — never Termux paths.
     * Termux detection is kept separately for legacy migration only.
     */
    fun isInstalled(): Boolean = prefixDir.resolve("bin/sh").exists()

    /**
     * True if Termux is installed on the device (legacy detection only).
     * Used only to decide whether to offer migration, never for process execution.
     */
    fun isTermuxInstalled(): Boolean = termuxPrefix.resolve("bin/sh").exists()

    /**
     * OpenClaw is fully installed when installed.json marker exists in the app sandbox.
     * Only checks app-local paths — never Termux paths.
     */
    fun isOpenClawInstalled(): Boolean {
        val localMarker = File(homeDir, ".openclaw-android/installed.json")
        return localMarker.exists()
    }

    fun needsPostSetup(): Boolean {
        // Verificar marker en rutas locales de la app (sandbox real)
        val markerLocal = File(homeDir, ".openclaw-android/.post-setup-done")
        // Verificar también en la ruta de Termux (si está instalado)
        val markerTermux = File(openclawDir, ".post-setup-done")
        if (!isInstalled() || markerLocal.exists() || markerTermux.exists()) return false

        // post-setup.sh requires assets/payload/ to be extracted first.
        // If the payload directory doesn't exist, running post-setup.sh will always
        // fail with "Payload directory not found". In that case, write the markers
        // directly so the bootstrap path is considered complete without running the script.
        val payloadDir = File(homeDir.parentFile, "payload")
        if (!payloadDir.isDirectory) {
            // Bootstrap is installed but no payload — write markers to unblock the app.
            // The bootstrap itself is already functional; post-setup.sh is only needed
            // for the payload-based install path (PayloadManager).
            writeBootstrapCompletionMarkers()
            return false
        }

        return true
    }

    /**
     * Writes the post-setup-done and installed.json markers for the bootstrap path.
     * Called when the bootstrap is installed but no payload is available to run
     * post-setup.sh. This unblocks the app without running a script that would fail.
     */
    private fun writeBootstrapCompletionMarkers() {
        val ocaDir = File(homeDir, ".openclaw-android")
        ocaDir.mkdirs()
        val markerDone = File(ocaDir, ".post-setup-done")
        val markerInstalled = File(ocaDir, "installed.json")
        try {
            if (!markerDone.exists()) {
                markerDone.createNewFile()
                AppLogger.i(TAG, "Bootstrap completion marker written: ${markerDone.absolutePath}")
            }
            if (!markerInstalled.exists()) {
                markerInstalled.writeText("""{"installed":true,"source":"bootstrap","initialized":true}""")
                AppLogger.i(TAG, "Bootstrap installed.json written: ${markerInstalled.absolutePath}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not write bootstrap completion markers: ${e.message}")
        }
    }

    val postSetupScript: File
        get() {
            // Preferir el script en el directorio local de la app (sandbox real)
            val localScript = File(homeDir, ".openclaw-android/post-setup.sh")
            if (localScript.exists()) return localScript
            // Fallback: ruta de Termux
            val termuxScript = File(openclawDir, "post-setup.sh")
            return if (termuxScript.exists()) termuxScript else localScript
        }

    data class SetupStatus(
        val bootstrapInstalled: Boolean,
        val runtimeInstalled: Boolean,
        val wwwInstalled: Boolean,
        val platformInstalled: Boolean,
    )

    fun getStatus(): SetupStatus {
        val nodeExists = prefixDir.resolve("bin/node").exists()
        val postSetupDone =
            File(homeDir, ".openclaw-android/.post-setup-done").exists() ||
                isOpenClawInstalled()
        return SetupStatus(
            bootstrapInstalled = isInstalled(),
            runtimeInstalled = nodeExists,
            wwwInstalled = wwwDir.resolve("index.html").exists(),
            platformInstalled = postSetupDone,
        )
    }

    /**
     * Full setup flow. Reports progress via callback (0.0–1.0).
     */
    suspend fun startSetup(onProgress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            // Clean up any incomplete previous attempt before starting
            if (stagingDir.exists()) {
                AppLogger.i(TAG, "Removing incomplete staging dir from previous attempt")
                stagingDir.deleteRecursively()
            }
            if (isInstalled()) {
                // Bootstrap exists but setup is incomplete — wipe and reinstall
                AppLogger.i(TAG, "Incomplete bootstrap detected, reinstalling...")
                prefixDir.deleteRecursively()
            }

            // Step 1: Download or extract bootstrap
            onProgress(PROGRESS_PREPARING, "Preparing bootstrap...")
            val zipStream = getBootstrapStream(onProgress)

            // Step 2: Extract bootstrap
            onProgress(PROGRESS_EXTRACTING, "Extracting bootstrap...")
            extractBootstrap(zipStream)

            // Step 3: Fix paths and configure
            onProgress(PROGRESS_CONFIGURING, "Configuring environment...")
            fixTermuxPaths(stagingDir)
            configureApt(stagingDir)

            // Step 4: Atomic rename
            stagingDir.renameTo(prefixDir)
            setupDirectories()
            copyAssetScripts()
            syncWwwFromAssets()
            setupTermuxExec()

            // Step 5: Create wrapper script and write installed marker
            CommandRunner.createWrapperScript(context.filesDir)
            CommandRunner.writeInstalledMarker(context.filesDir)

            onProgress(1f, "Setup complete")
        }

    // --- Bootstrap source ---

    private suspend fun getBootstrapStream(onProgress: (Float, String) -> Unit): InputStream {
        // Phase 0: Try assets first
        try {
            return context.assets.open("bootstrap-aarch64.zip")
        } catch (_: Exception) {
            // Phase 1: Download from network
        }

        onProgress(PROGRESS_DOWNLOADING, "Downloading bootstrap...")
        val url = UrlResolver(context).getBootstrapUrl()
        return URL(url).openStream()
    }

    // --- Extraction ---

    private fun extractBootstrap(inputStream: InputStream) {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                processZipEntry(zip, entry)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun processZipEntry(
        zip: ZipInputStream,
        entry: java.util.zip.ZipEntry,
    ) {
        if (entry.name == "SYMLINKS.txt") {
            processSymlinks(zip, stagingDir)
        } else if (!entry.isDirectory) {
            val file = File(stagingDir, entry.name)
            file.parentFile?.mkdirs()
            file.outputStream().use { out -> zip.copyTo(out) }
            markExecutableIfNeeded(file, entry.name)
        }
    }

    private fun markExecutableIfNeeded(
        file: File,
        name: String,
    ) {
        val knownExecutable =
            name.startsWith("bin/") ||
                name.startsWith("libexec/") ||
                name.startsWith("lib/apt/") ||
                name.startsWith("lib/bash/") ||
                name.endsWith(".so") ||
                name.contains(".so.")
        if (knownExecutable) {
            file.setExecutable(true)
        } else if (file.length() > ELF_MAGIC_SIZE && isElfBinary(file)) {
            file.setExecutable(true)
        }
    }

    private fun isElfBinary(file: File): Boolean =
        try {
            file.inputStream().use { fis ->
                val magic = ByteArray(ELF_MAGIC_SIZE)
                fis.read(magic) == ELF_MAGIC_SIZE &&
                    magic.contentEquals(ELF_SIGNATURE)
            }
        } catch (_: Exception) {
            false
        }

    /**
     * Process SYMLINKS.txt: each line is "target←linkpath".
     * Replace com.termux paths with our package name.
     */
    private fun processSymlinks(
        zip: ZipInputStream,
        targetDir: File,
    ) {
        val content = zip.bufferedReader().readText()
        val ourPackage = context.packageName
        content
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(SYMLINK_SEPARATOR)
                if (parts.size == SYMLINK_PARTS_COUNT) parts else null
            }.forEach { parts ->
                val symlinkTarget = parts[0].trim().replace("com.termux", ourPackage)
                val symlinkPath = parts[1].trim()
                val linkFile = File(targetDir, symlinkPath)
                linkFile.parentFile?.mkdirs()
                try {
                    Os.symlink(symlinkTarget, linkFile.absolutePath)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to create symlink: $symlinkPath -> $symlinkTarget", e)
                }
            }
    }

    // --- Path fixing (§2.2.2) ---

    private fun fixTermuxPaths(dir: File) {
        val ourPackage = context.packageName
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath

        // Fix dpkg status database
        fixTextFile(dir.resolve("var/lib/dpkg/status"), oldPrefix, newPrefix)

        // Fix dpkg info files
        val dpkgInfoDir = dir.resolve("var/lib/dpkg/info")
        if (dpkgInfoDir.isDirectory) {
            dpkgInfoDir.listFiles()?.filter { it.name.endsWith(".list") }?.forEach { file ->
                fixTextFile(file, "com.termux", ourPackage)
            }
        }

        // Fix git scripts shebangs
        val gitCoreDir = dir.resolve("libexec/git-core")
        if (gitCoreDir.isDirectory) {
            gitCoreDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.contains(".")) {
                    fixTextFile(file, oldPrefix, newPrefix)
                }
            }
        }

        // Fix shebangs in bin/ scripts (e.g. pkg, apt, termux-* helpers)
        // These have hardcoded /data/data/com.termux/... shebangs that break
        // when the app package name differs (e.g. com.openclaw.android.debug).
        val binDir = dir.resolve("bin")
        if (binDir.isDirectory) {
            binDir.listFiles()?.forEach { file ->
                if (file.isFile && !isElfBinary(file)) {
                    fixTextFile(file, "com.termux", ourPackage)
                }
            }
        }
    }

    private fun fixTextFile(
        file: File,
        oldText: String,
        newText: String,
    ) {
        if (!file.exists() || !file.isFile) return
        try {
            val content = file.readText()
            if (content.contains(oldText)) {
                file.writeText(content.replace(oldText, newText))
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to fix paths in ${file.name}", e)
        }
    }

    // --- apt configuration (§2.2.3) ---

    private fun configureApt(dir: File) {
        val prefix = prefixDir.absolutePath
        val ourPackage = context.packageName

        // sources.list: solo reemplazar el nombre del paquete, mantener HTTPS
        // NO degradar a HTTP — los certificados se instalan en la fase 0 del script
        val sourcesList = dir.resolve("etc/apt/sources.list")
        if (sourcesList.exists()) {
            sourcesList.writeText(
                sourcesList
                    .readText()
                    .replace("com.termux", ourPackage),
                // Nota: NO reemplazar https:// por http:// — los certs se activan en post-setup.sh
            )
        }

        // apt.conf: full rewrite with correct paths
        val aptConf = dir.resolve("etc/apt/apt.conf")
        aptConf.parentFile?.mkdirs()
        // Create directories needed by apt and dpkg
        dir.resolve("etc/apt/apt.conf.d").mkdirs()
        dir.resolve("etc/apt/preferences.d").mkdirs()
        dir.resolve("etc/dpkg/dpkg.cfg.d").mkdirs()
        dir.resolve("var/cache/apt").mkdirs()
        dir.resolve("var/log/apt").mkdirs()
        aptConf.writeText(
            """
            Dir "$prefix/";
            Dir::State "var/lib/apt/";
            Dir::State::status "var/lib/dpkg/status";
            Dir::Cache "var/cache/apt/";
            Dir::Log "var/log/apt/";
            Dir::Etc "etc/apt/";
            Dir::Etc::SourceList "etc/apt/sources.list";
            Dir::Etc::SourceParts "";
            Dir::Bin::dpkg "$prefix/bin/dpkg";
            Dir::Bin::Methods "$prefix/lib/apt/methods/";
            Dir::Bin::apt-key "$prefix/bin/apt-key";
            Dpkg::Options:: "--force-configure-any";
            Dpkg::Options:: "--force-bad-path";
            Dpkg::Options:: "--instdir=$prefix";
            Dpkg::Options:: "--admindir=$prefix/var/lib/dpkg";
            Acquire::AllowInsecureRepositories "true";
            APT::Get::AllowUnauthenticated "true";
            """.trimIndent(),
        )

        // Configurar resolv.conf para DNS en el sandbox de la app
        // Sin esto: "Could not resolve host" en apt/curl dentro del entorno
        val resolvConf = dir.resolve("etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        if (!resolvConf.exists() || resolvConf.readText().isBlank()) {
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n")
            AppLogger.i(TAG, "resolv.conf configured with Google/Cloudflare DNS")
        }

        // Configurar resolv.conf para glibc (Node.js lo usa para DNS)
        val glibcEtc = dir.resolve("glibc/etc")
        glibcEtc.mkdirs()
        val glibcResolv = glibcEtc.resolve("resolv.conf")
        if (!glibcResolv.exists()) {
            glibcResolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
    }

    // --- Setup helpers ---

    private fun setupDirectories() {
        homeDir.mkdirs()
        tmpDir.mkdirs()
        wwwDir.mkdirs()
        File(homeDir, ".openclaw-android/patches").mkdirs()

        // Configurar resolv.conf en el prefix de la app para DNS
        // Necesario porque el sandbox de Android no hereda /etc/resolv.conf del sistema
        val resolvConf = File(prefixDir, "etc/resolv.conf")
        if (!resolvConf.exists() || resolvConf.readText().isBlank()) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n")
            AppLogger.i(TAG, "resolv.conf initialized at ${resolvConf.absolutePath}")
        }

        // resolv.conf para glibc (Node.js)
        val glibcResolv = File(prefixDir, "glibc/etc/resolv.conf")
        if (!glibcResolv.exists()) {
            glibcResolv.parentFile?.mkdirs()
            glibcResolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
    }

    private fun setupTermuxExec() {
        // libtermux-exec.so is included in bootstrap.
        // It intercepts execve() to rewrite /data/data/com.termux paths (§2.2.4).
        // However, it does NOT intercept open()/opendir() calls, so binaries with
        // hardcoded config paths (dpkg, bash) need wrapper scripts.
        AppLogger.i(TAG, "Bootstrap installed at ${prefixDir.absolutePath}")

        // Create dpkg wrapper that handles confdir permission errors.
        // The bootstrap dpkg has /data/data/com.termux/.../etc/dpkg/ hardcoded.
        // Since libtermux-exec only rewrites execve() paths, not open() paths,
        // dpkg fails on opendir() of the old com.termux config directory.
        // The wrapper captures stderr and returns success if confdir is the only error.
        val dpkgBin = File(prefixDir, "bin/dpkg")
        val dpkgReal = File(prefixDir, "bin/dpkg.real")
        if (dpkgBin.exists() && (!dpkgReal.exists() || !dpkgBin.readText().contains("export PATH"))) {
            if (!dpkgReal.exists()) dpkgBin.renameTo(dpkgReal)
            val d = "$" // dollar sign for shell script
            val realPath = dpkgReal.absolutePath
            val wrapperContent = """#!/bin/bash
# dpkg wrapper: set PATH so dpkg can find sh, tar, rm, dpkg-deb etc.
# Filters confdir errors from hardcoded com.termux paths without
# suppressing real dpkg fatal errors (exit code 2).
export PATH="$realPath/../:$realPath/../applets:${d}PATH"
_stderr_tmp="${d}(mktemp 2>/dev/null || echo /tmp/dpkg-wrap-err)"
"$realPath" "$d@" 2>"${d}_stderr_tmp"
_rc=${d}?
if [ -s "${d}_stderr_tmp" ]; then
    grep -v "confdir\|com\.termux" "${d}_stderr_tmp" >&2 || true
fi
rm -f "${d}_stderr_tmp"
exit ${d}_rc
"""
            dpkgBin.writeText(wrapperContent)
            dpkgBin.setExecutable(true)
        }
    }

    /**
     * Copy all bundled scripts to the OCA dir.
     * post-setup.sh: try GitHub first, fall back to bundled asset.
     * All others: always use bundled asset.
     */
    private fun copyAssetScripts() {
        val ocaDir = File(homeDir, ".openclaw-android")
        ocaDir.mkdirs()
        File(ocaDir, "patches").mkdirs()

        val postSetup = File(ocaDir, "post-setup.sh")
        copyPostSetupScript(postSetup)
        copyBundledAsset("glibc-compat.js", File(ocaDir, "patches/glibc-compat.js"))
        copyBundledAsset("env-init.sh", File(ocaDir, "env-init.sh"))
        copyBundledAsset("run.sh", File(ocaDir, "run.sh"))
    }

    private fun copyPostSetupScript(target: File) {
        // Siempre usar el script bundled en assets como fuente primaria.
        // Intentar GitHub solo si el asset no existe (no debería ocurrir en producción).
        // IMPORTANTE: No intentar descargar de GitHub aquí porque:
        //   1. Los certificados CA pueden no estar listos aún (bootstrap no completado)
        //   2. El DNS puede no estar configurado en el sandbox de la app
        //   3. El script bundled siempre está disponible y es la versión correcta para esta APK
        try {
            context.assets.open("post-setup.sh").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true)
            AppLogger.i(TAG, "post-setup.sh copied from bundled assets")
            return
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to copy bundled post-setup.sh, trying GitHub fallback", e)
        }
        // Fallback: intentar GitHub solo si el asset bundled falla
        val url = "https://raw.githubusercontent.com/AidanPark/openclaw-android/main/post-setup.sh"
        try {
            java.net.URL(url).openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true)
            AppLogger.i(TAG, "post-setup.sh downloaded from GitHub (fallback)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to obtain post-setup.sh from both assets and GitHub", e)
        }
    }

    private fun copyBundledAsset(
        assetName: String,
        target: File,
    ) {
        try {
            context.assets.open(assetName).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            AppLogger.i(TAG, "$assetName copied from bundled assets")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to copy $assetName", e)
        }
    }

    // Runtime packages are installed by post-setup.sh in the terminal

    /**
     * Apply script update on APK version upgrade:
     * - Overwrites all bundled scripts from latest assets
     * - Installs/updates oa CLI from GitHub so users can run `oa --update`
     */
    fun applyScriptUpdate() {
        if (!isInstalled()) return
        copyAssetScripts()
        syncWwwFromAssets()
        installOaCli()
        // Regenerate the wrapper script with current paths
        CommandRunner.createWrapperScript(context.filesDir)
        AppLogger.i(TAG, "Script update applied")
    }

    /**
     * Copy bundled assets/www into wwwDir, overwriting any existing files.
     * Called on first install and on APK version upgrade to ensure the UI is always current.
     */
    fun syncWwwFromAssets() {
        try {
            wwwDir.mkdirs()
            copyAssetDir("www", wwwDir)
            AppLogger.i(TAG, "www synced from assets to ${wwwDir.absolutePath}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to sync www from assets", e)
        }
    }

    private fun copyAssetDir(
        assetPath: String,
        targetDir: File,
    ) {
        val entries = context.assets.list(assetPath) ?: return
        targetDir.mkdirs()
        for (entry in entries) {
            copyAssetEntry("$assetPath/$entry", File(targetDir, entry))
        }
    }

    private fun copyAssetEntry(
        assetPath: String,
        targetFile: File,
    ) {
        val children = context.assets.list(assetPath)
        if (!children.isNullOrEmpty()) {
            copyAssetDir(assetPath, targetFile)
        } else {
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    fun installOaCli() {
        val oaBin = File(prefixDir, "bin/oa")
        val oaUrl = "https://raw.githubusercontent.com/AidanPark/openclaw-android/main/oa.sh"
        try {
            java.net.URL(oaUrl).openStream().use { input ->
                oaBin.outputStream().use { output -> input.copyTo(output) }
            }
            oaBin.setExecutable(true)
            AppLogger.i(TAG, "oa CLI installed at ${oaBin.absolutePath}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to install oa CLI", e)
        }
    }
}

private object Os {
    @JvmStatic
    fun symlink(
        target: String,
        path: String,
    ) {
        android.system.Os.symlink(target, path)
    }
}
