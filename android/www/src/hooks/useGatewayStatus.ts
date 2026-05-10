/**
 * src/hooks/useGatewayStatus.ts
 * Polling del estado del gateway combinando HTTP + KotlinBridge.
 */

import { useState, useEffect, useCallback, useRef } from 'react'
import { getHealth, getStatus, type GatewayHealth, type GatewayStatus } from '../api/gateway'
import { getNativeGatewayState } from '../utils/androidBridge'

const POLL_INTERVAL = 5000

export type GatewayReachability = 'online' | 'starting' | 'unreachable' | 'unknown'

export interface UseGatewayStatusResult {
  health:        GatewayHealth | null
  status:        GatewayStatus | null
  reachability:  GatewayReachability
  isLoading:     boolean
  error:         string | null
  refresh:       () => void
}

export function useGatewayStatus(): UseGatewayStatusResult {
  const [health,    setHealth]    = useState<GatewayHealth | null>(null)
  const [status,    setStatus]    = useState<GatewayStatus | null>(null)
  const [reachability, setReachability] = useState<GatewayReachability>('unknown')
  const [isLoading, setIsLoading] = useState(true)
  const [error,     setError]     = useState<string | null>(null)
  const mountedRef  = useRef(true)

  const fetch = useCallback(async (silent = false) => {
    if (!silent) setIsLoading(true)
    setError(null)

    // 1. Intentar leer estado nativo del bridge (más rápido y confiable)
    const nativeState = getNativeGatewayState()
    if (nativeState === 'STARTING' || nativeState === 'RESTARTING') {
      if (mountedRef.current) setReachability('starting')
      if (!silent && mountedRef.current) setIsLoading(false)
      return
    }
    if (nativeState === 'FAILED') {
      if (mountedRef.current) setReachability('unreachable')
      setError('Gateway falló')
      if (!silent && mountedRef.current) setIsLoading(false)
      return
    }
    
    // Si el estado nativo es READY, marcar como online incluso antes del HTTP check
    if (nativeState === 'READY') {
      if (mountedRef.current) setReachability('online')
      setError(null)
    }

    // 2. HTTP health check (opcional, para obtener datos adicionales)
    try {
      const [h, s] = await Promise.allSettled([getHealth(), getStatus()])

      if (!mountedRef.current) return

      if (h.status === 'fulfilled') {
        setHealth(h.value)
        setReachability(h.value.status === 'ok' ? 'online' : (nativeState === 'READY' ? 'online' : 'unreachable'))
        setError(null)
      }
      
      if (s.status === 'fulfilled') setStatus(s.value)
    } catch {
      // Si el HTTP falla pero el estado nativo es READY, mantener online sin error
      if (nativeState === 'READY') {
        if (mountedRef.current) setReachability('online')
        setError(null)
      } else {
        if (mountedRef.current) setReachability('unreachable')
        setError('Gateway no responde')
      }
    } finally {
      if (mountedRef.current && !silent) setIsLoading(false)
    }
  }, [])

  const refresh = useCallback(() => { fetch(false) }, [fetch])

  useEffect(() => {
    mountedRef.current = true
    fetch(false)
    const id = setInterval(() => fetch(true), POLL_INTERVAL)
    return () => {
      mountedRef.current = false
      clearInterval(id)
    }
  }, [fetch])

  return { health, status, reachability, isLoading, error, refresh }
}
