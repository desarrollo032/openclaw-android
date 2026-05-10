package com.openclaw.android

import android.content.Context
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AssetDetectorTest : DescribeSpec({

    describe("AssetDetector") {

        val context = RuntimeEnvironment.getApplication()

        describe("detectSync") {
            it("should detect available assets correctly") {
                val assets = AssetDetector.detectSync(context)

                assets shouldNotBe null
                // Sin assets reales, debería ser false
                assets.payloadAvailable shouldBe false
                assets.migrationAvailable shouldBe false
                assets.hasEnoughSpace shouldBe true  // En emulador siempre hay espacio
            }

            it("should calculate free space") {
                val assets = AssetDetector.detectSync(context)

                assets.freeSpaceBytes shouldNotBe null
                assets.freeSpaceBytes shouldBe >= 0L
            }
        }

        describe("AssetDetectionResult data class") {
            it("should hold correct values") {
                val info = AssetDetectionResult(
                    payloadAvailable = true,
                    payloadSizeBytes = 1000L,
                    payloadContents = emptyList(),
                    migrationAvailable = false,
                    migrationSizeBytes = 0L,
                    migrationContents = emptyList(),
                    freeSpaceBytes = 1000000L,
                    hasEnoughSpace = true
                )

                info.payloadAvailable shouldBe true
                info.migrationAvailable shouldBe false
                info.freeSpaceBytes shouldBe 1000000L
                info.hasEnoughSpace shouldBe true
                info.payloadSizeBytes shouldBe 1000L
            }
        }
    }
})
