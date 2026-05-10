package com.openclaw.android

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point — pure router, never shows its own UI.
 *
 * SIEMPRE abre OpenClawDashboardActivity como primera pantalla.
 * NO verifica isPayloadReady() — el Dashboard decide si mostrar el sheet.
 * NO redirige a InstallationActivity.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivity(
            Intent(this, OpenClawDashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
        finish()
    }
}