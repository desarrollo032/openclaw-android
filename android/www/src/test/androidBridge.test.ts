import { describe, it, expect } from 'vitest'
import { AndroidBridge } from '../utils/androidBridge'

describe('AndroidBridge compatibility wrapper', () => {
  it('isAvailable returns true when window.OpenClaw is present', () => {
    expect(AndroidBridge.isAvailable()).toBe(true)
  })

  it('checkSetup calls getSetupStatus', () => {
    const status = AndroidBridge.checkSetup()
    expect(status).not.toBeNull()
  })

  it('startSetup calls startSetup on bridge', () => {
    AndroidBridge.startSetup()
    expect(window.OpenClaw?.startSetup).toHaveBeenCalled()
  })
})
