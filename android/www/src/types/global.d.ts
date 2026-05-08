// src/types/global.d.ts
// Tipos globales inyectados por el WebView Android

declare global {
  interface Window {
    /** Token JWT inyectado por OpenClawDashboardActivity.evaluateJavascript */
    __OPENCLAW_TOKEN?: string
    /** Android lo setea en true para que el frontend sepa el contexto */
    __OPENCLAW_ANDROID?: boolean
    /** Event emitter para eventos nativos (bridge Kotlin → React) */
    __oc?: {
      emit(type: string, data: unknown): void
    }
    /** Bridge nativo Kotlin */
    OpenClaw?: {
      getGatewayState?(): string
      getGatewayToken?(): string
      onReactReady?(): void
      [key: string]: unknown
    }
    /** AndroidBridge legacy compat */
    AndroidBridge?: {
      onReactReady?(): void
    }
  }
}

export {}
