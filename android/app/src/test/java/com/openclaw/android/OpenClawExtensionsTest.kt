package com.openclaw.android

import android.content.Context
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OpenClawExtensionsTest : DescribeSpec({

    describe("OpenClawExtensions") {

        val context = RuntimeEnvironment.getApplication()

        describe("File.deleteRecursivelySafe") {
            it("should safely delete non-existent directory") {
                val nonExistent = File(context.cacheDir, "non_existent_dir")
                nonExistent.deleteRecursivelySafe() shouldBe true
            }

            it("should delete directory with contents") {
                val dir = File(context.cacheDir, "test_delete_dir")
                dir.mkdirs()
                File(dir, "file1.txt").writeText("content")
                File(dir, "subdir").mkdirs()
                File(dir, "subdir/file2.txt").writeText("content2")

                dir.exists() shouldBe true
                dir.deleteRecursivelySafe()
                dir.exists() shouldBe false
            }
        }

        describe("ByteArray.toHex") {
            it("should convert bytes to hex string") {
                val bytes = byteArrayOf(0x00, 0x0F, 0xFF.toByte())
                bytes.toHex() shouldBe "000fff"
            }

            it("should handle empty array") {
                byteArrayOf().toHex() shouldBe ""
            }
        }

        describe("Context.getPayloadDir") {
            it("should return payload directory") {
                val dir = context.getPayloadDir()
                dir.name shouldBe "payload"
                dir.exists() shouldBe true
            }
        }

        describe("Context.getConfigDir") {
            it("should return .openclaw directory") {
                val dir = context.getConfigDir()
                dir.name shouldBe ".openclaw"
                dir.parentFile shouldBe context.filesDir
            }
        }

        describe("Context.assetSize") {
            it("should return 0 for non-existent asset") {
                context.assetSize("non_existent_asset.txt") shouldBe 0L
            }
        }

        describe("extractTarXzFromStream") {
            it("should handle invalid tar.xz gracefully") {
                runTest {
                    val invalidData = byteArrayOf(0x00, 0x01, 0x02, 0x03)
                    val result = ByteArrayInputStream(invalidData).use { stream ->
                        extractTarXzFromStream(stream, context.cacheDir, null)
                    }
                    result shouldBe false
                }
            }
        }

        describe("extractTarGzFromStream") {
            it("should handle invalid tar.gz gracefully") {
                runTest {
                    val invalidData = byteArrayOf(0x00, 0x01, 0x02, 0x03)
                    val result = ByteArrayInputStream(invalidData).use { stream ->
                        extractTarGzFromStream(stream, context.cacheDir)
                    }
                    result shouldBe false
                }
            }
        }
    }
})
