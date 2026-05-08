/**
 * src/screens/Logs.tsx — v3
 * Wrapper de página para LogsViewer con la nueva capa HTTP tipada.
 * Combina logs del gateway (HTTP /api/logs) + logs locales del bridge.
 */

import { useState, useCallback, useRef, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { LogsViewer } from '../components/LogsViewer'
import { useLogs } from '../hooks/useLogs'
import { useGatewayStatus } from '../hooks/useGatewayStatus'

type Tab = 'gateway' | 'native'

function formatUptime(secs: number): string {
  if (!secs) return '—'
  const h = Math.floor(secs / 3600)
  const m = Math.floor((secs % 3600) / 60)
  const s = secs % 60
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

export function Logs() {
  const { navigate } = useRoute()
  const { health, reachability } = useGatewayStatus()
  const { logs: nativeLogs, refresh: refreshNative, clear: clearNative, isLoading: nativeLoading } = useLogs(200)

  const [tab,    setTab]    = useState<Tab>('native')
  const [copied, setCopied] = useState(false)
  const [uptimeSecs] = useState(() => {
    try {
      const up = bridge.callJson<{ seconds: number }>('getGatewayUptime')
      return up?.seconds ?? 0
    } catch { return 0 }
  })

  const copyNative = useCallback(() => {
    const text = nativeLogs.map(l => l.message).join('\n')
    try { navigator.clipboard?.writeText(text) }
    catch { bridge.call('copyToClipboard', text) }
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }, [nativeLogs])

  const clearNativeLogs = useCallback(async () => {
    if (!confirm('¿Limpiar los logs nativos?')) return
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    bridge.call('clearGatewayLogs' as any)
    await clearNative()
  }, [clearNative])

  const uptime = health?.uptime ?? uptimeSecs
  const online = reachability === 'online'

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: 'var(--bg)' }}>
      {/* ── Header ── */}
      <div style={S.header}>
        <button style={S.backBtn} onClick={() => navigate('/dashboard')}>←</button>
        <div style={{ flex: 1 }}>
          <div style={S.title}>Logs del sistema</div>
          <div style={S.sub}>
            {online
              ? <><span style={{ color: '#4ade80' }}>●</span> Activo · Uptime {formatUptime(uptime)}</>
              : <><span style={{ color: '#f87171' }}>●</span> Gateway inactivo</>}
          </div>
        </div>
      </div>

      {/* ── Tab selector ── */}
      <div style={S.tabs}>
        <button
          style={{ ...S.tab, borderBottom: tab === 'native' ? '2px solid var(--purple)' : '2px solid transparent', color: tab === 'native' ? 'var(--purple)' : 'var(--text3)' }}
          onClick={() => setTab('native')}>
          🤖 Native / Bridge
        </button>
        <button
          style={{ ...S.tab, borderBottom: tab === 'gateway' ? '2px solid var(--cyan)' : '2px solid transparent', color: tab === 'gateway' ? 'var(--cyan)' : 'var(--text3)' }}
          onClick={() => setTab('gateway')}>
          🌐 Gateway HTTP
        </button>
      </div>

      {/* ── Content ── */}
      <div style={{ flex: 1, overflow: 'hidden', position: 'relative' }}>
        {/* Native logs tab — usa bridge.getGatewayLogs */}
        {tab === 'native' && (
          <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
            <NativeLogsPane
              logs={nativeLogs}
              loading={nativeLoading}
              onRefresh={refreshNative}
              onCopy={copyNative}
              onClear={clearNativeLogs}
              copied={copied}
            />
          </div>
        )}

        {/* Gateway HTTP logs — usa LogsViewer component */}
        {tab === 'gateway' && (
          online
            ? <LogsViewer lines={150} />
            : (
              <div style={S.offline}>
                <span style={{ fontSize: 32 }}>🔌</span>
                <div style={{ color: 'var(--text3)', textAlign: 'center' }}>
                  <div style={{ fontWeight: 700, marginBottom: 6 }}>Gateway sin respuesta</div>
                  <div style={{ fontSize: 12 }}>Los logs HTTP no están disponibles.<br />Usa la pestaña Native para ver los logs locales.</div>
                </div>
              </div>
            )
        )}
      </div>
    </div>
  )
}

