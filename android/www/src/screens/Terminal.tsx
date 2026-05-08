import { useState, useRef, useEffect, useCallback } from 'react'
import { bridge } from '../lib/bridge'

interface HistoryEntry { type: 'cmd' | 'out' | 'err'; text: string }
interface KeyDef { label: string; flex?: number; bg: string; fg: string; onPress: () => void }

// Openclaw commands for autocomplete
const OC_COMMANDS = [
  'openclaw gateway', 'openclaw status', 'openclaw health', 'openclaw logs',
  'openclaw onboard', 'openclaw setup', 'openclaw configure', 'openclaw config',
  'openclaw doctor', 'openclaw update', 'openclaw backup', 'openclaw reset',
  'openclaw uninstall', 'openclaw models', 'openclaw infer', 'openclaw capability',
  'openclaw message', 'openclaw agent', 'openclaw agents', 'openclaw sessions',
  'openclaw memory', 'openclaw commitments', 'openclaw wiki',
  'openclaw approvals', 'openclaw sandbox', 'openclaw chat', 'openclaw browser',
  'openclaw cron', 'openclaw tasks', 'openclaw hooks', 'openclaw webhooks',
  'openclaw security', 'openclaw secrets', 'openclaw skills', 'openclaw plugins',
  'openclaw proxy', 'openclaw dns', 'openclaw docs', 'openclaw pairing',
  'openclaw qr', 'openclaw channels', 'openclaw system',
  'openclaw --version', 'openclaw --help',
  'node -v', 'node --version',
]

// Commands that need interactive TTY → launch OnboardActivity natively
const INTERACTIVE_CMDS = ['gateway', 'onboard', 'configure', 'config', 'logs', 'chat', 'tui', 'browser', 'sandbox']

