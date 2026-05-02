package com.openclaw.android

import android.content.Context
import java.io.File

/**
 * Builds the complete process environment for running OpenClaw.
 *
 * Design contract:
 *   - ALL paths are resolved exclusively from context.filesDir (app sandbox).
 *   - Unified to /data/user/0/... or equivalent official path.
 */
object EnvironmentBuilder {

    fun build(context: Context): Map<String, String> =
        buildEnvironment(context.filesDir, context.packageName)

    /** Legacy alias for buildEnvironment/build, used by CommandRunner and tests. */
    fun buildTermuxEnvironment(context: Context? = null): Map<String, String> =
        if (context != null) build(context)
        else buildEnvironment(null)

    fun buildEnvironment(filesDir: File?, packageName: String? = null): Map<String, String> {
        val actualFilesDir = filesDir ?: resolveFilesDirFromEnv() ?: File("/data/local/tmp")
        val base = actualFilesDir.absolutePath
        val prefix = "$base/usr"
        val home = "$base/home"
        val actualPackageName = packageName ?: actualFilesDir.parentFile?.name ?: "com.openclaw.android"

        val ocaDir = "$home/.openclaw-android"
        val ocaBin = "$ocaDir/bin"
        val nodeDir = "$ocaDir/node"
        val glibcLib = "$prefix/glibc/lib"
        val tmpDir = "$base/tmp"

        // Ensure standard folders exist before shell starts
        File(home).mkdirs()
        File(prefix).mkdirs()
        File("$prefix/bin").mkdirs()
        File("$base/tmp").mkdirs()

        // Ensure resolv.conf exists before any network call
        ensureResolvConf(prefix)

        return buildMap {
            put("HOME", home)
            put("PREFIX", prefix)
            put("TMPDIR", tmpDir)
            put("APP_FILES_DIR", base)
            put("PAYLOAD_DIR", "$base/payload")
            put("APP_PACKAGE", actualPackageName)

            put("PATH", "$ocaBin:$nodeDir/bin:$prefix/bin:$prefix/bin/applets:/system/bin:/bin")
            put("NPM_CONFIG_PREFIX", prefix)
            put("npm_config_prefix", prefix)

            put("LD_LIBRARY_PATH", "$prefix/lib:$glibcLib")

            val termuxExecFile = File("$prefix/lib/libtermux-exec.so")
            if (termuxExecFile.exists()) {
                put("LD_PRELOAD", termuxExecFile.absolutePath)
            }

            put("TERMUX__PREFIX", prefix)
            put("TERMUX_PREFIX", prefix)
            put("TERMUX__ROOTFS", base)

            put("BASH_ENV", "/dev/null")
            put("ENV", "/dev/null")

            val certBundle = "$prefix/etc/tls/cert.pem"
            put("SSL_CERT_FILE", certBundle)
            put("CURL_CA_BUNDLE", certBundle)
            put("GIT_SSL_CAINFO", certBundle)

            put("RESOLV_CONF", "$prefix/etc/resolv.conf")

            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_EXEC_PATH", "$prefix/libexec/git-core")
            put("GIT_TEMPLATE_DIR", "$prefix/share/git-core/templates")

            put("APT_CONFIG", "$prefix/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "$prefix/var/lib/dpkg")
            put("DPKG_ROOT", prefix)

            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")

            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            put("OA_GLIBC", "1")
            put("CONTAINER", "1")
            put("CLAWDHUB_WORKDIR", "$home/.openclaw/workspace")

            put("_OA_COMPAT_PATH", "$ocaDir/patches/glibc-compat.js")
            put("_OA_WRAPPER_PATH", "$ocaBin/node")
        }
    }

    private fun resolveFilesDirFromEnv(): File? {
        val home = System.getenv("HOME") ?: return null
        val homeFile = File(home)
        return if (homeFile.name == "home") homeFile.parentFile else null
    }

    fun resolveActivePaths(filesDir: File? = null): Pair<String, String> {
        val actualFilesDir = filesDir ?: resolveFilesDirFromEnv() ?: File("/data/local/tmp")
        val base = actualFilesDir.absolutePath
        val localPrefix = "$base/usr"
        val localHome = "$base/home"
        File(localHome).mkdirs()
        return Pair(localPrefix, localHome)
    }

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
            } catch (_: Exception) {}
        }
    }
}
