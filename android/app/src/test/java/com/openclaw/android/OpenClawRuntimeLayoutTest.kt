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

        // rootfs se crea automáticamente (como todas las propiedades del layout)
        val rootfs = proot.rootfs
        rootfs.absolutePath shouldBe File(context.filesDir, "alpine-rootfs").absolutePath
        rootfs.isDirectory shouldBe true

        // Alpine no está instalado (falta bin/sh y bin/busybox)
        proot.isAlpineInstalled() shouldBe false
    }

    @Test
    fun `ensureDirectories creates all critical dirs`() {
        val context = RuntimeEnvironment.getApplication()
        val proot = OpenClawProot(context)

        // Llamar a ensureDirectories y verificar que todos existen
        proot.ensureDirectories()

        proot.prootTmpDir.isDirectory shouldBe true
        proot.homeDir.isDirectory shouldBe true
        proot.openclawHome.isDirectory shouldBe true
        proot.openclawTmp.isDirectory shouldBe true
        proot.rootfs.isDirectory shouldBe true
        File(proot.rootfs, "root").isDirectory shouldBe true
    }
}
