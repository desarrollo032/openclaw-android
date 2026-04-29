package com.openclaw.android

import android.content.Context
import java.io.File

/**
 * Builds the complete process environment for running OpenClaw.
 *
 * Architecture:
 *   - All paths are resolved from context.filesDir (app sandbox).
 *   - NO dependency on Termux. If Termux is not installed, everything still works.
 *   - Termux paths are checked as a legacy fallback only when the app-local
 *     runtime is not yet installed (e.g., during first-run migration).
 *
 * Path layout (all under context.filesDir):
 *   filesDir/usr/          → PREFIX
 *   filesDir/home/         → HOME
 *   filesDir/tmp/          → TMPDIR
 *   HOME/.openclaw-android/bin/   → OCA_BIN (node, npm, npx wrappers)
 *   HOME/.openclaw-android/node/  → NODE_DIR
 *   PREFIX/glibc/lib/             → GLIBC_LIB
 */
object EnvironmentBuilder {

    // Legacy Termux paths — used ONLY as fallback when app-local runtime is absent.
    // These are never written to; only read for detection.
    private const val TERMUX_HOME_LEGACY = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX_LEGACY = "/data/data/com.termux/files/usr"

    fun build(context: Context): Map<String, String> =
        buildEnvironment(context.filesDir, context.packageName)

    fun build(filesDir: File): Map<String, String> = buildEnvironment(filesDir)

    /**
     * Build environment from the process environment or provided filesDir.
     */
    fun buildTermuxEnvironment(context: Context? = null): Map<String, String> {
        val filesDir = context?.filesDir ?: resolveFilesDirFromEnv()
        return buildEnvironment(filesDir, context?.packageName)
    }

    private fun resolveFilesDirFromEnv(): File? {
        val home = System.getenv("HOME") ?: return null
        val homeFile = File(home)
        return if (homeFile.name == "home") homeFile.parentFile else homeFile.parentFile?.parentFile
    }

    /**
     * Resolve the active (prefix, home) pair.
     *
     * Priority:
     *   1. App-local runtime (filesDir/usr) — always preferred
     *   2. Legacy Termux runtime — only if app-local is not installed AND
     *      Termux is actually accessible (same UID or sharedUserId)
     */
    fun resolveActivePaths(filesDir: File? = null): Pair<String, String> {
        // 1. App-local runtime
        if (filesDir != null) {
            val localPrefix = filesDir.resolve("usr")
            val localHome = filesDir.resolve("home")
            if (localPrefix.resolve("bin/sh").exists()) {
                localHome.mkdirs()
                return Pair(localPrefix.absolutePath, localHome.absolutePath)
            }
        }

        // 2. Legacy Termux fallback (only if accessible)
        val termuxPrefix = File(TERMUX_PREFIX_LEGACY)
        val termuxHome = File(TERMUX_HOME_LEGACY)
        val termuxAccessible = try {
            termuxPrefix.resolve("bin/sh").exists() &&
                termuxHome.canWrite() &&
                termuxPrefix.resolve("bin").listFiles() != null
        } catch (_: SecurityException) {
            false
        }
        if (termuxAccessible) {
            return Pair(TERMUX_PREFIX_LEGACY, TERMUX_HOME_LEGACY)
        }

        // 3. App-local paths even if not yet installed (bootstrap in progress)
        if (filesDir != null) {
            val localPrefix = filesDir.resolve("usr")
            val localHome = filesDir.resolve("home")
            localHome.mkdirs()
            return Pair(localPrefix.absolutePath, localHome.absolutePath)
        }

        // 4. Last resort
        return Pair(TERMUX_PREFIX_LEGACY, TERMUX_HOME_LEGACY)
    }

