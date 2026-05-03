package com.openclaw.android

import android.content.Context
import java.io.File
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import android.system.Os

/**
 * ProotManager — descarga, instala y ejecuta proot como binario nativo.
 *opcion 2 
 * Por qué proot resuelve el Phantom Process Killer:
 *   - proot es un binario estático compilado con NDK (no un proceso hijo de shell)
 *   - Se ejecuta como proceso nativo de la app, no como subproceso de bash
 *   - Android 12+ solo mata procesos hijos de procesos que NO son foreground services
 *   - Al correr proot desde un foreground service (OpenClawService), sobrevive
 *
 * Arquitectura:
 *   filesDir/
 *   ├── bin/
 *   │   └── proot          ← binario estático arm64 descargado
 *   └── ubuntu-rootfs/     ← rootfs Ubuntu minimal extraído
 *       ├── bin/
 *       ├── usr/
 *       └── ...
 */
object ProotManager {

    private const val TAG = "ProotManager"

    // URL del binario proot estático para arm64 (Termux packages, GPL v2)
    // Versión 5.4.0 — estable, probada en Android 7-15
    private const val PROOT_URL_ARM64 =
        "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0_aarch64.deb"

    // URL del rootfs Ubuntu 24.04 minimal para arm64 (proot-distro)
    // ~80MB comprimido, ~250MB extraído
    private const val UBUNTU_ROOTFS_URL =
        "https://github.com/termux/proot-distro/releases/download/v4.22.0/ubuntu-aarch64-pd-v4.22.0.tar.xz"

    // Fallback: Ubuntu 22.04 LTS (más estable en dispositivos viejos)
    private const val UBUNTU_ROOTFS_URL_FALLBACK =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.1-base-arm64.tar.gz"

    data class ProotPaths(
        val prootBin: File,
        val rootfsDir: File,
        val homeDir: File,
    )

    fun getPaths(context: Context): ProotPaths {
        val filesDir = context.filesDir
        return ProotPaths(
            prootBin = File(filesDir, "bin/proot"),
            rootfsDir = File(filesDir, "ubuntu-rootfs"),
            homeDir = File(filesDir, "home"),
        )
    }

    fun isProotReady(context: Context): Boolean {
        val paths = getPaths(context)
        return paths.prootBin.exists() && paths.prootBin.canExecute()
    }

    fun isRootfsReady(context: Context): Boolean {
        val paths = getPaths(context)
        // Verificar que el rootfs tiene estructura básica de Ubuntu
        return paths.rootfsDir.resolve("usr/bin/apt").exists() ||
            paths.rootfsDir.resolve("bin/bash").exists() ||
            paths.rootfsDir.resolve("usr/bin/bash").exists()
    }

