/**
 * src/utils/androidBridge.ts
 * Abstracción del puente Android ↔ React.
 * Detecta si corre en WebView nativo y expone helpers tipados.
 */

import type {} from '../types/global'

// ── Token management ──────────────────────────────────────────────────────────

/** Lee el token inyectado por Android. Nunca lo loggea. */
export function getToken(): string {
  return window.__OPENCLAW_TOKEN ?? ''
}

/** Devuelve true si corre dentro del WebView nativo de Android */
export function isRunningInAndroid(): boolean {
  return window.__OPENCLAW_ANDROID === true ||
    typeof window.OpenClaw !== 'undefined'
}

// ── Lifecycle signals ─────────────────────────────────────────────────────────

/**
 * Notifica a Android que React montó correctamente.
 * Llamar una sola vez al inicio de la app.
 */
export function notifyReady(): void {
  try {
    (window.OpenClaw as any)?.onReactReady?.()
    (window.AndroidBridge as any)?.onReactReady?.()
  } catch {
    // No disponible en browser de desarrollo — ignorar
  }
}

// ── Token refresh ─────────────────────────────────────────────────────────────

type TokenRefreshCallback = (newToken: string) => void
const refreshCallbacks = new Set<TokenRefreshCallback>()

/**
 * Registra un callback para cuando Android refresque el token.
 * Android dispara: window.dispatchEvent(new CustomEvent('openclaw:token-refresh', { detail: { token } }))
 * Retorna función para desregistrar (usar en useEffect cleanup).
 */
export function onTokenRefresh(cb: TokenRefreshCallback): () => void {
  refreshCallbacks.add(cb)
  return () => refreshCallbacks.delete(cb)
}

// Escuchar el evento global una sola vez
if (typeof window !== 'undefined') {
  window.addEventListener('openclaw:token-refresh', (e: Event) => {
    const detail = (e as CustomEvent<{ token?: string }>).detail
    const newToken = detail?.token ?? ''
    if (newToken) {
      // Actualizar variable global (Android la reinyecta, pero por si acaso)
      ;(window as Window).__OPENCLAW_TOKEN = newToken
      refreshCallbacks.forEach(cb => cb(newToken))
    }
  })
}

// ── Gateway state from bridge ─────────────────────────────────────────────────

export type NativeGatewayState = 'STARTING' | 'READY' | 'RESTARTING' | 'FAILED' | 'UNKNOWN'

/** Lee el estado del gateway directamente del KotlinBridge (más fiable que HTTP) */
export function getNativeGatewayState(): NativeGatewayState {
  try {
    const state = window.OpenClaw?.getGatewayState?.()
    if (state === 'STARTING' || state === 'READY' || state === 'RESTARTING' || state === 'FAILED') {
      return state
    }
  } catch { /* no disponible */ }
  return 'UNKNOWN'
}
