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

            onProgress(0, "Preparing minimal OpenClaw runtime...")
            val env = CommandRunner.buildTermuxEnv(context)

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
            }

            onProgress(40, "Installing OpenClaw latest...")
            val installOk = runStreaming(
                "npm install -g openclaw@latest --ignore-scripts --no-fund --no-audit",
                env,
                homeDir,
                onProgress,
                40,
                82,
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
            onProgress(100, "OpenClaw installed")
            true to null
        }

    fun isInstalled(): Boolean =
        prefixDir.resolve("bin/openclaw").exists() ||
            prefixDir.resolve("lib/node_modules/openclaw/openclaw.mjs").exists()

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
            val shasums = URL("$NODE_RELEASE_BASE/SHASUMS256.txt").readText()
            val tarName = Regex("""node-v$NODE_MAJOR\.[^\s]+-linux-arm64\.tar\.xz""")
                .find(shasums)
                ?.value
                ?: throw IllegalStateException("Node.js linux-arm64 tarball not found")

            onProgress(16, "Downloading $tarName...")
            URL("$NODE_RELEASE_BASE/$tarName").openStream().use { input ->
                tarFile.outputStream().use { output -> input.copyTo(output) }
            }

            onProgress(28, "Extracting Node.js runtime...")
            nodeDir.deleteRecursively()
            nodeDir.mkdirs()
            val process = ProcessBuilder(
                "/system/bin/tar",
                "-xJf",
                tarFile.absolutePath,
                "-C",
                nodeDir.absolutePath,
                "--strip-components=1",
            ).apply {
                environment().put("PATH", "/system/bin:/bin")
                redirectErrorStream(true)
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                AppLogger.w(TAG, "Official Node extraction failed: $output")
                false
            } else {
                nodeDir.resolve("bin/node").setExecutable(true, false)
                nodeDir.resolve("bin/npm").setExecutable(true, false)
                nodeDir.resolve("bin/npx").setExecutable(true, false)
                onProgress(35, "Node.js runtime ready")
                true
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Official Node install failed: ${e.message}")
            onProgress(18, "Official Node failed, using package fallback...")
            false
        } finally {
            tarFile.delete()
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
