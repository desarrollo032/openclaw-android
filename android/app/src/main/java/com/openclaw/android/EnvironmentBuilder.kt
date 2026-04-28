package com.openclaw.android

import android.content.Context
import java.io.File

/**
 * Builds the complete process environment for Termux commands.
 * Always uses the real Termux paths and ensures grun is available.
 * Never references /usr/bin/node directly — use grun instead.
 */
object EnvironmentBuilder {
    // Real Termux paths — these are fixed regardless of app package name
    private const val TERMUX_HOME = CommandRunner.TERMUX_HOME
    private const val TERMUX_PREFIX = CommandRunner.TERMUX_PREFIX
    private const val OPENCLAW_BIN = CommandRunner.OPENCLAW_BIN

    fun build(context: Context): Map<String, String> = buildTermuxEnvironment()

    fun build(filesDir: File): Map<String, String> = buildTermuxEnvironment()

    /**
     * Full Termux environment with correct HOME, PATH, and grun support.
     */
    fun buildTermuxEnvironment(): Map<String, String> =
        buildMap {
            put("HOME", TERMUX_HOME)
            put("PREFIX", TERMUX_PREFIX)
            put("TMPDIR", "$TERMUX_PREFIX/../tmp")

            // PATH: openclaw bin first (contains grun), then termux bins
            put(
                "PATH",
                "$OPENCLAW_BIN:$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:/system/bin:/bin",
            )
            put("LD_LIBRARY_PATH", "$TERMUX_PREFIX/lib")

            // libtermux-exec for path rewriting
            val termuxExecLib = File("$TERMUX_PREFIX/lib/libtermux-exec.so")
            if (termuxExecLib.exists()) {
                put("LD_PRELOAD", termuxExecLib.absolutePath)
            }

            // Termux prefix vars
            put("TERMUX__PREFIX", TERMUX_PREFIX)
            put("TERMUX_PREFIX", TERMUX_PREFIX)
            put("TERMUX__ROOTFS", "$TERMUX_PREFIX/..")

            // apt/dpkg
            put("APT_CONFIG", "$TERMUX_PREFIX/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "$TERMUX_PREFIX/var/lib/dpkg")
            put("DPKG_ROOT", TERMUX_PREFIX)

            // SSL
            put("SSL_CERT_FILE", "$TERMUX_PREFIX/etc/tls/cert.pem")
            put("CURL_CA_BUNDLE", "$TERMUX_PREFIX/etc/tls/cert.pem")
            put("GIT_SSL_CAINFO", "$TERMUX_PREFIX/etc/tls/cert.pem")

            // Git
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_EXEC_PATH", "$TERMUX_PREFIX/libexec/git-core")
            put("GIT_TEMPLATE_DIR", "$TERMUX_PREFIX/share/git-core/templates")

            // Locale and terminal
            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")

            // Android
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            // OpenClaw — glibc compat required, never use node directly
            put("OA_GLIBC", "1")
            put("CONTAINER", "1")
            put("CLAWDHUB_WORKDIR", "$TERMUX_HOME/.openclaw/workspace")
            put("CPATH", "$TERMUX_PREFIX/include/glib-2.0:$TERMUX_PREFIX/lib/glib-2.0/include")
        }
}
