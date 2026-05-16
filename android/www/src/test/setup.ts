import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// Limpiar después de cada test
afterEach(() => {
  cleanup()
})

// Mock del objeto window.OpenClaw (Android Bridge)
Object.defineProperty(window, 'OpenClaw', {
  writable: true,
  value: {
    showTerminal: vi.fn(),
    showWebView: vi.fn(),
    createSession: vi.fn(() => 'session-1'),
    switchSession: vi.fn(),
    closeSession: vi.fn(),
    getTerminalSessions: vi.fn(() => '[]'),
    writeToTerminal: vi.fn(),
    getSetupStatus: vi.fn(() => JSON.stringify({
      bootstrapInstalled: true,
      platformInstalled: 'openclaw',
      onboardComplete: true
    })),
    startSetup: vi.fn(),
    pickFile: vi.fn(),
    installFromUri: vi.fn(),
    saveToolSelections: vi.fn(),
    getAvailablePlatforms: vi.fn(() => '[]'),
    getInstalledPlatforms: vi.fn(() => '[]'),
    installPlatform: vi.fn(),
    uninstallPlatform: vi.fn(),
    switchPlatform: vi.fn(),
    getActivePlatform: vi.fn(() => '{}'),
    getInstalledTools: vi.fn(() => '[]'),
    installTool: vi.fn(),
    uninstallTool: vi.fn(),
    isToolInstalled: vi.fn(() => 'false'),
    runCommand: vi.fn(() => '{"stdout": "", "exitCode": 0}'),
    runCommandAsync: vi.fn(),
    checkForUpdates: vi.fn(() => '{}'),
    applyUpdate: vi.fn(),
    getApkUpdateInfo: vi.fn(() => '{}'),
    getAppInfo: vi.fn(() => JSON.stringify({
      versionName: '1.0.0',
      versionCode: 1,
      packageName: 'com.openclaw.android'
    })),
    getBatteryOptimizationStatus: vi.fn(() => '{}'),
    requestBatteryOptimizationExclusion: vi.fn(),
    openSystemSettings: vi.fn(),
    copyToClipboard: vi.fn(),
    getStorageInfo: vi.fn(() => JSON.stringify({
      freeSpaceMB: 1000,
      totalSpaceMB: 8000
    })),
    clearCache: vi.fn(),
    openUrl: vi.fn(),
    getGatewayToken: vi.fn(() => 'test-token'),
    getGatewayUrl: vi.fn(() => 'http://127.0.0.1:18789'),
    getGatewayState: vi.fn(() => 'STOPPED'),
    getGatewayLogs: vi.fn(() => '[]'),
    clearGatewayLogs: vi.fn(),
    getGatewayUptime: vi.fn(() => '0s'),
    startGateway: vi.fn(),
    stopGateway: vi.fn(),
    launchInteractiveCommand: vi.fn(),
    openTerminal: vi.fn(),
    getAuthToken: vi.fn(() => 'auth-token'),
    notifyReady: vi.fn(),
  },
})

// Mock de matchMedia
defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(query => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
})

function defineProperty(obj: object, prop: string, value: object) {
  Object.defineProperty(obj, prop, value)
}
