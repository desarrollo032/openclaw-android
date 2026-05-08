import { useState, useEffect, useCallback } from 'react'
import { api } from '../lib/api'
import { bridge } from '../lib/bridge'
import { useRoute } from '../lib/router'

/* ── Types ─────────────────────────────────────────────── */
interface Health { status: string; uptime?: string; version?: string }
interface AppInfo { versionName: string; versionCode: number; packageName: string }

/* ── Component ─────────────────────────────────────────── */
export function Dashboard() {
  const { navigate } = useRoute()
  const [health, setHealth] = useState<Health | null>(null)
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null)
  const [nodeVer, setNodeVer] = useState('no encontrado')
  const [gitVer, setGitVer] = useState('no encontrado')
  const [ocVer, setOcVer] = useState('no encontrado')
  const [refreshing, setRefreshing] = useState(false)

  const load = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true)
    try {
      const h = await api.getHealth().catch(() => ({ status: 'offline' }))
      setHealth(h)

      const info = bridge.callJson<AppInfo>('getAppInfo')
      if (info) setAppInfo(info)

      const vNode = bridge.callJson<{ stdout: string }>('runCommand', 'node -v')
      const vOC = bridge.callJson<{ stdout: string }>('runCommand', 'openclaw --version')
      setNodeVer(vNode?.stdout?.trim() || 'no encontrado')
      setGitVer('no incluido')
      setOcVer(vOC?.stdout?.trim() || 'no encontrado')
    } finally {
      setRefreshing(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const online = health?.status === 'ok' || health?.status === 'online'

  return (
    <div className="page" style={{ paddingBottom: 32 }}>

      {/* ── Header card ── */}
      <div style={S.headerCard}>
        <div style={S.headerLeft}>
          <div style={S.logoBox}>
            <span style={{ fontSize: 28 }}>🦀</span>
          </div>
          <div>
            <div style={S.headerTitle}>Openclaw</div>
            <div style={S.statusRow}>
              <span style={{ ...S.dot, background: online ? '#4ade80' : '#f87171' }} />
              <span style={{ ...S.statusText, color: online ? '#4ade80' : '#f87171' }}>
                {online ? 'Activa' : 'Inactiva'}
              </span>
            </div>
          </div>
        </div>
        <button
          style={S.refreshBtn}
          onClick={() => load(true)}
        >
          <span style={{ display: 'inline-block', animation: refreshing ? 'spin 0.8s linear infinite' : 'none' }}>↻</span>
        </button>
      </div>

      {/* ── Entorno de ejecución ── */}
      <div style={S.sectionLabel}>ENTORNO DE EJECUCIÓN</div>
      <div style={S.envCard}>
        <EnvItem icon="⬡" label="Node.js" version={nodeVer} />
        <div style={S.envDivider} />
        <EnvItem icon="⎇" label="git" version={gitVer} />
        <div style={S.envDivider} />
        <EnvItem icon="🦀" label="openclaw" version={ocVer} />
      </div>

      {/* ── Comandos ── */}
      <div style={S.sectionLabel}>COMANDOS</div>
      <div style={S.listCard}>
        <CmdRow
          icon="▶" iconBg="#1e3a5f" iconColor="#60a5fa"
          title="Gateway" sub="openclaw gateway"
          onClick={() => navigate('/terminal')}
        />
        <CmdRow
          icon="●" iconBg="#14532d" iconColor="#4ade80"
          title="Status" sub="openclaw status"
          onClick={() => navigate('/terminal')}
        />
        <CmdRow
          icon="✦" iconBg="#3b2200" iconColor="#fbbf24"
          title="Onboard" sub="openclaw onboard"
          onClick={() => navigate('/terminal')}
        />
        <CmdRow
          icon="≡" iconBg="#1e1e35" iconColor="#a5b4fc"
          title="Logs" sub="openclaw logs --follow"
          onClick={() => navigate('/logs')}
          last
        />
      </div>

      {/* ── Gestión ── */}
      <div style={S.sectionLabel}>GESTIÓN</div>
      <div style={S.listCard}>
        <CmdRow
          icon="↑" iconBg="#14532d" iconColor="#4ade80"
          title="Update" sub="oa --update"
          onClick={() => navigate('/settings')}
        />
        <CmdRow
          icon="+" iconBg="#14532d" iconColor="#4ade80"
          title="Install Tools" sub="oa --install"
          onClick={() => navigate('/settings')}
          last
        />
      </div>

      {/* ── Quick Actions ── */}
      <div style={S.sectionLabel}>QUICK ACTIONS</div>
      <div style={S.quickGrid}>
        <QuickBtn icon="💬" label="Chat" onClick={() => navigate('/chat')} />
        <QuickBtn icon="💻" label="Terminal" onClick={() => navigate('/terminal')} />
        <QuickBtn icon="⚡" label="Skills" onClick={() => navigate('/skills')} />
        <QuickBtn icon="⚙️" label="Settings" onClick={() => navigate('/settings')} />
      </div>

      {/* ── App info ── */}
      {appInfo && (
        <div style={{ textAlign: 'center', marginTop: 16, color: '#444466', fontSize: 11 }}>
          v{appInfo.versionName} · {appInfo.packageName}
        </div>
      )}

    </div>
  )
}

/* ── Sub-components ─────────────────────────────────────── */

function EnvItem({ icon, label, version }: { icon: string; label: string; version: string }) {
  const found = version !== 'no encontrado'
  return (
    <div style={S.envItem}>
      <span style={{ fontSize: 22, marginBottom: 4, color: found ? '#e2e8f0' : '#555' }}>{icon}</span>
      <span style={{ fontSize: 13, fontWeight: 600, color: found ? '#e2e8f0' : '#555' }}>{label}</span>
      <span style={{ fontSize: 11, color: found ? '#6366f1' : '#555', marginTop: 2 }}>{version}</span>
      <span style={{ ...S.dot, background: found ? '#4ade80' : '#f87171', marginTop: 4 }} />
    </div>
  )
}

function CmdRow({
  icon, iconBg, iconColor, title, sub, onClick, last
}: {
  icon: string; iconBg: string; iconColor: string
  title: string; sub: string; onClick: () => void; last?: boolean
}) {
  return (
    <button
      style={{ ...S.cmdRow, borderBottom: last ? 'none' : '1px solid #1a1a2e' }}
      onClick={onClick}
      onTouchStart={e => (e.currentTarget.style.background = '#1a1a2e')}
      onTouchEnd={e => (e.currentTarget.style.background = 'transparent')}
    >
      <div style={{ ...S.cmdIcon, background: iconBg, color: iconColor }}>{icon}</div>
      <div style={S.cmdText}>
        <span style={S.cmdTitle}>{title}</span>
        <span style={S.cmdSub}>{sub}</span>
      </div>
      <span style={S.chevron}>›</span>
    </button>
  )
}

function QuickBtn({ icon, label, onClick }: { icon: string; label: string; onClick: () => void }) {
  return (
    <button
      style={S.quickBtn}
      onClick={onClick}
      onTouchStart={e => (e.currentTarget.style.background = '#1a1a2e')}
      onTouchEnd={e => (e.currentTarget.style.background = '#12122a')}
    >
      <span style={{ fontSize: 24 }}>{icon}</span>
      <span style={{ fontSize: 12, color: '#a0a0c0', marginTop: 4 }}>{label}</span>
    </button>
  )
}

/* ── Styles ─────────────────────────────────────────────── */
const S: Record<string, React.CSSProperties> = {
  headerCard: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    background: '#12122a',
    border: '1px solid #1e1e35',
    borderRadius: 16,
    padding: '14px 16px',
    marginBottom: 16,
  },
  headerLeft: { display: 'flex', alignItems: 'center', gap: 12 },
  logoBox: {
    width: 48, height: 48, borderRadius: 12,
    background: '#1e1e35',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
  },
  headerTitle: { fontSize: 18, fontWeight: 700, color: '#fff' },
  statusRow: { display: 'flex', alignItems: 'center', gap: 6, marginTop: 2 },
  dot: { width: 8, height: 8, borderRadius: '50%', display: 'inline-block', flexShrink: 0 },
  statusText: { fontSize: 13, fontWeight: 500 },
  refreshBtn: {
    width: 36, height: 36, borderRadius: 10,
    border: '1px solid #1e1e35',
    background: '#1a1a2e',
    color: '#a0a0c0', fontSize: 18,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    cursor: 'pointer',
  },
  sectionLabel: {
    fontSize: 11, fontWeight: 700, letterSpacing: '0.08em',
    color: '#555577', marginBottom: 8, marginTop: 4, paddingLeft: 2,
  },
  envCard: {
    display: 'flex',
    background: '#12122a',
    border: '1px solid #1e1e35',
    borderRadius: 14,
    marginBottom: 16,
    overflow: 'hidden',
  },
  envItem: {
    flex: 1, display: 'flex', flexDirection: 'column',
    alignItems: 'center', padding: '14px 8px',
  },
  envDivider: { width: 1, background: '#1e1e35', margin: '10px 0' },
  listCard: {
    background: '#12122a',
    border: '1px solid #1e1e35',
    borderRadius: 14,
    marginBottom: 16,
    overflow: 'hidden',
  },
  cmdRow: {
    width: '100%', display: 'flex', alignItems: 'center', gap: 12,
    padding: '14px 16px',
    background: 'transparent',
    border: 'none', cursor: 'pointer',
    textAlign: 'left',
  },
  cmdIcon: {
    width: 36, height: 36, borderRadius: 10,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 16, fontWeight: 700, flexShrink: 0,
  },
  cmdText: { flex: 1, display: 'flex', flexDirection: 'column', gap: 2 },
  cmdTitle: { fontSize: 15, fontWeight: 600, color: '#e2e8f0' },
  cmdSub: { fontSize: 12, color: '#555577', fontFamily: 'monospace' },
  chevron: { fontSize: 20, color: '#333355' },
  quickGrid: {
    display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr',
    gap: 8, marginBottom: 8,
  },
  quickBtn: {
    background: '#12122a',
    border: '1px solid #1e1e35',
    borderRadius: 12,
    padding: '12px 4px',
    display: 'flex', flexDirection: 'column',
    alignItems: 'center', cursor: 'pointer',
  },
}
