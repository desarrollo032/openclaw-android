import { useState, useRef, useEffect, useCallback } from 'react'
import { bridge } from '../lib/bridge'

interface HistoryEntry {
  type: 'cmd' | 'out' | 'err'
  text: string
}

// ── Keyboard key definition ───────────────────────────────────────────────────
interface KeyDef {
  label: string
  flex?: number
  bg: string
  fg: string
  onPress: () => void
}

export function Terminal() {
  const [history, setHistory] = useState<HistoryEntry[]>([
    { type: 'out', text: 'OpenClaw Terminal' },
    { type: 'out', text: 'Escribe un comando o usa los botones de abajo.' },
  ])
  const [input, setInput] = useState('')
  const [ctrlOn, setCtrlOn] = useState(false)
  const [altOn, setAltOn] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollTo(0, scrollRef.current.scrollHeight)
  }, [history])

  const append = (entry: HistoryEntry) =>
    setHistory(prev => [...prev, entry])

  // ── Execute a text command via bridge ─────────────────────────────────────
  const executeCommand = useCallback((cmd: string) => {
    if (!cmd.trim()) return
    append({ type: 'cmd', text: cmd })
    const result = bridge.callJson<{ stdout?: string; stderr?: string }>('runCommand', cmd)
    if (result?.stdout) append({ type: 'out', text: result.stdout })
    if (result?.stderr) append({ type: 'err', text: result.stderr })
    if (!result) append({ type: 'err', text: 'Bridge no disponible.' })
    setInput('')
  }, [])

  // ── Send raw bytes to a running process (not used here, kept for parity) ──
  // Terminal.tsx runs commands synchronously via runCommand bridge.
  // For interactive processes use OnboardActivity (native Kotlin).

  // ── Keyboard rows ─────────────────────────────────────────────────────────
  const key = (
    label: string, bg: string, fg: string,
    flex: number, onPress: () => void
  ): KeyDef => ({ label, bg, fg, flex, onPress })

  const toggleCtrl = () => { setCtrlOn(v => !v); setAltOn(false) }
  const toggleAlt = () => { setAltOn(v => !v); setCtrlOn(false) }

  const typeChar = (ch: string) => {
    if (ctrlOn) {
      // Ctrl+letter → insert ^X notation in input
      setInput(prev => prev + '^' + ch.toUpperCase())
      setCtrlOn(false)
    } else if (altOn) {
      setInput(prev => prev + 'M-' + ch)
      setAltOn(false)
    } else {
      setInput(prev => prev + ch)
    }
    inputRef.current?.focus()
  }

  const row1: KeyDef[] = [
    key('ESC', '#7f1d1d', '#fca5a5', 1, () => { setInput(''); setCtrlOn(false); setAltOn(false) }),
    key('TAB', '#14532d', '#86efac', 1, () => typeChar('\t')),
    key(ctrlOn ? 'CTRL●' : 'CTRL', ctrlOn ? '#92400e' : '#2d2200', '#fbbf24', 1.4, toggleCtrl),
    key(altOn ? 'ALT●' : 'ALT', altOn ? '#92400e' : '#2d2200', '#fbbf24', 1.2, toggleAlt),
    key('HOME', '#1c1c2e', '#c4b5fd', 1, () => setInput('')),
    key('END', '#1c1c2e', '#c4b5fd', 1, () => { }),
    key('PGUP', '#1c1c2e', '#c4b5fd', 1, () => scrollRef.current?.scrollBy(0, -200)),
    key('PGDN', '#1c1c2e', '#c4b5fd', 1, () => scrollRef.current?.scrollBy(0, 200)),
  ]

  const row2: KeyDef[] = [
    key('←', '#1e2a3e', '#93c5fd', 1, () => setInput(prev => prev.slice(0, -1))),
    key('↑', '#1e2a3e', '#93c5fd', 1, () => { }),
    key('↓', '#1e2a3e', '#93c5fd', 1, () => { }),
    key('→', '#1e2a3e', '#93c5fd', 1, () => typeChar(' ')),
    key('SPACE', '#1e2a3e', '#93c5fd', 1.5, () => typeChar(' ')),
    key('BKSP', '#7f1d1d', '#fca5a5', 1, () => setInput(prev => prev.slice(0, -1))),
    key('DEL', '#7f1d1d', '#fca5a5', 1, () => setInput('')),
  ]

  const row3: KeyDef[] = [
    key('↵ ENTER', '#14532d', '#86efac', 2, () => executeCommand(input)),
    key('^C', '#7f1d1d', '#fca5a5', 1, () => { setInput(''); append({ type: 'err', text: '^C' }) }),
    key('^D', '#3b1f1f', '#fca5a5', 1, () => typeChar('d')),
    key('^Z', '#2d2200', '#fbbf24', 1, () => typeChar('z')),
    key('^L', '#1c1c2e', '#c4b5fd', 1, () => setHistory([])),
    key('^U', '#1c1c2e', '#c4b5fd', 1, () => setInput('')),
    key('^K', '#1c1c2e', '#c4b5fd', 1, () => { }),
    key('^W', '#1c1c2e', '#c4b5fd', 1, () => setInput(prev => prev.replace(/\S+\s*$/, ''))),
  ]

  const row4: KeyDef[] = [
    key('F1', '#1c1c2e', '#94a3b8', 1, () => { }),
    key('F2', '#1c1c2e', '#94a3b8', 1, () => { }),
    key('F3', '#1c1c2e', '#94a3b8', 1, () => { }),
    key('F4', '#1c1c2e', '#94a3b8', 1, () => { }),
    key('F5', '#1c1c2e', '#94a3b8', 1, () => { }),
    key('F6', '#1c1c2e', '#94a3b8', 1, () => { }),
    key('INS', '#1c1c2e', '#94a3b8', 1, () => { }),
    key('📋', '#1e2d1e', '#86efac', 1.2, () => {
      const cm = (window as unknown as { OpenClaw?: { getGatewayToken?: () => string } }).OpenClaw
      // paste from clipboard via bridge if available
      try {
        const clip = navigator.clipboard?.readText?.()
        if (clip) clip.then(t => setInput(prev => prev + t))
      } catch { /* not available */ }
    }),
  ]

  const renderRow = (keys: KeyDef[]) => (
    <div style={S.kbRow}>
      {keys.map((k, i) => (
        <button
          key={i}
          style={{
            ...S.kbKey,
            flex: k.flex ?? 1,
            background: k.bg,
            color: k.fg,
          }}
          onPointerDown={e => { e.preventDefault(); k.onPress() }}
        >
          {k.label}
        </button>
      ))}
    </div>
  )

  return (
    <div style={S.root}>

      {/* ── Output ── */}
      <div ref={scrollRef} style={S.output}>
        {history.map((h, i) => (
          <div key={i} style={{
            ...S.line,
            color: h.type === 'cmd' ? '#4ade80' : h.type === 'err' ? '#f87171' : '#e2e8f0',
            fontWeight: h.type === 'cmd' ? 600 : 400,
          }}>
            {h.type === 'cmd' && <span style={{ color: '#6366f1' }}>$ </span>}
            {h.text}
          </div>
        ))}
      </div>

      {/* ── Keyboard ── */}
      <div style={S.keyboard}>
        {renderRow(row1)}
        {renderRow(row2)}
        {renderRow(row3)}
        {renderRow(row4)}

        {/* Input row */}
        <div style={S.inputRow}>
          <span style={S.prompt}>$</span>
          <input
            ref={inputRef}
            style={S.input}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); executeCommand(input) } }}
            placeholder="Escribe o pega tu respuesta..."
          />
          <button
            style={S.sendBtn}
            onPointerDown={e => { e.preventDefault(); executeCommand(input) }}
          >▶</button>
        </div>

        {/* Quick paste / clear */}
        <div style={S.quickRow}>
          <button style={S.quickBtn} onPointerDown={e => {
            e.preventDefault()
            try {
              navigator.clipboard?.readText?.().then(t => setInput(prev => prev + t))
            } catch { /* ignore */ }
          }}>📋 Pegar</button>
          <button style={{ ...S.quickBtn, color: '#f87171' }}
            onPointerDown={e => { e.preventDefault(); setInput('') }}>
            ✕ Limpiar
          </button>
        </div>
      </div>
    </div>
  )
}