export function Terminal() {
  const [history, setHistory] = useState<HistoryEntry[]>([
    { type: 'out', text: 'OpenClaw Terminal' },
    { type: 'out', text: '↑↓ historial  •  TAB autocompletar  •  ^L limpiar' },
  ])
  const [input, setInput] = useState('')
  const [ctrlOn, setCtrlOn] = useState(false)
  const [altOn, setAltOn] = useState(false)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [cmdHistory, setCmdHistory] = useState<string[]>([])
  const [_histIdx, setHistIdx] = useState(-1)
  const scrollRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => { scrollRef.current?.scrollTo(0, scrollRef.current.scrollHeight) }, [history])

  // Listen for terminal:run events from Dashboard shortcuts
  useEffect(() => {
    const h = (e: Event) => { const cmd = (e as CustomEvent<string>).detail; if (cmd) runCmd(cmd) }
    window.addEventListener('terminal:run', h)
    return () => window.removeEventListener('terminal:run', h)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Autocomplete
  useEffect(() => {
    if (input.length < 2) { setSuggestions([]); return }
    const q = input.toLowerCase()
    setSuggestions(OC_COMMANDS.filter(c => c.startsWith(q) && c !== input).slice(0, 5))
  }, [input])

  const append = (e: HistoryEntry) => setHistory(prev => [...prev, e])

  const histUp = useCallback(() => {
    setHistIdx(prev => {
      const next = Math.min(prev + 1, cmdHistory.length - 1)
      if (cmdHistory[next] !== undefined) setInput(cmdHistory[next])
      return next
    })
  }, [cmdHistory])

  const histDown = useCallback(() => {
    setHistIdx(prev => {
      const next = Math.max(prev - 1, -1)
      setInput(next === -1 ? '' : cmdHistory[next])
      return next
    })
  }, [cmdHistory])

  const runCmd = useCallback((cmd: string) => {
    if (!cmd.trim()) return
    append({ type: 'cmd', text: cmd })
    setSuggestions([])
    setInput('')
    setHistIdx(-1)
    setCmdHistory(prev => [cmd, ...prev.slice(0, 49)])

    const parts = cmd.trim().split(/\s+/)
    const isOC = parts[0] === 'openclaw' || parts[0] === 'oa'
    const sub = isOC ? (parts[1] ?? '') : parts[0]

    if (INTERACTIVE_CMDS.includes(sub)) {
      append({ type: 'out', text: `↗ Abriendo terminal interactivo: ${cmd}` })
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      bridge.call('launchInteractiveCommand' as any, cmd)
      return
    }

    const result = bridge.callJson<{ stdout?: string; stderr?: string }>('runCommand', cmd)
    if (result?.stdout) append({ type: 'out', text: result.stdout })
    if (result?.stderr) append({ type: 'err', text: result.stderr })
    if (!result) append({ type: 'err', text: 'Bridge no disponible.' })
  }, [cmdHistory]) // eslint-disable-line

  // ── Keyboard ─────────────────────────────────────────────────────────────
  const toggleCtrl = () => { setCtrlOn(v => !v); setAltOn(false) }
  const toggleAlt = () => { setAltOn(v => !v); setCtrlOn(false) }
  const typeChar = (ch: string) => { setInput(p => p + ch); setCtrlOn(false); setAltOn(false); inputRef.current?.focus() }
  const k = (label: string, bg: string, fg: string, flex: number, onPress: () => void): KeyDef => ({ label, bg, fg, flex, onPress })

  const row1: KeyDef[] = [
    k('ESC', '#7f1d1d', '#fca5a5', 1, () => { setInput(''); setCtrlOn(false); setAltOn(false) }),
    k('TAB', '#14532d', '#86efac', 1, () => { if (suggestions[0]) { setInput(suggestions[0]); setSuggestions([]) } else typeChar('\t') }),
    k(ctrlOn ? 'CTRL●' : 'CTRL', ctrlOn ? '#92400e' : '#2d2200', '#fbbf24', 1.4, toggleCtrl),
    k(altOn ? 'ALT●' : 'ALT', altOn ? '#92400e' : '#2d2200', '#fbbf24', 1.2, toggleAlt),
    k('HOME', '#1c1c2e', '#c4b5fd', 1, () => setInput('')),
    k('END', '#1c1c2e', '#c4b5fd', 1, () => inputRef.current?.focus()),
    k('PGUP', '#1c1c2e', '#c4b5fd', 1, () => scrollRef.current?.scrollBy(0, -200)),
    k('PGDN', '#1c1c2e', '#c4b5fd', 1, () => scrollRef.current?.scrollBy(0, 200)),
  ]
  const row2: KeyDef[] = [
    k('←', '#1e2a3e', '#93c5fd', 1, () => setInput(p => p.slice(0, -1))),
    k('↑', '#1e2a3e', '#93c5fd', 1, histUp),
    k('↓', '#1e2a3e', '#93c5fd', 1, histDown),
    k('→', '#1e2a3e', '#93c5fd', 1, () => inputRef.current?.focus()),
    k('SPACE', '#1e2a3e', '#93c5fd', 1.5, () => typeChar(' ')),
    k('BKSP', '#7f1d1d', '#fca5a5', 1, () => setInput(p => p.slice(0, -1))),
    k('DEL', '#7f1d1d', '#fca5a5', 1, () => setInput('')),
  ]
  const row3: KeyDef[] = [
    k('↵ ENTER', '#14532d', '#86efac', 2, () => runCmd(input)),
    k('^C', '#7f1d1d', '#fca5a5', 1, () => { setInput(''); append({ type: 'err', text: '^C' }) }),
    k('^D', '#3b1f1f', '#fca5a5', 1, () => append({ type: 'out', text: '^D' })),
    k('^Z', '#2d2200', '#fbbf24', 1, () => append({ type: 'out', text: '^Z' })),
    k('^L', '#1c1c2e', '#c4b5fd', 1, () => setHistory([])),
    k('^U', '#1c1c2e', '#c4b5fd', 1, () => setInput('')),
    k('^K', '#1c1c2e', '#c4b5fd', 1, () => setInput(p => p.slice(0, p.lastIndexOf(' ') + 1))),
    k('^W', '#1c1c2e', '#c4b5fd', 1, () => setInput(p => p.replace(/\S+\s*$/, ''))),
  ]
  // Row 4: quick-run common commands
  const row4: KeyDef[] = [
    k('status', '#1c1c2e', '#94a3b8', 1.2, () => runCmd('openclaw status')),
    k('health', '#1c1c2e', '#94a3b8', 1.2, () => runCmd('openclaw health')),
    k('models', '#1c1c2e', '#94a3b8', 1.2, () => runCmd('openclaw models')),
    k('doctor', '#1c1c2e', '#94a3b8', 1.2, () => runCmd('openclaw doctor')),
    k('version', '#1c1c2e', '#94a3b8', 1.2, () => runCmd('openclaw --version')),
    k('help', '#1c1c2e', '#94a3b8', 1.2, () => runCmd('openclaw --help')),
    k('skills', '#1c1c2e', '#94a3b8', 1.2, () => runCmd('openclaw skills')),
    k('📋', '#1e2d1e', '#86efac', 1.2, () => {
      try { navigator.clipboard?.readText?.().then(t => { if (t) setInput(p => p + t) }) } catch {/**/ }
    }),
  ]

  const renderRow = (keys: KeyDef[]) => (
    <div style={S.kbRow}>
      {keys.map((k, i) => (
        <button key={i}
          style={{ ...S.kbKey, flex: k.flex ?? 1, background: k.bg, color: k.fg }}
          onPointerDown={e => { e.preventDefault(); k.onPress() }}
        >{k.label}</button>
      ))}
    </div>
  )

  return (
    <div style={S.root}>
      {/* Output */}
      <div ref={scrollRef} style={S.output}>
        {history.map((h, i) => (
          <div key={i} style={{ ...S.line, color: h.type === 'cmd' ? '#4ade80' : h.type === 'err' ? '#f87171' : '#e2e8f0', fontWeight: h.type === 'cmd' ? 600 : 400 }}>
            {h.type === 'cmd' && <span style={{ color: '#6366f1' }}>$ </span>}
            {h.text}
          </div>
        ))}
      </div>

      {/* Autocomplete */}
      {suggestions.length > 0 && (
        <div style={S.suggestions}>
          {suggestions.map((s, i) => (
            <button key={i} style={S.suggestion}
              onPointerDown={e => { e.preventDefault(); setInput(s); setSuggestions([]) }}>
              {s}
            </button>
          ))}
        </div>
      )}

      {/* Keyboard */}
      <div style={S.keyboard}>
        {renderRow(row1)}
        {renderRow(row2)}
        {renderRow(row3)}
        {renderRow(row4)}
        <div style={S.inputRow}>
          <span style={S.prompt}>$</span>
          <input ref={inputRef} style={S.input}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter') { e.preventDefault(); runCmd(input) }
              if (e.key === 'Tab') { e.preventDefault(); if (suggestions[0]) { setInput(suggestions[0]); setSuggestions([]) } }
              if (e.key === 'ArrowUp') { e.preventDefault(); histUp() }
              if (e.key === 'ArrowDown') { e.preventDefault(); histDown() }
            }}
            placeholder="Escribe o pega tu respuesta..."
          />
          <button style={S.sendBtn} onPointerDown={e => { e.preventDefault(); runCmd(input) }}>▶</button>
        </div>
        <div style={S.quickRow}>
          <button style={S.quickBtn} onPointerDown={e => {
            e.preventDefault()
            try { navigator.clipboard?.readText?.().then(t => { if (t) setInput(p => p + t) }) } catch {/**/ }
          }}>📋 Pegar</button>
          <button style={{ ...S.quickBtn, color: '#f87171' }}
            onPointerDown={e => { e.preventDefault(); setInput('') }}>✕ Limpiar</button>
        </div>
      </div>
    </div>
  )
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', background: '#080810', overflow: 'hidden' },
  output: { flex: 1, overflowY: 'auto', padding: '10px 12px', fontFamily: 'monospace', fontSize: 13 },
  line: { marginBottom: 3, whiteSpace: 'pre-wrap', wordBreak: 'break-all', lineHeight: 1.5 },
  suggestions: { display: 'flex', flexWrap: 'wrap', gap: 4, padding: '4px 8px', background: '#0d0d1a', borderTop: '1px solid #1e1e35' },
  suggestion: { background: '#1e1e35', border: '1px solid #3d3d6e', borderRadius: 6, color: '#a5b4fc', fontSize: 11, padding: '3px 8px', cursor: 'pointer' },
  keyboard: { background: '#0a0a18', borderTop: '1px solid #1e1e35', padding: '4px 4px 6px', flexShrink: 0 },
  kbRow: { display: 'flex', gap: 3, marginBottom: 3 },
  kbKey: { height: 38, borderRadius: 7, border: '1px solid #2d2d4a', fontSize: 11, fontWeight: 700, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', userSelect: 'none', WebkitUserSelect: 'none', padding: 0 },
  inputRow: { display: 'flex', alignItems: 'center', gap: 6, background: '#12122a', borderRadius: 10, border: '1px solid #2d2d4a', padding: '4px 8px', margin: '4px 0' },
  prompt: { color: '#6366f1', fontWeight: 700, fontSize: 16, fontFamily: 'monospace' },
  input: { flex: 1, background: 'transparent', border: 'none', outline: 'none', color: '#e2e8f0', fontFamily: 'monospace', fontSize: 14, padding: '4px 0' },
  sendBtn: { background: '#6366f1', border: 'none', borderRadius: 8, color: '#fff', width: 32, height: 32, fontSize: 14, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' },
  quickRow: { display: 'flex', gap: 8, paddingTop: 2 },
  quickBtn: { background: 'transparent', border: 'none', color: '#a5b4fc', fontSize: 12, fontWeight: 600, cursor: 'pointer', padding: '2px 4px' },
}
