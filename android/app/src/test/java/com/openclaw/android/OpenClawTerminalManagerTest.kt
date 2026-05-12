package com.openclaw.android

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenClawTerminalManagerTest {

    @Test
    fun interactiveShellLoadsOpenClawFunctionsFromRcFile() {
        val context = RuntimeEnvironment.getApplication()
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
}
