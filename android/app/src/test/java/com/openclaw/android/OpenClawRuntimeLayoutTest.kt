package com.openclaw.android

import com.openclaw.android.proot.OpenClawProot
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenClawRuntimeLayoutTest {

    @Test
    fun `proot lazy properties create expected directory structure`() {
        val context = RuntimeEnvironment.getApplication()
        val proot = OpenClawProot(context)

        // Acceder a las propiedades lazy fuerza la creación de directorios
        val homeDir = proot.homeDir
        val openclawHome = proot.openclawHome
        val openclawTmp = proot.openclawTmp
        val prootTmp = proot.prootTmpDir

        homeDir.isDirectory shouldBe true
        openclawHome.isDirectory shouldBe true
        openclawTmp.isDirectory shouldBe true
        prootTmp.isDirectory shouldBe true

        // Verificar jerarquía
        openclawHome.parentFile shouldBe homeDir
        openclawTmp.parentFile shouldBe openclawHome
    }

    @Test
    fun `proot rootfs path is correct and alpine is not installed`() {
        val context = RuntimeEnvironment.getApplication()
        val proot = OpenClawProot(context)

        // rootfs es un path, no se crea automáticamente (solo al descargar Alpine)
        val rootfs = proot.rootfs
        rootfs.absolutePath shouldBe File(context.filesDir, "alpine-rootfs").absolutePath

        // Alpine no está instalado (no hay rootfs real)
        rootfs.exists() shouldBe false
        proot.isAlpineInstalled() shouldBe false
    }
}
