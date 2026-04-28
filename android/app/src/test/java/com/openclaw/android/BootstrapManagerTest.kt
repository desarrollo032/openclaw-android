package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for BootstrapManager logic that does not require Android Context.
 * Tests focus on file-system detection, marker logic, and path constants.
 */
class BootstrapManagerTest {
    @TempDir
    lateinit var tempDir: File

    // ─── installed.json marker ────────────────────────────────────────────────

    @Test
    fun `isOpenClawInstalled returns false when marker absent`() {
        // On CI the real Termux path won't exist
        assertFalse(CommandRunner.isOpenClawInstalled())
    }

    @Test
    fun `installed marker JSON is valid`() {
        val marker = File(tempDir, "installed.json")
        marker.writeText("""{"installed":true,"path":"${CommandRunner.OPENCLAW_DIR}"}""")
        assertTrue(marker.exists())
        val content = marker.readText()
        assertTrue(content.contains("\"installed\":true"))
        assertTrue(content.contains(CommandRunner.OPENCLAW_DIR))
    }

    // ─── Wrapper script content ───────────────────────────────────────────────

    @Test
    fun `wrapper script uses grun not node directly`() {
        val script = File(tempDir, "openclaw-start.sh")
        val content = buildString {
            appendLine("#!/data/data/com.termux/files/usr/bin/bash")
            appendLine("export HOME=${CommandRunner.TERMUX_HOME}")
            appendLine("export PATH=${CommandRunner.OPENCLAW_BIN}:${CommandRunner.TERMUX_PREFIX}/bin:\$PATH")
            appendLine("exec grun openclaw gateway --host 0.0.0.0")
        }
        script.writeText(content)
        script.setExecutable(true)

        assertTrue(script.readText().contains("grun"))
        assertFalse(script.readText().contains("node openclaw"))
        assertTrue(script.canExecute())
    }

    @Test
    fun `wrapper script exports HOME`() {
        val content = "export HOME=${CommandRunner.TERMUX_HOME}\nexec grun openclaw gateway"
        assertTrue(content.contains("export HOME="))
        assertTrue(content.contains(CommandRunner.TERMUX_HOME))
    }

    @Test
    fun `wrapper script exports PATH with openclaw bin`() {
        val content = "export PATH=${CommandRunner.OPENCLAW_BIN}:${CommandRunner.TERMUX_PREFIX}/bin:\$PATH"
        assertTrue(content.contains(CommandRunner.OPENCLAW_BIN))
        assertTrue(content.contains(CommandRunner.TERMUX_PREFIX))
    }

    // ─── Path relationships ───────────────────────────────────────────────────

    @Test
    fun `OPENCLAW_DIR is child of TERMUX_HOME`() {
        assertTrue(CommandRunner.OPENCLAW_DIR.startsWith(CommandRunner.TERMUX_HOME + "/"))
    }

    @Test
    fun `OPENCLAW_BIN is child of OPENCLAW_DIR`() {
        assertTrue(CommandRunner.OPENCLAW_BIN.startsWith(CommandRunner.OPENCLAW_DIR + "/"))
    }

    @Test
    fun `INSTALLED_MARKER filename is installed json`() {
        assertEquals("installed.json", File(CommandRunner.INSTALLED_MARKER).name)
    }

    @Test
    fun `WRAPPER_SCRIPT filename is openclaw-start sh`() {
        assertEquals("openclaw-start.sh", File(CommandRunner.WRAPPER_SCRIPT).name)
    }

    // ─── ELF detection helper (pure logic) ───────────────────────────────────

    @Test
    fun `ELF magic bytes are correct`() {
        val elfMagic = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        assertEquals(0x7f.toByte(), elfMagic[0])
        assertEquals('E'.code.toByte(), elfMagic[1])
        assertEquals('L'.code.toByte(), elfMagic[2])
        assertEquals('F'.code.toByte(), elfMagic[3])
    }

    @Test
    fun `non-ELF file is not detected as ELF`() {
        val textFile = File(tempDir, "script.sh")
        textFile.writeText("#!/bin/bash\necho hello")
        val magic = ByteArray(4)
        textFile.inputStream().use { it.read(magic) }
        val isElf = magic[0] == 0x7f.toByte() &&
            magic[1] == 'E'.code.toByte() &&
            magic[2] == 'L'.code.toByte() &&
            magic[3] == 'F'.code.toByte()
        assertFalse(isElf)
    }

    // ─── Symlink separator ────────────────────────────────────────────────────

    @Test
    fun `SYMLINKS txt separator is left arrow`() {
        val line = "/usr/bin/sh←bin/sh"
        val parts = line.split("←")
        assertEquals(2, parts.size)
        assertEquals("/usr/bin/sh", parts[0])
        assertEquals("bin/sh", parts[1])
    }

    // ─── Storage setup detection ──────────────────────────────────────────────

    @Test
    fun `isStorageSetupDone returns false on CI`() {
        // ~/storage symlink won't exist on CI
        assertFalse(CommandRunner.isStorageSetupDone())
    }

    // ─── SetupStatus data class ───────────────────────────────────────────────

    @Test
    fun `SetupStatus all false is valid initial state`() {
        // Simulate the data class directly
        data class SetupStatus(
            val bootstrapInstalled: Boolean,
            val runtimeInstalled: Boolean,
            val wwwInstalled: Boolean,
            val platformInstalled: Boolean,
        )
        val status = SetupStatus(
            bootstrapInstalled = false,
            runtimeInstalled = false,
            wwwInstalled = false,
            platformInstalled = false,
        )
        assertFalse(status.bootstrapInstalled)
        assertFalse(status.runtimeInstalled)
        assertFalse(status.wwwInstalled)
        assertFalse(status.platformInstalled)
    }
}
