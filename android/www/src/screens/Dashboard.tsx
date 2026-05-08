/**
 * src/screens/Dashboard.tsx — v4
 * Fiel a la estructura original:
 *   - Card gateway status
 *   - ENTORNO DE EJECUCIÓN (Node.js, git, openclaw)
 *   - COMANDOS (Gateway, Status, Onboard, Logs) — siempre visible
 *   - GESTIÓN (Update, Configure, Doctor, Skills) — siempre visible
 *   - Footer con versión del paquete
 */

import { useState, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { GatewayStatus } from '../components/GatewayStatus'
import { t } from '../i18n'

interface AppInfo { versionName: string; versionCode: number; packageName: string }

// ── Icon map con las exactas de la imagen ─────────────────────────────────────
const CMD_ROWS = [
  { icon: '▶', bg: '#1b3a5c', col: '#60a5fa', title: 'Gateway',   cmd: 'openclaw gateway',      sub: 'Iniciar el gateway' },
  { icon: '●', bg: '#14532d', col: '#4ade80', title: 'Status',    cmd: 'openclaw status',       sub: 'Mostrar estado del gateway' },
  { icon: '★', bg: '#3b2c00', col: '#fbbf24', title: 'Onboard',   cmd: 'openclaw onboard',      sub: 'Asistente de configuración inicial' },
  { icon: '≡', bg: '#1e1a3e', col: '#a78bfa', title: 'Logs',      cmd: 'openclaw logs --follow', sub: 'Seguir logs en vivo' },
] as const

const MGMT_ROWS = [
  { icon: '↑', bg: '#14532d', col: '#4ade80', title: 'Update',    cmd: 'openclaw update',       sub: 'Actualizar OpenClaw y componentes' },
  { icon: '🔧', bg:'#2d2200', col: '#fb923c', title: 'Configure', cmd: 'openclaw configure',    sub: 'Configurar el entorno' },
  { icon: '🩺', bg:'#2d1a00', col: '#fb923c', title: 'Doctor',    cmd: 'openclaw doctor',       sub: 'Diagnóstico del sistema' },
  { icon: '⚡', bg: '#2d2800', col: '#facc15', title: 'Skills',   cmd: 'openclaw skills',       sub: 'Gestionar skills instalados' },
] as const

export function Dashboard() {
  const { navigate } = useRoute()
  const [nodeVer, setNodeVer] = useState<string | null>(null)
  const [ocVer,   setOcVer]   = useState<string | null>(null)
  const [gitVer,  setGitVer]  = useState<string | null>(null)

  // Cargar versiones via bridge (primera vez)
  useState(() => {
    if (!bridge.isAvailable()) return
    const vNode = bridge.callJson<{ stdout?: string }>('runCommand', 'node -v')
    const vOC   = bridge.callJson<{ stdout?: string }>('runCommand', 'openclaw --version')
    const vGit  = bridge.callJson<{ stdout?: string }>('runCommand', 'git --version')
    setNodeVer(vNode?.stdout?.trim().split('\n')[0] ?? null)
    setOcVer(vOC?.stdout?.trim().split('\n')[0] ?? null)
    setGitVer(vGit?.stdout?.trim().replace('git version ', '') ?? null)
  })

  const appInfo = bridge.isAvailable()
    ? bridge.callJson<AppInfo>('getAppInfo')
    : null

  const runInTerminal = useCallback((cmd: string) => {
    navigate('/terminal')
    setTimeout(() => window.dispatchEvent(new CustomEvent('terminal:run', { detail: cmd })), 300)
  }, [navigate])

  const envTools = [
    { icon: '⬡',  label: 'Node.js',   value: nodeVer,  color: '#6366f1', installed: !!nodeVer },
    { icon: '⎇',  label: 'git',       value: gitVer,   color: '#22d3ee', installed: !!gitVer },
    { icon: '🦀', label: 'openclaw',  value: ocVer,    color: '#f97316', installed: !!ocVer  },
  ]

  return (
    <div style={S.page}>

      {/* ── 1. Gateway status card ── */}
      <GatewayStatus />

      {/* ── 2. ENTORNO DE EJECUCIÓN ── */}
      <div style={S.sectionLabel}>{t('dash_section_env')}</div>
      <div style={S.envCard}>
        {envTools.map((tool, i) => (
          <div key={tool.label} style={{ ...S.envTool, borderRight: i < envTools.length - 1 ? '1px solid var(--border)' : 'none' }}>
            <div style={{ ...S.envIcon, background: `${tool.color}15`, border: `1px solid ${tool.color}25` }}>
              <span style={{ fontSize: 18 }}>{tool.icon}</span>
            </div>
            <span style={S.envLabel}>{tool.label}</span>
            <span style={{ ...S.envValue, color: tool.installed ? tool.color : 'var(--text4)' }}>
              {tool.value ?? 'no incluido'}
            </span>
            <div style={{ ...S.envDot, background: tool.installed ? '#4ade80' : '#6b7280', boxShadow: tool.installed ? '0 0 5px #4ade80' : 'none' }} />
          </div>
        ))}
      </div>

      {/* ── 3. COMANDOS ── */}
      <div style={S.sectionLabel}>{t('dash_section_cmds')}</div>
      <div style={S.cmdCard}>
        {CMD_ROWS.map((r, i) => (
          <CmdRow key={r.cmd}
            icon={r.icon} bg={r.bg} col={r.col}
            title={r.title} sub={r.sub}
            last={i === CMD_ROWS.length - 1}
            onClick={() => {
              if (r.cmd === 'openclaw gateway') bridge.call('startGateway')
              else runInTerminal(r.cmd)
            }} />
        ))}
      </div>

      {/* ── 4. GESTIÓN ── */}
      <div style={S.sectionLabel}>GESTIÓN</div>
      <div style={S.cmdCard}>
        {MGMT_ROWS.map((r, i) => (
          <CmdRow key={r.cmd}
            icon={r.icon} bg={r.bg} col={r.col}
            title={r.title} sub={r.sub}
            last={i === MGMT_ROWS.length - 1}
            onClick={() => runInTerminal(r.cmd)} />
        ))}
      </div>

      {/* ── Footer ── */}
      {appInfo && (
        <div style={S.footer}>
          {appInfo.packageName} · v{appInfo.versionName} · build {appInfo.versionCode}
        </div>
      )}
    </div>
  )
}

// ── CmdRow ────────────────────────────────────────────────────────────────────

function CmdRow({ icon, bg, col, title, sub, onClick, last }: {
  icon: string
  bg: string
  col: string
  title: string
  sub: string
  onClick: () => void
  last?: boolean
}) {
  return (
    <button
      style={{ ...S.cmdRow, borderBottom: last ? 'none' : '1px solid var(--border)' }}
      onClick={onClick}
      onTouchStart={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.04)' }}
      onTouchEnd={e   => { e.currentTarget.style.background = 'transparent' }}>
      {/* Colored icon tile — exactly like the image */}
      <div style={{ width: 38, height: 38, borderRadius: 11, background: bg, color: col, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 16, fontWeight: 700, flexShrink: 0 }}>
        {icon}
      </div>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 2, textAlign: 'left' }}>
        <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text)' }}>{title}</span>
        <span style={{ fontSize: 11, color: 'var(--text3)' }}>{sub}</span>
      </div>
      <span style={{ color: 'var(--text4)', fontSize: 20, lineHeight: 1 }}>›</span>
    </button>
  )
}

