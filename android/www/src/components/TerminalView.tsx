/**
 * src/components/TerminalView.tsx
 * Terminal interactivo via WebSocket PTY.
 * ResizeObserver para cols/rows dinámicos.
 */

import { useEffect, useRef, useCallback } from 'react'
import { useTerminal } from '../hooks/useTerminal'
import { sendCtrlC, sendCtrlD } from '../api/terminal'
import type { TerminalState } from '../api/terminal'

const CHAR_W = 7.5   // px aproximados por carácter (JetBrains Mono 12.5px)
const LINE_H = 19    // px por línea

const STATE_COLOR: Record<TerminalState, string> = {
  connected:    '#4ade80',
  connecting:   '#facc15',
  disconnected: '#f87171',
  error:        '#f87171',
}
const STATE_LABEL: Record<TerminalState, string> = {
  connected:    'Conectado',
  connecting:   'Conectando...',
  disconnected: 'Desconectado',
  error:        'Error de conexión',
}

export function TerminalView() {
  const { outputBuffer, connectionState, sendInput, resize, reconnect, clearBuffer } = useTerminal()
  const containerRef = useRef<HTMLDivElement>(null)
  const outputRef    = useRef<HTMLDivElement>(null)
  const inputRef     = useRef<HTMLInputElement>(null)

  // Auto-scroll al final cuando llega nuevo output
  useEffect(() => {
    outputRef.current?.scrollTo({ top: outputRef.current.scrollHeight, behavior: 'smooth' })
  }, [outputBuffer])

  // Calcular cols/rows y enviar resize al PTY
  const sendResize = useCallback(() => {
    const el = containerRef.current
    if (!el) return
    const cols = Math.floor(el.clientWidth  / CHAR_W)
    const rows = Math.floor(el.clientHeight / LINE_H)
    if (cols > 0 && rows > 0) resize(cols, rows)
  }, [resize])

  // ResizeObserver para detectar cambios de tamaño del contenedor
  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    sendResize()
    const ro = new ResizeObserver(sendResize)
    ro.observe(el)
    return () => ro.disconnect()
  }, [sendResize])

  // Capturar teclas en el input oculto
  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    e.stopPropagation()
    // Ctrl+C, Ctrl+D sin enviar al input del estado
    if (e.ctrlKey && e.key === 'c') { e.preventDefault(); if (containerRef.current) sendCtrlC({ send: sendInput, resize, disconnect: () => {}, reconnect: () => {}, onData: () => () => {}, onStateChange: () => () => {}, onClose: () => () => {}, onError: () => () => {}, getState: () => connectionState } as any); return }
    if (e.ctrlKey && e.key === 'd') { e.preventDefault(); if (containerRef.current) sendCtrlD({ send: sendInput } as any); return }
    if (e.key === 'Enter') { e.preventDefault(); sendInput('\n'); return }
  }

  const handleInput = (e: React.FormEvent<HTMLInputElement>) => {
    const val = (e.target as HTMLInputElement).value
    if (val) {
      sendInput(val)
      ;(e.target as HTMLInputElement).value = ''
    }
  }

  const dotColor = STATE_COLOR[connectionState]

  return (
    <div style={S.root} ref={containerRef}
      onClick={() => inputRef.current?.focus()}>

      {/* ── Status bar ── */}
      <div style={S.statusBar}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 7, height: 7, borderRadius: '50%', background: dotColor, boxShadow: connectionState === 'connected' ? `0 0 6px ${dotColor}` : 'none' }} />
          <span style={{ fontSize: 11, color: dotColor, fontWeight: 700 }}>{STATE_LABEL[connectionState]}</span>
        </div>
        <div style={{ display: 'flex', gap: 6 }}>
          <button style={S.barBtn} onClick={clearBuffer} title="Limpiar">⌫ Clear</button>
          {connectionState !== 'connected' && (
            <button style={{ ...S.barBtn, color: '#6366f1' }} onClick={reconnect}>⟳ Reconectar</button>
          )}
        </div>
      </div>

      {/* ── Output ── */}
      <div style={S.output} ref={outputRef}>
        {outputBuffer.map((line, i) => (
          <div key={i} style={S.line}>{line || ' '}</div>
        ))}
        {outputBuffer.length === 0 && (
          <div style={{ color: '#444460', fontSize: 12 }}>
            {connectionState === 'connecting' ? 'Conectando al PTY...' : connectionState === 'connected' ? '$ _' : 'Sin conexión al terminal PTY'}
          </div>
        )}
      </div>

      {/* ── Input oculto (captura teclado) ── */}
      <input
        ref={inputRef}
        style={S.hiddenInput}
        onKeyDown={handleKeyDown}
        onInput={handleInput}
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        aria-hidden="true"
      />

      {/* ── Barra de input visible ── */}
      <div style={S.inputBar}>
        <span style={S.prompt}>$</span>
        <div style={S.inputDisplay}
          onClick={() => inputRef.current?.focus()}>
          <span style={{ color: '#a0a0c0', fontSize: 12 }}>Toca aquí para escribir...</span>
        </div>
        <button style={S.ctrlBtn} onPointerDown={e => { e.preventDefault(); sendInput('\x03') }}>^C</button>
        <button style={S.ctrlBtn} onPointerDown={e => { e.preventDefault(); sendInput('\x04') }}>^D</button>
      </div>
    </div>
  )
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex', flexDirection: 'column', height: '100%',
    background: '#050509', overflow: 'hidden', cursor: 'text',
  },
  statusBar: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '5px 10px', background: 'rgba(10,10,24,0.95)',
    borderBottom: '1px solid rgba(99,102,241,0.12)', flexShrink: 0,
  },
  barBtn: {
    background: 'none', border: 'none', color: '#50506a',
    fontSize: 11, fontWeight: 600, cursor: 'pointer', padding: '2px 6px',
  },
  output: {
    flex: 1, overflowY: 'auto', padding: '10px 12px',
    fontFamily: "'JetBrains Mono', 'Courier New', monospace",
    fontSize: 12.5, lineHeight: 1.52, color: '#d0d0e8',
  },
  line: { whiteSpace: 'pre-wrap', wordBreak: 'break-all', minHeight: '1em' },
  hiddenInput: {
    position: 'absolute', opacity: 0, width: 1, height: 1,
    top: 0, left: 0, border: 'none', background: 'transparent',
    WebkitUserSelect: 'text', userSelect: 'text',
  },
  inputBar: {
    display: 'flex', alignItems: 'center', gap: 6,
    padding: '6px 10px', background: 'rgba(14,14,28,0.96)',
    borderTop: '1px solid rgba(99,102,241,0.15)', flexShrink: 0,
  },
  prompt: {
    color: '#6366f1', fontWeight: 700, fontSize: 15,
    fontFamily: "'JetBrains Mono', monospace", flexShrink: 0,
  },
  inputDisplay: {
    flex: 1, padding: '4px 8px', background: 'rgba(99,102,241,0.07)',
    border: '1px solid rgba(99,102,241,0.15)', borderRadius: 7,
    cursor: 'text',
  },
  ctrlBtn: {
    background: 'rgba(248,113,113,0.1)', border: '1px solid rgba(248,113,113,0.2)',
    borderRadius: 6, color: '#f87171', fontSize: 11, fontWeight: 700,
    padding: '4px 8px', cursor: 'pointer', fontFamily: "'JetBrains Mono', monospace",
  },
}
