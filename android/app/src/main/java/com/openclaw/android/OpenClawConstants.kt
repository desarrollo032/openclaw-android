package com.openclaw.android

object OpenClawConstants {
    // ── Alpine Linux (modelo proot) ─────────────────────────────────────────
    const val ALPINE_VERSION = "3.22.0"
    const val ALPINE_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/" +
        "alpine-minirootfs-3.22.0-aarch64.tar.gz"

    // Asset names (legacy — sólo conservados porque AssetDetector los
    // referencia; nunca existen en assets/ tras la migración a proot).
    const val PAYLOAD_ASSET = "payload-v2.tar.xz"
    const val MIGRATION_ASSET = "openclaw-apk-migration.tar.gz"

    // ── Directory names ─────────────────────────────────────────────────────
    const val PAYLOAD_DIR_NAME = "alpine-rootfs"
    const val OPENCLAW_DIR_NAME = ".openclaw"

    // ── Gateway configuration ───────────────────────────────────────────────
    const val GATEWAY_PORT = 18789
    const val GATEWAY_HOST = "127.0.0.1"
    const val HEALTH_ENDPOINT = "/health"
    const val GATEWAY_TIMEOUT_MS = 60_000L
    const val HEALTH_POLL_MS = 2_000L

    // ── Binarios nativos ────────────────────────────────────────────────────
    // Único binario nativo del nuevo modelo. libldlinux.so/libnode.so/libbusybox.so
    // se ELIMINARON con la migración a proot.
    const val LIB_PROOT = "libproot.so"

    // ── Terminal configuration ──────────────────────────────────────────────
    const val TERMINAL_FONT_DEFAULT = 18f
    const val TERMINAL_FONT_MIN = 14f
    const val TERMINAL_FONT_MAX = 32f

    // ── Logging configuration ───────────────────────────────────────────────
    const val LOG_MAX_BYTES = 2 * 1024 * 1024
    const val LOG_TAG_GATEWAY = "OpenClawGW"
    const val LOG_TAG_INSTALLER = "OpenClawInstaller"
    const val LOG_TAG_BRIDGE = "OpenClawBridge"
    const val LOG_TAG_TERMINAL = "OpenClawTerminal"

    // ── Notification configuration ──────────────────────────────────────────
    const val NOTIFICATION_ID = 1001
    const val CHANNEL_ID = "openclaw_gateway"

    // ── Intent actions ──────────────────────────────────────────────────────
    const val ACTION_RESTART_GATEWAY = "com.openclaw.android.ACTION_RESTART_GATEWAY"

    // ── SharedPreferences ───────────────────────────────────────────────────
    const val PREFS_NAME = "openclaw_install"
    const val KEY_PAYLOAD_INSTALLED = "payload_installed"
    const val KEY_CONFIG_RESTORED = "config_restored"
    const val KEY_ONBOARD_COMPLETE = "onboard_complete"
}
