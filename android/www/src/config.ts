/**
 * src/config.ts
 * Configuración compartida del frontend.
 * Única fuente de verdad para host/puerto del gateway OpenClaw.
 *
 * Debe coincidir con OpenClawConstants.GATEWAY_HOST y GATEWAY_PORT
 * en el lado Kotlin (android/app/src/main/java/com/openclaw/android/OpenClawConstants.kt).
 */

export const GATEWAY_HOST = '127.0.0.1'
export const GATEWAY_PORT = 18789

export const GATEWAY_HTTP = `http://${GATEWAY_HOST}:${GATEWAY_PORT}`
export const GATEWAY_WS   = `ws://${GATEWAY_HOST}:${GATEWAY_PORT}`

/** Path WebSocket del PTY del terminal. */
export const TERMINAL_WS_PATH = '/terminal'

/** Timeout por defecto para llamadas HTTP al gateway (ms). */
export const DEFAULT_HTTP_TIMEOUT_MS = 5_000

/** Timeout por defecto para RPC WebSocket (ms). */
export const DEFAULT_WS_TIMEOUT_MS = 15_000
