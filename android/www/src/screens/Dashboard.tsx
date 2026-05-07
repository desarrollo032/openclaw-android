import { useState, useEffect, useCallback } from 'react'
import { api } from '../lib/api'
import { bridge } from '../lib/bridge'
import { useRoute } from '../lib/router'
import { fetchGatewayConfig, getProviderMeta } from '../lib/gateway'

/* ── Types ─────────────────────────────────────────────── */
interface Health { status: string; uptime?: string; version?: string }
interface Skill { id: string; name: string; active: boolean; description?: string }
interface Config {
  default_model?: string
  context_size?: number
  temperature?: number
  openai_key?: string
  anthropic_key?: string
  notifications?: boolean
}
interface StorageInfo {
  totalBytes: number; freeBytes: number
  bootstrapBytes: number; wwwBytes: number
}
interface AppInfo { versionName: string; versionCode: number; packageName: string }

/* ── Helpers ───────────────────────────────────────────── */
function fmtBytes(b: number) {
  if (!b || b < 0) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB']
  const i = Math.min(Math.floor(Math.log(b) / Math.log(1024)), 3)
  return (b / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + u[i]
}

function fmtUptime(s?: string) {
  if (!s) return '—'
  const n = parseFloat(s)
  if (isNaN(n)) return s
  const h = Math.floor(n / 3600), m = Math.floor((n % 3600) / 60)
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}

/* ── Component ─────────────────────────────────────────── */
export function Dashboard() {
  const { navigate } = useRoute()

  const [health, setHealth] = useState<Health | null>(null)
  const [config, setConfig] = useState<Config | null>(null)
  const [skills, setSkills] = useState<Skill[]>([])
  const [storage, setStorage] = useState<StorageInfo | null>(null)
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null)
  const [versions, setVersions] = useState({ node: '…', npm: '…', openclaw: '…', glibc: '…' })
  const [battery, setBattery] = useState<{ isIgnoring: boolean } | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    else setRefreshing(true)
    try {
      // Gateway health
      const h = await api.getHealth().catch(() => ({ status: 'offline' }))
      setHealth(h)

      // Config — try gateway WS first, fallback to HTTP
      let cfg = null
      try {
        const gwCfg = await fetchGatewayConfig()
        if (gwCfg?.agents?.defaults) {
          cfg = {
            default_model: gwCfg.agents.defaults.model?.primary,
            temperature: gwCfg.agents.defaults.temperature,
            context_size: (gwCfg.agents.defaults as Record<string, unknown>)['contextTokens'] as number,
          }
        }
      } catch { /* ignore */ }
      if (!cfg) cfg = await api.getConfig().catch(() => null)
      if (cfg) setConfig(cfg)
      // Skills
      const sk = await api.getSkills().catch(() => [])
      setSkills(sk || [])

      // Bridge data (sync)
      const stor = bridge.callJson<StorageInfo>('getStorageInfo')
      if (stor) setStorage(stor)

      const info = bridge.callJson<AppInfo>('getAppInfo')
      if (info) setAppInfo(info)

      const bat = bridge.callJson<{ isIgnoring: boolean }>('getBatteryOptimizationStatus')
      if (bat) setBattery(bat)

      const vNode = bridge.callJson<{ stdout: string }>('runCommand', 'node -v')
      const vNpm = bridge.callJson<{ stdout: string }>('runCommand', 'npm -v')
      const vOC = bridge.callJson<{ stdout: string }>('runCommand', 'openclaw --version')
      const vGl = bridge.callJson<{ stdout: string }>('runCommand', 'ldd --version')
      setVersions({
        node: vNode?.stdout?.trim() || '—',
        npm: vNpm?.stdout?.trim() || '—',
        openclaw: vOC?.stdout?.trim() || '—',
        glibc: vGl?.stdout?.trim() || '—',
      })
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const online = health?.status === 'ok' || health?.status === 'online'
  const usedBytes = storage ? storage.totalBytes - storage.freeBytes : 0
  const usedPct = storage ? Math.round((usedBytes / storage.totalBytes) * 100) : 0
  const activeSkills = skills.filter(s => s.active)

  if (loading) return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '60vh', flexDirection: 'column', gap: 16 }}>
      <div style={{ width: 40, height: 40, border: '3px solid var(--oc-surface3)', borderTopColor: 'var(--oc-purple)', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />
      <span style={{ color: 'var(--oc-text2)', fontSize: 14 }}>Cargando configuración...</span>
    </div>
  )

  return (
    <div className="page" style={{ paddingBottom: 24 }}>

      {/* ── Hero: Gateway status ── */}
      <div style={{
        background: online
          ? 'linear-gradient(135deg, rgba(74,222,128,0.08) 0%, rgba(34,211,238,0.06) 100%)'
          : 'linear-gradient(135deg, rgba(248,113,113,0.08) 0%, rgba(251,146,60,0.06) 100%)',
        border: `1px solid ${online ? 'rgba(74,222,128,0.2)' : 'rgba(248,113,113,0.2)'}`,
        borderRadius: 'var(--r-2xl)',
        padding: '18px 20px',
        marginBottom: 16,
        display: 'flex',
        alignItems: 'center',
        gap: 14,
      }}>
        <div style={{
          width: 48, height: 48,
          borderRadius: 'var(--r-lg)',
          background: online ? 'rgba(74,222,128,0.15)' : 'rgba(248,113,113,0.15)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 24, flexShrink: 0,
        }}>
          {online ? '🟢' : '🔴'}
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontWeight: 700, fontSize: 16, color: 'var(--oc-text)' }}>
            Gateway {online ? 'Activo' : 'Desconectado'}
          </div>
          <div style={{ fontSize: 12, color: 'var(--oc-text2)', marginTop: 2 }}>
            {online
              ? `Uptime: ${fmtUptime(health?.uptime)} · v${health?.version || appInfo?.versionName || '—'}`
              : 'El gateway no responde. Verifica la notificación.'}
          </div>
        </div>
        <button
          onClick={() => load(true)}
          style={{
            width: 36, height: 36,
            borderRadius: 'var(--r-md)',
            border: '1px solid var(--oc-border)',
            background: 'var(--oc-surface2)',
            color: 'var(--oc-text2)',
            fontSize: 16,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer',
            animation: refreshing ? 'spin 0.8s linear infinite' : 'none',
          }}
        >↻</button>
      </div>

      {/* ── Quick stats grid ── */}
      <div className="stat-grid">
        <div className="stat-tile">
          <span className="stat-icon">
            {config?.default_model
              ? getProviderMeta(config.default_model.split('/')[0]).icon
              : '🤖'}
          </span>
          <span className="stat-value" style={{ fontSize: 13, fontWeight: 700 }}>
            {config?.default_model
              ? (config.default_model.split('/').slice(1).join('/') || config.default_model)
              : '—'}
          </span>
          <span className="stat-label">
            {config?.default_model
              ? getProviderMeta(config.default_model.split('/')[0]).label
              : 'Modelo activo'}
          </span>
        </div>
        <div className="stat-tile">
          <span className="stat-icon">⚡</span>
          <span className="stat-value">{activeSkills.length}</span>
          <span className="stat-label">Skills activas</span>
        </div>
        <div className="stat-tile">
          <span className="stat-icon">💾</span>
          <span className="stat-value">{fmtBytes(usedBytes)}</span>
          <span className="stat-label">Almacenamiento</span>
        </div>
        <div className="stat-tile">
          <span className="stat-icon">🌡️</span>
          <span className="stat-value">{config?.temperature ?? '—'}</span>
          <span className="stat-label">Temperatura IA</span>
        </div>
      </div>

      {/* ── OpenClaw Config ── */}
      <div className="section-header">
        <span className="section-title">Configuración OpenClaw</span>
        <button className="btn btn-sm btn-ghost" onClick={() => navigate('/settings')}>Editar</button>
      </div>

      <div className="card" style={{ marginBottom: 12 }}>
        <ConfigRow icon="🤖" label="Modelo" value={config?.default_model || '—'} accent />
        <ConfigRow icon="📏" label="Contexto" value={config?.context_size ? `${config.context_size.toLocaleString()} tokens` : '—'} />
        <ConfigRow icon="🌡️" label="Temperatura" value={config?.temperature !== undefined ? String(config.temperature) : '—'} />
        <ConfigRow
          icon="🔑"
          label="OpenAI Key"
          value={config?.openai_key ? `sk-...${config.openai_key.slice(-4)}` : 'No configurada'}
          warn={!config?.openai_key}
        />
        <ConfigRow
          icon="🔑"
          label="Anthropic Key"
          value={config?.anthropic_key ? `sk-ant-...${config.anthropic_key.slice(-4)}` : 'No configurada'}
          warn={!config?.anthropic_key}
        />
        <ConfigRow
          icon="🔔"
          label="Notificaciones"
          value={config?.notifications ? 'Activadas' : 'Desactivadas'}
        />
      </div>

      {/* ── System versions ── */}
      <div className="section-header">
        <span className="section-title">Entorno del Sistema</span>
      </div>

      <div className="card" style={{ marginBottom: 12 }}>
        <ConfigRow icon="🟩" label="Node.js" value={versions.node} accent={versions.node !== '—'} />
        <ConfigRow icon="📦" label="NPM" value={versions.npm} />
        <ConfigRow icon="🦀" label="OpenClaw" value={versions.openclaw} accent={versions.openclaw !== '—'} />
        <ConfigRow icon="⚙️" label="GLIBC" value={versions.glibc} />
        <ConfigRow icon="📱" label="APK" value={appInfo?.versionName || '—'} />
        <ConfigRow icon="📦" label="Package" value={appInfo?.packageName || '—'} mono />
      </div>

      {/* ── Storage ── */}
      <div className="section-header">
        <span className="section-title">Almacenamiento</span>
        <span style={{ fontSize: 12, color: 'var(--oc-text2)' }}>{usedPct}% usado</span>
      </div>

      <div className="card" style={{ marginBottom: 12 }}>
        {storage ? (
          <>
            <div style={{ marginBottom: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6, fontSize: 13 }}>
                <span style={{ color: 'var(--oc-text2)' }}>Usado</span>
                <span style={{ fontWeight: 600 }}>{fmtBytes(usedBytes)} / {fmtBytes(storage.totalBytes)}</span>
              </div>
              <div className="progress-track">
                <div className="progress-fill" style={{ width: `${usedPct}%` }} />
              </div>
            </div>

            <StorageBar label="Payload" bytes={storage.bootstrapBytes} color="var(--oc-purple)" total={usedBytes} />
            <StorageBar label="Web App" bytes={storage.wwwBytes} color="var(--oc-cyan)" total={usedBytes} />
            <StorageBar label="Libre" bytes={storage.freeBytes} color="var(--oc-green)" total={storage.totalBytes} />
          </>
        ) : (
          <div style={{ color: 'var(--oc-text2)', fontSize: 13, textAlign: 'center', padding: '8px 0' }}>
            No disponible
          </div>
        )}
      </div>

      {/* ── Battery optimization ── */}
      {battery !== null && (
        <>
          <div className="section-header">
            <span className="section-title">Optimización de Batería</span>
          </div>
          <div className="card" style={{ marginBottom: 12 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <span style={{ fontSize: 28 }}>{battery.isIgnoring ? '✅' : '⚠️'}</span>
              <div style={{ flex: 1 }}>
                <div style={{ fontWeight: 600, fontSize: 14 }}>
                  {battery.isIgnoring ? 'Exclusión activa' : 'Sin exclusión'}
                </div>
                <div style={{ fontSize: 12, color: 'var(--oc-text2)', marginTop: 2 }}>
                  {battery.isIgnoring
                    ? 'El gateway puede correr en segundo plano sin restricciones.'
                    : 'Android puede matar el gateway. Se recomienda excluir la app.'}
                </div>
              </div>
              {!battery.isIgnoring && (
                <button
                  className="btn btn-sm btn-ghost"
                  onClick={() => bridge.call('requestBatteryOptimizationExclusion')}
                >
                  Excluir
                </button>
              )}
            </div>
          </div>
        </>
      )}

      {/* ── Active skills ── */}
      <div className="section-header">
        <span className="section-title">Skills Activas</span>
        <button className="btn btn-sm btn-ghost" onClick={() => navigate('/skills')}>Ver todas</button>
      </div>

      <div className="card" style={{ marginBottom: 12 }}>
        {activeSkills.length === 0 ? (
          <div style={{ color: 'var(--oc-text2)', fontSize: 13, textAlign: 'center', padding: '8px 0' }}>
            Ninguna skill activa
          </div>
        ) : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {activeSkills.map(s => (
              <span key={s.id} className="badge badge-purple">{s.name}</span>
            ))}
          </div>
        )}
      </div>

      {/* ── Quick actions ── */}
      <div className="section-header">
        <span className="section-title">Acciones Rápidas</span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 12 }}>
        <QuickAction icon="💬" label="Nuevo Chat" onClick={() => navigate('/chat')} />
        <QuickAction icon="💻" label="Terminal" onClick={() => navigate('/terminal')} />
        <QuickAction icon="⚙️" label="Configuración" onClick={() => navigate('/settings')} />
        <QuickAction icon="📝" label="Ver Logs" onClick={() => navigate('/logs')} />
      </div>

    </div>
  )
}

