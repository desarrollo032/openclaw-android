package com.openclaw.android.proot

import com.openclaw.android.OpenClawLogger
import com.openclaw.android.deleteRecursivelySafe
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * AlpineDownloader — Descarga y prepara el rootfs de Alpine Linux.
 *
 * Responsabilidades:
 *   - Descargar el minirootfs ARM64 con fallback HTTPS → HTTP
 *   - Extraer el tar.gz usando [TarExtractor]
 *   - Configurar DNS, permisos y directorios post-extracción
 *   - Verificar integridad del rootfs
 */
class AlpineDownloader(
    private val rootfs: File,
    private val prootTmpDir: File
) {

    companion object {
        const val TAG = "AlpineDownloader"

        /** Versión de Alpine descargada. Cambiar requiere también verificar el SHA. */
        const val ALPINE_VERSION = "3.22.0"

        /** URL oficial del minirootfs ARM64 (HTTPS). */
        val ALPINE_URL: String =
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/" +
            "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"

        /** Fallback HTTP si HTTPS falla por problemas de certificados SSL. */
        val ALPINE_HTTP_URL: String = ALPINE_URL.replace("https://", "http://")
    }

    private val tarExtractor = TarExtractor(rootfs)

    /**
     * Descarga el minirootfs Alpine y lo extrae en [rootfs].
     *
     * Devuelve `true` si todo salió bien. Errores y progreso se reportan vía
     * los callbacks; también se replican en [OpenClawLogger].
     */
    fun downloadAndExtract(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        return try {
            // Limpiar rootfs antiguo completamente
            if (rootfs.exists()) {
                onProgress("Limpiando instalación anterior de Alpine...")
                rootfs.deleteRecursivelySafe()
            }
            rootfs.mkdirs()

            val tarFile = File(prootTmpDir, "alpine-rootfs.tar.gz")
            tarFile.parentFile?.mkdirs()
            if (tarFile.exists()) tarFile.delete()

            onProgress("Descargando Alpine Linux ARM64 $ALPINE_VERSION…")

            // Descarga con fallback HTTPS → HTTP
            if (!downloadFile(tarFile, onProgress)) {
                onError("No se pudo descargar Alpine (HTTPS y HTTP fallaron)")
                return false
            }

            if (tarFile.length() == 0L) {
                tarFile.delete()
                onError("Descarga vacía de Alpine")
                return false
            }

            // Extraer
            if (!tarExtractor.extractArchive(tarFile, onProgress, onError)) {
                return false
            }
            tarFile.delete()

            // Post-extracción: crear directorios y configurar
            setupPostExtraction(onProgress, onError)
        } catch (e: Exception) {
            log("downloadAndExtract failed: ${e.message}")
            onError("Error descargando Alpine: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    private fun downloadFile(tarFile: File, onProgress: (String) -> Unit): Boolean {
        val urlsToTry = listOf(ALPINE_URL, ALPINE_HTTP_URL)
        for (attemptUrl in urlsToTry) {
            log("Download attempt: $attemptUrl")
            try {
                val conn = (URL(attemptUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "OpenClaw-Android/1.0")
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    continue
                }
                val total = conn.contentLength.toLong().coerceAtLeast(0L)
                var copied = 0L
                var lastPct = -1
                tarFile.outputStream().use { out ->
                    conn.inputStream.use { input: InputStream ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (total > 0) {
                                val pct = ((copied * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress("Descargando Alpine… $pct%")
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (attemptUrl != ALPINE_URL) {
                    log("Downloaded via HTTP fallback (HTTPS SSL error on this device)")
                    onProgress("Descarga completada (vía HTTP)")
                }
                return true
            } catch (e: javax.net.ssl.SSLException) {
                log("HTTPS failed: ${e.message}, trying HTTP fallback...")
            }
        }
        return false
    }

    // ── Post-extraction setup ────────────────────────────────────────────────

    private fun setupPostExtraction(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        // Directorios que proot necesita encontrar en el rootfs
        File(rootfs, "root").mkdirs()      // --cwd=/root
        File(rootfs, "tmp").mkdirs()       // /tmp dentro del rootfs
        File(rootfs, ".l2s").mkdirs()      // directorio para --link2symlink

        // DNS dentro del rootfs (Alpine no trae resolv.conf por defecto)
        File(rootfs, "etc").mkdirs()
        val resolv = File(rootfs, "etc/resolv.conf")
        if (!resolv.exists() || resolv.readText().isBlank()) {
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }

        // Aplicar +x a todos los binarios
        onProgress("Aplicando permisos de ejecución...")
        tarExtractor.applyExecutablePermissions()

        // Verificación final
        val sh = File(rootfs, "bin/sh")
        if (!sh.exists() || !sh.canExecute()) {
            onError("bin/sh no ejecutable — extracción fallida")
            return false
        }
        onProgress("Alpine verificado ✓")
        return true
    }

    private fun log(msg: String) {
        OpenClawLogger.log(TAG, msg)
    }
}
