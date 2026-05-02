package com.openclaw.android

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UrlResolverTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var urlResolver: UrlResolver

    @BeforeEach
    fun setup() {
        context = mockk()
        every { context.filesDir } returns tempDir
        urlResolver = UrlResolver(context)

        // Ensure config path exists for writing mock files
        val configFile = File(tempDir, "usr/share/openclaw-app/config.json")
        configFile.parentFile?.mkdirs()
    }

    @Test
    fun `getBootstrapUrl returns fallback when no config exists and remote fails`() = runTest {
        // config.json doesn't exist. Attempt to fetch will fail if it can't resolve CONFIG_URL.
        // It correctly returns the fallback.
        // Mocking the BuildConfig or URL is tricky but the fallback logic triggers naturally here.
        val url = urlResolver.getBootstrapUrl()
        assertEquals(BuildConfig.BOOTSTRAP_URL, url)
    }

    @Test
    fun `getBootstrapUrl reads from cached config`() = runTest {
        val configFile = File(tempDir, "usr/share/openclaw-app/config.json")
        configFile.writeText("""
            {
                "bootstrap": {
                    "url": "https://example.com/cached-bootstrap.zip"
                }
            }
        """.trimIndent())

        val url = urlResolver.getBootstrapUrl()
        assertEquals("https://example.com/cached-bootstrap.zip", url)
    }

    @Test
    fun `getWwwUrl returns fallback when no config exists`() = runTest {
        val url = urlResolver.getWwwUrl()
        assertEquals(BuildConfig.WWW_URL, url)
    }

    @Test
    fun `getWwwUrl reads from cached config`() = runTest {
        val configFile = File(tempDir, "usr/share/openclaw-app/config.json")
        configFile.writeText("""
            {
                "www": {
                    "url": "https://example.com/cached-www.zip"
                }
            }
        """.trimIndent())

        val url = urlResolver.getWwwUrl()
        assertEquals("https://example.com/cached-www.zip", url)
    }

    @Test
    fun `getWwwConfig returns full component config from cache`() = runTest {
        val configFile = File(tempDir, "usr/share/openclaw-app/config.json")
        configFile.writeText("""
            {
                "www": {
                    "url": "https://example.com/cached-www.zip",
                    "version": "1.0.0",
                    "sha256": "abcdef123456"
                }
            }
        """.trimIndent())

        val config = urlResolver.getWwwConfig()
        assertNotNull(config)
        assertEquals("https://example.com/cached-www.zip", config?.url)
        assertEquals("1.0.0", config?.version)
        assertEquals("abcdef123456", config?.sha256)
    }

    @Test
    fun `falls back to BuildConfig when cached config is invalid JSON`() = runTest {
        val configFile = File(tempDir, "usr/share/openclaw-app/config.json")
        configFile.writeText("""{ "invalid_json": """)

        val url = urlResolver.getBootstrapUrl()
        assertEquals(BuildConfig.BOOTSTRAP_URL, url)
    }
}
