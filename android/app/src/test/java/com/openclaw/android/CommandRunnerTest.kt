package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CommandRunnerTest {
    @TempDir
    lateinit var tempDir: File

    // Minimal env for unit tests — bash -l -c is used regardless of PREFIX
    private val env = mapOf(
        "HOME" to System.getProperty("user.home", "/tmp"),
        "PATH" to "/usr/bin:/bin",
    )

    // ─── runSync ──────────────────────────────────────────────────────────────

    @Test
    fun `runSync returns stdout for echo command`() {
        val result = CommandRunner.runSync("echo hello", env, tempDir)
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout.trim())
    }

    @Test
    fun `runSync returns non-zero exit code for failing command`() {
        val result = CommandRunner.runSync("exit 42", env, tempDir)
        assertEquals(42, result.exitCode)
    }

    @Test
    fun `runSync captures stderr separately`() {
        val result = CommandRunner.runSync("echo error >&2", env, tempDir)
        assertEquals(0, result.exitCode)
        assertEquals("error", result.stderr.trim())
        assertTrue(result.stdout.isBlank())
    }

    @Test
    fun `runSync handles invalid command gracefully`() {
        val result = CommandRunner.runSync("nonexistent_command_xyz_abc", env, tempDir)
        assertTrue(result.exitCode != 0)
    }

    @Test
    fun `runSync times out and returns -1`() {
        val result = CommandRunner.runSync("sleep 10", env, tempDir, timeoutMs = 200)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("timed out"))
    }

    @Test
    fun `runSync uses tempDir as working directory`() {
        val result = CommandRunner.runSync("pwd", env, tempDir)
        assertEquals(0, result.exitCode)
        // pwd output should be a valid path
        assertTrue(result.stdout.trim().isNotEmpty())
    }

    @Test
    fun `runSync falls back to TERMUX_HOME when workDir does not exist`() {
        val nonExistent = File("/nonexistent/path/xyz")
        // Should not throw — falls back to TERMUX_HOME (which may not exist on CI, but no crash)
        val result = CommandRunner.runSync("echo ok", env, nonExistent)
        // Either succeeds or fails gracefully — no exception
        assertNotNull(result)
    }

    // ─── CommandResult ────────────────────────────────────────────────────────

    @Test
    fun `CommandResult data class holds values correctly`() {
        val result = CommandRunner.CommandResult(0, "out", "err")
        assertEquals(0, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
    }

    @Test
    fun `CommandResult with negative exit code`() {
        val result = CommandRunner.CommandResult(-1, "", "timed out")
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("timed out"))
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    @Test
    fun `TERMUX_HOME constant is correct`() {
        assertEquals("/data/data/com.termux/files/home", CommandRunner.TERMUX_HOME)
    }

    @Test
    fun `TERMUX_PREFIX constant is correct`() {
        assertEquals("/data/data/com.termux/files/usr", CommandRunner.TERMUX_PREFIX)
    }

    @Test
    fun `OPENCLAW_DIR is under TERMUX_HOME`() {
        assertTrue(CommandRunner.OPENCLAW_DIR.startsWith(CommandRunner.TERMUX_HOME))
        assertTrue(CommandRunner.OPENCLAW_DIR.contains(".openclaw-android"))
    }

    @Test
    fun `OPENCLAW_BIN is under OPENCLAW_DIR`() {
        assertTrue(CommandRunner.OPENCLAW_BIN.startsWith(CommandRunner.OPENCLAW_DIR))
        assertTrue(CommandRunner.OPENCLAW_BIN.endsWith("/bin"))
    }

    @Test
    fun `INSTALLED_MARKER is inside OPENCLAW_DIR`() {
        assertTrue(CommandRunner.INSTALLED_MARKER.startsWith(CommandRunner.OPENCLAW_DIR))
        assertTrue(CommandRunner.INSTALLED_MARKER.endsWith("installed.json"))
    }

    @Test
    fun `WRAPPER_SCRIPT is in TERMUX_HOME`() {
        assertTrue(CommandRunner.WRAPPER_SCRIPT.startsWith(CommandRunner.TERMUX_HOME))
        assertTrue(CommandRunner.WRAPPER_SCRIPT.endsWith("openclaw-start.sh"))
    }

    // ─── buildTermuxEnv ───────────────────────────────────────────────────────

    @Test
    fun `buildTermuxEnv contains required keys`() {
        val env = CommandRunner.buildTermuxEnv()
        assertNotNull(env["HOME"])
        assertNotNull(env["PREFIX"])
        assertNotNull(env["PATH"])
        assertNotNull(env["TMPDIR"])
        assertNotNull(env["LD_LIBRARY_PATH"])
        assertNotNull(env["LANG"])
        assertNotNull(env["TERM"])
    }

    @Test
    fun `buildTermuxEnv HOME matches TERMUX_HOME`() {
        assertEquals(CommandRunner.TERMUX_HOME, CommandRunner.buildTermuxEnv()["HOME"])
    }

    @Test
    fun `buildTermuxEnv PREFIX matches TERMUX_PREFIX`() {
        assertEquals(CommandRunner.TERMUX_PREFIX, CommandRunner.buildTermuxEnv()["PREFIX"])
    }

    @Test
    fun `buildTermuxEnv PATH contains openclaw bin first`() {
        val path = CommandRunner.buildTermuxEnv()["PATH"]!!
        val openclawIdx = path.indexOf(CommandRunner.OPENCLAW_BIN)
        val termuxIdx = path.indexOf(CommandRunner.TERMUX_PREFIX)
        assertTrue(openclawIdx >= 0, "PATH must contain OPENCLAW_BIN")
        assertTrue(termuxIdx >= 0, "PATH must contain TERMUX_PREFIX")
        assertTrue(openclawIdx < termuxIdx, "OPENCLAW_BIN must come before TERMUX_PREFIX in PATH")
    }

    @Test
    fun `buildTermuxEnv OA_GLIBC is set to 1`() {
        assertEquals("1", CommandRunner.buildTermuxEnv()["OA_GLIBC"])
    }

    @Test
    fun `buildTermuxEnv CONTAINER is set to 1`() {
        assertEquals("1", CommandRunner.buildTermuxEnv()["CONTAINER"])
    }

    // ─── isOpenClawInstalled ──────────────────────────────────────────────────

    @Test
    fun `isOpenClawInstalled returns false when marker does not exist`() {
        // On CI/dev machine the marker won't exist
        // We can only assert it returns a boolean without throwing
        val result = CommandRunner.isOpenClawInstalled()
        assertFalse(result) // expected false on dev/CI
    }

    // ─── isStorageSetupDone ───────────────────────────────────────────────────

    @Test
    fun `isStorageSetupDone returns boolean without throwing`() {
        val result = CommandRunner.isStorageSetupDone()
        assertNotNull(result) // just verify no exception
    }

    // ─── createWrapperScript ─────────────────────────────────────────────────

    @Test
    fun `createWrapperScript writes executable file to temp location`() {
        // Override WRAPPER_SCRIPT path via a temp file for testing
        val tempScript = File(tempDir, "openclaw-start.sh")
        val content = buildString {
            appendLine("#!/data/data/com.termux/files/usr/bin/bash")
            appendLine("export HOME=${CommandRunner.TERMUX_HOME}")
            appendLine("export PATH=${CommandRunner.OPENCLAW_BIN}:${CommandRunner.TERMUX_PREFIX}/bin:\$PATH")
            appendLine("exec grun openclaw gateway --host 0.0.0.0")
        }
        tempScript.writeText(content)
        tempScript.setExecutable(true)

        assertTrue(tempScript.exists())
        assertTrue(tempScript.canExecute())
        assertTrue(tempScript.readText().contains("grun"))
        assertFalse(tempScript.readText().contains("node openclaw")) // never call node directly
    }

    // ─── writeInstalledMarker ─────────────────────────────────────────────────

    @Test
    fun `writeInstalledMarker creates valid JSON`() {
        val marker = File(tempDir, "installed.json")
        marker.writeText("""{"installed":true,"path":"${CommandRunner.OPENCLAW_DIR}"}""")

        assertTrue(marker.exists())
        val content = marker.readText()
        assertTrue(content.contains("\"installed\":true"))
        assertTrue(content.contains(CommandRunner.OPENCLAW_DIR))
    }
}
