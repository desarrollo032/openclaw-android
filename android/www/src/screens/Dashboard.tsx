import { useState, useEffect, useCallback, useRef } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface BootstrapStatus {
  installed: boolean
  openclawInstalled: boolean
  prefixPath?: string
  source?: string
}
interface PlatformInfo { id: string; name: string }
interface SessionInfo { id: string; active: boolean }
interface InstalledTool { id: string; name: string; version?: string }

function RuntimeItem({ icon, label, status, active, onClick }: { 
  icon: string, 
  label: string, 
  status: string, 
  active: boolean,
  onClick?: () => void 
}) {
  return (
    <div 
      className={`dash-env-item ${active ? 'active' : 'not-found'} ${onClick ? 'clickable' : ''}`}
      onClick={onClick}
    >
      <div className="dash-env-icon">{icon}</div>
      <div className="dash-env-label">{label}</div>
      <div className="dash-env-status">{status}</div>
      <div className={`dash-env-dot ${active ? 'ok' : 'err'}`} />
    </div>
  )
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
  const [bootstrapStatus, setBootstrapStatus] = useState<BootstrapStatus | null>(null)
  const [platform, setPlatform] = useState<PlatformInfo | null>(null)
  const [installedTools, setInstalledTools] = useState<InstalledTool[]>([])
  const [loading, setLoading] = useState(true)
  const [activeSessionId, setActiveSessionId] = useState<string>('')
  const [refreshing, setRefreshing] = useState(false)
  const bridgeReadyRef = useRef(false)
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const refreshStatus = useCallback((showSpinner = false) => {
    if (!bridge.isAvailable()) {
      return false
    }

    bridgeReadyRef.current = true
    if (showSpinner) setRefreshing(true)

    const bs = bridge.callJson<BootstrapStatus>('getBootstrapStatus')
    if (bs) setBootstrapStatus(bs)

    const ap = bridge.callJson<PlatformInfo>('getActivePlatform')
    if (ap) setPlatform(ap)

    const sessions = bridge.callJson<SessionInfo[]>('getTerminalSessions')
    if (sessions) {
      const active = sessions.find(s => s.active)
      if (active) setActiveSessionId(active.id)
    }

    const tools = bridge.callJson<InstalledTool[]>('getInstalledTools')
    if (tools) setInstalledTools(tools)

    setLoading(false)
    if (showSpinner) setTimeout(() => setRefreshing(false), 600)
    return true
  }, [])

  useEffect(() => {
    let attempts = 0
    const MAX_ATTEMPTS = 20

    const tryLoad = () => {
      attempts++
      const ok = refreshStatus()
      if (!ok && attempts < MAX_ATTEMPTS) {
        retryTimerRef.current = setTimeout(tryLoad, 200)
      } else if (!ok) {
        setLoading(false)
      }
    }

    tryLoad()
    return () => {
      if (retryTimerRef.current) clearTimeout(retryTimerRef.current)
    }
  }, [refreshStatus])

  const onSessionChanged = useCallback((data: unknown) => {
    const d = data as { id?: string; action?: string }
    if ((d.action === 'created' || d.action === 'switched') && d.id) {
      setActiveSessionId(d.id)
    }
  }, [])
  useNativeEvent('session_changed', onSessionChanged)

  const onSetupProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number }
    if (d.progress === 1) {
      setTimeout(() => refreshStatus(), 500)
    }
  }, [refreshStatus])
  useNativeEvent('setup_progress', onSetupProgress)

  function runInTerminal(cmd: string) {
    bridge.call('showTerminal')
    setTimeout(() => {
      bridge.call('writeToTerminal', activeSessionId, cmd + '\n')
    }, 150)
  }

  if (loading) {
    return (
      <div className="page">
        <div className="empty-state" style={{ minHeight: 'calc(100dvh - 80px)' }}>
          <div className="spinner" style={{ width: 36, height: 36, borderWidth: 3 }} />
        </div>
      </div>
    )
  }

  if (!bridge.isAvailable() && !bridgeReadyRef.current) {
    return (
      <div className="page">
        <div className="empty-state" style={{ minHeight: 'calc(100dvh - 80px)' }}>
          <div style={{ fontSize: 32, marginBottom: 8 }}>🔌</div>
          <div style={{ fontSize: 16, fontWeight: 600 }}>{t('about_bridge_unavailable')}</div>
          <div className="empty-state-text">{t('about_running_outside')}</div>
        </div>
      </div>
    )
  }

  const isInstalled = bootstrapStatus?.installed && bootstrapStatus?.openclawInstalled

  return (
    <div className="page">
      {!isInstalled && (
        <div className="card" style={{ 
          background: 'var(--bg-tertiary)', 
          border: '1px solid var(--warning)',
          marginBottom: 16,
          display: 'flex',
          flexDirection: 'column',
          gap: 12
        }}>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <span style={{ fontSize: 24 }}>⚠️</span>
            <div>
              <div style={{ fontWeight: 700, color: 'var(--warning)' }}>{t('dash_setup_required')}</div>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('dash_setup_desc')}</div>
            </div>
          </div>
          <button 
            className="btn btn-primary" 
            style={{ 
              width: '100%', 
              padding: '12px', 
              fontSize: '15px', 
              fontWeight: 600,
              boxShadow: '0 4px 12px rgba(var(--primary-rgb), 0.3)'
            }}
            onClick={() => window.location.hash = '/setup'}
          >
            🚀 {t('setup_start')}
          </button>
        </div>
      )}

      {/* Floating Action Button for Setup */}
      {!isInstalled && (
        <div 
          onClick={() => window.location.hash = '/setup'}
          style={{
            position: 'fixed',
            bottom: 100,
            right: 24,
            width: 64,
            height: 64,
            borderRadius: '50%',
            background: 'var(--primary)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 28,
            boxShadow: '0 8px 24px rgba(0,0,0,0.4)',
            zIndex: 1000,
            cursor: 'pointer',
            border: '2px solid rgba(255,255,255,0.2)',
            animation: 'pulse 2s infinite'
          }}
        >
          ⚙️
        </div>
      )}

      <div className="dash-header">
        <div className="dash-platform-icon">
          <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 28, height: 28 }} />
        </div>
        <div className="dash-platform-info">
          <div className="dash-platform-name">{platform?.name || 'OpenClaw'}</div>
          <div className="dash-platform-status">
            <span className="status-dot success" />
            <span>{t('platforms_active')}</span>
          </div>
        </div>
        <button
          className={`dash-refresh-btn${refreshing ? ' spinning' : ''}`}
          onClick={() => refreshStatus(true)}
          aria-label="Refresh"
        >
          ↻
        </button>
      </div>

      <div className="section-title">{t('dash_runtime')}</div>
      <div className="runtime-grid">
        <RuntimeItem
          icon="⬢"
          label="Node.js"
          status={bootstrapStatus?.installed ? 'detectado' : t('env_not_detected')}
          active={!!bootstrapStatus?.installed}
          onClick={() => !bootstrapStatus?.installed && (window.location.hash = '/setup')}
        />
        <RuntimeItem
          icon="⎇"
          label="git"
          status={bootstrapStatus?.installed ? 'detectado' : t('env_not_detected')}
          active={!!bootstrapStatus?.installed}
          onClick={() => !bootstrapStatus?.installed && (window.location.hash = '/setup')}
        />
        <RuntimeItem
          icon="🦀"
          label="openclaw"
          status={bootstrapStatus?.openclawInstalled ? 'detectado' : t('env_not_detected')}
          active={!!bootstrapStatus?.openclawInstalled}
          onClick={() => !bootstrapStatus?.openclawInstalled && (window.location.hash = '/setup')}
        />
      </div>

      {installedTools.length > 0 && (
        <>
          <div className="section-title">{t('tools_title')}</div>
          <div className="card">
            <div className="dash-tools-row">
              {installedTools.map(tool => (
                <span key={tool.id} className="pill pill-success dash-tool-pill">
                  ✓ {tool.name}
                </span>
              ))}
            </div>
          </div>
        </>
      )}

      <div className="section-title">{t('dash_commands')}</div>
      <div className="card" style={{ padding: 0, overflow: 'hidden', opacity: isInstalled ? 1 : 0.5 }}>
        {getCommands().map((item, i) => (
          <CommandRow
            key={item.cmd}
            icon={item.icon}
            label={item.label}
            cmd={item.cmd}
            color={item.color}
            borderTop={i > 0}
            onClick={() => isInstalled ? runInTerminal(item.cmd) : alert(t('dash_setup_required'))}
          />
        ))}
      </div>

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

      <div className="section-title">{t('dash_quick_actions')}</div>
      <div className="dash-quick-grid">
        <QuickAction icon="📋" label={t('dash_sessions')} onClick={() => {
          const sessions = bridge.callJson<SessionInfo[]>('getTerminalSessions')
          if (sessions && sessions.length > 0) {
            bridge.call('showTerminal')
          } else {
            bridge.call('createSession')
            bridge.call('showTerminal')
          }
        }} />
        <QuickAction icon="📋" label={t('dash_new_session')} onClick={() => {
          bridge.call('createSession')
          bridge.call('showTerminal')
        }} />
        <QuickAction icon="🔄" label={t('dash_reload_ui')} onClick={() => window.location.reload()} />
        <QuickAction icon="⚙" label={t('settings_title')} onClick={() => {
          bridge.call('showWebView')
          window.location.hash = '/settings'
        }} />
      </div>
    </div>
  )
}

function QuickAction({ icon, label, onClick }: { icon: string; label: string; onClick: () => void }) {
  const [pressed, setPressed] = useState(false)
  return (
    <button
      className={`dash-quick-btn${pressed ? ' pressed' : ''}`}
      onClick={onClick}
      onTouchStart={() => setPressed(true)}
      onTouchEnd={() => setPressed(false)}
      onMouseDown={() => setPressed(true)}
      onMouseUp={() => setPressed(false)}
      onMouseLeave={() => setPressed(false)}
    >
      <span className="dash-quick-icon">{icon}</span>
      <span className="dash-quick-label">{label}</span>
    </button>
  )
}

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
      onKeyDown={e => e.key === 'Enter' && onClick()}
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
        width: 36, height: 36, borderRadius: 9,
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
