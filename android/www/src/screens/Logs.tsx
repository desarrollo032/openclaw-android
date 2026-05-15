import { useState, useEffect, useRef, useCallback } from 'react'
import { bridge } from '../lib/bridge'
import { useGatewayStatus } from '../hooks/useGatewayStatus'
import { Activity, Wifi, WifiOff, X } from 'lucide-react'
import { LogsViewer } from '../components/LogsViewer'
import { SearchInput } from '../components/SearchInput'
import { CopyButton } from '../components/CopyButton'
import { EmptyState } from '../components/EmptyState'

function NativeLogsPane() {
  const [lines, setLines] = useState<{ text: string; level: string }[]>([])
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(true)
  const [uptime, setUptime] = useState('')
  const outputRef = useRef<HTMLDivElement>(null)

  const loadLogs = useCallback(() => {
    if (!bridge.isAvailable()) return
    try {
      // getGatewayLogs() retorna { logs: [{ level, message, timestamp }] }
      const raw = bridge.callJson<{ logs: Array<{ level: string; message: string; timestamp: number }> }>('getGatewayLogs')
      const parsed = raw?.logs?.map(log => ({
        text: log.message,
        level: log.level,
      })) ?? []
      setLines(parsed)
      // getGatewayUptime() retorna { seconds, uptimeSeconds }
      const u = bridge.callJson<{ seconds: number; uptimeSeconds: number }>('getGatewayUptime')
      setUptime(u ? `${u.uptimeSeconds}s` : '')
    } catch { /* */ }
    setLoading(false)
  }, [])

  useEffect(() => {
    loadLogs()
    const id = setInterval(loadLogs, 4000)
    return () => clearInterval(id)
  }, [loadLogs])

  useEffect(() => {
    outputRef.current?.scrollTo({ top: outputRef.current.scrollHeight, behavior: 'smooth' })
  }, [lines])

  const filtered = search ? lines.filter(l => l.text.toLowerCase().includes(search.toLowerCase())) : lines
  const copyText = filtered.map(l => l.text).join('\n')

  const clearLogs = () => {
    if (!bridge.isAvailable()) return
    bridge.call('clearGatewayLogs')
    setLines([])
  }

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
        <button onClick={loadLogs}
          className="p-1.5 rounded-lg text-text-muted hover:text-text-primary hover:bg-glass-bg transition-all"
          aria-label="Actualizar">
          <Activity size={13} className={loading ? 'animate-spin' : ''} />
        </button>
        <button onClick={clearLogs}
          className="p-1.5 rounded-lg text-text-muted hover:text-red hover:bg-red-soft transition-all"
          aria-label="Limpiar logs">
          <X size={13} />
        </button>
      </div>

      {/* ── Logs output ── */}
      <div ref={outputRef}
        className="rounded-xl bg-bg-code border border-glass-border h-[60vh] overflow-y-auto font-mono text-xs leading-relaxed p-4 space-y-0.5">
        {filtered.length === 0 && !loading && (
          <EmptyState title="Sin logs" subtitle={search ? 'Ningún log coincide con la búsqueda' : undefined} />
        )}
        {filtered.map((line, i) => (
          <div key={i} className={`whitespace-pre-wrap break-all ${
            line.level === 'error' ? 'text-red' :
            line.level === 'warn' ? 'text-yellow' :
            line.level === 'ok' ? 'text-green' :
            'text-text-muted'
          }`}>{line.text}</div>
        ))}
      </div>

      {/* ── Footer ── */}
      <div className="flex items-center justify-between text-[10px] text-text-dim px-1">
        <span>{filtered.length} líneas {search ? `(filtradas de ${lines.length})` : ''}</span>
        {uptime && <span className="font-mono">Uptime: {uptime}</span>}
      </div>
    </div>
  )
}

export function Logs() {
  const [tab, setTab] = useState<'native' | 'http'>('native')
  const { reachability } = useGatewayStatus()
  const online = reachability === 'online'

  return (
    <div className="page-container flex flex-col gap-4 pt-6 pb-4 animate-fade-in">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-text-primary tracking-tight">Logs</h1>
          <p className="text-[13px] text-text-muted mt-0.5">Registros del sistema</p>
        </div>
        <div className={`badge ${online ? 'badge-success' : 'badge-error'} gap-1`}>
          {online ? <Wifi size={10} /> : <WifiOff size={10} />}
          {online ? 'Online' : 'Offline'}
        </div>
      </div>

      {/* ── Tabs ── */}
      <div className="flex gap-1 card p-1">
        <button onClick={() => setTab('native')}
          className={`flex-1 flex items-center justify-center gap-1.5 py-2 rounded-xl text-xs font-semibold transition-all ${
            tab === 'native' ? 'bg-accent text-white shadow-sm' : 'text-text-muted hover:text-text-secondary'
          }`}>
          <Activity size={13} /> Native
        </button>
        <button onClick={() => setTab('http')}
          className={`flex-1 flex items-center justify-center gap-1.5 py-2 rounded-xl text-xs font-semibold transition-all ${
            tab === 'http' ? 'bg-accent text-white shadow-sm' : 'text-text-muted hover:text-text-secondary'
          }`}>
          <Wifi size={13} /> HTTP
        </button>
      </div>

      {tab === 'native' ? (
        <NativeLogsPane />
      ) : online ? (
        <LogsViewer />
      ) : (
        <div className="card p-8 text-center">
          <div className="w-12 h-12 rounded-2xl bg-red-soft flex items-center justify-center mx-auto mb-3">
            <WifiOff size={24} className="text-red" />
          </div>
          <p className="text-sm font-semibold text-text-primary mb-1">Gateway no disponible</p>
          <p className="text-xs text-text-muted">Inicia el gateway desde el Dashboard para ver los logs HTTP</p>
        </div>
      )}
    </div>
  )
}
