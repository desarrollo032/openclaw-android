import { useState, useCallback } from 'react'
import { bridge } from '../lib/bridge'
import { Brain, RefreshCw, Trash2, FileText } from 'lucide-react'
import { Skeleton } from '../components/Skeleton'

export function Memory() {
  const [context, setContext] = useState('')
  const [loading, setLoading] = useState(true)
  const [wordCount, setWordCount] = useState(0)
  const [cleared, setCleared] = useState(false)

  const loadContext = useCallback(() => {
    if (!bridge.isAvailable()) { setLoading(false); return }
    setLoading(true)
    try {
      const raw = bridge.call('runOpenClawCommand', 'cat ~/.openclaw/context.md')
      const text = typeof raw === 'string' ? raw : ''
      setContext(text)
      setWordCount(text.split(/\s+/).filter(Boolean).length)
    } catch { setContext('') }
    setLoading(false)
  }, [])

  useState(() => { loadContext() })

  const clearContext = () => {
    if (!bridge.isAvailable()) return
    try {
      bridge.call('runOpenClawCommand', 'rm -f ~/.openclaw/context.md')
      setContext('')
      setWordCount(0)
      setCleared(true)
      setTimeout(() => setCleared(false), 2500)
    } catch { /* */ }
  }

  return (
    <div className="page-container flex flex-col gap-4 pt-6 pb-4 animate-fade-in">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-text-primary tracking-tight">Memoria</h1>
          <p className="text-[13px] text-text-muted mt-0.5">Contexto persistente del asistente</p>
        </div>
        <div className="flex items-center gap-1">
          <button onClick={clearContext} disabled={!context}
            className="p-2 rounded-xl text-text-muted hover:text-red hover:bg-red-soft transition-all disabled:opacity-30 disabled:pointer-events-none">
            <Trash2 size={14} />
          </button>
          <button onClick={loadContext}
            className="p-2 rounded-xl text-text-muted hover:text-text-primary hover:bg-glass-bg transition-all">
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          </button>
        </div>
      </div>

      {cleared && (
        <div className="px-4 py-2.5 rounded-xl bg-green-soft border border-green/10 text-xs text-green font-medium animate-slide-up">
          ✓ Contexto eliminado
        </div>
      )}

      <div className="card p-4">
        <div className="flex items-center gap-2.5 mb-3">
          <div className="w-9 h-9 rounded-xl bg-accent-soft flex items-center justify-center">
            <Brain size={17} className="text-accent" />
          </div>
          <div>
            <div className="text-sm font-semibold text-text-primary">Contexto</div>
            <div className="text-[10px] text-text-muted">{wordCount > 0 ? `${wordCount} palabras` : 'Vacío'}</div>
          </div>
        </div>

        {/* Content */}
        {loading ? (
          <div className="space-y-2">
            <Skeleton count={3} variant="text" width="w-40" />
          </div>
        ) : context ? (
          <pre className="text-xs text-text-secondary leading-relaxed whitespace-pre-wrap max-h-48 overflow-y-auto bg-bg-code rounded-xl p-3 border border-glass-border font-mono">
            {context}
          </pre>
        ) : (
          <div className="py-6 text-center">
            <FileText size={24} className="mx-auto mb-2 text-text-dim opacity-30" />
            <p className="text-xs text-text-muted">No hay contexto guardado</p>
          </div>
        )}
      </div>

      <div className="card p-4">
        <h3 className="text-[10px] font-semibold text-text-muted tracking-widest uppercase mb-2">¿Qué es la memoria?</h3>
        <p className="text-xs text-text-muted leading-relaxed">
          El contexto persistente se almacena en <code className="code-inline">~/.openclaw/context.md</code>.
          Puedes editarlo manualmente o dejar que el asistente lo gestione automáticamente.
        </p>
      </div>
    </div>
  )
}
