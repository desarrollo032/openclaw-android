package com.openclaw.android

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenClawTerminalActivityTest {

    @Test
    fun uses40spAsTheInitialAndMinimumTerminalFontSize() {
        val activity = OpenClawTerminalActivity()

        activity.privateFloat("currentFontSizeSp") shouldBe 40f
        activity.privateFloat("MIN_FONT_SP") shouldBe 40f
    }
}

private fun OpenClawTerminalActivity.privateFloat(name: String): Float {
    val field = OpenClawTerminalActivity::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.getFloat(this)
}
