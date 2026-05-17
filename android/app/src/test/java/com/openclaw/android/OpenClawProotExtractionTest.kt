package com.openclaw.android

import com.openclaw.android.proot.OpenClawProot
import com.openclaw.android.proot.TarExtractor
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenClawProotExtractionTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var proot: OpenClawProot

    @Before
    fun setUp() {
        proot = OpenClawProot(context)
        proot.wipeAlpine()
    }

    @Test
    fun `extracting alpine archive preserves executable symlink entries`() {
        val archive = File(context.cacheDir, "rootfs-with-symlink.tar.gz")
        writeTarGz(archive) { tar ->
            tar.addDirectory("bin/")
            tar.addFile("bin/busybox", "busybox binary", mode = 0b111_101_101)
            tar.addSymlink("bin/sh", "busybox")
        }

        val errors = mutableListOf<String>()
        val ok = TarExtractor(proot.rootfs).extractArchive(
            tarFile = archive,
            onProgress = {},
            onError = { errors += it }
        )

        ok shouldBe true
        errors.isEmpty() shouldBe true

        val sh = File(proot.rootfs, "bin/sh")
        sh.exists() shouldBe true
        sh.canExecute() shouldBe true
        (Files.isSymbolicLink(sh.toPath()) || sh.readText() == "busybox binary") shouldBe true
    }

    @Test
    fun `alpine is not installed when bin sh is an empty regular file`() {
        val sh = File(proot.rootfs, "bin/sh")
        sh.parentFile?.mkdirs()
        sh.writeText("")
        sh.setExecutable(true, false)

        proot.isAlpineInstalled() shouldBe false
    }

    private fun writeTarGz(
        output: File,
        block: (TarArchiveOutputStream) -> Unit
    ) {
        output.parentFile?.mkdirs()
        output.outputStream().use { fileOut ->
            GzipCompressorOutputStream(fileOut).use { gzOut ->
                TarArchiveOutputStream(gzOut).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    block(tar)
                }
            }
        }
    }

    private fun TarArchiveOutputStream.addDirectory(name: String) {
        val entry = TarArchiveEntry(name)
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.addFile(name: String, content: String, mode: Int) {
        val bytes = content.toByteArray()
        val entry = TarArchiveEntry(name).apply {
            size = bytes.size.toLong()
            this.mode = mode
        }
        putArchiveEntry(entry)
        write(bytes)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.addSymlink(name: String, target: String) {
        val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply {
            linkName = target
        }
        putArchiveEntry(entry)
        closeArchiveEntry()
    }
}
