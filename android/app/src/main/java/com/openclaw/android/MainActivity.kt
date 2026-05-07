package com.openclaw.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.openclaw.android.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var payloadUri: Uri? = null
    private var configUri: Uri? = null

    private val payloadPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            payloadUri = uri
            binding.statusText.text = "Payload selected: ${uri.path?.split("/")?.last()}"
            checkReadyToInstall()
        }
    }

    private val configPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            configUri = uri
            binding.statusText.text = "Config selected: ${uri.path?.split("/")?.last()}"
            checkReadyToInstall()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkInstallation()
    }

    private fun setupUI() {
        binding.progressBar.visibility = View.GONE
        
        // Dynamic UI creation for selection buttons
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = android.view.Gravity.CENTER
        }

        val hasAssets = OpenClawInstaller.hasAssets(this)
        
        if (hasAssets) {
            Button(this).apply {
                text = "Install from Assets (Fast)"
                setOnClickListener { runInstallationFromAssets() }
                buttonContainer.addView(this)
            }
        }

        Button(this).apply {
            text = "Select Payload (.tar.xz)"
            setOnClickListener { payloadPicker.launch("*/*") }
            buttonContainer.addView(this)
        }

        Button(this).apply {
            text = "Select Config (.tar.gz)"
            setOnClickListener { configPicker.launch("*/*") }
            buttonContainer.addView(this)
        }

        Button(this).apply {
            id = View.generateViewId()
            text = "Start Installation"
            visibility = View.GONE
            setOnClickListener { runInstallationFromUris() }
            buttonContainer.addView(this)
        }

        (binding.root as android.view.ViewGroup).addView(buttonContainer)
    }

    private fun checkReadyToInstall() {
        val installBtn = (binding.root as android.view.ViewGroup).getChildAt(1) as? LinearLayout
        val startBtn = installBtn?.getChildAt(installBtn.childCount - 1) as? Button
        if (payloadUri != null && configUri != null) {
            startBtn?.visibility = View.VISIBLE
        }
    }

    private fun checkInstallation() {
        lifecycleScope.launch {
            if (OpenClawInstaller.isPayloadInstalled(this@MainActivity) && 
                OpenClawInstaller.isConfigRestored(this@MainActivity)) {
                startOpenClaw()
            }
        }
    }

    private fun runInstallationFromAssets() {
        hideSelectionButtons()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val pOk = OpenClawInstaller.installPayloadFromAsset(this@MainActivity) { progress ->
                    runOnUiThread { binding.statusText.text = progress }
                }
                if (!pOk) return@withContext false
                
                val cOk = OpenClawInstaller.restoreConfigFromAsset(this@MainActivity) { progress ->
                    runOnUiThread { binding.statusText.text = progress }
                }
                cOk
            }

            if (success) startOpenClaw()
            else showSelectionButtons("Installation failed.")
        }
    }

    private fun runInstallationFromUris() {
        val pUri = payloadUri ?: return
        val cUri = configUri ?: return
        
        hideSelectionButtons()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val pOk = OpenClawInstaller.installPayloadFromUri(this@MainActivity, pUri) { progress ->
                    runOnUiThread { binding.statusText.text = progress }
                }
                if (!pOk) return@withContext false
                
                val cOk = OpenClawInstaller.restoreConfigFromUri(this@MainActivity, cUri) { progress ->
                    runOnUiThread { binding.statusText.text = progress }
                }
                cOk
            }

            if (success) startOpenClaw()
            else showSelectionButtons("Installation failed.")
        }
    }

    private fun hideSelectionButtons() {
        (binding.root as android.view.ViewGroup).getChildAt(1).visibility = View.GONE
    }

    private fun showSelectionButtons(error: String) {
        binding.progressBar.visibility = View.GONE
        binding.statusText.text = error
        (binding.root as android.view.ViewGroup).getChildAt(1).visibility = View.VISIBLE
    }

    private fun startOpenClaw() {
        val serviceIntent = Intent(this, OpenClawGatewayService::class.java)
        startService(serviceIntent)

        val dashboardIntent = Intent(this, OpenClawDashboardActivity::class.java)
        startActivity(dashboardIntent)
        finish()
    }
}