    /**
     * Descarga el binario proot desde Termux packages.
     * El .deb contiene el binario en data.tar.xz → data/data/com.termux/files/usr/bin/proot
     */
    fun downloadProot(
        context: Context,
        onProgress: (Int, String) -> Unit,
    ): Boolean {
        val paths = getPaths(context)
        val binDir = paths.prootBin.parentFile!!
        binDir.mkdirs()

        // Si ya existe y es ejecutable, no re-descargar
        if (paths.prootBin.exists() && paths.prootBin.canExecute() && paths.prootBin.length() > 100_000) {
            AppLogger.i(TAG, "proot already present: ${paths.prootBin.absolutePath}")
            return true
        }

        val debFile = File(context.cacheDir, "proot.deb")
        val dataTar = File(context.cacheDir, "proot-data.tar.xz")

        return try {
            onProgress(2, "Descargando proot (binario nativo)...")
            AppLogger.i(TAG, "Downloading proot from $PROOT_URL_ARM64")

            downloadFile(PROOT_URL_ARM64, debFile) { downloaded, total ->
                if (total > 0) {
                    val pct = (downloaded * 8 / total).toInt().coerceIn(2, 8)
                    onProgress(pct, "Descargando proot... ${downloaded / 1024}KB / ${total / 1024}KB")
                }
            }

            onProgress(8, "Extrayendo proot del paquete .deb...")

            // Un .deb es un archivo ar. Extraemos data.tar.xz manualmente
            extractProotFromDeb(debFile, dataTar, paths.prootBin)

            if (!paths.prootBin.exists() || paths.prootBin.length() < 100_000) {
                AppLogger.e(TAG, "proot extraction failed — binary missing or too small")
                return false
            }

            paths.prootBin.setExecutable(true, false)
            AppLogger.i(TAG, "proot ready: ${paths.prootBin.absolutePath} (${paths.prootBin.length()} bytes)")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "downloadProot failed: ${e.message}", e)
            false
        } finally {
            debFile.delete()
            dataTar.delete()
        }
    }

    /**
     * Descarga y extrae el rootfs Ubuntu minimal para arm64.
     * ~80MB descarga, ~250MB en disco.
     */
    fun downloadAndExtractRootfs(
        context: Context,
        onProgress: (Int, String) -> Unit,
    ): Boolean {
        val paths = getPaths(context)

        if (isRootfsReady(context)) {
            AppLogger.i(TAG, "Ubuntu rootfs already ready at ${paths.rootfsDir.absolutePath}")
            return true
        }

        paths.rootfsDir.mkdirs()
        val tarFile = File(context.cacheDir, "ubuntu-rootfs.tar.xz")

        return try {
            onProgress(10, "Descargando Ubuntu rootfs (~80MB)...")
            AppLogger.i(TAG, "Downloading Ubuntu rootfs from $UBUNTU_ROOTFS_URL")

            var downloadOk = false
            try {
                downloadFile(UBUNTU_ROOTFS_URL, tarFile) { downloaded, total ->
                    if (total > 0) {
                        val pct = 10 + (downloaded * 40 / total).toInt().coerceIn(0, 40)
                        onProgress(pct, "Descargando Ubuntu... ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
                    } else {
                        onProgress(20, "Descargando Ubuntu... ${downloaded / 1024 / 1024}MB")
                    }
                }
                downloadOk = tarFile.exists() && tarFile.length() > 10_000_000
            } catch (e: Exception) {
                AppLogger.w(TAG, "Primary URL failed: ${e.message}, trying fallback...")
            }

            if (!downloadOk) {
                onProgress(10, "Intentando URL alternativa...")
                downloadFile(UBUNTU_ROOTFS_URL_FALLBACK, tarFile) { downloaded, total ->
                    if (total > 0) {
                        val pct = 10 + (downloaded * 40 / total).toInt().coerceIn(0, 40)
                        onProgress(pct, "Descargando Ubuntu (fallback)... ${downloaded / 1024 / 1024}MB")
                    }
                }
            }

            if (!tarFile.exists() || tarFile.length() < 10_000_000) {
                AppLogger.e(TAG, "Rootfs download failed — file too small: ${tarFile.length()}")
                return false
            }

            onProgress(50, "Extrayendo rootfs Ubuntu (~250MB, puede tardar 2-3 min)...")
            AppLogger.i(TAG, "Extracting rootfs to ${paths.rootfsDir.absolutePath}")

            val count = extractTarXzOrGz(tarFile, paths.rootfsDir) { entriesProcessed ->
                // Actualizar progreso cada 500 entradas
                if (entriesProcessed % 500 == 0) {
                    val pct = (50 + (entriesProcessed / 200).coerceAtMost(28)).toInt()
                    onProgress(pct, "Extrayendo rootfs... $entriesProcessed archivos")
                }
            }

            AppLogger.i(TAG, "Rootfs extracted: $count entries")

            // Crear directorios necesarios para proot
            setupRootfsDirs(paths.rootfsDir)

            onProgress(80, "Rootfs Ubuntu listo ($count archivos)")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "downloadAndExtractRootfs failed: ${e.message}", e)
            false
        } finally {
            tarFile.delete()
        }
    }

    /**
     * Construye el comando proot para ejecutar un comando dentro del rootfs Ubuntu.
     *
     * Equivalente a: proot --rootfs=<dir> -0 -w /root /bin/bash -c "<cmd>"
     *
     * Flags importantes:
     *   -0  : simula root (uid 0) — necesario para apt
     *   -w  : directorio de trabajo inicial
     *   --bind=/proc : monta /proc del host (necesario para Node.js)
     *   --bind=/dev  : monta /dev del host
     *   --kill-on-exit : mata todos los procesos al salir
     */
    fun buildProotCommand(
        context: Context,
        command: String,
        extraBinds: List<String> = emptyList(),
    ): List<String> {
        val paths = getPaths(context)
        val rootfs = paths.rootfsDir.absolutePath
        val proot = paths.prootBin.absolutePath

        return buildList {
            add(proot)
            add("--rootfs=$rootfs")
            add("-0")                          // simular root
            add("-w=/root")                    // working dir dentro del rootfs
            add("--kill-on-exit")
            add("--link2symlink")              // convierte hardlinks a symlinks (necesario en Android)
            add("--sysvipc")                   // IPC para Node.js
            // Binds esenciales
            add("--bind=/proc")
            add("--bind=/dev")
            add("--bind=/sys")
            add("--bind=/dev/urandom:/dev/random")  // Android no tiene /dev/random real
            // Bind del home de la app para intercambio de archivos
            add("--bind=${paths.homeDir.absolutePath}:/mnt/app-home")
            // Binds adicionales opcionales
            extraBinds.forEach { add("--bind=$it") }
            // Shell y comando
            add("/bin/sh")
            add("-c")
            add(command)
        }
    }

    /**
     * Ejecuta un comando dentro del rootfs Ubuntu via proot.
     * Streams la salida línea a línea.
     *
     * @return exit code del proceso
     */
    fun runInProot(
        context: Context,
        command: String,
        env: Map<String, String> = buildProotEnv(context),
        onOutput: (String) -> Unit,
    ): Int {
        val cmd = buildProotCommand(context, command)
        AppLogger.i(TAG, "Running in proot: $command")

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(false)

            val process = pb.start()

            val stdoutThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[proot] $line")
                    onOutput(line)
                }
            }.also { it.start() }

            val stderrThread = Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[proot-err] $line")
                    onOutput("[err] $line")
                }
            }.also { it.start() }

            stdoutThread.join()
            stderrThread.join()
            val exitCode = process.waitFor()
            AppLogger.i(TAG, "proot command exited with code $exitCode")
            exitCode
        } catch (e: Exception) {
            AppLogger.e(TAG, "runInProot failed: ${e.message}", e)
            onOutput("Error ejecutando proot: ${e.message}")
            -1
        }
    }

    /**
     * Lanza el gateway de OpenClaw dentro de proot como proceso persistente.
     * Retorna el Process para que OpenClawService pueda monitorearlo.
     */
    fun launchGatewayInProot(context: Context): Process? {
        val paths = getPaths(context)
        val cmd = buildProotCommand(
            context,
            // Lanzar openclaw gateway con las variables correctas
            """
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export NODE_PATH=/usr/local/lib/node_modules
            export OA_GLIBC=0
            export CONTAINER=1
            cd /root
            exec node /usr/local/lib/node_modules/openclaw/openclaw.mjs gateway --host 0.0.0.0
            """.trimIndent(),
        )

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(buildProotEnv(context))
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val process = pb.start()
            AppLogger.i(TAG, "OpenClaw gateway launched in proot")
            process
        } catch (e: Exception) {
            AppLogger.e(TAG, "launchGatewayInProot failed: ${e.message}", e)
            null
        }
    }

    fun buildProotEnv(context: Context): Map<String, String> {
        val paths = getPaths(context)
        return mapOf(
            "HOME" to paths.homeDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to "/system/bin:/bin",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            // Necesario para que proot funcione en Android
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_TMP_DIR" to context.cacheDir.absolutePath,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers privados
    // ─────────────────────────────────────────────────────────────────────────

    private fun downloadFile(
        urlStr: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "OpenClaw-Android/1.0")

        val total = conn.contentLengthLong
        var downloaded = 0L

        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(32 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    output.write(buf, 0, n)
                    downloaded += n
                    onProgress(downloaded, total)
                }
            }
        }
    }

    /**
     * Extrae el binario proot de un paquete .deb de Termux.
     *
     * Estructura de un .deb:
     *   ar archive:
     *     debian-binary
     *     control.tar.xz
     *     data.tar.xz   ← aquí está el binario
     *
     * El binario está en: data/data/com.termux/files/usr/bin/proot
     */
    private fun extractProotFromDeb(debFile: File, dataTarDest: File, prootDest: File) {
        // Parsear el formato ar manualmente (es simple: magic + entries)
        debFile.inputStream().use { fis ->
            val magic = ByteArray(8)
            fis.read(magic)
            val magicStr = String(magic)
            if (!magicStr.startsWith("!<arch>")) {
                throw IllegalStateException("Not a valid .deb file (bad ar magic: $magicStr)")
            }

            // Leer entradas ar hasta encontrar data.tar.xz
            while (true) {
                val header = ByteArray(60)
                val read = fis.read(header)
                if (read < 60) break

                val name = String(header, 0, 16).trim()
                val sizeStr = String(header, 48, 10).trim()
                val size = sizeStr.toLongOrNull() ?: break

                AppLogger.d(TAG, "ar entry: '$name' size=$size")

                if (name.startsWith("data.tar")) {
                    // Encontrado — copiar a archivo temporal
                    dataTarDest.parentFile?.mkdirs()
                    val buf = ByteArray(32 * 1024)
                    var remaining = size
                    dataTarDest.outputStream().use { out ->
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = fis.read(buf, 0, toRead)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    break
                } else {
                    // Saltar esta entrada (con padding a 2 bytes)
                    val toSkip = size + (size % 2)
                    fis.skip(toSkip)
                }
            }
        }

        if (!dataTarDest.exists() || dataTarDest.length() == 0L) {
            throw IllegalStateException("data.tar.xz not found in .deb")
        }

        // Extraer el binario proot del data.tar.xz
        // Buscar: ./data/data/com.termux/files/usr/bin/proot
        val possiblePaths = setOf(
            "data/data/com.termux/files/usr/bin/proot",
            "./data/data/com.termux/files/usr/bin/proot",
            "usr/bin/proot",
            "./usr/bin/proot",
        )

        dataTarDest.inputStream().use { raw ->
            BufferedInputStream(raw).use { buf ->
                org.apache.commons.compress.compressors.xz.XZCompressorInputStream(buf).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            val entryName = entry.name.trimStart('/')
                            AppLogger.d(TAG, "tar entry: $entryName")
                            if (possiblePaths.any { entryName.endsWith(it.trimStart('.', '/')) }) {
                                prootDest.parentFile?.mkdirs()
                                prootDest.outputStream().use { out ->
                                    tar.copyTo(out)
                                }
                                AppLogger.i(TAG, "Extracted proot binary: $entryName → ${prootDest.absolutePath}")
                                break
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
            }
        }
    }

    /**
     * Extrae un tar.xz o tar.gz al directorio destino.
     * Detecta el formato por los primeros bytes.
     */
    private fun extractTarXzOrGz(
        tarFile: File,
        destDir: File,
        onEntry: (Int) -> Unit,
    ): Int {
        var count = 0
        tarFile.inputStream().use { raw ->
            BufferedInputStream(raw).use { buf ->
                // Detectar formato: XZ empieza con FD 37 7A 58 5A 00, GZ con 1F 8B
                buf.mark(6)
                val header = ByteArray(6)
                buf.read(header)
                buf.reset()

                val isXz = header[0] == 0xFD.toByte() && header[1] == 0x37.toByte()
                val decompressed = if (isXz) {
                    org.apache.commons.compress.compressors.xz.XZCompressorInputStream(buf)
                } else {
                    GZIPInputStream(buf)
                }

                decompressed.use { decomp ->
                    TarArchiveInputStream(decomp).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            val destFile = File(destDir, entry.name)
                            try {
                                when {
                                    entry.isDirectory -> destFile.mkdirs()
                                    entry.isSymbolicLink -> {
                                        destFile.parentFile?.mkdirs()
                                        destFile.delete()
                                        try {
                                            Os.symlink(entry.linkName, destFile.absolutePath)
                                        } catch (_: Exception) {}
                                    }
                                    else -> {
                                        destFile.parentFile?.mkdirs()
                                        destFile.outputStream().use { out -> tar.copyTo(out) }
                                        if ((entry.mode and 0b001_001_001) != 0) {
                                            destFile.setExecutable(true, false)
                                        }
                                    }
                                }
                                count++
                                onEntry(count)
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Skip entry ${entry.name}: ${e.message}")
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
            }
        }
        return count
    }

    /**
     * Crea directorios y archivos necesarios para que proot funcione correctamente.
     */
    private fun setupRootfsDirs(rootfsDir: File) {
        // Directorios que proot necesita montar
        listOf("proc", "dev", "sys", "tmp", "root", "mnt/app-home").forEach { dir ->
            File(rootfsDir, dir).mkdirs()
        }

        // resolv.conf con DNS de Google (para que apt funcione dentro de proot)
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }

        // hosts básico
        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists()) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }

        AppLogger.i(TAG, "Rootfs dirs setup complete")
    }
}