// ── Native logs pane ──────────────────────────────────────────────────────────

interface NativeLog { level?: string; message: string; timestamp: number | string }

function NativeLogsPane({ logs, loading, onRefresh, onCopy, onClear, copied }: {
  logs: NativeLog[]
  loading: boolean
  onRefresh: () => void
  onCopy: () => void
  onClear: () => void
  copied: boolean
}) {
  const bottomRef = useRef<HTMLDivElement>(null)
  const [search, setSearch] = useState('')

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs])

  const filtered = search
    ? logs.filter(l => l.message.toLowerCase().includes(search.toLowerCase()))
    : logs

  const levelColor = (lv: string) => {
    switch (lv) { case 'error': return '#f87171'; case 'warn': return '#facc15'; case 'debug': return '#6b7280'; default: return '#a5b4fc' }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* Toolbar */}
      <div style={S.toolbar}>
        <input style={S.search} placeholder="Buscar..." value={search} onChange={e => setSearch(e.target.value)} />
        <button style={S.toolBtn} onClick={onCopy}>{copied ? '✓' : '📋'}</button>
        <button style={S.toolBtn} onClick={onClear}>🗑</button>
        <button style={{ ...S.toolBtn, animation: loading ? 'spin 0.7s linear infinite' : 'none' }} onClick={onRefresh}>↻</button>
      </div>
      {/* Lines */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '4px 8px', fontFamily: "'JetBrains Mono', monospace", fontSize: 11.5 }}>
        {filtered.length === 0 && !loading && (
          <div style={{ color: 'var(--text4)', padding: '30px', textAlign: 'center' }}>Sin logs disponibles</div>
        )}
        {filtered.map((l, i) => (
          <div key={i} style={{ display: 'flex', gap: 6, padding: '2px 0', borderBottom: '1px solid rgba(255,255,255,0.02)', alignItems: 'flex-start' }}>
            <span style={{ fontSize: 9, fontWeight: 700, color: levelColor(l.level ?? 'info'), flexShrink: 0, paddingTop: 2 }}>
              {l.level?.toUpperCase().slice(0, 1) ?? 'I'}
            </span>
            <span style={{ color: '#c0c0d8', flex: 1, wordBreak: 'break-all' }}>{l.message}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      {/* Footer */}
      <div style={{ padding: '4px 10px', borderTop: '1px solid var(--border)', color: 'var(--text4)', fontSize: 10 }}>
        {filtered.length}/{logs.length} líneas · Bridge c/4s
      </div>
    </div>
  )
}

// ── Styles ────────────────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  header: { display: 'flex', alignItems: 'center', gap: 10, padding: '12px 14px', background: 'rgba(8,8,16,0.9)', backdropFilter: 'blur(16px)', borderBottom: '1px solid var(--border)', flexShrink: 0 },
  backBtn: { width: 34, height: 34, borderRadius: 10, border: '1px solid var(--border)', background: 'var(--glass)', color: 'var(--text)', fontSize: 16, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  title: { fontSize: 16, fontWeight: 800, color: 'var(--text)' },
  sub:   { fontSize: 11, color: 'var(--text3)', marginTop: 1 },
  tabs:  { display: 'flex', borderBottom: '1px solid var(--border)', background: 'var(--surface)', flexShrink: 0 },
  tab:   { flex: 1, padding: '10px', background: 'transparent', border: 'none', fontSize: 12, fontWeight: 700, cursor: 'pointer', transition: 'color 0.2s', letterSpacing: '0.2px' },
  toolbar: { display: 'flex', gap: 5, padding: '6px 8px', background: 'rgba(8,8,20,0.95)', borderBottom: '1px solid rgba(99,102,241,0.1)', flexShrink: 0, alignItems: 'center' },
  search:  { flex: 1, background: 'var(--surface2)', border: '1px solid var(--border)', borderRadius: 7, padding: '5px 9px', color: 'var(--text)', fontSize: 12, outline: 'none' },
  toolBtn: { width: 28, height: 28, borderRadius: 7, border: '1px solid var(--border)', background: 'var(--glass)', color: 'var(--text2)', fontSize: 13, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  offline: { display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', gap: 16 },
}
