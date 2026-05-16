package com.openclaw.android

import com.openclaw.android.proot.OpenClawProot
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenClawTerminalManagerTest {

    @Test
    fun `terminal environment uses proot internal paths`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = OpenClawTerminalManager(context)
        val proot = OpenClawProot(context)

        // Verificar que buildShellEnv contiene las variables esperadas
        val env = proot.buildShellEnv().toList()

        env.shouldContain("HOME=/root")
        env.shouldContain("OPENCLAW_HOME=/data/home/.openclaw")
        env.shouldContain("TMPDIR=/data/home/.openclaw/tmp")
        env.shouldContain("PROOT_NO_SECCOMP=1")
        env.shouldContain("PROOT_TMP_DIR=${proot.prootTmpDir.absolutePath}")
        env.shouldContain("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        env.shouldContain("TERM=xterm-256color")
        env.shouldContain("SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt")
    }

    @Test
    fun `terminal shell command includes proot binds and sh interactive`() {
        val context = RuntimeEnvironment.getApplication()
        val proot = OpenClawProot(context)

        val cmd = proot.buildShellCommand().toList()

        // Verificar binds canónicos
        cmd.shouldContain("--bind=/proc")
        cmd.shouldContain("--bind=/dev")
        cmd.shouldContain("--bind=/sys")
        cmd.shouldContain("--bind=${proot.filesDir.absolutePath}:/data")
        cmd.shouldContain("--bind=${proot.prootTmpDir.absolutePath}:/tmp")
        cmd.shouldContain("--change-id=0:0")
        cmd.shouldContain("/bin/sh")
        cmd.shouldContain("-i")
    }

    @Test
    fun `manager returns alpine not ready when proot has no rootfs`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = OpenClawTerminalManager(context)

        manager.isAlpineReady() shouldBe false
        manager.isOpenClawReady() shouldBe false
    }
}
