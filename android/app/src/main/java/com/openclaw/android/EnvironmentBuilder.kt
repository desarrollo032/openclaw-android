package com.openclaw.android

import android.content.Context
import java.io.File

/**
 * Builds the complete process environment for Termux commands.
 * Detecta automáticamente si usar las rutas reales de Termux o las locales de la app.
 * Nunca referencia /usr/bin/node directamente — usar grun en su lugar.
 */
object EnvironmentBuilder {
    private const val TERMUX_HOME = CommandRunner.TERMUX_HOME
    private const val TERMUX_PREFIX = CommandRunner.TERMUX_PREFIX
    private const val OPENCLAW_BIN = CommandRunner.OPENCLAW_BIN

    fun build(context: Context): Map<String, String> = buildEnvironment(context.filesDir)

    fun build(filesDir: File): Map<String, String> = buildEnvironment(filesDir)

    /**
     * Resuelve las rutas reales a usar:
     * - Si Termux está instalado y accesible → usa sus rutas
     * - Si no → usa las rutas locales de la app (filesDir/usr, filesDir/home)
     */
    fun resolveActivePaths(filesDir: File? = null): Pair<String, String> {
        val termuxPrefixFile = File(TERMUX_PREFIX)
        val termuxHomeFile = File(TERMUX_HOME)

        return if (termuxPrefixFile.resolve("bin/sh").exists() && termuxHomeFile.canWrite()) {
            // Termux real disponible y accesible
            Pair(TERMUX_PREFIX, TERMUX_HOME)
        } else if (filesDir != null) {
            // Usar rutas locales de la app
            val localPrefix = filesDir.resolve("usr")
            val localHome = filesDir.resolve("home")
            localHome.mkdirs()
            Pair(localPrefix.absolutePath, localHome.absolutePath)
        } else {
            // Fallback: intentar Termux de todas formas
            Pair(TERMUX_PREFIX, TERMUX_HOME)
        }
    }

    fun buildTermuxEnvironment(): Map<String, String> = buildEnvironment(null)

    fun buildEnvironment(filesDir: File?): Map<String, String> {
        val (prefix, home) = resolveActivePaths(filesDir)
        val openclawDir = "$home/.openclaw-android"
        val openclawBin = "$openclawDir/bin"
        val tmpDir = File(prefix).parent?.let { "$it/tmp" } ?: "/data/local/tmp"

        return buildMap {
            put("HOME", home)
            put("PREFIX", prefix)
            put("TMPDIR", tmpDir)

            // PATH: bin de openclaw primero (contiene grun), luego bins de termux
            put("PATH", "$openclawBin:$prefix/bin:$prefix/bin/applets:/system/bin:/bin")
            put("LD_LIBRARY_PATH", "$prefix/lib")

            // libtermux-exec solo si existe (evita crash por librería no encontrada)
            val termuxExecLib = File("$prefix/lib/libtermux-exec.so")
            if (termuxExecLib.exists()) {
                put("LD_PRELOAD", termuxExecLib.absolutePath)
            }

            // Variables de prefix de Termux
            put("TERMUX__PREFIX", prefix)
            put("TERMUX_PREFIX", prefix)
            put("TERMUX__ROOTFS", File(prefix).parent ?: prefix)

            // apt/dpkg
            put("APT_CONFIG", "$prefix/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "$prefix/var/lib/dpkg")
            put("DPKG_ROOT", prefix)

            // SSL
            put("SSL_CERT_FILE", "$prefix/etc/tls/cert.pem")
            put("CURL_CA_BUNDLE", "$prefix/etc/tls/cert.pem")
            put("GIT_SSL_CAINFO", "$prefix/etc/tls/cert.pem")

            // Git
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_EXEC_PATH", "$prefix/libexec/git-core")
            put("GIT_TEMPLATE_DIR", "$prefix/share/git-core/templates")

            // Locale y terminal
            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")

            // Android
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            // OpenClaw — compatibilidad glibc requerida, nunca usar node directamente
            put("OA_GLIBC", "1")
            put("CONTAINER", "1")
            put("CLAWDHUB_WORKDIR", "$home/.openclaw/workspace")
            put("CPATH", "$prefix/include/glib-2.0:$prefix/lib/glib-2.0/include")
        }
    }
}
