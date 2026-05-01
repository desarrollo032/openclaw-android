package com.openclaw.android

import android.content.Context
import java.io.File

/**
 * Builds the complete process environment for running OpenClaw.
 *
 * Design contract:
 *   - ALL paths are resolved exclusively from context.filesDir (app sandbox).
 *   - NO fallback to Termux paths at runtime. Termux constants exist only for
 *     legacy detection (isInstalled checks), never for process execution.
 *   - Every process launched by this app uses only paths under filesDir.
 *
 * Sandbox layout (all under context.filesDir):
 *   filesDir/usr/                        → PREFIX
 *   filesDir/home/                       → HOME
 *   filesDir/tmp/                        → TMPDIR
 *   filesDir/payload/                    → PAYLOAD_DIR
 *   HOME/.openclaw-android/bin/          → OCA_BIN (node, npm, npx wrappers)
 *   HOME/.openclaw-android/node/         → NODE_DIR
 *   PREFIX/glibc/lib/                    → GLIBC_LIB
 */
object EnvironmentBuilder {

    fun build(context: Context): Map<String, String> =
        buildEnvironment(normalizeFilesDir(context.filesDir), context.packageName)

    fun build(filesDir: File): Map<String, String> = buildEnvironment(normalizeFilesDir(filesDir))

    fun buildTermuxEnvironment(context: Context? = null): Map<String, String> {
        val filesDir = context?.filesDir?.let { normalizeFilesDir(it) } ?: resolveFilesDirFromEnv()
        return buildEnvironment(filesDir, context?.packageName)
    }

    private fun resolveFilesDirFromEnv(): File? {
        val home = System.getenv("HOME") ?: return null
        val homeFile = File(home)
        // HOME is filesDir/home — parent is filesDir
        return if (homeFile.name == "home") homeFile.parentFile else null
    }

    /**
     * Resolve (prefix, home) exclusively from the app sandbox.
     *
     * Always returns app-local paths. Never falls back to Termux.
     * If filesDir is null (edge case during early init), returns safe defaults
     * that will fail loudly rather than silently using Termux paths.
     */
    fun resolveActivePaths(filesDir: File? = null): Pair<String, String> {
        if (filesDir != null) {
            val localPrefix = filesDir.resolve("usr")
            val localHome = filesDir.resolve("home")
            localHome.mkdirs()
            return Pair(localPrefix.absolutePath, localHome.absolutePath)
        }
        // filesDir unknown — derive from process HOME if set by us
        val envHome = System.getenv("HOME")
        if (envHome != null && envHome.contains("/files/home")) {
            val inferredFilesDir = File(envHome).parentFile
            if (inferredFilesDir != null) {
                return Pair(
                    inferredFilesDir.resolve("usr").absolutePath,
                    envHome,
                )
            }
        }
        // Last resort: return placeholder paths that will fail clearly
        // rather than silently using /data/data/com.termux/...
        return Pair("/data/local/tmp/openclaw-usr", "/data/local/tmp/openclaw-home")
    }