    fun buildEnvironment(filesDir: File?, packageName: String? = null): Map<String, String> {
        val (prefix, home) = resolveActivePaths(filesDir)
        val actualFilesDir = filesDir ?: (if (home.endsWith("/home")) File(home).parentFile else null)
        val actualPackageName = packageName
            ?: actualFilesDir?.parentFile?.name
            ?: "com.openclaw.android"

        val ocaDir = "$home/.openclaw-android"
        val ocaBin = "$ocaDir/bin"
        val nodeDir = "$ocaDir/node"
        val glibcLib = "$prefix/glibc/lib"
        val tmpDir = if (actualFilesDir != null) {
            actualFilesDir.resolve("tmp").also { it.mkdirs() }.absolutePath
        } else {
            File(prefix).parent?.let { "$it/tmp" } ?: "/data/local/tmp"
        }

        // Ensure resolv.conf exists before any network call
        ensureResolvConf(prefix)

        return buildMap {
            put("HOME", home)
            put("PREFIX", prefix)
            put("TMPDIR", tmpDir)

            // Essential for scripts that expect the PayloadManager context
            if (actualFilesDir != null) {
                put("APP_FILES_DIR", actualFilesDir.absolutePath)
            }
            put("APP_PACKAGE", actualPackageName)

            // PATH: OCA bin first (contains glibc-wrapped node), then prefix bins
            put("PATH", "$ocaBin:$nodeDir/bin:$prefix/bin:$prefix/bin/applets:/system/bin:/bin")

            // glibc libs must be on LD_LIBRARY_PATH for any glibc binary
            put("LD_LIBRARY_PATH", "$glibcLib:$prefix/lib")

            // Termux-exec redirection
            val termuxExec = "$prefix/lib/libtermux-exec.so"
            if (File(termuxExec).exists()) {
                put("LD_PRELOAD", termuxExec)
            }

            // NOTE: The node wrapper (OCA_BIN/node) unsets LD_PRELOAD before exec'ing
            // node.real via ld.so to prevent bionic libtermux-exec.so from loading
            // into the glibc process (which would cause a "Could not find a PHDR" crash).

            // Termux-compat vars (some tools read these)
            put("TERMUX__PREFIX", prefix)
            put("TERMUX_PREFIX", prefix)
            put("TERMUX__ROOTFS", File(prefix).parent ?: prefix)

            // SSL — point to the cert bundle in the app-local prefix
            val certBundle = "$prefix/etc/tls/cert.pem"
            put("SSL_CERT_FILE", certBundle)
            put("CURL_CA_BUNDLE", certBundle)
            put("GIT_SSL_CAINFO", certBundle)

            // DNS — resolv.conf path for resolvers that read it explicitly
            put("RESOLV_CONF", "$prefix/etc/resolv.conf")

            // Git — avoid hardcoded system paths
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_EXEC_PATH", "$prefix/libexec/git-core")
            put("GIT_TEMPLATE_DIR", "$prefix/share/git-core/templates")

            // dpkg/apt (used by post-setup.sh if dynamic install path is taken)
            put("APT_CONFIG", "$prefix/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "$prefix/var/lib/dpkg")
            put("DPKG_ROOT", prefix)

            // Locale and terminal
            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")

            // Android system paths
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            // OpenClaw flags
            put("OA_GLIBC", "1")
            put("CONTAINER", "1")
            put("CLAWDHUB_WORKDIR", "$home/.openclaw/workspace")

            // glibc-compat.js path (loaded via NODE_OPTIONS in the node wrapper)
            put("_OA_COMPAT_PATH", "$ocaDir/patches/glibc-compat.js")
            put("_OA_WRAPPER_PATH", "$ocaBin/node")
        }
    }

    /**
     * Ensure resolv.conf exists with valid nameservers.
     * Android sandbox does not inherit /etc/resolv.conf from the system.
     * Without this, DNS resolution fails for all network calls (curl, npm, git).
     */
    private fun ensureResolvConf(prefix: String) {
        val resolvPaths = listOf(
            File("$prefix/etc/resolv.conf"),
            File("$prefix/glibc/etc/resolv.conf"),
        )
        val dnsContent = "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n"
        for (f in resolvPaths) {
            try {
                if (!f.exists() || f.readText().isBlank() || !f.readText().contains("nameserver")) {
                    f.parentFile?.mkdirs()
                    f.writeText(dnsContent)
                }
            } catch (_: Exception) {
                // Non-fatal: resolv.conf may already be correct
            }
        }
    }
}
