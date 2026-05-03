import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Tool { id: string; name: string; desc: string; category: string }

// Las descripciones se generan dentro del componente para respetar el locale
function getTools(): Tool[] {
  return [
    { id: 'tmux', name: 'tmux', desc: t('tool_tmux'), category: 'terminal' },
    { id: 'code-server', name: 'code-server', desc: t('tool_code_server'), category: 'terminal' },
    { id: 'claude-code', name: 'Claude Code', desc: t('tool_claude_code'), category: 'ai' },
    { id: 'gemini-cli', name: 'Gemini CLI', desc: t('tool_gemini_cli'), category: 'ai' },
    { id: 'codex-cli', name: 'Codex CLI', desc: t('tool_codex_cli'), category: 'ai' },
    { id: 'openssh-server', name: 'SSH Server', desc: t('tool_ssh_server'), category: 'network' },
    { id: 'ttyd', name: 'ttyd', desc: t('tool_ttyd'), category: 'network' },
    { id: 'dufs', name: 'dufs', desc: t('tool_dufs'), category: 'network' },
    { id: 'android-tools', name: 'Android Tools', desc: 'ADB — Phantom Process Killer', category: 'system' },
    { id: 'chromium', name: 'Chromium', desc: 'Browser automation (~400MB)', category: 'system' },
  ]
}

function getCatLabel(cat: string): string {
  const map: Record<string, string> = {
    terminal: `🖥 ${t('tools_cat_terminal')}`,
    ai: `🤖 ${t('tools_cat_ai')}`,
    network: `🌐 ${t('tools_cat_network')}`,
    system: `⚙ ${t('tools_cat_system')}`,
  }
  return map[cat] || cat
}

export function SettingsTools() {
  const { navigate } = useRoute()
  const [installed, setInstalled] = useState<Set<string>>(new Set())
  const [installing, setInstalling] = useState<string | null>(null)
  const [progress, setProgress] = useState(0)
  const [progressMsg, setProgressMsg] = useState('')

  useEffect(() => {
    const result = bridge.callJson<Array<{ id: string }>>('getInstalledTools')
    if (result) setInstalled(new Set(result.map(r => r.id)))
  }, [])

  const onInstallProgress = useCallback((data: unknown) => {
    const d = data as { target?: string; progress?: number; message?: string }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.message) setProgressMsg(d.message)
    if (d.progress !== undefined && d.progress >= 1) {
      if (d.target) setInstalled(prev => new Set([...prev, d.target!]))
      setInstalling(null)
      setProgress(0)
      setProgressMsg('')
    }
  }, [])
  useNativeEvent('install_progress', onInstallProgress)

  function handleInstall(id: string) {
    setInstalling(id)
    setProgress(0)
    setProgressMsg(t('tools_installing', { name: id }))
    bridge.call('installTool', id)
  }

  function handleUninstall(id: string) {
    bridge.call('uninstallTool', id)
    setInstalled(prev => { const n = new Set(prev); n.delete(id); return n })
  }

  const tools = getTools()
  const categories = [...new Set(tools.map(tool => tool.category))]

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('tools_title')}</div>
      </div>

      {/* Progreso de instalación */}
      {installing && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
            <div className="spinner" />
            <div style={{ fontSize: 14, fontWeight: 600 }}>
              {t('tools_installing', { name: installing })}
            </div>
          </div>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
          {progressMsg && (
            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 6 }}>
              {progressMsg}
            </div>
          )}
        </div>
      )}

      {/* Herramientas agrupadas por categoría */}
      {categories.map(cat => (
        <div key={cat}>
          <div className="section-title">{getCatLabel(cat)}</div>
          {tools.filter(tool => tool.category === cat).map(tool => {
            const isInstalled = installed.has(tool.id)
            const isInstalling = installing === tool.id
            return (
              <div key={tool.id} className="card">
                <div className="card-row">
                  <div className="card-content">
                    <div className="card-label" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      {tool.name}
                      {isInstalled && (
                        <span className="pill pill-success" style={{ fontSize: 10 }}>✓</span>
                      )}
                    </div>
                    <div className="card-desc">{tool.desc}</div>
                  </div>
                  {isInstalled ? (
                    <button
                      className="btn btn-secondary btn-sm"
                      onClick={() => handleUninstall(tool.id)}
                      disabled={installing !== null}
                    >
                      {t('tools_installed')}
                    </button>
                  ) : (
                    <button
                      className="btn btn-primary btn-sm"
                      onClick={() => handleInstall(tool.id)}
                      disabled={installing !== null}
                    >
                      {isInstalling ? '...' : t('tools_install')}
                    </button>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
}
