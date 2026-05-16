/**
 * JsBridge wrapper — typed interface to window.OpenClaw (§2.6).
 * All Kotlin @JavascriptInterface methods return JSON strings.
 * Centraliza toda la comunicación Frontend ↔ Android nativo.
 */

interface OpenClawBridge {
  // Terminal
  showTerminal(): void
  showWebView(): void
  createSession(): string
  switchSession(id: string): void
  closeSession(id: string): void
  getTerminalSessions(): string
  writeToTerminal(id: string, data: string): void
  launchInteractiveCommand(cmd: string): void
  openTerminal(): void

  // Setup / Installation
  getSetupStatus(): string
  startSetup(): void
  pickFile(callbackId: string): void
  installFromUri(payloadUri: string, configUri: string): void
  getAssetStatus(): string

  // Tools & Platforms
  saveToolSelections(json: string): void
  getAvailablePlatforms(): string
  getInstalledPlatforms(): string
  installPlatform(id: string): void
  uninstallPlatform(id: string): void
  switchPlatform(id: string): void
  getActivePlatform(): string
  getInstalledTools(): string
  installTool(id: string): void
  uninstallTool(id: string): void
  isToolInstalled(id: string): string

  // Commands
  runCommand(cmd: string): string
  runOpenClawCommand(cmd: string): string
  runCommandAsync(callbackId: string, cmd: string): void

  // System
  getSystemInfo(): string
  getAppInfo(): string
  getApkUpdateInfo(): string
  getBatteryOptimizationStatus(): string
  requestBatteryOptimizationExclusion(): void
  openSystemSettings(page: string): void
  copyToClipboard(text: string): void
  getStorageInfo(): string
  clearCache(): void
  openUrl(url: string): void

  // Background execution
  isBackgroundExecutionEnabled(): string
  setBackgroundExecutionEnabled(enabled: boolean): void

  // OpenClaw config file
  readOpenclawJson(): string
  writeOpenclawJson(content: string): string

  // Gateway & logs
  getGatewayToken(): string
  getGatewayUrl(): string
  getGatewayState(): string
  getGatewayLogs(): string
  getLogs(lines: number): string
  clearGatewayLogs(): void
  clearLogs(): void
  getGatewayUptime(): string
  startGateway(): void
  stopGateway(): void
  restartGateway(): void

  // Auth
  getAuthToken(): string
  getGatewayToken(): string

  // Locale & Theme (sincronización con Android nativo)
  getLocale(): string
  getSystemTheme(): string

  // Updates
  checkForUpdates(): string
  applyUpdate(component: string): void

  // Legacy / misc
  notifyReady(): void
}

declare global {
  interface Window {
    OpenClaw?: OpenClawBridge
    __oc?: { emit(type: string, data: unknown): void }
    __OPENCLAW_TOKEN?: string
    __OPENCLAW_ANDROID?: boolean
  }
}

/* ── Core bridge call helpers ── */

export function isAvailable(): boolean {
  return typeof window.OpenClaw !== 'undefined'
}

export function call<K extends keyof OpenClawBridge>(
  method: K,
  ...args: Parameters<OpenClawBridge[K]>
): ReturnType<OpenClawBridge[K]> | null {
  if (window.OpenClaw && typeof window.OpenClaw[method] === 'function') {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return (window.OpenClaw[method] as (...a: any[]) => any)(...args)
  }
  console.warn('[bridge] OpenClaw not available:', method)
  return null
}

export function callJson<T>(
  method: keyof OpenClawBridge,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ...args: any[]
): T | null {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const raw = (call as any)(method, ...args)
  if (raw == null) return null
  try {
    return JSON.parse(raw as string) as T
  } catch {
    return raw as unknown as T
  }
}

/* ── Token management ── */

/** Obtiene el token de autenticación desde el bridge o variable global */
export function getToken(): string {
  // Intentar variable global primero
  if (window.__OPENCLAW_TOKEN) return window.__OPENCLAW_TOKEN
  // Fallback al bridge Android
  try {
    const token = call('getAuthToken') ?? call('getGatewayToken') ?? ''
    if (token && typeof token === 'string') {
      window.__OPENCLAW_TOKEN = token
      return token
    }
  } catch { /* not in Android */ }
  return ''
}

/* ── Event listeners (Android → WebView) ── */

type NativeEventHandler<T = unknown> = (data: T) => void

/**
 * Escuchar eventos nativos de Android enviados como CustomEvent('android:X')
 * Retorna el handler para poder removerlo con off().
 */
export function on<T = unknown>(
  event: string,
  callback: NativeEventHandler<T>
): EventListenerOrEventListenerObject {
  const handler = (e: Event) => {
    const customEvent = e as CustomEvent<T>
    callback(customEvent.detail)
  }
  window.addEventListener(`android:${event}`, handler)
  // También escuchar versión 'native:' para compatibilidad con eventos del __oc
  window.addEventListener(`native:${event}`, handler)
  return handler
}

/** Remueve un listener de evento Android registrado con on() */
export function off(event: string, handler: EventListenerOrEventListenerObject): void {
  window.removeEventListener(`android:${event}`, handler)
  window.removeEventListener(`native:${event}`, handler)
}

/* ── Token refresh listener ── */

export function onTokenRefresh(callback: (token: string) => void): EventListenerOrEventListenerObject {
  return on<{ token: string }>('onTokenRefresh', (data) => {
    if (data.token) {
      window.__OPENCLAW_TOKEN = data.token
      callback(data.token)
    }
  })
}

/* ── Locale & Theme helpers ── */

/** Obtiene el locale del sistema Android */
export function getNativeLocale(): string {
  const raw = call('getLocale')
  if (raw && typeof raw === 'string') return raw
  return navigator.language || 'en'
}

/** Obtiene el tema del sistema Android (dark/light) */
export function getNativeTheme(): string {
  const raw = call('getSystemTheme')
  if (raw && typeof raw === 'string') return raw
  // Fallback a prefers-color-scheme
  if (typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
    return 'dark'
  }
  return 'light'
}

export const bridge = {
  isAvailable,
  call,
  callJson,
  getToken,
  on,
  off,
  onTokenRefresh,
  getNativeLocale,
  getNativeTheme,
}
