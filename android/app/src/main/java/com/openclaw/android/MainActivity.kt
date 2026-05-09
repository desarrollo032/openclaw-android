package com.openclaw.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point - pure router, never shows its own UI.
 *
 * Flujo:
 *  - Si el payload ya fue extraido -> Dashboard (sin iniciar gateway automaticamente)
 *  - Si no -> InstallationActivity
 *
 * MainActivity no tiene layout propio - solo routing con finish().
 * El gateway NUNCA se inicia aqui - el usuario lo controla desde el Dashboard.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payloadReady = OpenClawInstaller.isPayloadReady(this)

        Log.i("MainActivity", "payloadReady=$payloadReady")

        val intent = if (payloadReady) {
            Intent(this, OpenClawDashboardActivity::class.java)
        } else {
            Intent(this, InstallationActivity::class.java)
        }
        startActivity(intent)
        finish() // MainActivity no queda en el back stack
    }
}