// ── Styles ────────────────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  page: {
    padding: '12px 14px 32px',
    maxWidth: 600,
    margin: '0 auto',
    overflowY: 'auto',
  },

  sectionLabel: {
    fontSize: 11,
    fontWeight: 700,
    letterSpacing: '0.08em',
    color: 'var(--text3)',
    marginBottom: 10,
    marginTop: 22,
    paddingLeft: 2,
    textTransform: 'uppercase',
  },

  // Env card — 3 columns like the screenshot
  envCard: {
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--r-xl)',
    display: 'flex',
    overflow: 'hidden',
    boxShadow: 'var(--sh-inset)',
  },
  envTool: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: 4,
    padding: '14px 8px 12px',
  },
  envIcon: {
    width: 40, height: 40, borderRadius: 12,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    marginBottom: 2,
  },
  envLabel: {
    fontSize: 11,
    color: 'var(--text2)',
    fontWeight: 600,
  },
  envValue: {
    fontSize: 11,
    fontFamily: "'JetBrains Mono', monospace",
    fontWeight: 600,
    textAlign: 'center',
    wordBreak: 'break-all',
    lineHeight: 1.3,
  },
  envDot: {
    width: 7, height: 7, borderRadius: '50%',
    marginTop: 2, transition: 'all 0.4s',
  },

  // Command rows card
  cmdCard: {
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--r-xl)',
    overflow: 'hidden',
    marginBottom: 4,
    boxShadow: 'var(--sh-inset)',
  },
  cmdRow: {
    width: '100%',
    display: 'flex',
    alignItems: 'center',
    gap: 14,
    padding: '13px 16px',
    background: 'transparent',
    border: 'none',
    cursor: 'pointer',
    transition: 'background 0.12s',
  },

  footer: {
    textAlign: 'center',
    color: 'var(--text4)',
    fontSize: 11,
    marginTop: 20,
    paddingBottom: 8,
  },
}
