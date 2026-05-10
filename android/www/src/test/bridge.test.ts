import { describe, it, expect, vi, beforeEach } from 'vitest'
import { bridge, isAvailable, call, callJson } from '../lib/bridge'

describe('Bridge', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('isAvailable', () => {
    it('should return true when window.OpenClaw is defined', () => {
      expect(isAvailable()).toBe(true)
    })

    it('should return false when window.OpenClaw is undefined', () => {
      const original = window.OpenClaw
      window.OpenClaw = undefined as unknown as typeof window.OpenClaw
      expect(isAvailable()).toBe(false)
      window.OpenClaw = original
    })
  })

  describe('call', () => {
    it('should call method on window.OpenClaw when available', () => {
      const result = call('getGatewayToken')
      expect(window.OpenClaw?.getGatewayToken).toHaveBeenCalled()
      expect(result).toBe('test-token')
    })

    it('should return null when bridge is not available', () => {
      const original = window.OpenClaw
      window.OpenClaw = undefined as unknown as typeof window.OpenClaw
      const result = call('getGatewayToken')
      expect(result).toBeNull()
      window.OpenClaw = original
    })

    it('should pass arguments to bridge methods', () => {
      call('runCommand', 'ls -la')
      expect(window.OpenClaw?.runCommand).toHaveBeenCalledWith('ls -la')
    })

    it('should warn when method is not available', () => {
      const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
      const original = window.OpenClaw
      window.OpenClaw = undefined as unknown as typeof window.OpenClaw
      call('getGatewayToken')
      expect(consoleSpy).toHaveBeenCalledWith('[bridge] OpenClaw not available:', 'getGatewayToken')
      window.OpenClaw = original
      consoleSpy.mockRestore()
    })
  })

  describe('callJson', () => {
    it('should parse JSON response', () => {
      const result = callJson('getAppInfo')
      expect(result).toEqual({
        versionName: '1.0.0',
        versionCode: 1,
        packageName: 'com.openclaw.android'
      })
    })

    it('should return null when method returns null', () => {
      const mockFn = vi.fn().mockReturnValueOnce(null)
      const originalFn = window.OpenClaw?.getSetupStatus
      window.OpenClaw!.getSetupStatus = mockFn
      const result = callJson('getSetupStatus')
      expect(result).toBeNull()
      window.OpenClaw!.getSetupStatus = originalFn!
    })

    it('should handle non-JSON responses gracefully', () => {
      const mockFn = vi.fn().mockReturnValueOnce('plain-text-token')
      const originalFn = window.OpenClaw?.getGatewayToken
      window.OpenClaw!.getGatewayToken = mockFn
      const result = callJson('getGatewayToken')
      expect(result).toBe('plain-text-token')
      window.OpenClaw!.getGatewayToken = originalFn!
    })
  })

  describe('bridge object', () => {
    it('should export all functions', () => {
      expect(bridge.isAvailable).toBe(isAvailable)
      expect(bridge.call).toBe(call)
      expect(bridge.callJson).toBe(callJson)
    })
  })
})
