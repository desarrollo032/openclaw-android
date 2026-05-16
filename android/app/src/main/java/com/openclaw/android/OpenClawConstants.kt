package com.openclaw.android

object OpenClawConstants {

    // Gateway configuration
    const val GATEWAY_PORT = 18789
    const val GATEWAY_HOST = "127.0.0.1"
    const val HEALTH_ENDPOINT = "/health"
    const val GATEWAY_TIMEOUT_MS = 60_000L
    const val HEALTH_POLL_MS = 2_000L

    // Terminal configuration
    const val TERMINAL_FONT_DEFAULT = 18f
    const val TERMINAL_FONT_MIN = 14f
    const val TERMINAL_FONT_MAX = 32f

    // Logging configuration
    const val LOG_MAX_BYTES = 2 * 1024 * 1024
    const val LOG_TAG_GATEWAY = "OpenClawGW"
    const val LOG_TAG_INSTALLER = "OpenClawInstaller"
    const val LOG_TAG_BRIDGE = "OpenClawBridge"
    const val LOG_TAG_TERMINAL = "OpenClawTerminal"

    // Notification configuration
    const val NOTIFICATION_ID = 1001
    const val CHANNEL_ID = "openclaw_gateway"

    // Intent actions
    const val ACTION_RESTART_GATEWAY = "com.openclaw.android.ACTION_RESTART_GATEWAY"

    // SharedPreferences
    const val PREFS_NAME = "openclaw_install"
    const val KEY_CONFIG_RESTORED = "config_restored"
    const val KEY_ONBOARD_COMPLETE = "onboard_complete"
}
