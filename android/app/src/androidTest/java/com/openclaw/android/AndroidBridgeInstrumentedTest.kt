package com.openclaw.android

import android.content.Context
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class AndroidBridgeInstrumentedTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testContextIsAvailable() {
        assertNotNull(context)
        assertEquals("com.openclaw.android", context.packageName)
    }

    @Test
    fun testInstallerState() {
        // Verificar estado inicial del instalador
        val isReady = OpenClawInstaller.isAlpineSetupComplete(context)
        // Sin Alpine instalado, debería ser false
        assertFalse(isReady)
    }

    @Test
    fun testAssetDetection() {
        val assets = AssetDetector.detectSync(context)
        assertNotNull(assets)
        assertFalse(assets.alpineAvailable)
        assertTrue(assets.freeSpaceBytes > 0)
    }

    @Test
    fun testProotPresent() {
        assertFalse(OpenClawInstaller.isProotPresent(context))
    }

    @Test
    fun testConfigDirectory() {
        val configDir = OpenClawInstaller.getConfigDir(context)
        assertNotNull(configDir)
        assertEquals(".openclaw", configDir.name)
    }
}
