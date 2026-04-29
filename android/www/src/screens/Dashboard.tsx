import { useState, useEffect, useCallback } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface BootstrapStatus { installed: boolean; prefixPath?: string }
interface PlatformInfo { id: string; name: string }
interface SessionInfo { id: string; active: boolean }
interface EnvComponent { version?: string; detected: boolean; path?: string }
interface EnvInfo {
  node?: EnvComponent
  git?: EnvComponent
  openclaw?: EnvComponent
  prefix?: string
  home?: string
}

function getCommands() {
  return [
    { icon: '▶', label: 'Gateway', cmd: 'openclaw gateway', desc: t('cmd_gateway'), color: 'var(--accent)' },
    { icon: '◉', label: 'Status', cmd: 'openclaw status', desc: t('cmd_status'), color: 'var(--success)' },
    { icon: '✦', label: 'Onboard', cmd: 'openclaw onboard', desc: t('cmd_onboard'), color: 'var(--warning)' },
    { icon: '≡', label: 'Logs', cmd: 'openclaw logs --follow', desc: t('cmd_logs'), color: 'var(--text-secondary)' },
  ]
}

function getManagement() {
  return [
    { icon: '↑', label: 'Update', cmd: 'oa --update', desc: t('cmd_update') },
    { icon: '+', label: 'Install Tools', cmd: 'oa --install', desc: t('cmd_install_tools') },
  ]
}

export function Dashboard() {
  const [status, setStatus] = useState<BootstrapStatus | null>(null)
  const [platform, setPlatform] = useState<PlatformInfo | null>(null)
  const [envInfo, setEnvInfo] = useState<EnvInfo>({})
  const [loading, setLoading] = useState(true)
  const [activeSessionId, setActiveSessionId] = useState<string>('')

  function refreshStatus() {
    const bs = bridge.callJson<BootstrapStatus>('getBootstrapStatus')
    if (bs) setStatus(bs)

    const ap = bridge.callJson<PlatformInfo>('getActivePlatform')
    if (ap) setPlatform(ap)

    // Obtener sesión activa para writeToTerminal
    const sessions = bridge.callJson<SessionInfo[]>('getTerminalSessions')
    if (sessions) {
      const active = sessions.find(s => s.active)
      if (active) setActiveSessionId(active.id)
    }

    const env = bridge.callJson<EnvInfo>('getEnvironmentInfo')
    if (env) setEnvInfo(env)
    setLoading(false)
  }

  useEffect(() => { refreshStatus() }, [])

  // Actualizar sesión activa cuando cambia
  const onSessionChanged = useCallback((data: unknown) => {
    const d = data as { id?: string; action?: string }
    if (d.action === 'created' || d.action === 'switched') {
      if (d.id) setActiveSessionId(d.id)
    }
  }, [])
  useNativeEvent('session_changed', onSessionChanged)

  function runInTerminal(cmd: string) {
    bridge.call('showTerminal')
    // Pequeño delay para que el terminal esté visible antes de escribir
    setTimeout(() => {
      bridge.call('writeToTerminal', activeSessionId, cmd + '\n')
    }, 150)
  }

  if (loading) {
    return (
      <div className="page">
        <div className="empty-state" style={{ minHeight: 'calc(100dvh - 80px)' }}>
          <div className="spinner" style={{ width: 32, height: 32, borderWidth: 3 }} />
        </div>
      </div>
    )
  }

  if (!status?.installed) {
    return (
      <div className="page">
        <div className="empty-state" style={{ minHeight: 'calc(100dvh - 80px)' }}>
          <img src="./openclaw.svg" alt="OpenClaw"
            style={{ width: 72, height: 72, opacity: 0.5, marginBottom: 8 }} />
          <div style={{ fontSize: 18, fontWeight: 700 }}>{t('dash_setup_required')}</div>
          <div className="empty-state-text">{t('dash_setup_desc')}</div>
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      {/* Cabecera de plataforma */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 24 }}>
        <div style={{
          width: 48, height: 48, borderRadius: 12,
          background: 'var(--accent-dim)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          flexShrink: 0,
        }}>
          <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 30, height: 30 }} />
        </div>
        <div>
          <div style={{ fontSize: 20, fontWeight: 800, letterSpacing: '-0.3px' }}>
            {platform?.name || 'OpenClaw'}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 3 }}>
            <span className="status-dot success" />
            <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
              {status.prefixPath || t('platforms_active')}
            </span>
          </div>
        </div>
        {/* Botón refrescar */}
        <button
          onClick={refreshStatus}
          style={{
            marginLeft: 'auto', background: 'var(--bg-tertiary)',
            border: '1px solid var(--border)', borderRadius: 8,
            width: 36, height: 36, cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 16, color: 'var(--text-secondary)',
          }}
          title="Refrescar"
        >
          ↻
        </button>
      </div>

      {/* Comandos */}
      <div className="section-title">{t('dash_commands')}</div>
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {getCommands().map((item, i) => (
          <CommandRow
            key={item.cmd}
            icon={item.icon}
            label={item.label}
            cmd={item.cmd}
            color={item.color}
            borderTop={i > 0}
            onClick={() => runInTerminal(item.cmd)}
          />
        ))}
      </div>

      {/* Entorno de ejecución */}
      <div className="section-title">{t('dash_runtime')}</div>
      <div className="card">
        {([
          { key: 'node', label: 'Node.js' },
          { key: 'git', label: 'git' },
          { key: 'openclaw', label: 'openclaw' },
        ] as { key: keyof EnvInfo; label: string }[]).map(({ key, label }) => {
          const comp = envInfo[key] as EnvComponent | undefined
          const detected = comp?.detected ?? false
          const version = comp?.version || '—'
          return (
            <div className="info-row" key={key}>
              <span className="label" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span
                  style={{
                    width: 7, height: 7, borderRadius: '50%', flexShrink: 0,
                    background: detected ? 'var(--success)' : 'var(--error)',
                    display: 'inline-block',
                  }}
                />
                {label}
              </span>
              <span style={{ fontFamily: 'monospace', fontSize: 13, color: detected ? 'var(--text-primary)' : 'var(--text-muted)' }}>
                {detected ? version : t('env_not_detected')}
              </span>
            </div>
          )
        })}
        {envInfo.prefix && (
          <div className="info-row" style={{ marginTop: 4, borderTop: '1px solid var(--border-subtle)', paddingTop: 8 }}>
            <span className="label" style={{ fontSize: 11, color: 'var(--text-muted)' }}>prefix</span>
            <span style={{ fontFamily: 'monospace', fontSize: 11, color: 'var(--text-muted)', wordBreak: 'break-all' }}>
              {envInfo.prefix}
            </span>
          </div>
        )}
      </div>

      {/* Gestión */}
      <div className="section-title">{t('dash_management')}</div>
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {getManagement().map((item, i) => (
          <CommandRow
            key={item.cmd}
            icon={item.icon}
            label={item.label}
            cmd={item.cmd}
            color="var(--success)"
            borderTop={i > 0}
            onClick={() => runInTerminal(item.cmd)}
          />
        ))}
      </div>
    </div>
  )
}

