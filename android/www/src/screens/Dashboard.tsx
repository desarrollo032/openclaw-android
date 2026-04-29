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
interface EnvComponent { version?: string; detected: boolean; path?: string }
interface EnvInfo {
  node?: EnvComponent
  git?: EnvComponent
  openclaw?: EnvComponent
  prefix?: string
  home?: string
}
interface InstalledTool { id: string; name: string; version?: string }

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
  const [envInfo, setEnvInfo] = useState<EnvInfo>({})
  const [installedTools, setInstalledTools] = useState<InstalledTool[]>([])
  // loading=true until bridge responds at least once
  const [loading, setLoading] = useState(true)
  const [activeSessionId, setActiveSessionId] = useState<string>('')
  const [refreshing, setRefreshing] = useState(false)
  // Track if bridge was ever available (prevents flicker on slow WebView init)
  const bridgeReadyRef = useRef(false)
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const refreshStatus = useCallback((showSpinner = false) => {
    if (!bridge.isAvailable()) {
      // Bridge not ready yet — retry in 300ms (up to ~3s total)
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

    const env = bridge.callJson<EnvInfo>('getEnvironmentInfo')
    if (env) setEnvInfo(env)

    const tools = bridge.callJson<InstalledTool[]>('getInstalledTools')
    if (tools) setInstalledTools(tools)

    setLoading(false)
    if (showSpinner) setTimeout(() => setRefreshing(false), 600)
    return true
  }, [])

  // Poll until bridge is available, then load once
  useEffect(() => {
    let attempts = 0
    const MAX_ATTEMPTS = 20 // 20 × 200ms = 4s max wait

    const tryLoad = () => {
      attempts++
      const ok = refreshStatus()
      if (!ok && attempts < MAX_ATTEMPTS) {
        retryTimerRef.current = setTimeout(tryLoad, 200)
      } else if (!ok) {
        // Bridge never became available — stop loading, show error state
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

  // Re-check status when setup completes
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

  // ── Loading state ──────────────────────────────────────────────────────────
  if (loading) {
    return (
      <div className="page">
        <div className="empty-state" style={{ minHeight: 'calc(100dvh - 80px)' }}>
          <div className="spinner" style={{ width: 36, height: 36, borderWidth: 3 }} />
        </div>
      </div>
    )
  }

  // ── Bridge unavailable (running in browser/dev mode) ──────────────────────
  if (!bridge.isAvailable() && !bridgeReadyRef.current) {
    return (
      <div className="page">
        <div className="empty-state" style={{ minHeight: 'calc(100dvh - 80px)' }}>
          <div style={{ fontSize: 32, marginBottom: 8 }}>🔌</div>
          <div style={{ fontSize: 16, fontWeight: 600 }}>Bridge not available</div>
          <div className="empty-state-text">Running outside Android WebView</div>
        </div>
      </div>
    )
  }

  // ── Not installed ──────────────────────────────────────────────────────────
  const isInstalled = bootstrapStatus?.installed && bootstrapStatus?.openclawInstalled

  if (!isInstalled) {
    return (
      <div className="page">
        <div className="empty-state" style={{ minHeight: 'calc(100dvh - 80px)' }}>
          <img src="./openclaw.svg" alt="OpenClaw"
            style={{ width: 72, height: 72, opacity: 0.4, marginBottom: 8 }} />
          <div style={{ fontSize: 18, fontWeight: 700 }}>{t('dash_setup_required')}</div>
          <div className="empty-state-text">{t('dash_setup_desc')}</div>
          {/* Debug info — remove in production */}
          {bootstrapStatus && (
            <div style={{
              marginTop: 16, fontSize: 11, color: 'var(--text-muted)',
              fontFamily: 'monospace', textAlign: 'left', padding: '8px 16px',
              background: 'var(--bg-tertiary)', borderRadius: 8, maxWidth: 320,
            }}>
              <div>installed: {String(bootstrapStatus.installed)}</div>
              <div>openclaw: {String(bootstrapStatus.openclawInstalled)}</div>
              <div>source: {bootstrapStatus.source ?? 'none'}</div>
              {bootstrapStatus.prefixPath && (
                <div style={{ wordBreak: 'break-all' }}>
                  prefix: {bootstrapStatus.prefixPath}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    )
  }

  // ── Main dashboard ─────────────────────────────────────────────────────────
  return (
    <div className="page">
      {/* Platform header */}
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

      {/* Runtime environment */}
      <div className="section-title">{t('dash_runtime')}</div>
      <div className="card dash-env-grid">
        {([
          { key: 'node' as keyof EnvInfo, label: 'Node.js', icon: '⬡' },
          { key: 'git' as keyof EnvInfo, label: 'git', icon: '⎇' },
          { key: 'openclaw' as keyof EnvInfo, label: 'openclaw', icon: '🦀' },
        ]).map(({ key, label, icon }) => {
          const comp = envInfo[key] as EnvComponent | undefined
          const detected = comp?.detected ?? false
          const version = comp?.version
          return (
            <div className="dash-env-item" key={key}>
              <div className="dash-env-icon">{icon}</div>
              <div className="dash-env-label">{label}</div>
              <div className={`dash-env-version${detected ? '' : ' not-found'}`}>
                {detected
                  ? (version || '✓ installed')
                  : t('env_not_detected')}
              </div>
              <div className={`dash-env-dot${detected ? ' ok' : ' err'}`} />
            </div>
          )
        })}
      </div>

      {/* Installed tools badges */}
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

      {/* Commands */}
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

      {/* Management */}
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

      {/* Quick actions */}
      <div className="section-title">Quick Actions</div>
      <div className="dash-quick-grid">
        <QuickAction icon="📋" label="Sessions" onClick={() => {
          const sessions = bridge.callJson<SessionInfo[]>('getTerminalSessions')
          if (sessions && sessions.length > 0) {
            bridge.call('showTerminal')
          } else {
            bridge.call('createSession')
            bridge.call('showTerminal')
          }
        }} />
        <QuickAction icon="📋" label="New Session" onClick={() => {
          bridge.call('createSession')
          bridge.call('showTerminal')
        }} />
        <QuickAction icon="🔄" label="Reload UI" onClick={() => window.location.reload()} />
        <QuickAction icon="⚙" label="Settings" onClick={() => {
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
