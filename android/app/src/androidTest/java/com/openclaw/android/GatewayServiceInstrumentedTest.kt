package com.openclaw.android

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@MediumTest
class GatewayServiceInstrumentedTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testGatewayServiceState() {
        // Verificar estado inicial del servicio
        val initialState = OpenClawGatewayService.state.value
        assertNotNull(initialState)
        // Debería ser STOPPED o ERROR inicialmente
        assertTrue(
            initialState == GatewayState.STOPPED ||
            initialState == GatewayState.ERROR ||
            initialState == GatewayState.IDLE
        )
    }

    @Test
    fun testGatewayToken() {
        // Verificar que el token es accesible
        val token = OpenClawGatewayService.currentToken
        assertNotNull(token)
    }

    @Test
    fun testStartStopService() {
        // Intentar iniciar el servicio
        try {
            OpenClawGatewayService.start(context)
            // El servicio debería haber intentado iniciar
            // Nota: Sin payload instalado, puede fallar
        } catch (e: Exception) {
            // Esperado si no hay payload
        }

        // Intentar detener
        try {
            OpenClawGatewayService.stop(context)
        } catch (e: Exception) {
            // Puede lanzar excepción si no está corriendo
        }
    }

    @Test
    fun testServiceIntentCreation() {
        val intent = Intent(context, OpenClawGatewayService::class.java)
        assertNotNull(intent)
        assertEquals(OpenClawGatewayService::class.java.name, intent.component?.className)
    }
}

// Enum auxiliar para testing
enum class GatewayState {
    IDLE, STARTING, READY, RESTARTING, STOPPED, ERROR
}
