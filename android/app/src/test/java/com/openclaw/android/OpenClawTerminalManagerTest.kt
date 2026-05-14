package com.openclaw.android

import android.content.Context
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenClawTerminalManagerTest {

    @Test
    fun interactiveShellLoadsOpenClawFunctionsFromRcFile() {
        val context = prepareContext()
        val env = OpenClawTerminalManager(context).buildEnvironment()
        val rcPath = env.first { it.startsWith("ENV=") }.removePrefix("ENV=")
        val rcFile = java.io.File(rcPath)

        rcFile.exists().shouldBeTrue()
        env.toList().shouldContain("OPENCLAW_TERMINAL_RC=$rcPath")
        rcFile.readText().let { rc ->
            rc shouldContain "openclaw()"
            rc shouldNotContain "exec \"$"
        }
    }

    @Test
    fun terminalEnvironmentUsesHomeOpenClawHomeTmpAndUsrBinLayout() {
        val context = prepareContext()
        val env = OpenClawTerminalManager(context).buildEnvironment().toList()
        val homeDir = File(context.filesDir, "home")
        val openclawHome = File(homeDir, ".openclaw")
        val tmpDir = File(openclawHome, "tmp")
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val payloadDir = OpenClawInstaller.getPayloadDir(context)

        env.shouldContain("HOME=${homeDir.absolutePath}")
        env.shouldContain("OPENCLAW_HOME=${openclawHome.absolutePath}")
        env.shouldContain("TMPDIR=${tmpDir.absolutePath}")
        env.shouldContain("PATH=${File(context.filesDir, "usr/bin").absolutePath}:${nativeDir.absolutePath}:/system/bin:/system/xbin")
        env.shouldContain("NODE_PATH=${File(payloadDir, "lib/node_modules").absolutePath}")
    }

    @Test
    fun generatedOpenClawWrapperDisablesCliRespawnOnAndroid() {
        val context = prepareContext()
        val payloadDir = OpenClawInstaller.getPayloadDir(context)

        OpenClawInstaller.deployScripts(context, payloadDir)

        val wrapper = File(context.filesDir, "usr/bin/openclaw").readText()

        wrapper shouldContain "OPENCLAW_NO_RESPAWN=1"
        wrapper shouldContain "OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED=1"
        wrapper shouldContain "OPENCLAW_SOURCE_COMPILE_CACHE_RESPAWNED=1"
        wrapper shouldContain "export OPENCLAW_HOME=\"${File(context.filesDir, "home/.openclaw").absolutePath}\""
        wrapper shouldContain "export TMPDIR=\"${File(context.filesDir, "home/.openclaw/tmp").absolutePath}\""
        wrapper shouldContain "--disable-warning=ExperimentalWarning"
        wrapper shouldNotContain "app_payload/bin/node"
    }

    private fun prepareContext(): Context {
        val context = RuntimeEnvironment.getApplication()
        val nativeDir = File(context.filesDir, "native").apply { mkdirs() }
        context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        return context
    }
}
