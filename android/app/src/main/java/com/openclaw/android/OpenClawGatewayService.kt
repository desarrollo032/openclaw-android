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
import java.util.UUID

private const val TAG = "OpenClawGW"

enum class GatewayState { STARTING, READY, RESTARTING, FAILED }

class OpenClawGatewayService : Service() {

    private var gatewayProcess: Process? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mainJob: Job? = null

    // Token de autenticación — generado en cada arranque, nunca persiste en disco
    private var dashboardToken: String = ""

    // Contador de reinicios para la notificación
    private var restartCount = 0

    // ── Companion: static state accessible from Activities ───────────────────

    companion object {
        private val _state = MutableStateFlow(GatewayState.STARTING)
        val state: StateFlow<GatewayState> = _state

        // Token compartido estáticamente — Activities lo leen ANTES de que el
        // servicio envíe el Intent (race condition safety)
        @Volatile private var _currentToken: String = ""
        val currentToken: String get() = _currentToken

        fun isRunning(): Boolean = _state.value == GatewayState.READY
        fun getState(): GatewayState = _state.value

        // Uptime en segundos desde que el proceso arrancó (0 si no está activo)
        private var _processStartTime = 0L
        fun getUptimeSeconds(): Long {
            if (_processStartTime == 0L) return 0L
            return (System.currentTimeMillis() - _processStartTime) / 1000L
        }
        internal fun markProcessStart() { _processStartTime = System.currentTimeMillis() }
        internal fun markProcessStop()  { _processStartTime = 0L }


        fun start(context: Context) {
            // Si la ejecución en segundo plano está deshabilitada, no iniciar
            if (!OpenClawPreferences.isBackgroundExecutionEnabled) {
                OpenClawLogger.log(TAG, "Background execution disabled by user — not starting service")
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
        // Manejar acción de reinicio desde la notificación
        if (intent?.action == OpenClawConstants.ACTION_RESTART_GATEWAY) {
            OpenClawLogger.log(TAG, "Restart requested via notification action")
            serviceScope.launch { restartProcess() }
            return START_STICKY
        }

        startForeground(OpenClawConstants.NOTIFICATION_ID, buildNotification())

        if (!OpenClawInstaller.isPayloadReady(this)) {
            OpenClawLogger.log(TAG, "Payload not ready — cannot start gateway")
            _state.value = GatewayState.FAILED
            updateNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        if (mainJob == null || mainJob?.isActive == false) {
            OpenClawLogger.log(TAG, "Starting gateway main loop")
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
            } else {
                if (_state.value == GatewayState.READY) {
                    OpenClawLogger.log(TAG, "Health check failed (process still alive)")
                    updateNotification()
                }
            }
        }
    }

    private suspend fun restartProcess() {
        gatewayProcess?.destroyForcibly()
        gatewayProcess = null
        restartCount++
        _state.value = GatewayState.RESTARTING
        updateNotification()
        delay(1_000)
        startProcess()
    }

    private fun getNodeCompileCacheDir(): File {
        val compileCacheDir = File(cacheDir, "openclaw-compile-cache")
        if (!compileCacheDir.exists()) {
            compileCacheDir.mkdirs()
        }
        return compileCacheDir
    }

    private fun startProcess() {
        // Generar nuevo token en cada arranque del proceso
        dashboardToken = UUID.randomUUID().toString().replace("-", "") +
                         System.currentTimeMillis().toString()
        _currentToken = dashboardToken
        // Registrar en el logger para redacción automática
        OpenClawLogger.registerSensitiveToken(dashboardToken)

        try {
            val base             = OpenClawInstaller.getPayloadDir(this)
            val filesDir         = this.filesDir
            val nativeDir        = File(applicationInfo.nativeLibraryDir)
            val loader           = File(nativeDir, "libldlinux.so")
            val nodeExec         = File(nativeDir, "libnode.so")
            val glibcLibs        = File(base, "glibc/lib").absolutePath
            val libs             = "${nativeDir.absolutePath}:${glibcLibs}"
            val openclaw         = File(base, "lib/node_modules/openclaw/openclaw.mjs")
            val homeDir          = File(filesDir, "home").apply { mkdirs() }
            val ocHome           = File(homeDir, ".openclaw").apply { mkdirs() }
            val tmpDir           = File(ocHome, "tmp").apply { mkdirs() }
            val nodeCompileCache = getNodeCompileCacheDir()
            val channelsDir      = File(base, "lib/node_modules/openclaw/channels")

            listOf(loader, nodeExec).forEach { f ->
                if (!f.exists()) {
                    OpenClawLogger.log(TAG, "Missing: ${f.absolutePath}")
                    _state.value = GatewayState.FAILED
                    updateNotification()
                    return
                }
                if (!f.canExecute()) {
                    OpenClawLogger.log(TAG, "Fixing exec bit for ${f.absolutePath}")
                    f.setExecutable(true, false)
                }
            }
            if (!openclaw.exists()) {
                OpenClawLogger.log(TAG, "Missing: ${openclaw.absolutePath}")
                _state.value = GatewayState.FAILED
                updateNotification()
                return
            }
            if (!openclaw.canRead()) {
                OpenClawLogger.log(TAG, "Fixing read bit for ${openclaw.absolutePath}")
                openclaw.setReadable(true, false)
            }

            val pb = ProcessBuilder(
                loader.absolutePath,
                "--library-path", libs,
                nodeExec.absolutePath,
                "--disable-warning=ExperimentalWarning",
                openclaw.absolutePath,
                "gateway"
            ).apply {
                directory(base)
                redirectErrorStream(true)
                environment().apply {
                    remove("LD_PRELOAD")
                    put("OA_GLIBC",        "1")
                    put("CONTAINER",       "1")
                    put("TMPDIR",          tmpDir.absolutePath)
                    put("HOME",            homeDir.absolutePath)
                    put("TERM",            "xterm-256color")
                    put("COLORTERM",       "truecolor")
                    put("USER",            "openclaw")
                    put("LOGNAME",         "openclaw")
                    put("PS1",             "~ $ ")
                    put("PATH",            "${File(filesDir, "usr/bin").absolutePath}:${nativeDir.absolutePath}:/system/bin:/system/xbin")
                    put("LD_LIBRARY_PATH", libs)
                    put("NODE_PATH",       File(base, "lib/node_modules").absolutePath)
                    put("OPENCLAW_HOME",   ocHome.absolutePath)
                    put("SSL_CERT_FILE",   File(base, "etc/tls/cert.pem").absolutePath)
                    put("LANG",            "en_US.UTF-8")
                    if (channelsDir.exists()) {
                        put("OPENCLAW_PLUGIN_PATH", channelsDir.absolutePath)
                    }
                    put("NODE_NO_WARNINGS",                          "1")
                    put("OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED", "1")
                    put("OPENCLAW_SOURCE_COMPILE_CACHE_RESPAWNED",   "1")
                    // Variables de optimización de rendimiento
                    put("NODE_COMPILE_CACHE", nodeCompileCache.absolutePath)
                    put("OPENCLAW_NO_RESPAWN", "1")
                    // Token de autenticación del dashboard — nunca se loguea (redactado)
                    put("OPENCLAW_DASHBOARD_TOKEN", dashboardToken)
                }
            }



            gatewayProcess = pb.start()
            markProcessStart()
            val pid = gatewayProcess.hashCode() // Java Process no expone PID en API < 26
            OpenClawLogger.log(TAG, "Process started [pid~${pid}]: ${loader.name} → ${nodeExec.name} → openclaw.mjs")
            _state.value = GatewayState.STARTING
            updateNotification()

            // Capturar stdout/stderr — SIEMPRE, según reglas críticas
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
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val (title, text) = when (_state.value) {
            GatewayState.READY      -> {
                val uptime = formatUptime()
                "🦀 OpenClaw · Activo" to "127.0.0.1:18789 · $uptime"
            }
            GatewayState.RESTARTING ->
                "🦀 OpenClaw · Reiniciando..." to "Intento $restartCount de 3"
            GatewayState.STARTING   ->
                "🦀 OpenClaw · Iniciando..." to "Arrancando gateway Node.js..."
            GatewayState.FAILED     ->
                "🦀 OpenClaw · Error" to "Gateway caído — toca para ver logs"
        }

        // PendingIntent → abrir Dashboard
        val dashIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, OpenClawDashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action: Restart
        val restartIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OpenClawGatewayService::class.java).apply {
                action = OpenClawConstants.ACTION_RESTART_GATEWAY
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action: Ver logs
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
