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
 *
 * If the payload IS installed but something else failed (e.g. onboard error),
 * we go straight to the dashboard so the user can retry from there — never
 * back to the installer.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payloadReady   = OpenClawInstaller.isPayloadReady(this)
        val configRestored = OpenClawInstaller.isConfigRestored(this)
        val onboarded      = OpenClawInstaller.isOnboardComplete(this)

        when {
            // Payload missing → must install first
            !payloadReady || !configRestored -> {
                startActivity(Intent(this, InstallationActivity::class.java))
            }
            // Payload ready but not yet onboarded → run onboard wizard
            !onboarded -> {
                startActivity(Intent(this, OnboardActivity::class.java))
            }
            // Fully set up → go straight to dashboard
            else -> {
                OpenClawGatewayService.start(this)
                startActivity(Intent(this, OpenClawDashboardActivity::class.java))
            }
        }

        finish()
    }
}
