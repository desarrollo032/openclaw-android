/**
 * src/components/LogsViewer.tsx
 * Visualizador de logs del gateway. Usa useLogs() para datos vía HTTP.
 * Tag colorido, auto-scroll, copiar, limpiar.
 */

import { useRef, useEffect, useState, useCallback } from 'react'
import { useLogs } from '../hooks/useLogs'
import { bridge } from '../lib/bridge'
import type { LogEntry } from '../api/gateway'

// ── Tag coloring ──────────────────────────────────────────────────────────────

const TAG_COLORS: Record<string, string> = {
  OpenClawGW: '#6366f1',
  INSTALL:    '#22d3ee',
  ERROR:      '#f87171',
  WARN:       '#facc15',
  DEBUG:      '#6b7280',
}

function tagColor(tag: string): string {
  for (const key of Object.keys(TAG_COLORS)) {
    if (tag.toUpperCase().includes(key.toUpperCase())) return TAG_COLORS[key]
  }
  return '#7070a0'
}

function levelColor(level?: string): string {
  switch (level) {
    case 'error': return '#f87171'
    case 'warn':  return '#facc15'
    case 'debug': return '#6b7280'
    default:      return '#a5b4fc'
  }
}

// ── Component ─────────────────────────────────────────────────────────────────

interface Props {
  lines?: number
  compact?: boolean
}

