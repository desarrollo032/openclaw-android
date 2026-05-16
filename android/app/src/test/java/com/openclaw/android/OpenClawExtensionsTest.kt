package com.openclaw.android

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OpenClawExtensionsTest {

    private val context = RuntimeEnvironment.getApplication()

    // ── File.deleteRecursivelySafe ────────────────────────────────────────────

    @Test
    fun `should safely delete non-existent directory`() {
        val nonExistent = File(context.cacheDir, "non_existent_dir")
        nonExistent.deleteRecursivelySafe()
    }

    @Test
    fun `should delete directory with contents`() {
        val dir = File(context.cacheDir, "test_delete_dir")
        dir.mkdirs()
        File(dir, "file1.txt").writeText("content")
        File(dir, "subdir").mkdirs()
        File(dir, "subdir/file2.txt").writeText("content2")

        dir.exists() shouldBe true
        dir.deleteRecursivelySafe()
        dir.exists() shouldBe false
    }

    // ── ByteArray.toHex ──────────────────────────────────────────────────────

    @Test
    fun `should convert bytes to hex string`() {
        val bytes = byteArrayOf(0x00, 0x0F, 0xFF.toByte())
        bytes.toHex() shouldBe "000fff"
    }

    @Test
    fun `should handle empty array`() {
        byteArrayOf().toHex() shouldBe ""
    }

    // ── OpenClawInstaller.getConfigDir ────────────────────────────────────────

    @Test
    fun `should return openclaw directory under filesDir home`() {
        val dir = OpenClawInstaller.getConfigDir(context)
        dir.name shouldBe ".openclaw"
        dir.parentFile shouldBe File(context.filesDir, "home")
    }

    // ── Context.assetSize ─────────────────────────────────────────────────────

    @Test
    fun `should return minus 1 for non-existent asset`() {
        context.assetSize("non_existent_asset.txt") shouldBe -1L
    }

    // ── extractTarXzFromStream ────────────────────────────────────────────────

    @Test
    fun `should handle invalid tar xz gracefully`() = runTest {
        val invalidData = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val result = ByteArrayInputStream(invalidData).use { stream ->
            extractTarXzFromStream(stream, context.cacheDir, null)
        }
        result shouldBe false
    }

    // ── extractTarGzFromStream ────────────────────────────────────────────────

    @Test
    fun `should handle invalid tar gz gracefully`() = runTest {
        val invalidData = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val result = ByteArrayInputStream(invalidData).use { stream ->
            extractTarGzFromStream(stream, context.cacheDir)
        }
        result shouldBe false
    }
}
