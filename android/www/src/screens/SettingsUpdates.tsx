import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface UpdateItem { component: string; currentVersion: string; newVersion: string }

export function SettingsUpdates() {
  const { navigate } = useRoute()
  const [updates, setUpdates] = useState<UpdateItem[]>([])
  const [updating, setUpdating] = useState<string | null>(null)
  const [progress, setProgress] = useState(0)
  const [checking, setChecking] = useState(true)

  useEffect(() => {
    const data = bridge.callJson<UpdateItem[]>('checkForUpdates')
    setUpdates(data || [])
    setChecking(false)
  }, [])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { target?: string; progress?: number }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.progress !== undefined && d.progress >= 1) {
      setUpdating(null)
      setUpdates(prev => prev.filter(u => u.component !== d.target))
    }
  }, [])
  useNativeEvent('install_progress', onProgress)

  function handleApply(component: string) {
    setUpdating(component)
    setProgress(0)
    bridge.call('applyUpdate', component)
  }

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('updates_title')}</div>
      </div>

      {/* Progreso de actualización */}
      {updating && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
            <div className="spinner" />
            <div style={{ fontSize: 14, fontWeight: 600 }}>
              {t('updates_updating', { name: updating })}
            </div>
          </div>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
        </div>
      )}

      {checking && (
        <div className="empty-state">
          <div className="spinner" />
          <div className="empty-state-text">{t('updates_checking')}</div>
        </div>
      )}

      {!checking && updates.length === 0 && (
        <div className="empty-state">
          <div className="empty-state-icon">✓</div>
          <div className="empty-state-text">{t('updates_up_to_date')}</div>
        </div>
      )}

      {updates.map(u => (
        <div key={u.component} className="card">
          <div className="card-row">
            <div className="card-content">
              <div className="card-label">{u.component}</div>
              <div className="card-desc">
                <span style={{ fontFamily: 'monospace' }}>{u.currentVersion}</span>
                {' → '}
                <span style={{ fontFamily: 'monospace', color: 'var(--success)' }}>{u.newVersion}</span>
              </div>
            </div>
            <button
              className="btn btn-primary btn-sm"
              onClick={() => handleApply(u.component)}
              disabled={updating !== null}
            >
              {updating === u.component ? '...' : t('updates_update')}
            </button>
          </div>
        </div>
      ))}
    </div>
  )
}
