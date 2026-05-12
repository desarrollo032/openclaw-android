import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { AndroidBridge } from '../utils/androidBridge'

describe('AndroidBridge compatibility wrapper', () => {
  const originalOpenClaw = window.OpenClaw
  const originalAndroidBridge = window.AndroidBridge

  beforeEach(() => {
    vi.clearAllMocks()
    window.AndroidBridge = undefined
    window.OpenClaw = {
      ...originalOpenClaw!,
      checkInstallation: vi.fn(() => JSON.stringify({
        payloadReady: false,
        payloadAvailable: true,
        migrationAvailable: true,
        freeSpaceMB: 1024,
        requiredSpaceMB: 400,
        payloadSource: 'apk',
        migrationSource: 'apk',
      })),
      startInstallation: vi.fn(),
      pickPayloadFile: vi.fn(),
      pickMigrationFile: vi.fn(),
    }
  })

  afterEach(() => {
    window.OpenClaw = originalOpenClaw
    window.AndroidBridge = originalAndroidBridge
  })

  it('uses window.OpenClaw when the legacy window.AndroidBridge name is absent', () => {
    expect(AndroidBridge.isAvailable()).toBe(true)

    const status = AndroidBridge.checkInstallation()

    expect(window.OpenClaw?.checkInstallation).toHaveBeenCalled()
    expect(status).toMatchObject({
      payloadReady: false,
      payloadAvailable: true,
      payloadSource: 'apk',
    })
  })

  it('starts installation through window.OpenClaw', () => {
    AndroidBridge.startInstallation()

    expect(window.OpenClaw?.startInstallation).toHaveBeenCalled()
  })
})
