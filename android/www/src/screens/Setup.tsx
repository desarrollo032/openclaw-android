import { useState, useCallback, useEffect, Fragment } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Props {
  onComplete: () => void
}

type SetupPhase = 'platform-select' | 'path-select' | 'tool-select' | 'installing' | 'done'

interface Platform {
  id: string
  name: string
  icon: string
  desc: string
}

function getOptionalTools() {
  return [
    { id: 'tmux', name: 'tmux', desc: t('tool_tmux') },
    { id: 'ttyd', name: 'ttyd', desc: t('tool_ttyd') },
    { id: 'dufs', name: 'dufs', desc: t('tool_dufs') },
    { id: 'code-server', name: 'code-server', desc: t('tool_code_server') },
    { id: 'claude-code', name: 'Claude Code', desc: t('tool_claude_code') },
    { id: 'gemini-cli', name: 'Gemini CLI', desc: t('tool_gemini_cli') },
    { id: 'codex-cli', name: 'Codex CLI', desc: t('tool_codex_cli') },
  ]
}

function getTips() {
  return [
    t('tip_1'),
    t('tip_2'),
    t('tip_3'),
    t('tip_4'),
  ]
}

export function Setup({ onComplete }: Props) {
  const [phase, setPhase] = useState<SetupPhase>('platform-select')
  const [platforms, setPlatforms] = useState<Platform[]>([])
  const [selectedPlatform, setSelectedPlatform] = useState('')
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set())
  const [installPath, setInstallPath] = useState<'local' | 'termux'>('local')
  const [appFilesInfo, setAppFilesInfo] = useState<{ filesDir?: string; prefix?: string } | null>(null)
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [tipIndex, setTipIndex] = useState(0)

  // Load available platforms
  useEffect(() => {
    const data = bridge.callJson<Platform[]>('getAvailablePlatforms')
    if (data) {
      setPlatforms(data)
    } else {
      setPlatforms([
        { id: 'openclaw', name: 'OpenClaw', icon: '/openclaw.svg', desc: 'AI agent platform' },
      ])
    }
    // Load app files info once
    const filesInfo = bridge.callJson<{ filesDir?: string; prefix?: string }>('getAppFilesDir')
    setAppFilesInfo(filesInfo)
  }, [])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.message) setMessage(d.message)
    if (d.progress !== undefined && d.progress >= 1) {
      setPhase('done')
    }
    setTipIndex(i => (i + 1) % getTips().length)
  }, [])

  useNativeEvent('setup_progress', onProgress)

  function handleSelectPlatform(id: string) {
    setSelectedPlatform(id)
    setPhase('path-select')
  }

  function toggleTool(id: string) {
    setSelectedTools(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function handleStartSetup() {
    // Save install path preference
    bridge.call('saveInstallPath', installPath)

    // Save tool selections
    const selections: Record<string, boolean> = {}
    getOptionalTools().forEach(tool => {
      selections[tool.id] = selectedTools.has(tool.id)
    })
    bridge.call('saveToolSelections', JSON.stringify(selections))

    // Start bootstrap setup
    setPhase('installing')
    setProgress(0)
    setMessage(t('setup_preparing'))
    setError('')
    bridge.call('startSetup')
  }

  // --- Stepper ---
  const currentStep = phase === 'platform-select' ? 0
    : phase === 'path-select' ? 1
      : phase === 'tool-select' ? 2
        : phase === 'installing' ? 3 : 4

  const STEPS = [t('step_platform'), t('step_path'), t('step_tools'), t('step_setup')]

  function renderStepper() {
    return (
      <div className="stepper">
        {STEPS.map((label, i) => (
          <Fragment key={label}>
            {i > 0 && <div className={`step-line${i <= currentStep ? ' done' : ''}`} />}
            <div className={`step${i < currentStep ? ' done' : i === currentStep ? ' active' : ''}`}>
              <span className="step-icon">{i < currentStep ? '✓' : i === currentStep ? '●' : '○'}</span>
              <span>{label}</span>
            </div>
          </Fragment>
        ))}
      </div>
    )
  }

  // --- Platform Select ---
  if (phase === 'platform-select') {
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-title">{t('setup_choose_platform')}</div>

        {platforms.map(p => (
          <div
            key={p.id}
            className="platform-card"
            onClick={() => handleSelectPlatform(p.id)}
          >
            <div style={{ fontSize: 36, marginBottom: 10 }}>
              {p.icon.startsWith('/') ? (
                <img src={p.icon.replace(/^\//, './')} alt={p.name} style={{ width: 44, height: 44 }} />
              ) : p.icon}
            </div>
            <div style={{ fontSize: 17, fontWeight: 700 }}>{p.name}</div>
            <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4, lineHeight: 1.5 }}>
              {p.desc}
            </div>
          </div>
        ))}

        <div className="setup-subtitle">{t('setup_more_platforms')}</div>
      </div>
    )
  }

  // --- Path Select ---
  if (phase === 'path-select') {
    const localPath = appFilesInfo?.prefix || t('setup_path_local_default')
    const termuxPath = '/data/data/com.termux/files/usr'

    return (
      <div className="setup-container" style={{ justifyContent: 'flex-start', paddingTop: 48 }}>
        {renderStepper()}
        <div className="setup-title" style={{ fontSize: 22 }}>{t('setup_path_title')}</div>
        <div className="setup-subtitle">{t('setup_path_desc')}</div>

        <div style={{ width: '100%', maxWidth: 360 }}>
          {([
            {
              id: 'local' as const,
              label: t('setup_path_local_label'),
              desc: localPath,
              badge: t('setup_path_recommended'),
            },
            {
              id: 'termux' as const,
              label: t('setup_path_termux_label'),
              desc: termuxPath,
              badge: null,
            },
          ]).map(opt => {
            const selected = installPath === opt.id
            return (
              <div
                key={opt.id}
                className="card"
                style={{
                  cursor: 'pointer', marginBottom: 10,
                  border: selected ? '2px solid var(--accent)' : '2px solid transparent',
                }}
                onClick={() => setInstallPath(opt.id)}
              >
                <div className="card-row">
                  <div className="card-content">
                    <div className="card-label" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      {opt.label}
                      {opt.badge && (
                        <span className="pill pill-accent" style={{ fontSize: 10 }}>{opt.badge}</span>
                      )}
                    </div>
                    <div className="card-desc" style={{ fontFamily: 'monospace', fontSize: 11, wordBreak: 'break-all' }}>
                      {opt.desc}
                    </div>
                  </div>
                  <div style={{
                    width: 20, height: 20, borderRadius: '50%', flexShrink: 0,
                    border: selected ? '6px solid var(--accent)' : '2px solid var(--border)',
                    background: 'var(--bg-secondary)',
                  }} />
                </div>
              </div>
            )
          })}
        </div>

        <button className="btn btn-primary" onClick={() => setPhase('tool-select')} style={{ marginTop: 8 }}>
          {t('setup_next')}
        </button>
      </div>
    )
  }

  // --- Tool Select ---
  if (phase === 'tool-select') {
    return (
      <div className="setup-container" style={{ justifyContent: 'flex-start', paddingTop: 48 }}>
        {renderStepper()}

        <div className="setup-title" style={{ fontSize: 22 }}>{t('setup_optional_tools')}</div>
        <div className="setup-subtitle">
          {t('setup_tools_desc', { platform: selectedPlatform })}
        </div>

        <div style={{ width: '100%', maxWidth: 360 }}>
          {getOptionalTools().map(tool => {
            const isSelected = selectedTools.has(tool.id)
            return (
              <div
                key={tool.id}
                className="card"
                style={{ cursor: 'pointer', marginBottom: 8 }}
                onClick={() => toggleTool(tool.id)}
              >
                <div className="card-row">
                  <div className="card-content">
                    <div className="card-label">{tool.name}</div>
                    <div className="card-desc">{tool.desc}</div>
                  </div>
                  {/* Evitar doble disparo: el click ya lo maneja el card padre */}
                  <div
                    className={`toggle${isSelected ? ' on' : ''}`}
                    onClick={e => e.stopPropagation()}
                  >
                    <div className="toggle-thumb" />
                  </div>
                </div>
              </div>
            )
          })}
        </div>

        <button className="btn btn-primary" onClick={handleStartSetup} style={{ marginTop: 8 }}>
          {t('setup_start')}
        </button>
      </div>
    )
  }

  // --- Installing ---
  if (phase === 'installing') {
    const pct = Math.round(progress * 100)
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-title">{t('setup_setting_up')}</div>

        <div style={{ width: '100%', maxWidth: 320 }}>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${pct}%` }} />
          </div>
          <div style={{ textAlign: 'center', fontSize: 13, color: 'var(--text-secondary)', marginTop: 8 }}>
            {pct}%
          </div>
          <div style={{ textAlign: 'center', fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
            {message}
          </div>
        </div>

        {error && (
          <div style={{ color: 'var(--error)', fontSize: 14, textAlign: 'center' }}>{error}</div>
        )}

        <div className="tip-card">💡 {getTips()[tipIndex]}</div>
      </div>
    )
  }

  // --- Done ---
  return (
    <div className="setup-container">
      {renderStepper()}
      <div className="setup-logo">✅</div>
      <div className="setup-title">{t('setup_done_title')}</div>
      <div className="setup-subtitle">
        {t('setup_done_desc')}
      </div>

      <button className="btn btn-primary" onClick={() => {
        bridge.call('showTerminal')
        onComplete()
      }}>
        {t('setup_open_terminal')}
      </button>
    </div>
  )
}