/* ── Sub-components ─────────────────────────────────────── */

function ConfigRow({
  icon, label, value, accent, warn, mono
}: {
  icon: string; label: string; value: string
  accent?: boolean; warn?: boolean; mono?: boolean
}) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10,
      padding: '10px 0',
      borderBottom: '1px solid var(--oc-border)',
    }}
      className="config-row"
    >
      <span style={{ fontSize: 16, width: 22, textAlign: 'center', flexShrink: 0 }}>{icon}</span>
      <span style={{ flex: 1, fontSize: 13, color: 'var(--oc-text2)' }}>{label}</span>
      <span style={{
        fontSize: mono ? 11 : 13,
        fontWeight: 600,
        color: warn ? 'var(--oc-yellow)' : accent ? 'var(--oc-purple)' : 'var(--oc-text)',
        fontFamily: mono ? 'monospace' : undefined,
        maxWidth: '55%',
        textAlign: 'right',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap',
      }}>
        {value}
      </span>
    </div>
  )
}

function StorageBar({ label, bytes, color, total }: { label: string; bytes: number; color: string; total: number }) {
  const pct = total > 0 ? Math.min(100, Math.round((bytes / total) * 100)) : 0
  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 4 }}>
        <span style={{ color: 'var(--oc-text2)' }}>{label}</span>
        <span style={{ color: 'var(--oc-text)', fontWeight: 600 }}>{fmtBytes(bytes)} ({pct}%)</span>
      </div>
      <div className="storage-bar">
        <div className="storage-fill" style={{ width: `${pct}%`, background: color }} />
      </div>
    </div>
  )
}

function QuickAction({ icon, label, onClick }: { icon: string; label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      style={{
        background: 'var(--oc-surface)',
        border: '1px solid var(--oc-border)',
        borderRadius: 'var(--r-xl)',
        padding: '14px 12px',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 6,
        cursor: 'pointer',
        transition: 'all 0.15s',
        color: 'var(--oc-text)',
      }}
      onTouchStart={e => (e.currentTarget.style.background = 'var(--oc-surface2)')}
      onTouchEnd={e => (e.currentTarget.style.background = 'var(--oc-surface)')}
    >
      <span style={{ fontSize: 26 }}>{icon}</span>
      <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--oc-text2)' }}>{label}</span>
    </button>
  )
}