// ── Styles ────────────────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex', flexDirection: 'column',
    height: '100%', background: '#080810', overflow: 'hidden',
  },
  output: {
    flex: 1, overflowY: 'auto',
    padding: '10px 12px',
    fontFamily: 'monospace', fontSize: 13,
  },
  line: {
    marginBottom: 3, whiteSpace: 'pre-wrap', wordBreak: 'break-all', lineHeight: 1.5,
  },
  keyboard: {
    background: '#0a0a18',
    borderTop: '1px solid #1e1e35',
    padding: '4px 4px 6px',
    flexShrink: 0,
  },
  kbRow: {
    display: 'flex', gap: 3, marginBottom: 3,
  },
  kbKey: {
    height: 38, borderRadius: 7, border: '1px solid #2d2d4a',
    fontSize: 11, fontWeight: 700, cursor: 'pointer',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    userSelect: 'none', WebkitUserSelect: 'none',
    padding: 0,
  },
  inputRow: {
    display: 'flex', alignItems: 'center', gap: 6,
    background: '#12122a', borderRadius: 10,
    border: '1px solid #2d2d4a',
    padding: '4px 8px', margin: '4px 0',
  },
  prompt: { color: '#6366f1', fontWeight: 700, fontSize: 16, fontFamily: 'monospace' },
  input: {
    flex: 1, background: 'transparent', border: 'none', outline: 'none',
    color: '#e2e8f0', fontFamily: 'monospace', fontSize: 14,
    padding: '4px 0',
  },
  sendBtn: {
    background: '#6366f1', border: 'none', borderRadius: 8,
    color: '#fff', width: 32, height: 32, fontSize: 14,
    cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
  },
  quickRow: {
    display: 'flex', gap: 8, paddingTop: 2,
  },
  quickBtn: {
    background: 'transparent', border: 'none',
    color: '#a5b4fc', fontSize: 12, fontWeight: 600,
    cursor: 'pointer', padding: '2px 4px',
  },
}
