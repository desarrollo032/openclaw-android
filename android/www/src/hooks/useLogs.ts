/**
 * src/hooks/useLogs.ts
 * Carga y gestión de logs del gateway via HTTP.
 * Auto-refresh cada 30 segundos.
 */

import { useState, useEffect, useCallback, useRef } from 'react'
import { getLogs, clearLogs as apiClearLogs, type LogEntry } from '../api/gateway'

const AUTO_REFRESH_MS = 30_000

export interface UseLogsResult {
  logs:      LogEntry[]
  isLoading: boolean
  error:     string | null
  refresh:   () => Promise<void>
  clear:     () => Promise<void>
}

export function useLogs(lines = 100): UseLogsResult {
  const [logs,      setLogs]      = useState<LogEntry[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error,     setError]     = useState<string | null>(null)
  const mountedRef = useRef(true)

  const refresh = useCallback(async () => {
    setIsLoading(true)
    setError(null)
    try {
      const data = await getLogs(lines)
      if (mountedRef.current) setLogs(data)
    } catch (err) {
      if (mountedRef.current) setError((err as Error).message ?? 'Error cargando logs')
    } finally {
      if (mountedRef.current) setIsLoading(false)
    }
  }, [lines])

  const clear = useCallback(async () => {
    try {
      await apiClearLogs()
      if (mountedRef.current) {
        setLogs([])
        setError(null)
      }
    } catch (err) {
      if (mountedRef.current) setError((err as Error).message ?? 'Error limpiando logs')
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true
    refresh()
    const id = setInterval(refresh, AUTO_REFRESH_MS)
    return () => {
      mountedRef.current = false
      clearInterval(id)
    }
  }, [refresh])

  return { logs, isLoading, error, refresh, clear }
}
