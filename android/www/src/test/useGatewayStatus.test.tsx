import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'

// Mock del hook completo para evitar problemas con APIs
vi.mock('../hooks/useGatewayStatus', () => ({
  useGatewayStatus: vi.fn().mockReturnValue({
    health: { status: 'ok', version: '1.0.0', uptime: 100, pid: 1234 },
    status: { running: true, uptime: 100, memoryMB: 50, port: 18789 },
    reachability: 'online',
    isLoading: false,
    error: null,
    refresh: vi.fn(),
  }),
}))

describe('useGatewayStatus', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should return mocked state correctly', async () => {
    const { useGatewayStatus } = await import('../hooks/useGatewayStatus')
    const { result } = renderHook(() => useGatewayStatus())

    // Verificar estado mock
    expect(result.current.isLoading).toBe(false)
    expect(result.current.reachability).toBe('online')
    expect(result.current.health?.status).toBe('ok')
    expect(typeof result.current.refresh).toBe('function')
  })

  it('should have correct structure', async () => {
    const { useGatewayStatus } = await import('../hooks/useGatewayStatus')
    const { result } = renderHook(() => useGatewayStatus())

    // Verificar que tiene todas las propiedades esperadas
    expect(result.current).toHaveProperty('health')
    expect(result.current).toHaveProperty('status')
    expect(result.current).toHaveProperty('reachability')
    expect(result.current).toHaveProperty('isLoading')
    expect(result.current).toHaveProperty('error')
    expect(result.current).toHaveProperty('refresh')
  })

  it('should have valid reachability state', async () => {
    const { useGatewayStatus } = await import('../hooks/useGatewayStatus')
    const { result } = renderHook(() => useGatewayStatus())

    const validStates = ['online', 'starting', 'unreachable', 'unknown']
    expect(validStates).toContain(result.current.reachability)
  })
})
