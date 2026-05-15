import { useState, useEffect, useRef, useCallback } from 'react'
import { getLogs } from '../api/gateway'
import { RefreshCw } from 'lucide-react'
import { SearchInput } from './SearchInput'
import { CopyButton } from './CopyButton'
import { EmptyState } from './EmptyState'

interface LogEntry {
  level: string
  message: string
}

function getLevelBadge(level: string): { variant: string; label: string } {
  const l = level.toUpperCase()
  if (l === 'E' || l === 'ERROR')   return { variant: 'badge-error', label: 'ERROR' }
  if (l === 'W' || l === 'WARN')    return { variant: 'badge-warning', label: 'WARN' }
  if (l === 'I' || l === 'INFO')    return { variant: 'badge-info', label: 'INFO' }
  if (l === 'OK')                   return { variant: 'badge-success', label: 'OK' }
  return { variant: 'badge-info', label: level }
}

export function LogsViewer() {
  const [data, setData] = useState<LogEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [autoScroll, setAutoScroll] = useState(true)
  const containerRef = useRef<HTMLDivElement>(null)

  const loadLogs = useCallback(async () => {
    try {
      const entries = await getLogs()
      if (Array.isArray(entries)) setData(entries.map(e => ({ level: e.level ?? 'info', message: e.message ?? '' })))
    } catch { /* */ }
    setLoading(false)
  }, [])

  useEffect(() => {
    loadLogs()
    const id = setInterval(loadLogs, 4000)
    return () => clearInterval(id)
  }, [loadLogs])

  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight
    }
  }, [data, autoScroll])

  const filtered = search
    ? data.filter(e => e.message.toLowerCase().includes(search.toLowerCase()) || e.level.toLowerCase().includes(search.toLowerCase()))
    : data

  const copyText = filtered.map(e => `[${e.level}] ${e.message}`).join('\n')

  return (
    <div className="flex flex-col gap-2">
      {/* ── Toolbar ── */}
      <div className="flex items-center gap-2">
        <SearchInput
          value={search}
          onChange={setSearch}
          placeholder="Filtrar logs..."
          className="flex-1"
        />
        <CopyButton text={copyText} label="Copiar" />
        <button onClick={() => { setLoading(true); loadLogs() }}
          className="p-1.5 rounded-lg text-text-muted hover:text-text-primary hover:bg-glass-bg transition-all"
          aria-label="Actualizar logs">
          <RefreshCw size={13} className={loading ? 'animate-spin' : ''} />
        </button>
      </div>

      {/* ── Logs content ── */}
      <div ref={containerRef}
        className="rounded-xl bg-bg-code border border-glass-border h-[60vh] overflow-y-auto font-mono text-xs leading-relaxed p-4 space-y-1">
        {filtered.length === 0 && !loading && (
          <EmptyState title="Sin logs" subtitle={search ? 'Ningún log coincide con la búsqueda' : undefined} />
        )}
        {filtered.map((entry, i) => (
          <div key={i} className="flex items-start gap-2">
            <span className={`badge shrink-0 ${getLevelBadge(entry.level).variant}`}>
              {getLevelBadge(entry.level).label}
            </span>
            <span className="text-text-secondary break-all">{entry.message}</span>
          </div>
        ))}
      </div>

      {/* ── Footer ── */}
      <div className="flex items-center justify-between text-[10px] text-text-dim px-1">
        <span>{filtered.length} líneas {search ? `(filtradas de ${data.length})` : ''}</span>
        <button onClick={() => setAutoScroll(!autoScroll)}
          className={`px-2 py-0.5 rounded text-[9px] font-medium transition-colors ${
            autoScroll ? 'text-accent' : 'text-text-dim'
          }`}>
          Auto-scroll {autoScroll ? 'ON' : 'OFF'}
        </button>
      </div>
    </div>
  )
}
