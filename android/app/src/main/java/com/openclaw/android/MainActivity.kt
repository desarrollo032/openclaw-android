package com.openclaw.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point - pure router, never shows its own UI.
 *
 * Flujo corregido:
 *  - SIEMPRE abre OpenClawDashboardActivity como primera pantalla
 *  - Si el payload no está instalado, pasa un extra para que el dashboard
 *    muestre el InstallationBottomSheet automáticamente
 *
 * MainActivity no tiene layout propio - solo routing con finish().
 * El gateway NUNCA se inicia aquí - el usuario lo controla desde el Dashboard.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payloadReady = OpenClawInstaller.isPayloadReady(this)

        Log.i("MainActivity", "payloadReady=$payloadReady → always opening Dashboard")

        val intent = Intent(this, OpenClawDashboardActivity::class.java).apply {
            putExtra(OpenClawDashboardActivity.EXTRA_NEEDS_INSTALL, !payloadReady)
        }
        startActivity(intent)
        finish() // MainActivity no queda en el back stack
    }
}