import { useState, useEffect, useRef, useCallback } from 'react'
import { bridge } from '../lib/bridge'
import { Terminal as TerminalIcon, RefreshCw, Play, Activity, Star, Zap } from 'lucide-react'

interface HistoryLine { type: 'input' | 'output' | 'error'; text: string }

const OC_COMMANDS = [
  'openclaw status', 'openclaw logs --follow', 'openclaw doctor',
  'openclaw update', 'openclaw help', 'openclaw gateway',
  'openclaw onboard', 'openclaw skills', 'openclaw configure',
  'openclaw configure --edit', 'openclaw configure --section channels',
  'openclaw configure --section platform', 'openclaw configure --section tools',
  'openclaw configure --section system.keepalive',
]

const QUICK = [
  { label: 'Status', cmd: 'openclaw status', icon: Activity },
  { label: 'Logs', cmd: 'openclaw logs --follow', icon: TerminalIcon },
  { label: 'Doctor', cmd: 'openclaw doctor', icon: Zap },
  { label: 'Update', cmd: 'openclaw update', icon: RefreshCw },
  { label: 'Help', cmd: 'openclaw help', icon: Star },
]

const MAX_HISTORY = 50

export function Terminal() {
  const [history, setHistory] = useState<HistoryLine[]>([])
  const [input, setInput] = useState('')
  const [cmdHistory, setCmdHistory] = useState<string[]>([])
  const [cmdIdx, setCmdIdx] = useState(-1)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [selSugg, setSelSugg] = useState(0)
  const [showSugg, setShowSugg] = useState(false)
  const outputRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const addLine = useCallback((line: HistoryLine) => {
    setHistory(prev => [...prev.slice(-200), line])
  }, [])

  useEffect(() => {
    outputRef.current?.scrollTo({ top: outputRef.current.scrollHeight, behavior: 'smooth' })
  }, [history])

  useEffect(() => {
    // Listen for terminal:run events
    const handler = (e: Event) => {
      const cmd = (e as CustomEvent).detail
      if (typeof cmd === 'string') executeCommand(cmd)
    }
    window.addEventListener('terminal:run', handler)
    // Check for pending command
    try {
      const pending = sessionStorage.getItem('openclaw.pendingTerminalCommand')
      if (pending) {
        sessionStorage.removeItem('openclaw.pendingTerminalCommand')
        executeCommand(pending)
      }
    } catch { /* */ }
    return () => window.removeEventListener('terminal:run', handler)
  }, [])

  const sanitize = (s: string) => s.replace(/[<>|;&$`\\]/g, '').trim()

  const executeCommand = useCallback((raw: string) => {
    const cmd = sanitize(raw)
    if (!cmd) return
    addLine({ type: 'input', text: `$ ${cmd}` })
    setCmdHistory(prev => [cmd, ...prev].slice(0, MAX_HISTORY))
    setCmdIdx(-1)

    if (!bridge.isAvailable()) {
      addLine({ type: 'error', text: 'Bridge no disponible — función solo en Android' })
      return
    }

    try {
      if (cmd === 'openclaw gateway' || cmd === 'openclaw gateway start') {
        bridge.call('startGateway')
        addLine({ type: 'output', text: '▶ Iniciando gateway...' })
      } else if (cmd.startsWith('openclaw')) {
        const result = bridge.callJson<string>('runOpenClawCommand', cmd) ?? bridge.call('launchInteractiveCommand', cmd)
        if (typeof result === 'string') addLine({ type: 'output', text: result })
        else addLine({ type: 'output', text: `✔ Comando ejecutado: ${cmd}` })
      } else {
        bridge.call('runOpenClawCommand', cmd)
        addLine({ type: 'output', text: `✔ ${cmd}` })
      }
    } catch (e) {
      addLine({ type: 'error', text: `✖ Error: ${e instanceof Error ? e.message : String(e)}` })
    }
  }, [addLine])

  const handleSubmit = () => {
    const cmd = input.trim()
    if (!cmd) return
    setInput('')
    setShowSugg(false)
    executeCommand(cmd)
  }

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSubmit(); return }
    if (e.key === 'Tab' && showSugg && suggestions.length > 0) {
      e.preventDefault()
      setInput(suggestions[selSugg])
      setShowSugg(false)
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (showSugg && suggestions.length > 0) {
        setSelSugg(prev => Math.max(0, prev - 1))
        return
      }
      if (cmdHistory.length > 0) {
        const next = cmdIdx < cmdHistory.length - 1 ? cmdIdx + 1 : cmdIdx
        setCmdIdx(next)
        setInput(cmdHistory[next] ?? '')
      }
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (showSugg && suggestions.length > 0) {
        setSelSugg(prev => Math.min(suggestions.length - 1, prev + 1))
        return
      }
      if (cmdIdx > 0) {
        setCmdIdx(cmdIdx - 1)
        setInput(cmdHistory[cmdIdx - 1] ?? '')
      } else {
        setCmdIdx(-1)
        setInput('')
      }
      return
    }
    if (e.key === 'Escape') { setShowSugg(false); return }
    // Re-filter suggestions on input change
    setTimeout(() => {
      const val = inputRef.current?.value ?? ''
      if (val.length > 0) {
        const matches = OC_COMMANDS.filter(c => c.toLowerCase().includes(val.toLowerCase())).slice(0, 6)
        setSuggestions(matches)
        setShowSugg(matches.length > 0)
        setSelSugg(0)
      } else {
        setShowSugg(false)
      }
    }, 0)
  }

  const clearHistory = () => setHistory([])

  return (
    <div className="flex flex-col h-full bg-bg">
      {/* ── Header ── */}
      <div className="flex items-center justify-between px-4 pt-4 pb-3">
        <div className="flex items-center gap-2.5">
          <div className="w-9 h-9 rounded-xl bg-accent-soft flex items-center justify-center">
            <TerminalIcon size={18} className="text-accent" />
          </div>
          <div>
            <h2 className="text-sm font-bold text-text-primary">Terminal</h2>
            <span className="text-[10px] text-text-muted">openclaw CLI</span>
          </div>
        </div>
        <button onClick={clearHistory}	className="p-2 rounded-xl text-text-muted hover:text-text-primary hover:bg-glass-bg transition-all">
          <RefreshCw size={14} />
        </button>
      </div>

      {/* ── Quick commands ── */}
      <div className="px-4 pb-3">
        <div className="flex gap-1.5 overflow-x-auto scrollbar-none">
          {QUICK.map(q => (
            <button key={q.cmd} onClick={() => executeCommand(q.cmd)}
              className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg glass text-[10px] text-text-secondary hover:text-text-primary hover:bg-glass-bg transition-all whitespace-nowrap shrink-0">
              <q.icon size={10} /> {q.label}
            </button>
          ))}
        </div>
      </div>

      {/* ── Output area ── */}
      <div ref={outputRef}
        className="flex-1 overflow-y-auto mx-3 mb-2 rounded-xl bg-bg-code border border-glass-border font-mono text-[12px] leading-relaxed">
        <div className="p-4 space-y-0.5 min-h-[120px]">
          {history.length === 0 && (
            <div className="text-text-dim text-center py-8">
              <TerminalIcon size={24} className="mx-auto mb-2 opacity-30" />
              <p className="text-xs">Escribe un comando para empezar</p>
              <p className="text-[10px] mt-1">Usa Tab para autocompletar · ↑↓ para historial</p>
            </div>
          )}
          {history.map((line, i) => (
            <div key={i} className={`${
              line.type === 'input' ? 'text-accent-light' :
              line.type === 'error' ? 'text-red' : 'text-text-secondary'
            } whitespace-pre-wrap break-all`}>
              {line.text}
            </div>
          ))}
        </div>
      </div>

      {/* ── Autocomplete ── */}
      {showSugg && (
        <div className="mx-3 mb-1 glass-strong rounded-xl border-glass-strong-border overflow-hidden shadow-lg">
          {suggestions.map((s, i) => (
            <button key={s}
              onClick={() => { setInput(s); setShowSugg(false); inputRef.current?.focus() }}
              className={`w-full text-left px-3 py-2 text-xs font-mono transition-colors ${
                i === selSugg ? 'bg-accent-soft text-accent' : 'text-text-secondary hover:bg-glass-bg'
              }`}>
              {s}
            </button>
          ))}
        </div>
      )}

      {/* ── Input ── */}
      <div className="mx-3 mb-3">
        <div className="flex items-center gap-2 glass-strong rounded-xl px-3 py-2.5 border-glass-strong-border focus-within:border-accent/20 transition-all">
          <span className="text-accent font-mono text-xs shrink-0">$</span>
          <input ref={inputRef}
            className="flex-1 bg-transparent border-none outline-none text-sm text-text-primary font-mono placeholder-text-dim"
            placeholder="Escribe un comando..."
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKey}
            autoFocus
          />
          <button onClick={handleSubmit}
            disabled={!input.trim()}
            className={`p-1.5 rounded-lg transition-all ${
              input.trim() ? 'text-accent hover:bg-accent-soft' : 'text-text-dim'
            }`}>
            <Play size={14} />
          </button>
        </div>
      </div>
    </div>
  )
}
