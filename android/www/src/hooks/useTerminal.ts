/**
 * src/hooks/useTerminal.ts
 * Hook para el terminal WebSocket PTY.
 * Buffer circular de 1000 líneas, cleanup automático.
 */

import { useState, useEffect, useRef, useCallback } from 'react'
import { TerminalSocket, type TerminalState } from '../api/terminal'

const MAX_BUFFER_LINES = 1000

export interface UseTerminalResult {
  outputBuffer:    string[]
  connectionState: TerminalState
  sendInput:       (data: string) => void
  resize:          (cols: number, rows: number) => void
  disconnect:      () => void
  reconnect:       () => void
  clearBuffer:     () => void
}

export function useTerminal(): UseTerminalResult {
  const [outputBuffer,    setOutputBuffer]    = useState<string[]>([])
  const [connectionState, setConnectionState] = useState<TerminalState>('disconnected')
  const socketRef = useRef<TerminalSocket | null>(null)
  const bufferRef = useRef<string[]>([])

  // Inicializar socket al montar
  useEffect(() => {
    const socket = new TerminalSocket()
    socketRef.current = socket

    // Escuchar datos del PTY
    const unsubData = socket.onData((raw: string) => {
      // Añadir líneas al buffer circular
      const newLines = raw.split('\n')
      bufferRef.current = [...bufferRef.current, ...newLines].slice(-MAX_BUFFER_LINES)
      setOutputBuffer([...bufferRef.current])
    })

    // Escuchar cambios de estado
    const unsubState = socket.onStateChange((s: TerminalState) => {
      setConnectionState(s)
    })

    return () => {
      unsubData()
      unsubState()
      socket.disconnect()
      socketRef.current = null
    }
  }, [])

  const sendInput = useCallback((data: string) => {
    socketRef.current?.send(data)
  }, [])

  const resize = useCallback((cols: number, rows: number) => {
    socketRef.current?.resize(cols, rows)
  }, [])

  const disconnect = useCallback(() => {
    socketRef.current?.disconnect()
  }, [])

  const reconnect = useCallback(() => {
    socketRef.current?.reconnect()
  }, [])

  const clearBuffer = useCallback(() => {
    bufferRef.current = []
    setOutputBuffer([])
  }, [])

  return { outputBuffer, connectionState, sendInput, resize, disconnect, reconnect, clearBuffer }
}