// Componente de fila de comando con feedback táctil correcto
function CommandRow({
  icon, label, cmd, color, borderTop, onClick,
}: {
  icon: string; label: string; cmd: string; color: string;
  borderTop: boolean; onClick: () => void;
}) {
  const [pressed, setPressed] = useState(false)

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onClick}
      onTouchStart={() => setPressed(true)}
      onTouchEnd={(e) => { e.preventDefault(); setPressed(false); onClick() }}
      onTouchCancel={() => setPressed(false)}
      onMouseDown={() => setPressed(true)}
      onMouseUp={() => setPressed(false)}
      onMouseLeave={() => setPressed(false)}
      style={{
        display: 'flex', alignItems: 'center', gap: 14,
        padding: '13px 16px',
        borderTop: borderTop ? '1px solid var(--border-subtle)' : 'none',
        cursor: 'pointer',
        background: pressed ? 'var(--bg-tertiary)' : 'transparent',
        transition: 'background 0.1s',
        userSelect: 'none',
        WebkitUserSelect: 'none',
      }}
    >
      <div style={{
        width: 34, height: 34, borderRadius: 8,
        background: 'var(--bg-tertiary)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 15, color, fontWeight: 700, flexShrink: 0,
      }}>
        {icon}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, fontWeight: 600 }}>{label}</div>
        <div style={{
          fontSize: 11, color: 'var(--text-secondary)',
          fontFamily: 'monospace', marginTop: 2,
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          {cmd}
        </div>
      </div>
      <span style={{ color: 'var(--text-muted)', fontSize: 18, flexShrink: 0 }}>›</span>
    </div>
  )
}
