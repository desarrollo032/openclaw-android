package com.openclaw.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

private const val TAG           = "OpenClawGW"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID    = "openclaw_gateway"

enum class GatewayState { STARTING, READY, RESTARTING, FAILED }

class OpenClawGatewayService : Service() {

    private var gatewayProcess: Process? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Companion: static state accessible from Activities ───────────────────

    companion object {
        private val _state = MutableStateFlow(GatewayState.STARTING)
        val state: StateFlow<GatewayState> = _state

        fun isRunning(): Boolean = _state.value == GatewayState.READY

        fun getState(): GatewayState = _state.value

        fun start(context: Context) {
            val intent = Intent(context, OpenClawGatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OpenClawGatewayService::class.java))
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando gateway..."))
        _state.value = GatewayState.STARTING

        if (!OpenClawInstaller.isPayloadReady(this)) {
            Log.e(TAG, "Payload not ready — cannot start gateway")
            _state.value = GatewayState.FAILED
            updateNotification("Error: payload no instalado")
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            launchGateway()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        gatewayProcess?.destroyForcibly()
        gatewayProcess = null
        _state.value = GatewayState.FAILED
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ── Gateway launch ────────────────────────────────────────────────────────

    private suspend fun launchGateway() {
        startProcess()

        // Health-check loop: poll every 5s, restart if dead
        while (true) {
            delay(5_000)

            val alive = gatewayProcess?.isAlive == true
            if (!alive) {
                Log.w(TAG, "Process died — restarting in 3s")
                _state.value = GatewayState.RESTARTING
                updateNotification("Reiniciando gateway...")
                delay(3_000)
                startProcess()
                continue
            }

            val healthy = withContext(Dispatchers.IO) { isGatewayAlive() }
            if (healthy) {
                if (_state.value != GatewayState.READY) {
                    _state.value = GatewayState.READY
                    updateNotification("Gateway activo ✓")
                    Log.i(TAG, "Gateway is READY")
                }
            } else {
                if (_state.value == GatewayState.READY) {
                    Log.w(TAG, "Health check failed (process still alive)")
                    updateNotification("Gateway: health check fallido")
                }
            }
        }
    }

    private fun startProcess() {
        try {
            val base     = OpenClawInstaller.getPayloadDir(this)
            val loader   = File(base, "glibc/lib/ld-linux-aarch64.so.1")
            val nodeReal = File(base, "node/bin/node.real")
            val libs     = File(base, "glibc/lib").absolutePath
            val openclaw = File(base, "lib/node_modules/openclaw/openclaw.mjs")
            val tmpDir   = File(cacheDir, "tmp").apply { mkdirs() }
            val configDir = OpenClawInstaller.getConfigDir(this)

            // Validate critical files
            listOf(loader, nodeReal, openclaw).forEach { f ->
                if (!f.exists()) {
                    Log.e(TAG, "Missing: ${f.absolutePath}")
                    _state.value = GatewayState.FAILED
                    updateNotification("Error: falta ${f.name}")
                    return
                }
            }

            // ── THE EXACT EXECUTION CHAIN ──────────────────────────────────
            // loader → node.real → openclaw.mjs gateway
            // NEVER call node/bin/node — it does not exist
            val pb = ProcessBuilder(
                loader.absolutePath,
                "--library-path", libs,
                nodeReal.absolutePath,
                openclaw.absolutePath,
                "gateway"
            ).apply {
                directory(base)
                redirectErrorStream(true)
                environment().apply {
                    remove("LD_PRELOAD")                          // must remove
                    put("LD_LIBRARY_PATH", libs)
                    put("OA_GLIBC",        "1")
                    put("CONTAINER",       "1")
                    put("TMPDIR",          tmpDir.absolutePath)
                    put("HOME",            base.absolutePath)
                    put("NODE_PATH",       "${base.absolutePath}/lib/node_modules")
                    put("OPENCLAW_HOME",   configDir.absolutePath)
                    put("SSL_CERT_FILE",   "${base.absolutePath}/etc/tls/cert.pem")
                    put("PATH",            "${base.absolutePath}/node/bin:/system/bin")
                }
            }

            gatewayProcess = pb.start()
            Log.i(TAG, "Process started: ${loader.name} → ${nodeReal.name} → openclaw.mjs")
            updateNotification("Gateway iniciando...")

            // Capture stdout/stderr in a separate coroutine
            val proc = gatewayProcess!!
            serviceScope.launch(Dispatchers.IO) {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, line)
                    }
                    val exit = proc.waitFor()
                    Log.w(TAG, "Proceso terminó con código: $exit")
                } catch (e: Exception) {
                    Log.w(TAG, "Log reader closed: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "startProcess failed", e)
            _state.value = GatewayState.FAILED
            updateNotification("Error al iniciar: ${e.message}")
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenClaw Gateway",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Estado del gateway de OpenClaw" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, OpenClawDashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
