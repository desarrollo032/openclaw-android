package com.openclaw.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point — pure router, never shows its own UI.
 * Modificado: Ahora el frontend maneja toda la instalacion.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payloadReady   = OpenClawInstaller.isPayloadReady(this)
        val configRestored = OpenClawInstaller.isConfigRestored(this)

        Log.i("MainActivity", "payloadReady=$payloadReady configRestored=$configRestored")

        // Iniciar el servicio en segundo plano SOLO si la configuracion ya esta lista
        if (payloadReady && configRestored) {
            OpenClawGatewayService.start(this)
        }

        // Siempre cargar el dashboard (que hostea el frontend web).
        // El frontend detectara que no esta instalado via bridge.getSetupStatus() y mostrara la UI de instalacion.
        val intent = Intent(this, OpenClawDashboardActivity::class.java)
        if (payloadReady && configRestored) {
            intent.putExtra(
                OpenClawDashboardActivity.EXTRA_DASHBOARD_TOKEN,
                OpenClawGatewayService.currentToken
            )
        }
        startActivity(intent)

        finish()
    }
}
