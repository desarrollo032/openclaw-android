package com.openclaw.android

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point — routes to installer or dashboard.
 * Never shows its own UI.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payloadReady = OpenClawInstaller.isPayloadReady(this)
        val configReady  = OpenClawInstaller.isConfigRestored(this)

        if (payloadReady && configReady) {
            // Fully installed → launch gateway + dashboard
            OpenClawGatewayService.start(this)
            startActivity(Intent(this, OpenClawDashboardActivity::class.java))
        } else {
            // Not installed (or partial) → go to installer
            startActivity(Intent(this, InstallationActivity::class.java))
        }

        finish()
    }
}
