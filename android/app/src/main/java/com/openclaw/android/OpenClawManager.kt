package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Installs and updates the minimum runtime needed for OpenClaw.
 *
 * This deliberately avoids turning the app sandbox into a full Termux distro.
 * The core contract is small:
 *   1. Ensure npm exists.
 *   2. npm install -g openclaw@latest with scripts disabled.
 *   3. Regenerate app-local launch wrappers.
 *   4. Mark OpenClaw installed only after the package is actually present.
 */
class OpenClawManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "OpenClawManager"
        private const val NODE_MAJOR = 24
        private const val NODE_RELEASE_BASE = "https://nodejs.org/dist/latest-v24.x"
    }

    private val filesDir: File = context.filesDir
    private val prefixDir = filesDir.resolve("usr")
    private val homeDir = filesDir.resolve("home")
    private val ocaDir = homeDir.resolve(".openclaw-android")

    /**
     * Install or update OpenClaw.
     * @return Pair of (success, lastError). On success, lastError is null.
     */
    suspend fun installOrUpdate(onProgress: (Int, String) -> Unit): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            homeDir.mkdirs()
            ocaDir.mkdirs()

            // 1. Check if ALREADY fully installed and valid
            if (isInstalled() && isMarkerValid()) {
                onProgress(100, "OpenClaw already installed and verified")
                AppLogger.i(TAG, "OpenClaw is already installed and marker is valid. Skipping.")
                return@withContext true to null
            }

            onProgress(0, "Preparing minimal OpenClaw runtime...")
            val env = CommandRunner.buildTermuxEnv(context).toMutableMap()

            // 2. Incremental Node.js installation
            if (!hasUsableNpm(env)) {
                onProgress(10, "Installing Node.js $NODE_MAJOR runtime...")
                val runtimeOk = installOfficialNode(onProgress) ||
                    runStreaming(
                        "pkg install -y nodejs git || apt-get install -y nodejs git",
                        env,
                        homeDir,
                        onProgress,
                        10,
                        35,
                    )
                if (!runtimeOk) return@withContext false to "Node.js runtime installation failed"
            } else {
                onProgress(35, "Node.js runtime already available")
                AppLogger.i(TAG, "Incremental install: Node.js already usable.")
            }

            // Verify npm registry connectivity BEFORE installing
            onProgress(35, "Verifying network connectivity...")
            // Use mirror by default for better reliability in some regions
            env["NPM_CONFIG_REGISTRY"] = "https://registry.npmmirror.com/"
            val registryOk = verifyNpmRegistry(onProgress, env)
            if (!registryOk) {
                onProgress(35, "Mirror unreachable — trying official registry...")
                env["NPM_CONFIG_REGISTRY"] = "https://registry.npmjs.org/"
                val officialOk = verifyNpmRegistry(onProgress, env)
                if (!officialOk) {
                    return@withContext false to "npm registry unreachable — check internet/DNS"
                }
            }

            // Install with retry logic
            onProgress(40, "Installing OpenClaw latest (attempt 1/3)...")
            var installOk = runStreamingWithRetry(
                "npm install -g openclaw@latest --ignore-scripts --no-fund --no-audit",
                env,
                homeDir,
                onProgress,
                40,
                82,
                maxRetries = 3,
            )
            if (!installOk) return@withContext false to "npm install openclaw failed — check network or disk space"

            onProgress(84, "Restoring lightweight OpenClaw dependencies...")
            restoreBundledPluginDependencies(env, onProgress)

            onProgress(90, "Creating launch wrappers...")
            CommandRunner.createWrapperScript(filesDir)

            if (!isInstalled()) {
                onProgress(0, "OpenClaw package was not found after installation")
                AppLogger.e(TAG, "OpenClaw verification failed after npm install")
                return@withContext false to "OpenClaw binary not found after installation"
            }

            writeInstalledMarker()

            // ── ACTIVATION PHASE ───────────────────────────────────────────
            onProgress(95, "Activating environment...")
            try {
                // Forzar recreación de scripts y asegurar permisos antes de terminar
                CommandRunner.createWrapperScript(filesDir)
                ocaDir.resolve("installed.json").let {
                    if (it.exists()) AppLogger.i(TAG, "Environment activated: ${it.name}")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Activation minor error: ${e.message}")
            }

            onProgress(100, "OpenClaw installed and activated")
            true to null
        }

    fun isInstalled(): Boolean =
        prefixDir.resolve("bin/openclaw").exists() ||
            prefixDir.resolve("lib/node_modules/openclaw/openclaw.mjs").exists()

    /** Verifies that the installation marker exists and is readable */
    private fun isMarkerValid(): Boolean {
        val marker = ocaDir.resolve("installed.json")
        return marker.exists() && marker.length() > 10
    }

    private fun hasUsableNpm(env: Map<String, String>): Boolean {
        val hasNpm = prefixDir.resolve("bin/npm").exists() ||
            ocaDir.resolve("node/bin/npm").exists() ||
            ocaDir.resolve("bin/npm").exists()
        if (!hasNpm) return false

        val result = CommandRunner.runSync("node -v", env, homeDir, timeoutMs = 5_000)
        val version = result.stdout.trim().removePrefix("v")
        return isNodeVersionSupported(version)
    }

    private fun isNodeVersionSupported(version: String): Boolean {
        val parts = version.split(".").map { it.toIntOrNull() ?: 0 }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        return major > 22 || (major == 22 && minor >= 14)
    }

    private fun installOfficialNode(onProgress: (Int, String) -> Unit): Boolean {
        val nodeDir = ocaDir.resolve("node")
        val tarFile = File(context.cacheDir, "node-linux-arm64.tar.xz")
        return try {
            onProgress(12, "Resolving latest Node.js $NODE_MAJOR...")

            // Intentar descargar SHASUMS con timeout y reintentos
            var shasums: String? = null
            val shasumsUrl = "$NODE_RELEASE_BASE/SHASUMS256.txt"

            for (i in 1..2) {
                try {
                    AppLogger.i(TAG, "Fetching SHASUMS (attempt $i): $shasumsUrl")
                    val connection = URL(shasumsUrl).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    shasums = connection.inputStream.bufferedReader().use { it.readText() }
                    if (!shasums.isNullOrBlank()) break
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to fetch SHASUMS (attempt $i): ${e.message}")
                    Thread.sleep(3000)
                }
            }

            val tarName = if (!shasums.isNullOrBlank()) {
                Regex("""node-v$NODE_MAJOR\.[^\s]+-linux-arm64\.tar\.xz""")
                    .find(shasums)
                    ?.value
                    ?: "node-v24.1.0-linux-arm64.tar.xz" // Fallback a versión conocida
            } else {
                AppLogger.w(TAG, "Could not fetch SHASUMS, using hardcoded version fallback")
                "node-v24.1.0-linux-arm64.tar.xz"
            }

            onProgress(16, "Downloading $tarName...")
            val downloadUrl = "$NODE_RELEASE_BASE/$tarName"
            AppLogger.i(TAG, "Downloading Node.js from: $downloadUrl")

            val conn = URL(downloadUrl).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                tarFile.outputStream().use { output -> input.copyTo(output) }
            }

            if (!tarFile.exists() || tarFile.length() == 0L) {
                throw IllegalStateException("Downloaded tarball is empty or missing")
            }

            onProgress(28, "Extracting Node.js runtime...")
            nodeDir.deleteRecursively()
            nodeDir.mkdirs()

            // Usar /system/bin/sh para ejecutar tar con redirección de errores capturable
            val process = ProcessBuilder(
                "/system/bin/sh", "-c",
                "/system/bin/tar -xJf \"${tarFile.absolutePath}\" -C \"${nodeDir.absolutePath}\" --strip-components=1 2>&1"
            ).apply {
                environment().put("PATH", "/system/bin:/bin")
            }.start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                AppLogger.e(TAG, "Node extraction failed (code $exitCode): $output")
                false
            } else {
                nodeDir.resolve("bin/node").setExecutable(true, false)
                nodeDir.resolve("bin/npm").setExecutable(true, false)
                nodeDir.resolve("bin/npx").setExecutable(true, false)
                onProgress(35, "Node.js runtime ready")
                true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Official Node install failed: ${e.message}", e)
            onProgress(18, "Official download failed (${e.message}), using package fallback...")
            false
        } finally {
            if (tarFile.exists()) tarFile.delete()
        }
    }

    private suspend fun runStreaming(
        command: String,
        env: Map<String, String>,
        workDir: File,
        onProgress: (Int, String) -> Unit,
        start: Int,
        end: Int,
    ): Boolean {
        var ticks = 0
        val result = CommandRunner.runStreaming(command, env, workDir) { line ->
            ticks++
            val pct = (start + (ticks % 12) * ((end - start).coerceAtLeast(1) / 12.0)).toInt()
                .coerceIn(start, end)
            onProgress(pct, line)
        }
        if (result.exitCode != 0) {
            onProgress(start, result.stderr.ifBlank { "Command failed: $command" })
            AppLogger.e(TAG, "Command failed ($command): ${result.stderr}")
            return false
        }
        return true
    }

    /** Run command with retry logic and exponential backoff. */
    private suspend fun runStreamingWithRetry(
        command: String,
        env: Map<String, String>,
        workDir: File,
        onProgress: (Int, String) -> Unit,
        start: Int,
        end: Int,
        maxRetries: Int = 3,
    ): Boolean {
        var delaySec = 5
        for (attempt in 1..maxRetries) {
            onProgress(start, "Installing OpenClaw latest (attempt $attempt/$maxRetries)...")
            if (runStreaming(command, env, workDir, onProgress, start, end)) {
                return true
            }
            if (attempt < maxRetries) {
                onProgress(start, "Install failed, retrying in ${delaySec}s...")
                Thread.sleep(delaySec * 1000L)
                delaySec *= 2  // exponential backoff
                // Clean npm cache before retry
                val cacheDir = ocaDir.resolve(".npm/_cacache/tmp")
                cacheDir.deleteRecursively()
            }
        }
        return false
    }

    /** Verify npm registry connectivity. Returns true if reachable. */
    private fun verifyNpmRegistry(onProgress: (Int, String) -> Unit, env: Map<String, String>): Boolean {
        val registry = env["NPM_CONFIG_REGISTRY"] ?: "https://registry.npmjs.org/"

        // Reintentos específicos para DNS/Red con conexión directa forzada
        for (i in 1..2) {
            try {
                onProgress(36, "Checking connectivity to $registry (attempt $i)...")
                // Usar curl con --noproxy '*' para asegurar conexión directa
                val result = CommandRunner.runSync(
                    "curl -fsSL --noproxy '*' --connect-timeout 15 --retry 1 $registry",
                    env,
                    homeDir,
                    timeoutMs = 25_000,
                )
                if (result.exitCode == 0) return true

                AppLogger.w(TAG, "Registry check attempt $i failed with exit code ${result.exitCode}")
                if (i < 2) Thread.sleep(3000)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Registry check attempt $i exception: ${e.message}")
                if (i < 2) Thread.sleep(3000)
            }
        }
        return false
    }

    private suspend fun restoreBundledPluginDependencies(
        env: Map<String, String>,
        onProgress: (Int, String) -> Unit,
    ) {
        val openClawDir = prefixDir.resolve("lib/node_modules/openclaw")
        val postInstall = openClawDir.resolve("scripts/postinstall-bundled-plugins.mjs")
        if (!postInstall.exists()) return

        runStreaming(
            "cd \"${openClawDir.absolutePath}\" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs",
            env,
            homeDir,
            onProgress,
            84,
            89,
        )
    }

    private fun writeInstalledMarker() {
        val version = readOpenClawVersion()
        val marker = ocaDir.resolve("installed.json")
        marker.parentFile?.mkdirs()
        marker.writeText(
            """
            |{
            |  "installed": true,
            |  "source": "openclaw-manager",
            |  "version": "$version",
            |  "prefix": "${prefixDir.absolutePath}",
            |  "home": "${homeDir.absolutePath}"
            |}
            |
            """.trimMargin(),
        )
    }

    private fun readOpenClawVersion(): String {
        val pkg = prefixDir.resolve("lib/node_modules/openclaw/package.json")
        if (!pkg.exists()) return "unknown"
        val match = Regex(""""version"\s*:\s*"([^"]+)"""").find(pkg.readText())
        return match?.groupValues?.getOrNull(1) ?: "unknown"
    }
}
