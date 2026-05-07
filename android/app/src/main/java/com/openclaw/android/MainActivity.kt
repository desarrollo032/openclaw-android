package com.openclaw.android

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash/router activity — never shows UI.
 * Decides immediately whether to install or launch the dashboard.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenClawInstaller.isPayloadReady(this)) {
            // First run or corrupted install → go to installer
            startActivity(Intent(this, InstallationActivity::class.java))
        } else {
            // Already installed → start gateway and open dashboard
            OpenClawGatewayService.start(this)
            startActivity(Intent(this, OpenClawDashboardActivity::class.java))
        }

        finish()
    }
}
