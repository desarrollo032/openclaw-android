package com.openclaw.android

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AssetDetectorTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `should detect available assets correctly`() {
        val assets = AssetDetector.detectSync(context)

        assets shouldNotBe null
        // En Robolectric los assets del proyecto están disponibles
        assets.alpineAvailable shouldBe true
        assets.hasEnoughSpace shouldBe true  // En emulador siempre hay espacio
    }

    @Test
    fun `should calculate free space`() {
        val assets = AssetDetector.detectSync(context)

        assets.freeSpaceBytes shouldNotBe null
        assets.freeSpaceBytes shouldBeGreaterThan -1L
    }

    @Test
    fun `should hold correct values in data class`() {
        val info = AssetDetectionResult(
            alpineAvailable = true,
            alpineSizeBytes = 1000L,
            alpineContents = emptyList(),
            freeSpaceBytes = 1000000L,
            hasEnoughSpace = true
        )

        info.alpineAvailable shouldBe true
        info.freeSpaceBytes shouldBe 1000000L
        info.hasEnoughSpace shouldBe true
        info.alpineSizeBytes shouldBe 1000L
    }
}
