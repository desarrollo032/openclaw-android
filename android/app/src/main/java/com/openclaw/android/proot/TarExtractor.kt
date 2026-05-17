package com.openclaw.android.proot

import com.openclaw.android.OpenClawLogger
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * TarExtractor — Extracción segura de archivos tar.gz con manejo de symlinks.
 *
 * Responsabilidades:
 *   - Extraer archivos tar.gz con Apache Commons Compress
 *   - Crear symlinks y hardlinks correctamente
 *   - Resolver links pendientes (tar puede tener links antes de targets)
 *   - Aplicar permisos de ejecución a binarios
 *   - Validar rutas para evitar path-traversal attacks
 */
class TarExtractor(
    private val rootfs: File,
    private val tag: String = "TarExtractor"
) {

    data class PendingLink(
        val outputFile: File,
        val linkName: String,
        val hardLink: Boolean
    )

    /**
     * Extrae un archivo tar.gz en [rootfs] preservando enlaces.
     * Alpine depende de symlinks críticos como /bin/sh → busybox
     * y /lib/ld-musl-aarch64.so.1 → libc.
     */
    fun extractArchive(
        tarFile: File,
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val pendingLinks = mutableListOf<PendingLink>()

        onProgress("Extrayendo Alpine (~50 MB)…")
        return try {
            FileInputStream(tarFile).use { fis ->
                BufferedInputStream(fis, 64 * 1024).use { bis ->
                    GzipCompressorInputStream(bis).use { gzis ->
                        TarArchiveInputStream(gzis).use { tais ->
                            var entry = tais.nextEntry
                            var extractedCount = 0
                            while (entry != null) {
                                val outputFile = safeRootfsFile(entry.name)
                                if (outputFile == null) {
                                    log("Skipping unsafe tar entry: ${entry.name}")
                                    entry = tais.nextEntry
                                    continue
                                }

                                when {
                                    entry.isDirectory -> outputFile.mkdirs()
                                    entry.isSymbolicLink -> {
                                        createArchiveLink(
                                            outputFile = outputFile,
                                            linkName = entry.linkName,
                                            hardLink = false,
                                            pendingLinks = pendingLinks
                                        )
                                    }
                                    entry.isLink -> {
                                        createArchiveLink(
                                            outputFile = outputFile,
                                            linkName = entry.linkName,
                                            hardLink = true,
                                            pendingLinks = pendingLinks
                                        )
                                    }
                                    else -> {
                                        outputFile.parentFile?.mkdirs()
                                        outputFile.outputStream().use { out ->
                                            val buf = ByteArray(64 * 1024)
                                            var n = tais.read(buf, 0, buf.size)
                                            while (n != -1) {
                                                out.write(buf, 0, n)
                                                n = tais.read(buf, 0, buf.size)
                                            }
                                        }
                                        applyTarFilePermissions(outputFile, entry.mode)
                                    }
                                }

                                extractedCount++
                                if (extractedCount % 500 == 0) {
                                    onProgress("Extrayendo Alpine… (archivo $extractedCount)")
                                }
                                entry = tais.nextEntry
                            }
                        }
                    }
                }
            }

            resolvePendingLinks(pendingLinks)
            true
        } catch (e: Exception) {
            log("tar extraction via commons-compress failed: ${e.message}")
            onError("Error extrayendo Alpine: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Aplica permisos de ejecución a todos los binarios dentro del rootfs.
     * Safety net para archivos extraídos que perdieron permisos.
     */
    fun applyExecutablePermissions() {
        val binDirs = listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin")

        for (dirName in binDirs) {
            val dir = File(rootfs, dirName)
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && !file.canExecute()) {
                    file.setExecutable(true, false)
                }
            }
        }

        // Binarios críticos que deben ser ejecutables
        val criticals = listOf(
            "bin/sh", "bin/busybox", "sbin/apk",
            "usr/bin/node", "usr/bin/npm", "usr/local/bin/openclaw"
        )
        criticals.forEach { rel ->
            val f = File(rootfs, rel)
            if (f.exists() && !f.canExecute()) {
                if (Files.isSymbolicLink(f.toPath())) {
                    repairBrokenSymlink(f)
                }
                f.setExecutable(true, false)
            }
        }
    }

    // ── Link helpers ─────────────────────────────────────────────────────────

    private fun createArchiveLink(
        outputFile: File,
        linkName: String,
        hardLink: Boolean,
        pendingLinks: MutableList<PendingLink>
    ) {
        outputFile.parentFile?.mkdirs()
        Files.deleteIfExists(outputFile.toPath())

        if (hardLink) {
            val targetFile = resolveArchiveLinkTarget(outputFile, linkName)
            if (targetFile == null || !targetFile.exists()) {
                pendingLinks += PendingLink(outputFile, linkName, hardLink = true)
                return
            }

            try {
                Files.createLink(outputFile.toPath(), targetFile.toPath())
            } catch (e: Exception) {
                if (!copyResolvedLinkTarget(outputFile, linkName)) {
                    pendingLinks += PendingLink(outputFile, linkName, hardLink = true)
                }
            }
            return
        }

        val symlinkTarget = symlinkTargetForHost(outputFile, linkName)
        if (symlinkTarget == null) {
            log("Skipping unsafe symlink ${outputFile.name} -> $linkName")
            return
        }

        try {
            Files.createSymbolicLink(outputFile.toPath(), Paths.get(symlinkTarget))
        } catch (e: Exception) {
            if (!copyResolvedLinkTarget(outputFile, linkName)) {
                pendingLinks += PendingLink(outputFile, linkName, hardLink = false)
            }
        }
    }

    private fun resolvePendingLinks(pendingLinks: List<PendingLink>) {
        pendingLinks.forEach { link ->
            if (link.outputFile.exists()) return@forEach

            val resolved = if (link.hardLink) {
                copyResolvedLinkTarget(link.outputFile, link.linkName)
            } else {
                val symlinkTarget = symlinkTargetForHost(link.outputFile, link.linkName)
                if (symlinkTarget != null) {
                    try {
                        link.outputFile.parentFile?.mkdirs()
                        Files.createSymbolicLink(link.outputFile.toPath(), Paths.get(symlinkTarget))
                        true
                    } catch (e: Exception) {
                        copyResolvedLinkTarget(link.outputFile, link.linkName)
                    }
                } else {
                    false
                }
            }

            if (!resolved) {
                log("Unresolved tar link: ${link.outputFile.absolutePath} -> ${link.linkName}")
            }
        }
    }

    // ── Path resolution ──────────────────────────────────────────────────────

    fun safeRootfsFile(entryName: String): File? {
        val safeName = entryName.replace('\\', '/').removePrefix("/")
        if (safeName.isBlank()) return null

        val root = rootfs.toPath().toAbsolutePath().normalize()
        val output = root.resolve(safeName).normalize()
        return if (output.startsWith(root)) output.toFile() else null
    }

    internal fun resolveArchiveLinkTarget(outputFile: File, linkName: String): File? {
        val root = rootfs.toPath().toAbsolutePath().normalize()
        val parent = outputFile.parentFile?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        val target = if (linkName.startsWith("/")) {
            root.resolve(linkName.removePrefix("/"))
        } else {
            parent.resolve(linkName)
        }.normalize()

        return if (target.startsWith(root)) target.toFile() else null
    }

    private fun symlinkTargetForHost(outputFile: File, linkName: String): String? {
        val targetFile = resolveArchiveLinkTarget(outputFile, linkName) ?: return null
        val parent = outputFile.parentFile?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        return parent.relativize(targetFile.toPath().toAbsolutePath().normalize()).toString()
    }

    private fun copyResolvedLinkTarget(outputFile: File, linkName: String): Boolean {
        val targetFile = resolveArchiveLinkTarget(outputFile, linkName) ?: return false
        if (!targetFile.exists()) return false

        outputFile.parentFile?.mkdirs()
        return try {
            if (targetFile.isDirectory) {
                outputFile.mkdirs()
            } else {
                Files.copy(
                    targetFile.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
                outputFile.setReadable(true, false)
                if (targetFile.canExecute()) {
                    outputFile.setExecutable(true, false)
                }
            }
            true
        } catch (e: Exception) {
            log("Could not materialize tar link ${outputFile.absolutePath} -> $linkName: ${e.message}")
            false
        }
    }

    // ── Permission helpers ───────────────────────────────────────────────────

    private fun applyTarFilePermissions(outputFile: File, mode: Int) {
        val ownerExec  = (mode and 0b001_000_000) != 0
        val groupExec  = (mode and 0b000_001_000) != 0
        val otherExec  = (mode and 0b000_000_001) != 0
        if (ownerExec || groupExec || otherExec) {
            outputFile.setExecutable(true, false)
        }
        outputFile.setReadable(true, false)
    }

    private fun repairBrokenSymlink(f: File) {
        val target = Files.readSymbolicLink(f.toPath())
        val targetFile = File(f.parentFile, target.toString())
        if (!targetFile.exists() || !targetFile.canExecute()) {
            log("Reparando symlink ${f.absolutePath} -> $target (target no ejecutable)")
            Files.delete(f.toPath())
            val resolvedTarget = resolveArchiveLinkTarget(f, target.toString())
            if (resolvedTarget != null && resolvedTarget.exists() && resolvedTarget.canExecute()) {
                resolvedTarget.copyTo(f, overwrite = true)
                log("Symlink materializado como copia de ${resolvedTarget.absolutePath}")
            } else {
                log("No se pudo resolver target $target para symlink ${f.name}")
            }
        }
    }

    private fun log(msg: String) {
        OpenClawLogger.log(tag, msg)
    }
}
