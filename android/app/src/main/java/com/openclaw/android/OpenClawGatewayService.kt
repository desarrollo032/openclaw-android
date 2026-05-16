package com.openclaw.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openclaw.android.proot.OpenClawProot
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "OpenClawGW"

enum class GatewayState { STARTING, READY, RESTARTING, FAILED }

/**
 * OpenClawGatewayService — versión proot + Alpine.
 *
 * El proceso del gateway corre AHORA dentro del rootfs Alpine via proot:
 *
 *     libproot.so --rootfs=alpine-rootfs --bind=... /bin/sh -lc "openclaw gateway"
 *
 * En vez del antiguo:
 *
 *     libldlinux.so --library-path libs libnode.so openclaw.mjs gateway
 *
 * La máquina de estados (STARTING/READY/RESTARTING/FAILED), el token rotativo,
 * el uptime, la notificación foreground y la acción "Restart" se conservan
 * exactamente como antes.
 */
class OpenClawGatewayService : Service() {

    private var gatewayProcess: Process? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mainJob: Job? = null

    private var dashboardToken: String = ""
    private var restartCount = 0

    companion object {
        private val _state = MutableStateFlow(GatewayState.STARTING)
        val state: StateFlow<GatewayState> = _state

        @Volatile private var _currentToken: String = ""
        val currentToken: String get() = _currentToken

        fun isRunning(): Boolean = _state.value == GatewayState.READY
        fun getState(): GatewayState = _state.value

        private var _processStartTime = 0L
        fun getUptimeSeconds(): Long {
            if (_processStartTime == 0L) return 0L
            return (System.currentTimeMillis() - _processStartTime) / 1000L
        }
        internal fun markProcessStart() { _processStartTime = System.currentTimeMillis() }
        internal fun markProcessStop()  { _processStartTime = 0L }

        fun start(context: Context) {
            if (!OpenClawPreferences.isBackgroundExecutionEnabled) {
                OpenClawLogger.log(TAG, "Background execution disabled — not starting service")
                return
            }
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
        OpenClawLogger.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == OpenClawConstants.ACTION_RESTART_GATEWAY) {
            OpenClawLogger.log(TAG, "Restart requested via notification action")
            serviceScope.launch { restartProcess() }
            return START_STICKY
        }

        startForeground(OpenClawConstants.NOTIFICATION_ID, buildNotification())

        if (!OpenClawInstaller.isPayloadReady(this)) {
            OpenClawLogger.log(TAG, "Alpine/openclaw no listos — no se puede iniciar gateway")
            _state.value = GatewayState.FAILED
            updateNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        if (mainJob == null || mainJob?.isActive == false) {
            OpenClawLogger.log(TAG, "Iniciando bucle del gateway (proot)")
            _state.value = GatewayState.STARTING
            mainJob = serviceScope.launch { launchGateway() }
        } else {
            OpenClawLogger.log(TAG, "Gateway loop already running, ignoring start command")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        gatewayProcess?.destroyForcibly()
        gatewayProcess = null
        _state.value = GatewayState.FAILED
        markProcessStop()
        OpenClawLogger.log(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ── Gateway launch ────────────────────────────────────────────────────────

    private suspend fun launchGateway() {
        startProcess()

        while (true) {
            delay(5_000)
            val alive = gatewayProcess?.isAlive == true
            if (!alive) {
                val exit = try { gatewayProcess?.exitValue() } catch (_: Exception) { null }
                OpenClawLogger.log(TAG, "Process died (exit=$exit) — restarting in 3s")
                restartCount++
                _state.value = GatewayState.RESTARTING
                updateNotification()
                delay(3_000)
                startProcess()
                continue
            }

            val healthy = withContext(Dispatchers.IO) { isGatewayAlive() }
            if (healthy) {
                if (_state.value != GatewayState.READY) {
                    _state.value = GatewayState.READY
                    restartCount = 0
                    updateNotification()
                    OpenClawLogger.log(TAG, "Gateway is READY")
                }
            } else if (_state.value == GatewayState.READY) {
                OpenClawLogger.log(TAG, "Health check failed (proceso vivo)")
                updateNotification()
            }
        }
    }

    private suspend fun restartProcess() {
        gatewayProcess?.destroyForcibly()
        gatewayProcess = null
        markProcessStop()
        restartCount++
        _state.value = GatewayState.RESTARTING
        updateNotification()
        delay(1_000)
        startProcess()
    }

    private fun startProcess() {
        dashboardToken = UUID.randomUUID().toString().replace("-", "") +
            System.currentTimeMillis().toString()
        _currentToken = dashboardToken
        OpenClawLogger.registerSensitiveToken(dashboardToken)

        try {
            val proot = OpenClawProot(this)
            if (!proot.isProotPresent()) {
                OpenClawLogger.log(TAG, "libproot.so no presente en nativeLibraryDir")
                _state.value = GatewayState.FAILED
                updateNotification()
                return
            }
            if (!proot.isAlpineInstalled() || !proot.isOpenClawInstalled()) {
                OpenClawLogger.log(TAG, "Alpine u openclaw no instalados — abortando")
                _state.value = GatewayState.FAILED
                updateNotification()
                return
            }

            val pb = proot.buildProotProcess(
                listOf("/bin/sh", "-lc", "openclaw gateway")
            ).apply {
                directory(proot.openclawHome)
                redirectErrorStream(true)
                environment().apply {
                    // Token de auth del dashboard — nunca se loguea (redactado)
                    put("OPENCLAW_DASHBOARD_TOKEN", dashboardToken)
                    // Garantizar puerto/port-friendly env (openclaw lo lee)
                    put("OPENCLAW_GATEWAY_PORT", OpenClawConstants.GATEWAY_PORT.toString())
                    put("OPENCLAW_GATEWAY_HOST", OpenClawConstants.GATEWAY_HOST)
                }
            }

            gatewayProcess = pb.start()
            markProcessStart()
            OpenClawLogger.log(TAG, "Gateway proot process started [hash=${gatewayProcess.hashCode()}]")
            _state.value = GatewayState.STARTING
            updateNotification()

            // Lector de stdout (siempre activo, según reglas críticas)
            val proc = gatewayProcess!!
            serviceScope.launch(Dispatchers.IO) {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        OpenClawLogger.log(TAG, line)
                    }
                    val exit = proc.waitFor()
                    OpenClawLogger.log(TAG, "Proceso terminó con código: $exit")
                } catch (e: Exception) {
                    OpenClawLogger.log(TAG, "Log reader closed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            OpenClawLogger.log(TAG, "startProcess failed: ${e.message}")
            _state.value = GatewayState.FAILED
            updateNotification()
        }
    }

    // ── Uptime ────────────────────────────────────────────────────────────────

    private fun formatUptime(): String {
        val seconds = getUptimeSeconds()
        if (seconds == 0L) return ""
        return when {
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else           -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                OpenClawConstants.CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val (title, text) = when (_state.value) {
            GatewayState.READY      -> {
                val uptime = formatUptime()
                "🦀 OpenClaw · Activo" to
                    "${OpenClawConstants.GATEWAY_HOST}:${OpenClawConstants.GATEWAY_PORT} · $uptime"
            }
            GatewayState.RESTARTING ->
                "🦀 OpenClaw · Reiniciando..." to "Intento $restartCount"
            GatewayState.STARTING   ->
                "🦀 OpenClaw · Iniciando..." to "Arrancando openclaw en Alpine…"
            GatewayState.FAILED     ->
                "🦀 OpenClaw · Error" to "Gateway caído — toca para ver logs"
        }

        val dashIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, OpenClawDashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val restartIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OpenClawGatewayService::class.java).apply {
                action = OpenClawConstants.ACTION_RESTART_GATEWAY
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val logsIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, OpenClawLogsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, OpenClawConstants.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Ejecución en segundo plano")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(dashIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Restart", restartIntent)
            .addAction(0, "Ver logs", logsIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(OpenClawConstants.NOTIFICATION_ID, buildNotification())
    }
}
