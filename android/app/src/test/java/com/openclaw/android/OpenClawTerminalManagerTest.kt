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
    fun generatedOpenClawWrapperDisablesCliRespawnOnAndroid() {
        val context = prepareContext()
        val payloadDir = OpenClawInstaller.getPayloadDir(context)

        OpenClawInstaller.deployScripts(context, payloadDir)

        val wrapper = File(payloadDir, "bin/openclaw").readText()

        wrapper shouldContain "OPENCLAW_NO_RESPAWN=1"
        wrapper shouldContain "OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED=1"
        wrapper shouldContain "OPENCLAW_SOURCE_COMPILE_CACHE_RESPAWNED=1"
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
