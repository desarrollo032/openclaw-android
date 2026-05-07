package com.openclaw.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class OpenClawGatewayService : Service() {

    private var gatewayProcess: Process? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "openclaw_gateway"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Starting OpenClaw Gateway..."))
        
        serviceScope.launch {
            startGateway()
            monitorGateway()
        }

        return START_STICKY
    }

    private fun startGateway() {
        try {
            val payloadDir = OpenClawInstaller.getPayloadDir(this)
            val configDir = OpenClawInstaller.getConfigDir(this)
            val tmpDir = File(filesDir, "tmp").apply { if (!exists()) mkdirs() }

            val node = File(payloadDir, "bin/node")
            val openclaw = File(payloadDir, "lib/node_modules/openclaw/openclaw.mjs")

            if (!node.exists() || !openclaw.exists()) {
                updateNotification("Error: Payload files missing")
                return
            }

            val pb = ProcessBuilder()
                .command(node.absolutePath, openclaw.absolutePath, "gateway")
                .directory(payloadDir)
                .redirectErrorStream(true)

            // Environment variables
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = File(payloadDir, "glibc/lib").absolutePath
            env["OPENCLAW_HOME"] = configDir.absolutePath
            env["NODE_PATH"] = File(payloadDir, "lib/node_modules").absolutePath
            env["TMPDIR"] = tmpDir.absolutePath
            env["PATH"] = "${File(payloadDir, "bin").absolutePath}:${System.getenv("PATH")}"

            gatewayProcess = pb.start()
            updateNotification("OpenClaw Gateway is running")
            
        } catch (e: Exception) {
            e.printStackTrace()
            updateNotification("Failed to start Gateway: ${e.message}")
        }
    }

    private suspend fun monitorGateway() {
        while (true) {
            val isAlive = gatewayProcess?.isAlive ?: false
            if (!isAlive) {
                updateNotification("Gateway stopped, restarting...")
                startGateway()
            }
            
            // Health check
            val isHealthy = checkHealth()
            if (isHealthy) {
                updateNotification("OpenClaw Gateway is healthy")
            } else {
                updateNotification("OpenClaw Gateway: Health check failed")
            }
            
            delay(10000) // Check every 10s
        }
    }

    private fun checkHealth(): Boolean {
        return try {
            val connection = URL("http://127.0.0.1:18789/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val code = connection.responseCode
            code == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "OpenClaw Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        gatewayProcess?.destroy()
        super.onDestroy()
    }
}
