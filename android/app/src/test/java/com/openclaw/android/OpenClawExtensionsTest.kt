package com.openclaw.android

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

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

    // ── OpenClawInstaller.getConfigDir ────────────────────────────────────────

    @Test
    fun `should return openclaw directory under filesDir home`() {
        val dir = OpenClawInstaller.getConfigDir(context)
        dir.name shouldBe ".openclaw"
        dir.parentFile shouldBe File(context.filesDir, "home")
    }

    // ── isGatewayAlive ────────────────────────────────────────────────────────

    @Test
    fun `should return false when gateway is not running`() {
        isGatewayAlive() shouldBe false
    }
}
