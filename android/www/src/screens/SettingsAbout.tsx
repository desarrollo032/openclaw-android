import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

interface AppInfo { versionName: string; versionCode: number; packageName: string }
interface EnvComponent { version?: string; detected: boolean; path?: string }
interface EnvInfo { node?: EnvComponent; git?: EnvComponent; openclaw?: EnvComponent; prefix?: string }

export function SettingsAbout() {
  const { navigate } = useRoute()
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null)
  const [scriptVersion, setScriptVersion] = useState<string>('—')
  const [envInfo, setEnvInfo] = useState<EnvInfo>({})
  const [apkUpdateAvailable, setApkUpdateAvailable] = useState(false)

  useEffect(() => {
    const info = bridge.callJson<AppInfo>('getAppInfo')
    if (info) setAppInfo(info)

    // Verificar actualización APK (no bloqueante)
    setTimeout(() => {
      const apkInfo = bridge.callJson<{ updateAvailable?: boolean }>('getApkUpdateInfo')
      if (apkInfo?.updateAvailable) setApkUpdateAvailable(true)
    }, 0)

    const env = bridge.callJson<EnvInfo>('getEnvironmentInfo')
    if (env) setEnvInfo(env)

    const oaV = bridge.callJson<{ stdout: string }>('runCommand', 'oa --version 2>/dev/null | head -1')
    setScriptVersion(oaV?.stdout?.trim() || '—')
  }, [])

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('about_title')}</div>
      </div>

      {/* Logo */}
      <div style={{ textAlign: 'center', padding: '20px 0 24px' }}>
        <div style={{
          width: 72, height: 72, borderRadius: 18,
          background: 'var(--accent-dim)',
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          marginBottom: 12,
        }}>
          <img src="./claw-icon.svg" alt="Claw" style={{ width: 48, height: 48 }} />
        </div>
        <div style={{ fontSize: 22, fontWeight: 800 }}>Claw</div>
        <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4 }}>
          {t('about_made_for')}
        </div>
      </div>

      {/* Versión */}
      <div className="section-title">{t('about_version')}</div>
      <div className="card">
        <div className="info-row">
          <span className="label">{t('about_apk')}</span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontFamily: 'monospace' }}>{appInfo?.versionName || '—'}</span>
            {apkUpdateAvailable && (
              <button
                className="pill pill-accent"
                style={{ cursor: 'pointer', border: 'none', fontSize: 11 }}
                onClick={() => bridge.call('openUrl', 'https://github.com/AidanPark/openclaw-android/releases/latest')}
              >
                ↑ {t('about_update_available')}
              </button>
            )}
          </span>
        </div>
        <div className="info-row">
          <span className="label">{t('about_package')}</span>
          <span style={{ fontSize: 12, fontFamily: 'monospace' }}>{appInfo?.packageName || '—'}</span>
        </div>
      </div>

      {/* Entorno */}
      <div className="section-title">{t('about_runtime')}</div>
      <div className="card">
        {([
          { key: 'node' as const, label: 'Node.js' },
          { key: 'git' as const, label: 'git' },
          { key: 'openclaw' as const, label: 'openclaw' },
        ]).map(({ key, label }) => {
          const comp = envInfo[key]
          const detected = comp?.detected ?? false
          return (
            <div className="info-row" key={key}>
              <span className="label" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{
                  width: 7, height: 7, borderRadius: '50%', flexShrink: 0,
                  background: detected ? 'var(--success)' : 'var(--error)',
                  display: 'inline-block',
                }} />
                {label}
              </span>
              <span style={{ fontFamily: 'monospace', fontSize: 13, color: detected ? 'var(--text-primary)' : 'var(--text-muted)' }}>
                {detected ? (comp?.version || '✓') : t('env_not_detected')}
              </span>
            </div>
          )
        })}
        <div className="info-row">
          <span className="label">{t('about_script')}</span>
          <span style={{ fontFamily: 'monospace' }}>{scriptVersion}</span>
        </div>
      </div>

      {/* Licencia */}
      <div className="section-title">{t('about_license')}</div>
      <div className="card">
        <div className="info-row">
          <span className="label">{t('about_license')}</span>
          <span>GPL v3</span>
        </div>
      </div>

      {/* Acciones */}
      <div style={{ display: 'flex', gap: 10, marginTop: 20 }}>
        <button
          className="btn btn-secondary"
          style={{ flex: 1 }}
          onClick={() => bridge.call('openSystemSettings', 'app_info')}
        >
          {t('about_app_info')}
        </button>
        <button
          className="btn btn-ghost"
          style={{ flex: 1 }}
          onClick={() => bridge.call('openUrl', 'https://github.com/AidanPark/openclaw-android')}
        >
          GitHub
        </button>
      </div>
    </div>
  )
}
