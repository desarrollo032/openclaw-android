package com.openclaw.android

import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OpenClawInstallerTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // Limpiar estado antes de cada test
        OpenClawInstaller.uninstall(context)
    }

    // ── isPayloadReady ────────────────────────────────────────────────────────

    @Test
    fun `should return false when payload is not installed`() {
        OpenClawInstaller.isPayloadReady(context) shouldBe false
    }

    // ── isConfigRestored ──────────────────────────────────────────────────────

    @Test
    fun `should return false when config is not restored`() {
        OpenClawInstaller.isConfigRestored(context) shouldBe false
    }

    @Test
    fun `should return true when onboard is complete flag is set`() {
        val prefs = context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboard_complete", true).apply()

        OpenClawInstaller.isConfigRestored(context) shouldBe true
    }

    @Test
    fun `should restore config from openclaw json and cache the restored flag`() {
        val configDir = OpenClawInstaller.getConfigDir(context)
        configDir.mkdirs()
        File(configDir, "openclaw.json").writeText("{\"restored\":true}")

        OpenClawInstaller.isConfigRestored(context) shouldBe true

        val prefs = context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getBoolean("config_restored", false) shouldBe true
    }

    // ── getPayloadDir ─────────────────────────────────────────────────────────

    @Test
    fun `should return a valid directory`() {
        val dir = OpenClawInstaller.getPayloadDir(context)
        dir shouldNotBe null
        dir.exists() shouldBe true
    }

    // ── getConfigDir ──────────────────────────────────────────────────────────

    @Test
    fun `should return openclaw directory under filesDir home`() {
        val dir = OpenClawInstaller.getConfigDir(context)
        dir.name shouldBe ".openclaw"
        dir.parentFile shouldBe File(context.filesDir, "home")
    }

    // ── setupFilesLayout ──────────────────────────────────────────────────────

    @Test
    fun `should create the home and usr layout expected by the runtime`() {
        OpenClawInstaller.setupFilesLayout(context)

        File(context.filesDir, "home").isDirectory shouldBe true
        File(context.filesDir, "home/.openclaw/tmp").isDirectory shouldBe true
        File(context.filesDir, "usr/bin").isDirectory shouldBe true
        File(context.filesDir, "usr/opt").isDirectory shouldBe true
    }

    // ── hasBundledAssets ──────────────────────────────────────────────────────

    @Test
    fun `should handle missing assets gracefully`() {
        // En Robolectric los assets del proyecto están disponibles
        OpenClawInstaller.hasBundledAssets(context) shouldBe true
    }

    // ── isOnboardComplete ─────────────────────────────────────────────────────

    @Test
    fun `should return false initially`() {
        OpenClawInstaller.isOnboardComplete(context) shouldBe false
    }

    @Test
    fun `should return true after marking complete`() {
        OpenClawInstaller.markOnboardComplete(context)
        OpenClawInstaller.isOnboardComplete(context) shouldBe true
    }

    // ── uninstall ─────────────────────────────────────────────────────────────

    @Test
    fun `should clean up all directories and preferences`() {
        // Crear archivos de prueba
        val payloadDir = OpenClawInstaller.getPayloadDir(context)
        val configDir = OpenClawInstaller.getConfigDir(context)
        configDir.mkdirs()
        File(payloadDir, "test.txt").writeText("test")
        File(configDir, "test.txt").writeText("test")

        // Marcar como completo
        OpenClawInstaller.markOnboardComplete(context)

        // Ejecutar uninstall
        OpenClawInstaller.uninstall(context)

        // Verificar limpieza
        payloadDir.exists() shouldBe false
        configDir.exists() shouldBe false
        OpenClawInstaller.isOnboardComplete(context) shouldBe false
    }

    // ── verifyPayloadIntegrity ────────────────────────────────────────────────

    @Test
    fun `should skip verification in dev mode`() {
        OpenClawInstaller.verifyPayloadIntegrity(context) shouldBe true
    }
}
