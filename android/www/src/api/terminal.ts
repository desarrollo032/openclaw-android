/**
 * src/api/terminal.ts
 * WebSocket PTY client para el terminal interactivo.
 * Reconexión automática con backoff exponencial.
 * El token se envía en la URL de conexión (estándar WebSocket — no headers).
 */

import { getToken, getBaseUrl } from './client'

// ── Types ─────────────────────────────────────────────────────────────────────

export type TerminalState = 'connecting' | 'connected' | 'disconnected' | 'error'

export type DataCallback  = (data: string) => void
export type StateCallback = (state: TerminalState) => void
export type ErrorCallback = (error: Event) => void

interface PTYInputMessage  { type: 'input';  data: string }
interface PTYResizeMessage { type: 'resize'; cols: number; rows: number }
type PTYMessage = PTYInputMessage | PTYResizeMessage

const WS_PATH       = '/terminal'
const MAX_RETRIES   = 3
const BACKOFF_MS    = [1000, 2000, 4000] as const

// ── TerminalSocket ────────────────────────────────────────────────────────────

export class TerminalSocket {
  private ws:       WebSocket | null = null
  private state:    TerminalState    = 'disconnected'
  private retries   = 0
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private destroyed = false

  private dataCallbacks:  Set<DataCallback>  = new Set()
  private closeCallbacks: Set<() => void>    = new Set()
  private errorCallbacks: Set<ErrorCallback> = new Set()
  private stateCallbacks: Set<StateCallback> = new Set()

  constructor() {
    this.connect()
  }

  // ── Connection ──────────────────────────────────────────────────────────────

  private buildUrl(): string {
    const base  = getBaseUrl().replace('http://', 'ws://').replace('https://', 'wss://')
    const token = getToken()
    return token
      ? `${base}${WS_PATH}?token=${encodeURIComponent(token)}`
      : `${base}${WS_PATH}`
  }

  private connect(): void {
    if (this.destroyed) return
    this.setState('connecting')

    try {
      const ws = new WebSocket(this.buildUrl())

      ws.onopen = () => {
        if (this.destroyed) { ws.close(); return }
        this.ws = ws
        this.retries = 0
        this.setState('connected')
      }

      ws.onmessage = (ev: MessageEvent<string>) => {
        const data = typeof ev.data === 'string' ? ev.data : ''
        this.dataCallbacks.forEach(cb => cb(data))
      }

      ws.onerror = (ev: Event) => {
        this.errorCallbacks.forEach(cb => cb(ev))
        this.setState('error')
      }

      ws.onclose = () => {
        this.ws = null
        if (this.destroyed) return
        this.setState('disconnected')
        this.closeCallbacks.forEach(cb => cb())
        this.scheduleReconnect()
      }
    } catch {
      this.setState('error')
      this.scheduleReconnect()
    }
  }

  private scheduleReconnect(): void {
    if (this.destroyed || this.retries >= MAX_RETRIES) return
    const delay = BACKOFF_MS[this.retries] ?? 4000
    this.retries++
    this.retryTimer = setTimeout(() => this.connect(), delay)
  }

  private setState(s: TerminalState): void {
    if (this.state === s) return
    this.state = s
    this.stateCallbacks.forEach(cb => cb(s))
  }

  // ── Public API ──────────────────────────────────────────────────────────────

  /** Envía input de teclado/texto al PTY */
  send(data: string): void {
    if (this.ws?.readyState !== WebSocket.OPEN) return
    const msg: PTYInputMessage = { type: 'input', data }
    this.ws.send(JSON.stringify(msg))
  }

  /** Notifica al PTY el nuevo tamaño del terminal */
  resize(cols: number, rows: number): void {
    if (this.ws?.readyState !== WebSocket.OPEN) return
    const msg: PTYResizeMessage = { type: 'resize', cols, rows }
    this.ws.send(JSON.stringify(msg))
  }

  /** Registra callback para datos recibidos del PTY */
  onData(cb: DataCallback): () => void {
    this.dataCallbacks.add(cb)
    return () => this.dataCallbacks.delete(cb)
  }

  /** Registra callback para cambios de estado de conexión */
  onStateChange(cb: StateCallback): () => void {
    cb(this.state) // emitir estado actual inmediatamente
    this.stateCallbacks.add(cb)
    return () => this.stateCallbacks.delete(cb)
  }

  /** Registra callback para cierre de conexión */
  onClose(cb: () => void): () => void {
    this.closeCallbacks.add(cb)
    return () => this.closeCallbacks.delete(cb)
  }

  /** Registra callback para errores */
  onError(cb: ErrorCallback): () => void {
    this.errorCallbacks.add(cb)
    return () => this.errorCallbacks.delete(cb)
  }

  /** Estado actual de la conexión */
  getState(): TerminalState { return this.state }

  /** Cierra limpiamente y cancela reconexiones */
  disconnect(): void {
    this.destroyed = true
    if (this.retryTimer) clearTimeout(this.retryTimer)
    this.ws?.close()
    this.ws = null
    this.setState('disconnected')
  }

  /** Fuerza reconexión manual (resetea contador de retries) */
  reconnect(): void {
    this.destroyed = false
    this.retries = 0
    this.ws?.close()
    this.connect()
  }
}

// ── Singleton helpers para uso en hooks ───────────────────────────────────────

/**
 * Envía una línea de raw-input al mensaje de PTY.
 * Útil para botones de teclado virtual.
 */
export function sendRaw(socket: TerminalSocket, text: string): void {
  socket.send(text)
}

/** Envía Ctrl+C al PTY */
export function sendCtrlC(socket: TerminalSocket): void {
  socket.send('\x03')
}

/** Envía Ctrl+D al PTY */
export function sendCtrlD(socket: TerminalSocket): void {
  socket.send('\x04')
}