export function LogsViewer({ lines = 100, compact = false }: Props) {
  const { logs, isLoading, error, refresh, clear } = useLogs(lines)
  const [search, setSearch]       = useState('')
  const [levelFilter, setLevelFilter] = useState<string>('all')
  const [copied, setCopied]       = useState(false)
  const [autoScroll, setAutoScroll] = useState(true)
  const bottomRef = useRef<HTMLDivElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  // Auto-scroll al fondo
  useEffect(() => {
    if (autoScroll) bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs, autoScroll])

  const onScroll = () => {
    const el = scrollRef.current
    if (!el) return
    setAutoScroll(el.scrollHeight - el.scrollTop - el.clientHeight < 40)
  }

  const filtered = logs.filter((l: LogEntry) => {
    const matchLevel = levelFilter === 'all' || l.level === levelFilter
    const matchSearch = !search || l.message.toLowerCase().includes(search.toLowerCase()) ||
                        l.tag?.toLowerCase().includes(search.toLowerCase())
    return matchLevel && matchSearch
  })

  const copyAll = useCallback(() => {
    const text = filtered.map((l: LogEntry) =>
      `[${l.timestamp}] [${l.tag ?? '?'}] ${l.message}`
    ).join('\n')
    try {
      navigator.clipboard?.writeText(text)
    } catch {
      bridge.call('copyToClipboard', text)
    }
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }, [filtered])

  const handleClear = async () => {
    if (!confirm('¿Limpiar todos los logs?')) return
    await clear()
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: '#050509' }}>
      {/* ── Toolbar ── */}
      <div style={S.toolbar}>
        <div style={S.searchWrap}>
          <span style={{ color: 'var(--text3)', fontSize: 13 }}>🔍</span>
          <input style={S.searchInput}
            placeholder="Buscar..."
            value={search}
            onChange={e => setSearch(e.target.value)} />
          {search && <button style={S.clearBtn} onClick={() => setSearch('')}>✕</button>}
        </div>

        {/* Level filter */}
        {!compact && (
          <div style={{ display: 'flex', gap: 4 }}>
            {['all', 'error', 'warn', 'info', 'debug'].map(lv => (
              <button key={lv}
                style={{ ...S.lvBtn, background: levelFilter === lv ? levelColor(lv === 'all' ? 'info' : lv) + '20' : 'transparent', color: levelFilter === lv ? levelColor(lv === 'all' ? 'info' : lv) : 'var(--text3)' }}
                onClick={() => setLevelFilter(lv)}>
                {lv.slice(0, 3).toUpperCase()}
              </button>
            ))}
          </div>
        )}

        <div style={{ display: 'flex', gap: 5, marginLeft: 'auto' }}>
          <button style={S.toolBtn} onClick={copyAll}>{copied ? '✓' : '📋'}</button>
          <button style={S.toolBtn} onClick={handleClear}>🗑</button>
          <button style={{ ...S.toolBtn, animation: isLoading ? 'spin 0.7s linear infinite' : 'none' }} onClick={refresh}>↻</button>
        </div>
      </div>

      {/* ── Log lines ── */}
      <div style={S.logArea} ref={scrollRef} onScroll={onScroll}>
        {isLoading && filtered.length === 0 && (
          <div style={S.empty}>
            <div style={{ width: 20, height: 20, borderRadius: '50%', border: '2px solid rgba(99,102,241,0.2)', borderTopColor: '#6366f1', animation: 'spin 0.8s linear infinite' }} />
          </div>
        )}
        {!isLoading && filtered.length === 0 && (
          <div style={S.empty}>
            <span style={{ fontSize: 28 }}>📭</span>
            <span style={{ color: 'var(--text3)', fontSize: 12 }}>
              {error ?? (search ? 'Sin coincidencias' : 'Sin logs')}
            </span>
          </div>
        )}

        {filtered.map((l: LogEntry, i: number) => (
          <div key={i} style={S.logLine}>
            {/* Timestamp (compacto) */}
            <span style={S.ts}>{l.timestamp ? l.timestamp.slice(-8) : ''}</span>
            {/* Tag */}
            {l.tag && (
              <span style={{ ...S.tag, color: tagColor(l.tag), border: `1px solid ${tagColor(l.tag)}30` }}>
                {l.tag.slice(0, 12)}
              </span>
            )}
            {/* Level */}
            {l.level && (
              <span style={{ ...S.levelBadge, color: levelColor(l.level) }}>
                {l.level.toUpperCase().slice(0, 1)}
              </span>
            )}
            {/* Message */}
            <span style={{ color: '#c0c0d8', flex: 1, wordBreak: 'break-all', fontSize: 11.5 }}>
              {l.message}
            </span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      {/* ── Footer ── */}
      <div style={S.footer}>
        <span style={{ color: 'var(--text4)', fontSize: 10 }}>
          {filtered.length}/{logs.length} líneas
        </span>
        {!autoScroll && (
          <button style={{ background: 'none', border: 'none', color: 'var(--purple)', fontSize: 10, fontWeight: 700, cursor: 'pointer' }}
            onClick={() => { setAutoScroll(true); bottomRef.current?.scrollIntoView({ behavior: 'smooth' }) }}>
            ↓ Al final
          </button>
        )}
      </div>
    </div>
  )
}

// ── Styles ────────────────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  toolbar: {
    display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap',
    padding: '7px 10px', background: 'rgba(8,8,20,0.95)',
    borderBottom: '1px solid rgba(99,102,241,0.1)', flexShrink: 0,
  },
  searchWrap: { display: 'flex', alignItems: 'center', gap: 6, background: 'var(--surface2)', border: '1px solid var(--border)', borderRadius: 8, padding: '5px 10px', minWidth: 120, flex: 1 },
  searchInput: { flex: 1, background: 'transparent', border: 'none', outline: 'none', color: 'var(--text)', fontSize: 12, WebkitUserSelect: 'text', userSelect: 'text' },
  clearBtn: { background: 'none', border: 'none', color: 'var(--text3)', cursor: 'pointer', fontSize: 11 },
  lvBtn: { border: 'none', borderRadius: 5, fontSize: 9, fontWeight: 800, padding: '3px 7px', cursor: 'pointer', letterSpacing: '0.3px' },
  toolBtn: { width: 28, height: 28, borderRadius: 7, border: '1px solid var(--border)', background: 'var(--glass)', color: 'var(--text2)', fontSize: 13, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  logArea: { flex: 1, overflowY: 'auto', padding: '4px 8px', fontFamily: "'JetBrains Mono', monospace" },
  logLine: { display: 'flex', alignItems: 'flex-start', gap: 5, padding: '2px 0', borderBottom: '1px solid rgba(255,255,255,0.02)' },
  ts:    { fontSize: 9, color: 'var(--text4)', flexShrink: 0, paddingTop: 2, fontFamily: 'monospace' },
  tag:   { fontSize: 9, fontWeight: 700, padding: '1px 5px', borderRadius: 4, flexShrink: 0 },
  levelBadge: { fontSize: 9, fontWeight: 900, flexShrink: 0, paddingTop: 2 },
  empty:  { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '40px 20px', color: 'var(--text3)' },
  footer: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '4px 10px', borderTop: '1px solid var(--border)', flexShrink: 0 },
}
