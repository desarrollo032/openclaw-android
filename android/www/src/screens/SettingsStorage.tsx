import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

interface StorageInfo {
  totalBytes: number
  freeBytes: number
  bootstrapBytes: number
  wwwBytes: number
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`
}

export function SettingsStorage() {
  const { navigate } = useRoute()
  const [info, setInfo] = useState<StorageInfo | null>(null)
  const [source, setSource] = useState<string>('bootstrap')
  const [clearing, setClearing] = useState(false)

  function loadInfo() {
    const data = bridge.callJson<StorageInfo>('getStorageInfo')
    if (data) setInfo(data)
    
    const bs = bridge.callJson<{source?: string}>('getBootstrapStatus')
    if (bs?.source) setSource(bs.source)
  }

  useEffect(() => { loadInfo() }, [])

  function handleClearCache() {
    setClearing(true)
    bridge.call('clearCache')
    setTimeout(() => {
      setClearing(false)
      loadInfo()
    }, 2000)
  }

  const totalUsed = info ? info.bootstrapBytes + info.wwwBytes : 0
  const totalDisk = info ? info.totalBytes : 1

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('storage_title')}</div>
      </div>

      {!info && (
        <div className="empty-state">
          <div className="spinner" />
          <div className="empty-state-text">{t('storage_loading')}</div>
        </div>
      )}

      {info && (
        <>
          {/* Resumen total */}
          <div className="card" style={{ marginBottom: 16 }}>
            <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 8 }}>
              {t('storage_total')}<strong style={{ color: 'var(--text-primary)' }}>{formatBytes(totalUsed)}</strong>
            </div>
            {/* Barra combinada */}
            <div style={{ height: 8, background: 'var(--bg-tertiary)', borderRadius: 4, overflow: 'hidden', display: 'flex' }}>
              <div style={{
                width: `${(info.bootstrapBytes / totalDisk) * 100}%`,
                background: '#58a6ff', transition: 'width 0.4s',
              }} />
              <div style={{
                width: `${(info.wwwBytes / totalDisk) * 100}%`,
                background: '#3fb950', transition: 'width 0.4s',
              }} />
            </div>
            {/* Leyenda */}
            <div style={{ display: 'flex', gap: 16, marginTop: 10, flexWrap: 'wrap' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
                <div style={{ width: 10, height: 10, borderRadius: 2, background: '#58a6ff' }} />
                <span style={{ color: 'var(--text-secondary)' }}>{source === 'payload' ? 'Payload' : 'Bootstrap'}</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
                <div style={{ width: 10, height: 10, borderRadius: 2, background: '#3fb950' }} />
                <span style={{ color: 'var(--text-secondary)' }}>Web UI</span>
              </div>
            </div>
          </div>

          {/* Bootstrap / Payload */}
          <div className="card">
            <div className="card-row" style={{ cursor: 'default' }}>
              <div className="card-content">
                <div className="card-label">{source === 'payload' ? 'Payload (usr/)' : t('storage_bootstrap')}</div>
                <div className="card-desc">{formatBytes(info.bootstrapBytes)}</div>
              </div>
              <span style={{ fontSize: 13, color: 'var(--text-secondary)', fontFamily: 'monospace' }}>
                {totalDisk > 0 ? `${((info.bootstrapBytes / totalDisk) * 100).toFixed(1)}%` : '—'}
              </span>
            </div>
            <div className="storage-bar">
              <div className="storage-fill" style={{
                width: `${Math.min(100, (info.bootstrapBytes / totalDisk) * 100)}%`,
                background: '#58a6ff',
              }} />
            </div>
          </div>

          {/* Web UI */}
          <div className="card">
            <div className="card-row" style={{ cursor: 'default' }}>
              <div className="card-content">
                <div className="card-label">{t('storage_www')}</div>
                <div className="card-desc">{formatBytes(info.wwwBytes)}</div>
              </div>
              <span style={{ fontSize: 13, color: 'var(--text-secondary)', fontFamily: 'monospace' }}>
                {totalDisk > 0 ? `${((info.wwwBytes / totalDisk) * 100).toFixed(1)}%` : '—'}
              </span>
            </div>
            <div className="storage-bar">
              <div className="storage-fill" style={{
                width: `${Math.min(100, (info.wwwBytes / totalDisk) * 100)}%`,
                background: '#3fb950',
              }} />
            </div>
          </div>

          {/* Espacio libre */}
          <div className="card">
            <div className="card-row" style={{ cursor: 'default' }}>
              <div className="card-content">
                <div className="card-label">{t('storage_free')}</div>
                <div className="card-desc">{formatBytes(info.freeBytes)}</div>
              </div>
              <span className="pill pill-success" style={{ fontSize: 11 }}>
                {totalDisk > 0 ? `${((info.freeBytes / totalDisk) * 100).toFixed(0)}%` : '—'}
              </span>
            </div>
          </div>

          {/* Acción */}
          <div style={{ marginTop: 24 }}>
            <button
              className="btn btn-secondary"
              style={{ width: '100%' }}
              onClick={handleClearCache}
              disabled={clearing}
            >
              {clearing ? (
                <><span className="spinner" style={{ width: 16, height: 16, marginRight: 8 }} />{t('storage_clearing')}</>
              ) : t('storage_clear')}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
