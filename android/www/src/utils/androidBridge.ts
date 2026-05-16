/**
 * src/utils/androidBridge.ts
 * Capa de compatibilidad — re-exporta desde bridge.ts.
 * Mantiene la API legacy para no romper imports existentes.
 *
 * Los nuevos desarrollos deben importar directamente desde '../lib/bridge'.
 */

export {
  bridge,
  isAvailable,
  call,
  callJson,
  getToken,
  on,
  off,
  onTokenRefresh,
  getNativeLocale,
  getNativeTheme,
} from '../lib/bridge'

import { call, callJson, getToken, on, off, onTokenRefresh } from '../lib/bridge'

/* ── Installation status (proot + Alpine) ── */
export interface InstallationStatus {
    bootstrapInstalled?: boolean
    alpineReady?: boolean
    alpineAvailable?: boolean
    freeSpaceMB?: number
    requiredSpaceMB?: number
    hasEnoughSpace?: boolean
  }

export interface InstallProgress {
  step: number
  totalSteps: number
  extractedMB: number
  totalMB: number
  percent: number
  currentFile: string
  stepName: string
}

/* ── Wrapper AndroidBridge legacy ── */
export const AndroidBridge = {
    isAvailable: () => typeof window.OpenClaw !== 'undefined',

    checkSetup: (): InstallationStatus | null => {
      return callJson<InstallationStatus>('getSetupStatus')
    },

    startSetup: () => {
      call('startSetup')
    },

    startGateway: () => {
    call('startGateway')
  },

  stopGateway: () => {
    call('stopGateway')
  },

  openTerminal: () => {
    call('openTerminal')
  },

  showTerminal: () => {
    call('showTerminal')
  },

  launchInteractiveCommand: (cmd: string) => {
    call('launchInteractiveCommand', cmd)
  },

  runCommand: (cmd: string): string => {
    return call('runCommand', cmd) ?? ''
  },

  runOpenClawCommand: (cmd: string): string => {
    return call('runOpenClawCommand', cmd) ?? ''
  },

  getSystemInfo: (): string => {
    return call('getSystemInfo') ?? '{}'
  },

  getAppInfo: (): string => {
    return call('getAppInfo') ?? ''
  },

  getStorageInfo: (): string => {
    return call('getStorageInfo') ?? ''
  },

  getGatewayToken: (): string => {
    return call('getGatewayToken') ?? window.__OPENCLAW_TOKEN ?? ''
  },

  getAuthToken: (): string => {
    return call('getAuthToken') ?? window.__OPENCLAW_TOKEN ?? ''
  },

  getLogs: (lines: number): string => {
    return call('getLogs', lines) ?? '[]'
  },

  clearLogs: () => {
    call('clearLogs')
  },    getLocale: (): string => {
      return (call('getLocale') ?? navigator.language) || 'en'
    },

  getSystemTheme: (): string => {
    const raw = call('getSystemTheme')
    if (raw && typeof raw === 'string') return raw
    if (typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
      return 'dark'
    }
    return 'light'
  },

  getToken: (): string => {
    return getToken()
  },

  getNativeGatewayState: (): string => {
    return call('getGatewayState') ?? 'UNKNOWN'
  },

  notifyReady: () => {
    call('notifyReady')
  },

  onTokenRefresh: (callback: (token: string) => void) => {
    return onTokenRefresh(callback)
  },

  on: <T = unknown>(event: string, callback: (data: T) => void) => {
    return on<T>(event, callback)
  },

  off: (event: string, handler: EventListenerOrEventListenerObject) => {
    off(event, handler)
  },
}

/* ── Exports individuales legacy ── */

export const notifyReady = AndroidBridge.notifyReady
export const getNativeGatewayState = AndroidBridge.getNativeGatewayState