    fun buildEnvironment(filesDir: File?, packageName: String? = null): Map<String, String> {
        val (prefix, home) = resolveActivePaths(filesDir)

        val actualFilesDir = filesDir
            ?: if (home.contains("/files/home")) File(home).parentFile else null

        val actualPackageName = packageName
            ?: actualFilesDir?.parentFile?.name
            ?: "com.openclaw.android"

        val ocaDir = "$home/.openclaw-android"
        val ocaBin = "$ocaDir/bin"
        val nodeDir = "$ocaDir/node"
        val glibcLib = "$prefix/glibc/lib"

        val tmpDir = actualFilesDir
            ?.resolve("tmp")
            ?.also { it.mkdirs() }
            ?.absolutePath
            ?: "/data/local/tmp"

        // Ensure resolv.conf exists before any network call
        ensureResolvConf(prefix)

        return buildMap {
            // ── Core sandbox paths ────────────────────────────────────────────
            put("HOME", home)
            put("PREFIX", prefix)
            put("TMPDIR", tmpDir)

            if (actualFilesDir != null) {
                put("APP_FILES_DIR", actualFilesDir.absolutePath)
                // PAYLOAD_DIR is always filesDir/payload.
                // Set unconditionally so post-setup.sh always has it, regardless
                // of whether it is invoked programmatically or from a terminal session.
                put("PAYLOAD_DIR", actualFilesDir.resolve("payload").absolutePath)
            }
            put("APP_PACKAGE", actualPackageName)

            // ── PATH: sandbox bins first, system bins last ────────────────────
            // OCA_BIN contains the glibc-wrapped node launcher.
            // /system/bin and /bin are Android system utilities only.
            put("PATH", "$ocaBin:$nodeDir/bin:$prefix/bin:$prefix/bin/applets:/system/bin:/bin")
            put("NPM_CONFIG_PREFIX", prefix)
            put("npm_config_prefix", prefix)

            // ── Dynamic linker ────────────────────────────────────────────────
            // Termux/Bionic commands (curl, bash, apt) need $PREFIX/lib first.
            // glibc launchers add their own loader/library path when needed.
            put("LD_LIBRARY_PATH", "$prefix/lib:$glibcLib")

            // LD_PRELOAD: only set if libtermux-exec.so exists in OUR prefix.
            // This intercepts execve() to rewrite hardcoded com.termux paths.
            // The node wrapper unsets LD_PRELOAD before launching node.real via
            // ld.so to prevent bionic libtermux-exec.so from crashing glibc.
            // Bug #5 fix: use the normalized prefix path so the .so is found even
            // when context.filesDir returns /data/user/0/... instead of /data/data/...
            val termuxExecFile = File("$prefix/lib/libtermux-exec.so")
            if (termuxExecFile.exists()) {
                put("LD_PRELOAD", termuxExecFile.absolutePath)
            }
            // Note: if libtermux-exec.so is missing, dpkg/apt will fail to rewrite
            // hardcoded com.termux paths. The dpkg wrapper script handles confdir
            // errors as a fallback, but the .so is required for full compatibility.

            // ── Termux-compat vars ────────────────────────────────────────────
            // Point TERMUX__PREFIX to OUR prefix so the bootstrap bash binary
            // sources OUR bash.bashrc instead of /data/data/com.termux/...
            // This is the key fix for "bash.bashrc: Permission denied".
            put("TERMUX__PREFIX", prefix)
            put("TERMUX_PREFIX", prefix)
            put("TERMUX__ROOTFS", File(prefix).parent ?: prefix)

            // ── Bash startup suppression ──────────────────────────────────────
            // The Termux bootstrap bash has /data/data/com.termux/files/usr
            // compiled in as its prefix. Even with TERMUX__PREFIX set, some
            // bash versions ignore it for the initial bashrc lookup.
            // BASH_ENV suppresses startup files for non-interactive bash.
            // Interactive bash is handled by --norc --noprofile in TerminalSessionManager.
            put("BASH_ENV", "/dev/null")
            put("ENV", "/dev/null")

            // ── SSL certificates ──────────────────────────────────────────────
            val certBundle = "$prefix/etc/tls/cert.pem"
            put("SSL_CERT_FILE", certBundle)
            put("CURL_CA_BUNDLE", certBundle)
            put("GIT_SSL_CAINFO", certBundle)

            // ── DNS ───────────────────────────────────────────────────────────
            put("RESOLV_CONF", "$prefix/etc/resolv.conf")

            // ── Git ───────────────────────────────────────────────────────────
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_EXEC_PATH", "$prefix/libexec/git-core")
            put("GIT_TEMPLATE_DIR", "$prefix/share/git-core/templates")

            // ── dpkg/apt ──────────────────────────────────────────────────────
            put("APT_CONFIG", "$prefix/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "$prefix/var/lib/dpkg")
            put("DPKG_ROOT", prefix)

            // ── Locale and terminal ───────────────────────────────────────────
            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")

            // ── Android system ────────────────────────────────────────────────
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            // ── OpenClaw flags ────────────────────────────────────────────────
            put("OA_GLIBC", "1")
            put("CONTAINER", "1")
            put("CLAWDHUB_WORKDIR", "$home/.openclaw/workspace")

            // ── glibc-compat shim ─────────────────────────────────────────────
            put("_OA_COMPAT_PATH", "$ocaDir/patches/glibc-compat.js")
            put("_OA_WRAPPER_PATH", "$ocaBin/node")
        }
    }

    /**
     * Ensure resolv.conf exists with valid nameservers.
     * Android sandbox does not inherit /etc/resolv.conf from the system.
     */
    private fun ensureResolvConf(prefix: String) {
        val dnsContent = "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n"
        listOf(
            File("$prefix/etc/resolv.conf"),
            File("$prefix/glibc/etc/resolv.conf"),
        ).forEach { f ->
            try {
                if (!f.exists() || f.length() == 0L || !f.readText().contains("nameserver")) {
                    f.parentFile?.mkdirs()
                    f.writeText(dnsContent)
                }
            } catch (_: Exception) {
                // Non-fatal
            }
        }
    }

    /**
     * Bug #1 fix: normalize /data/user/0/<pkg>/files → /data/data/<pkg>/files.
     *
     * Android 7+ with multi-user support exposes context.filesDir as
     * /data/user/0/<pkg>/files (a bind-mount alias). Bootstrap binaries compiled
     * for Termux have /data/data/... hardcoded in ELF strings and config lookups
     * via open()/opendir() — calls NOT intercepted by libtermux-exec.so.
     * Using the canonical path ensures dpkg, bash, and apt find their config dirs.
     */
    private fun normalizeFilesDir(filesDir: File): File {
        val path = filesDir.absolutePath
        val normalized = path.replace(Regex("^/data/user/\\d+/"), "/data/data/")
        return if (normalized != path) File(normalized) else filesDir
    }
}
