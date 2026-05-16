package com.openclaw.android

import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenClawInstallerTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // Robolectric no asigna nativeLibraryDir automáticamente
        val nativeDir = File(context.filesDir, "native").apply { mkdirs() }
        context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath

        // Limpiar estado antes de cada test
        OpenClawInstaller.uninstall(context)
    }

    // ── isAlpineSetupComplete ──────────────────────────────────────────────────

    @Test
    fun `should return false when alpine setup is not complete`() {
        OpenClawInstaller.isAlpineSetupComplete(context) shouldBe false
    }



    // ── isConfigRestored ──────────────────────────────────────────────────────

    @Test
    fun `should return false when config is not restored`() {
        OpenClawInstaller.isConfigRestored(context) shouldBe false
    }

    @Test
    fun `should detect openclaw json via isOnboardComplete when present`() {
        val configDir = OpenClawInstaller.getConfigDir(context)
        configDir.mkdirs()
        File(configDir, "openclaw.json").writeText("{\"ok\":true}")

        // isOnboardComplete busca openclaw.json en el directorio de configuración
        OpenClawInstaller.isOnboardComplete(context) shouldBe true

        val prefs = context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getBoolean("config_restored", false) shouldBe false
    }

    // ── isProotPresent ────────────────────────────────────────────────────────

    @Test
    fun `should return false when proot is not bundled`() {
        OpenClawInstaller.isProotPresent(context) shouldBe false
    }

    // ── getConfigDir ──────────────────────────────────────────────────────────

    @Test
    fun `should return openclaw directory under filesDir home`() {
        val dir = OpenClawInstaller.getConfigDir(context)
        dir.name shouldBe ".openclaw"
        dir.parentFile shouldBe File(context.filesDir, "home")
    }

    // ── isAlpineInstalledOnly ─────────────────────────────────────────────────

    @Test
    fun `should return false when alpine is not installed for isAlpineInstalledOnly`() {
        OpenClawInstaller.isAlpineInstalledOnly(context) shouldBe false
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
        val configDir = OpenClawInstaller.getConfigDir(context)
        configDir.mkdirs()
        File(configDir, "test.txt").writeText("test")

        // Marcar como completo
        OpenClawInstaller.markOnboardComplete(context)

        // Ejecutar uninstall
        OpenClawInstaller.uninstall(context)

        // Verificar limpieza
        configDir.exists() shouldBe false
        OpenClawInstaller.isOnboardComplete(context) shouldBe false
    }

    // ── isNetworkAvailable ────────────────────────────────────────────────────

    @Test
    fun `should not crash when checking network`() {
        OpenClawInstaller.isNetworkAvailable(context) // no deberia lanzar excepcion
    }
}
