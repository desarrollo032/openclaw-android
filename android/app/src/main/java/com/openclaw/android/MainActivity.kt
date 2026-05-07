package com.openclaw.android

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point — pure router, never shows its own UI.
 *
 * Decision tree:
 *  1. Payload not installed          → InstallationActivity
 *  2. Payload installed, not onboarded → OnboardActivity (runs `openclaw onboard`)
 *  3. Fully configured               → start gateway + OpenClawDashboardActivity
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payloadReady   = OpenClawInstaller.isPayloadReady(this)
        val configRestored = OpenClawInstaller.isConfigRestored(this)
        val onboarded      = OpenClawInstaller.isOnboardComplete(this)

        val target = when {
            !payloadReady || !configRestored -> InstallationActivity::class.java
            !onboarded                       -> OnboardActivity::class.java
            else                             -> null   // go to dashboard
        }

        if (target != null) {
            startActivity(Intent(this, target))
        } else {
            OpenClawGatewayService.start(this)
            startActivity(Intent(this, OpenClawDashboardActivity::class.java))
        }

        finish()
    }
}
