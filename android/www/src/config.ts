/**
 * src/config.ts
 * Configuración compartida del frontend.
 * Única fuente de verdad para host/puerto del gateway OpenClaw.
 *
 * Debe coincidir con OpenClawConstants.GATEWAY_HOST y GATEWAY_PORT
 * en el lado Kotlin (android/app/src/main/java/com/openclaw/android/OpenClawConstants.kt).
 *
 * Las constantes admiten override por variables de entorno Vite
 * (VITE_GATEWAY_HOST, VITE_GATEWAY_PORT, VITE_TERMINAL_WS_PATH) — útil
 * para dev/staging cuando el gateway corre en otra máquina.
 */

const ENV = (typeof import.meta !== 'undefined' && import.meta.env) || ({} as ImportMetaEnv)

function parsePort(raw: string | undefined, fallback: number): number {
  if (!raw) return fallback
  const n = Number.parseInt(raw, 10)
  return Number.isFinite(n) && n > 0 && n < 65536 ? n : fallback
}

export const GATEWAY_HOST: string = ENV.VITE_GATEWAY_HOST ?? '127.0.0.1'
export const GATEWAY_PORT: number = parsePort(ENV.VITE_GATEWAY_PORT, 18789)

export const GATEWAY_HTTP = `http://${GATEWAY_HOST}:${GATEWAY_PORT}`
export const GATEWAY_WS   = `ws://${GATEWAY_HOST}:${GATEWAY_PORT}`

/** Path WebSocket del PTY del terminal. */
export const TERMINAL_WS_PATH: string = ENV.VITE_TERMINAL_WS_PATH ?? '/terminal'

/** Timeout por defecto para llamadas HTTP al gateway (ms). */
export const DEFAULT_HTTP_TIMEOUT_MS = 5_000

/** Timeout por defecto para RPC WebSocket (ms). */
export const DEFAULT_WS_TIMEOUT_MS = 15_000

/** Si DEBUG_BRIDGE está activo, hooks/utilidades pueden registrar llamadas. */
export const DEBUG_BRIDGE: boolean = ENV.VITE_DEBUG_BRIDGE === 'true'
