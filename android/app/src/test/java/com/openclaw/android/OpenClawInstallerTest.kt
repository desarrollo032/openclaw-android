package com.openclaw.android

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import io.mockk.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OpenClawInstallerTest : DescribeSpec({

    describe("OpenClawInstaller") {

        val context = RuntimeEnvironment.getApplication()

        beforeEach {
            // Limpiar estado antes de cada test
            OpenClawInstaller.uninstall(context)
        }

        describe("isPayloadReady") {
            it("should return false when payload is not installed") {
                OpenClawInstaller.isPayloadReady(context) shouldBe false
            }
        }

        describe("isConfigRestored") {
            it("should return false when config is not restored") {
                OpenClawInstaller.isConfigRestored(context) shouldBe false
            }

            it("should return true when onboard is complete flag is set") {
                val prefs = context.getSharedPreferences("openclaw_install", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("onboard_complete", true).apply()

                OpenClawInstaller.isConfigRestored(context) shouldBe true
            }

            it("should restore config from openclaw.json and cache the restored flag") {
                val configDir = OpenClawInstaller.getConfigDir(context)
                configDir.mkdirs()
                File(configDir, "openclaw.json").writeText("{\"restored\":true}")

                OpenClawInstaller.isConfigRestored(context) shouldBe true

                val prefs = context.getSharedPreferences("openclaw_install", Context.MODE_PRIVATE)
                prefs.getBoolean("config_restored", false) shouldBe true
            }
        }

        describe("getPayloadDir") {
            it("should return a valid directory") {
                val dir = OpenClawInstaller.getPayloadDir(context)
                dir shouldNotBe null
                dir.exists() shouldBe true
            }
        }

        describe("getConfigDir") {
            it("should return .openclaw directory under filesDir home") {
                val dir = OpenClawInstaller.getConfigDir(context)
                dir.name shouldBe ".openclaw"
                dir.parentFile shouldBe File(context.filesDir, "home")
            }
        }

        describe("setupFilesLayout") {
            it("should create the home and usr layout expected by the runtime") {
                OpenClawInstaller.setupFilesLayout(context)

                File(context.filesDir, "home").isDirectory shouldBe true
                File(context.filesDir, "home/.openclaw/tmp").isDirectory shouldBe true
                File(context.filesDir, "usr/bin").isDirectory shouldBe true
                File(context.filesDir, "usr/opt").isDirectory shouldBe true
            }
        }

        describe("hasBundledAssets") {
            it("should handle missing assets gracefully") {
                // Sin assets reales, debería retornar false
                OpenClawInstaller.hasBundledAssets(context) shouldBe false
            }
        }

        describe("isOnboardComplete") {
            it("should return false initially") {
                OpenClawInstaller.isOnboardComplete(context) shouldBe false
            }

            it("should return true after marking complete") {
                OpenClawInstaller.markOnboardComplete(context)
                OpenClawInstaller.isOnboardComplete(context) shouldBe true
            }
        }

        describe("uninstall") {
            it("should clean up all directories and preferences") {
                // Crear archivos de prueba
                val payloadDir = OpenClawInstaller.getPayloadDir(context)
                val configDir = OpenClawInstaller.getConfigDir(context)
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
        }

        describe("verifyPayloadIntegrity") {
            it("should skip verification in dev mode") {
                OpenClawInstaller.verifyPayloadIntegrity(context) shouldBe true
            }
        }
    }
})
