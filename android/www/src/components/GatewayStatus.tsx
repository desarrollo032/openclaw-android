/**
 * src/components/GatewayStatus.tsx
 * Muestra estado del gateway: uptime, memoria, PID, puerto.
 * Botón Restart con confirmación.
 */

import { useState } from 'react'
import { useGatewayStatus, type GatewayReachability } from '../hooks/useGatewayStatus'
import { restartGateway } from '../api/gateway'

// ── Helpers ───────────────────────────────────────────────────────────────────

function formatUptime(secs: number): string {
  if (!secs || secs < 0) return '—'
  const d = Math.floor(secs / 86400)
  const h = Math.floor((secs % 86400) / 3600)
  const m = Math.floor((secs % 3600) / 60)
  const s = secs % 60
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

const REACH_COLOR: Record<GatewayReachability, string> = {
  online:      '#4ade80',
  starting:    '#facc15',
  unreachable: '#f87171',
  unknown:     '#6b7280',
}
const REACH_BG: Record<GatewayReachability, string> = {
  online:      'rgba(74,222,128,0.12)',
  starting:    'rgba(250,204,21,0.12)',
  unreachable: 'rgba(248,113,113,0.12)',
  unknown:     'rgba(107,114,128,0.1)',
}
const REACH_LABEL: Record<GatewayReachability, string> = {
  online:      'Activo',
  starting:    'Iniciando...',
  unreachable: 'Sin respuesta',
  unknown:     'Desconocido',
}

// ── Component ─────────────────────────────────────────────────────────────────

export function GatewayStatus() {
  const { health, status, reachability, isLoading, error, refresh } = useGatewayStatus()
  const [restarting, setRestarting] = useState(false)
  const [restartMsg, setRestartMsg] = useState('')

  const handleRestart = async () => {
    if (!confirm('¿Reiniciar el gateway? Perderás la sesión actual.')) return
    setRestarting(true)
    setRestartMsg('')
    try {
      await restartGateway()
      setRestartMsg('Gateway reiniciando...')
      setTimeout(refresh, 3000)
    } catch {
      setRestartMsg('Error al reiniciar — intenta desde la notificación')
    } finally {
      setRestarting(false)
    }
  }

  const color = REACH_COLOR[reachability]
  const bg    = REACH_BG[reachability]

  return (
    <div style={S.card}>
      {/* ── Header row ── */}
      <div style={S.headerRow}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          {/* Status dot */}
          <div style={{ ...S.dot, background: color, boxShadow: reachability === 'online' ? `0 0 8px ${color}` : 'none' }} />
          <div>
            <div style={S.title}>Gateway OpenClaw</div>
            <div style={{ ...S.pill, background: bg, color, border: `1px solid ${color}30` }}>
              {isLoading ? 'Verificando...' : REACH_LABEL[reachability]}
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', gap: 6 }}>
          <button style={S.iconBtn}
            onClick={refresh}
            title="Actualizar">
            <span style={{ display: 'inline-block', animation: isLoading ? 'spin 0.7s linear infinite' : 'none' }}>↻</span>
          </button>
          <button style={{ ...S.restartBtn, opacity: restarting ? 0.6 : 1 }}
            onClick={handleRestart}
            disabled={restarting}>
            {restarting ? '⟳' : '⟳'} Reiniciar
          </button>
        </div>
      </div>

      {/* ── Stats grid ── */}
      {!isLoading && reachability !== 'unreachable' && (
        <div style={S.statsGrid}>
          <StatCell icon="⏱" label="Uptime"   value={formatUptime(health?.uptime ?? status?.uptime ?? 0)} />
          <StatCell icon="🧠" label="Memoria"  value={status?.memoryMB ? `${status.memoryMB} MB` : '—'} />
          <StatCell icon="🔌" label="Puerto"   value={status?.port ? `:${status.port}` : ':18789'} />
          <StatCell icon="🏷" label="PID"      value={health?.pid ? String(health.pid) : '—'} />
          <StatCell icon="📦" label="Versión"  value={health?.version ?? '—'} />
          <StatCell icon="🔄" label="Reinicios" value={String(status?.restartCount ?? '—')} />
        </div>
      )}

      {/* ── Error message ── */}
      {error && !isLoading && (
        <div style={S.errorBanner}>{error}</div>
      )}

      {/* ── Restart feedback ── */}
      {restartMsg && (
        <div style={{ ...S.errorBanner, color: '#facc15', background: 'rgba(250,204,21,0.1)', borderColor: 'rgba(250,204,21,0.2)' }}>
          {restartMsg}
        </div>
      )}

      {/* ── Skeleton ── */}
      {isLoading && (
        <div style={S.skeleton}>
          {[1,2,3].map(i => <div key={i} style={S.skeletonLine} />)}
        </div>
      )}
    </div>
  )
}

function StatCell({ icon, label, value }: { icon: string; label: string; value: string }) {
  return (
    <div style={S.statCell}>
      <span style={{ fontSize: 14 }}>{icon}</span>
      <div>
        <div style={S.statLabel}>{label}</div>
        <div style={S.statValue}>{value}</div>
      </div>
    </div>
  )
}

// ── Styles ────────────────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  card: {
    background: 'var(--surface)', border: '1px solid var(--border2)',
    borderRadius: 'var(--r-xl)', padding: '16px',
    boxShadow: 'var(--sh-inset)',
  },
  headerRow: { display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 14, gap: 8 },
  dot:   { width: 10, height: 10, borderRadius: '50%', flexShrink: 0, marginTop: 3, transition: 'all 0.4s' },
  title: { fontSize: 15, fontWeight: 700, color: 'var(--text)', marginBottom: 4 },
  pill:  { display: 'inline-flex', alignItems: 'center', fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 'var(--r-full)', letterSpacing: '0.3px' },
  iconBtn: {
    width: 32, height: 32, borderRadius: 9, border: '1px solid var(--border)',
    background: 'var(--glass)', color: 'var(--text2)', fontSize: 16,
    cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
  },
  restartBtn: {
    display: 'flex', alignItems: 'center', gap: 5,
    padding: '6px 12px', borderRadius: 9,
    border: '1px solid rgba(248,113,113,0.25)', background: 'var(--red-dim)',
    color: 'var(--red)', fontSize: 12, fontWeight: 700, cursor: 'pointer',
  },
  statsGrid: { display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8 },
  statCell:  { display: 'flex', alignItems: 'center', gap: 7, background: 'var(--glass)', border: '1px solid var(--border)', borderRadius: 10, padding: '8px 10px' },
  statLabel: { fontSize: 10, color: 'var(--text3)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.4px' },
  statValue: { fontSize: 13, color: 'var(--text)', fontWeight: 700, fontFamily: "'JetBrains Mono', monospace" },
  errorBanner: { marginTop: 10, padding: '8px 12px', borderRadius: 8, background: 'var(--red-dim)', border: '1px solid rgba(248,113,113,0.2)', color: 'var(--red)', fontSize: 12 },
  skeleton:    { display: 'flex', flexDirection: 'column', gap: 6, marginTop: 10 },
  skeletonLine: { height: 12, background: 'var(--surface3)', borderRadius: 6, animation: 'shimmer 1.5s ease-in-out infinite', backgroundImage: 'linear-gradient(90deg, var(--surface3) 0%, var(--surface2) 50%, var(--surface3) 100%)', backgroundSize: '200% 100%' },
}
