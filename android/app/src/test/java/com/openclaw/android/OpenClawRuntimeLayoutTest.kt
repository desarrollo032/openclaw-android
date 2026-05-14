package com.openclaw.android

import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenClawRuntimeLayoutTest {

    @Test
    fun setupFilesLayoutCreatesHomeOpenClawTmpAndUsrDirs() {
        val context = RuntimeEnvironment.getApplication()

        OpenClawInstaller.setupFilesLayout(context)

        File(context.filesDir, "home").isDirectory shouldBe true
        File(context.filesDir, "home/.openclaw/tmp").isDirectory shouldBe true
        File(context.filesDir, "usr/bin").isDirectory shouldBe true
        File(context.filesDir, "usr/opt").isDirectory shouldBe true
    }

    @Test
    fun tarGzExtractionPreservesOpenClawRootEntries() {
        val context = RuntimeEnvironment.getApplication()
        val targetDir = File(context.cacheDir, "openclaw-root-extract").apply {
            deleteRecursively()
            mkdirs()
        }
        val archive = tarGzOf(".openclaw/openclaw.json" to "{\"ok\":true}")

        val result = ByteArrayInputStream(archive).use { stream ->
            extractTarGzFromStream(stream, targetDir)
        }

        result shouldBe true
        File(targetDir, ".openclaw/openclaw.json").readText() shouldBe "{\"ok\":true}"
        File(targetDir, "openclaw/openclaw.json").exists() shouldBe false
    }
}

private fun tarGzOf(vararg entries: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    GZIPOutputStream(bytes).use { gzip ->
        entries.forEach { (name, content) ->
            val data = content.toByteArray(StandardCharsets.UTF_8)
            gzip.write(tarHeader(name, data.size.toLong()))
            gzip.write(data)
            val padding = (512 - (data.size % 512)) % 512
            if (padding > 0) gzip.write(ByteArray(padding))
        }
        gzip.write(ByteArray(1024))
    }
    return bytes.toByteArray()
}

private fun tarHeader(name: String, size: Long): ByteArray {
    val header = ByteArray(512)
    fun writeString(offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        bytes.copyInto(header, offset, endIndex = minOf(bytes.size, length))
    }
    fun writeOctal(offset: Int, length: Int, value: Long) {
        writeString(offset, length, value.toString(8).padStart(length - 1, '0'))
    }

    writeString(0, 100, name)
    writeOctal(100, 8, 420)
    writeOctal(108, 8, 0)
    writeOctal(116, 8, 0)
    writeOctal(124, 12, size)
    writeOctal(136, 12, 0)
    repeat(8) { header[148 + it] = ' '.code.toByte() }
    header[156] = '0'.code.toByte()
    writeString(257, 6, "ustar")
    writeString(263, 2, "00")

    val checksum = header.sumOf { it.toInt() and 0xff }
    writeString(148, 8, checksum.toString(8).padStart(6, '0') + "\u0000 ")
    return header
}
