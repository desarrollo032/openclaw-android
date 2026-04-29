import { useState, useCallback, useEffect, Fragment } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Props {
  onComplete: () => void
}

type SetupPhase = 'welcome' | 'tool-select' | 'installing' | 'done'

function getOptionalTools() {
  return [
    { id: 'tmux', name: 'tmux', desc: t('tool_tmux'), icon: '🖥' },
    { id: 'code-server', name: 'code-server', desc: t('tool_code_server'), icon: '💻' },
    { id: 'claude-code', name: 'Claude Code', desc: t('tool_claude_code'), icon: '🤖' },
    { id: 'gemini-cli', name: 'Gemini CLI', desc: t('tool_gemini_cli'), icon: '✨' },
    { id: 'codex-cli', name: 'Codex CLI', desc: t('tool_codex_cli'), icon: '🧠' },
    { id: 'ttyd', name: 'ttyd', desc: t('tool_ttyd'), icon: '🌐' },
    { id: 'dufs', name: 'dufs', desc: t('tool_dufs'), icon: '📁' },
  ]
}

function getTips() {
  return [t('tip_1'), t('tip_2'), t('tip_3'), t('tip_4')]
}

export function Setup({ onComplete }: Props) {
  const [phase, setPhase] = useState<SetupPhase>('welcome')
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set())
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [tipIndex, setTipIndex] = useState(0)

  // Rotate tips during install
  useEffect(() => {
    if (phase !== 'installing') return
    const id = setInterval(() => setTipIndex(i => (i + 1) % getTips().length), 4000)
    return () => clearInterval(id)
  }, [phase])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string; error?: string }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.message) setMessage(d.message)
    if (d.error) setError(d.error)
    if (d.progress !== undefined && d.progress >= 1 && !d.error) {
      setPhase('done')
    }
  }, [])

  useNativeEvent('setup_progress', onProgress)

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
    bridge.call('saveInstallPath', 'local')

    setPhase('installing')
    setProgress(0)
    setMessage(t('setup_preparing'))
    setError('')

    // startSetup() uses BootstrapManager — same as MainActivity checks
    bridge.call('startSetup')
  }

  // Stepper
  const steps = [t('step_platform'), t('step_tools'), t('step_setup')]
  const currentStep = phase === 'welcome' ? 0 : phase === 'tool-select' ? 1 : 2

  function renderStepper() {
    return (
      <div className="stepper">
        {steps.map((label, i) => (
          <Fragment key={label}>
            {i > 0 && <div className={`step-line${i <= currentStep ? ' done' : ''}`} />}
            <div className={`step${i < currentStep ? ' done' : i === currentStep ? ' active' : ''}`}>
              <span className="step-icon">{i < currentStep ? '✓' : i === currentStep ? '●' : '○'}</span>
              <span className="step-label-text">{label}</span>
            </div>
          </Fragment>
        ))}
      </div>
    )
  }

  // ── Welcome ──────────────────────────────────────────────────────────────
  if (phase === 'welcome') {
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-logo">
          <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 80, height: 80 }} />
        </div>
        <div className="setup-title">OpenClaw</div>
        <div className="setup-subtitle">{t('setup_choose_platform')}</div>

        <div className="setup-feature-list">
          <div className="setup-feature-item">
            <span className="setup-feature-icon">⚡</span>
            <span>{t('tip_3')}</span>
          </div>
          <div className="setup-feature-item">
            <span className="setup-feature-icon">🔒</span>
            <span>{t('tip_4')}</span>
          </div>
          <div className="setup-feature-item">
            <span className="setup-feature-icon">🔄</span>
            <span>{t('tip_2')}</span>
          </div>
        </div>

        <button className="btn btn-primary btn-full" onClick={() => setPhase('tool-select')}>
          {t('setup_next')} →
        </button>
      </div>
    )
  }

  // ── Tool Select ──────────────────────────────────────────────────────────
  if (phase === 'tool-select') {
    return (
      <div className="setup-container setup-container--scroll">
        {renderStepper()}
        <div className="setup-title" style={{ fontSize: 22 }}>{t('setup_optional_tools')}</div>
        <div className="setup-subtitle">
          {t('setup_tools_desc', { platform: 'OpenClaw' })}
        </div>

        <div className="setup-tools-grid">
          {getOptionalTools().map(tool => {
            const isSelected = selectedTools.has(tool.id)
            return (
              <div
                key={tool.id}
                className={`setup-tool-card${isSelected ? ' selected' : ''}`}
                onClick={() => toggleTool(tool.id)}
                role="checkbox"
                aria-checked={isSelected}
                tabIndex={0}
                onKeyDown={e => e.key === 'Enter' && toggleTool(tool.id)}
              >
                <div className="setup-tool-icon">{tool.icon}</div>
                <div className="setup-tool-name">{tool.name}</div>
                <div className="setup-tool-desc">{tool.desc}</div>
                <div className={`setup-tool-check${isSelected ? ' on' : ''}`}>
                  {isSelected ? '✓' : ''}
                </div>
              </div>
            )
          })}
        </div>

        <div className="setup-actions">
          <button className="btn btn-ghost btn-sm" onClick={() => setPhase('welcome')}>
            ← {t('step_platform')}
          </button>
          <button className="btn btn-primary" onClick={handleStartSetup}>
            {t('setup_start')}
          </button>
        </div>
      </div>
    )
  }

  // ── Installing ───────────────────────────────────────────────────────────
  if (phase === 'installing') {
    const pct = Math.round(progress * 100)
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-title">{t('setup_setting_up')}</div>

        <div className="setup-progress-wrap">
          {/* Circular progress ring */}
          <div className="setup-progress-ring">
            <svg viewBox="0 0 80 80" width="80" height="80">
              <circle cx="40" cy="40" r="34" fill="none"
                stroke="var(--bg-tertiary)" strokeWidth="6" />
              <circle
                cx="40" cy="40" r="34" fill="none"
                stroke="var(--accent)" strokeWidth="6"
                strokeLinecap="round"
                strokeDasharray={`${2 * Math.PI * 34}`}
                strokeDashoffset={`${2 * Math.PI * 34 * (1 - progress)}`}
                transform="rotate(-90 40 40)"
                style={{ transition: 'stroke-dashoffset 0.4s ease' }}
              />
            </svg>
            <div className="setup-progress-pct">{pct}%</div>
          </div>

          <div className="setup-progress-msg">{message}</div>

          {error && (
            <div className="setup-error">
              <span>⚠ {error}</span>
              <button className="btn btn-sm btn-ghost" onClick={() => {
                setError('')
                setPhase('tool-select')
              }}>
                Retry
              </button>
            </div>
          )}
        </div>

        <div className="tip-card">💡 {getTips()[tipIndex]}</div>
      </div>
    )
  }

  // ── Done ─────────────────────────────────────────────────────────────────
  return (
    <div className="setup-container">
      {renderStepper()}
      <div className="setup-logo" style={{ fontSize: 72 }}>✅</div>
      <div className="setup-title">{t('setup_done_title')}</div>
      <div className="setup-subtitle">{t('setup_done_desc')}</div>

      <button className="btn btn-primary btn-full" onClick={() => {
        bridge.call('showTerminal')
        onComplete()
      }}>
        {t('setup_open_terminal')}
      </button>
    </div>
  )
}
