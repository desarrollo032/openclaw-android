import { useState, useCallback, useEffect, Fragment } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Props {
  onComplete: () => void
}

type SetupPhase = 'platform-select' | 'tool-select' | 'installing' | 'done'

interface Platform {
  id: string
  name: string
  icon: string
  desc: string
}

function getOptionalTools() {
  return [
    { id: 'tmux', name: 'tmux', desc: t('tool_tmux'), icon: '🪟' },
    { id: 'ttyd', name: 'ttyd', desc: t('tool_ttyd'), icon: '🌐' },
    { id: 'dufs', name: 'dufs', desc: t('tool_dufs'), icon: '📁' },
    { id: 'code-server', name: 'code-server', desc: t('tool_code_server'), icon: '💻' },
    { id: 'claude-code', name: 'Claude Code', desc: t('tool_claude_code'), icon: '🤖' },
    { id: 'gemini-cli', name: 'Gemini CLI', desc: t('tool_gemini_cli'), icon: '✨' },
    { id: 'codex-cli', name: 'Codex CLI', desc: t('tool_codex_cli'), icon: '🧠' },
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
        { id: 'openclaw', name: 'OpenClaw', icon: '🦀', desc: 'AI agent platform' },
      ])
    }
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
    setPhase('tool-select')
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
    : phase === 'tool-select' ? 1
    : phase === 'installing' ? 2 : 3

  const STEPS = [t('step_platform'), t('step_tools'), t('step_setup')]

  function renderStepper() {
    return (
      <div className="stepper" style={{ marginBottom: 32 }}>
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
        <div className="setup-subtitle" style={{ marginBottom: 32 }}>
          {t('setup_more_platforms')}
        </div>

        <div className="settings-list" style={{ width: '100%', maxWidth: 360 }}>
          {platforms.map(p => (
            <div
              key={p.id}
              className="settings-item"
              onClick={() => handleSelectPlatform(p.id)}
            >
              <div className="card-icon">
                {p.icon.startsWith('/') ? (
                  <img src={p.icon.replace(/^\//, './')} alt={p.name} style={{ width: 32, height: 32 }} />
                ) : p.icon}
              </div>
              <div className="card-content">
                <div className="card-label">{p.name}</div>
                <div className="card-desc">{p.desc}</div>
              </div>
              <div className="card-chevron">›</div>
            </div>
          ))}
        </div>
      </div>
    )
  }

  // --- Tool Select ---
  if (phase === 'tool-select') {
    return (
      <div className="setup-container" style={{ justifyContent: 'flex-start', paddingTop: 64 }}>
        {renderStepper()}

        <div className="setup-title">{t('setup_optional_tools')}</div>
        <div className="setup-subtitle">
          {t('setup_tools_desc', { platform: selectedPlatform })}
        </div>

        <div className="settings-list" style={{ width: '100%', maxWidth: 360, marginBottom: 24 }}>
          {getOptionalTools().map(tool => {
            const isSelected = selectedTools.has(tool.id)
            return (
              <div
                key={tool.id}
                className="settings-item"
                onClick={() => toggleTool(tool.id)}
              >
                <div className="card-icon">{tool.icon}</div>
                <div className="card-content">
                  <div className="card-label">{tool.name}</div>
                  <div className="card-desc">{tool.desc}</div>
                </div>
                <div
                  style={{
                    width: 44, height: 24, borderRadius: 12,
                    backgroundColor: isSelected ? 'var(--accent)' : 'var(--bg-tertiary)',
                    position: 'relative', flexShrink: 0,
                    transition: 'background-color 0.2s',
                  }}
                >
                  <div style={{
                    width: 20, height: 20, borderRadius: 10,
                    backgroundColor: '#fff', position: 'absolute', top: 2,
                    left: isSelected ? 22 : 2,
                    transition: 'left 0.2s',
                    boxShadow: '0 1px 3px rgba(0,0,0,0.3)',
                  }} />
                </div>
              </div>
            )
          })}
        </div>

        <button className="btn btn-primary" onClick={handleStartSetup}>
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

        <div style={{ width: '100%', maxWidth: 320, margin: '24px 0' }}>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${pct}%` }} />
          </div>
          <div style={{ textAlign: 'center', fontSize: 18, fontWeight: 700, marginTop: 12 }}>
            {pct}%
          </div>
          <div style={{ textAlign: 'center', fontSize: 13, color: 'var(--text-secondary)', marginTop: 4 }}>
            {message}
          </div>
        </div>

        {error && (
          <div style={{ color: 'var(--error)', fontSize: 14, textAlign: 'center', marginBottom: 16 }}>{error}</div>
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
      <div className="setup-subtitle" style={{ marginBottom: 32 }}>
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
