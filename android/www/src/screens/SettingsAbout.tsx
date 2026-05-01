import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

interface AppInfo { versionName: string; versionCode: number; packageName: string }
interface EnvComponent { version?: string; detected: boolean }
interface EnvInfo { node?: EnvComponent; git?: EnvComponent; openclaw?: EnvComponent }
interface BootstrapStatus {
  installed: boolean
  openclawInstalled: boolean
}

export function SettingsAbout() {
  const { navigate } = useRoute()
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null)
  const [envInfo, setEnvInfo] = useState<EnvInfo>({})
  const [bootstrapStatus, setBootstrapStatus] = useState<BootstrapStatus | null>(null)
  const [apkUpdateAvailable, setApkUpdateAvailable] = useState(false)
  const [checkingApk, setCheckingApk] = useState(false)

  useEffect(() => {
    const info = bridge.callJson<AppInfo>('getAppInfo')
    if (info) setAppInfo(info)

    const env = bridge.callJson<EnvInfo>('getEnvironmentInfo')
    if (env) setEnvInfo(env)

    const bs = bridge.callJson<BootstrapStatus>('getBootstrapStatus')
    if (bs) setBootstrapStatus(bs)
  }, [])

  function checkApkUpdate() {
    setCheckingApk(true)
    setTimeout(() => {
      const apkInfo = bridge.callJson<{ updateAvailable?: boolean }>('getApkUpdateInfo')
      if (apkInfo?.updateAvailable) setApkUpdateAvailable(true)
      setCheckingApk(false)
    }, 0)
  }

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('about_title')}</div>
      </div>

      <div style={{ textAlign: 'center', padding: '20px 0 28px' }}>
        <div style={{
          width: 80, height: 80, borderRadius: 20,
          background: 'var(--accent-dim)',
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          marginBottom: 14,
        }}>
          <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 52, height: 52 }} />
        </div>
        <div style={{ fontSize: 24, fontWeight: 800 }}>OpenClaw</div>
        <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4 }}>
          {t('about_made_for')}
        </div>
      </div>

      <div className="section-title">{t('about_version')}</div>
      <div className="card">
        <div className="info-row">
          <span className="label">{t('about_apk')}</span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>
              {appInfo?.versionName || '—'}
            </span>
            {apkUpdateAvailable && (
              <button
                className="pill pill-accent"
                style={{ cursor: 'pointer', border: 'none', fontSize: 11 }}
                onClick={() => bridge.call('openUrl', 'https://github.com/AidanPark/openclaw-android/releases/latest')}
              >
                ↑ {t('about_update_available')}
              </button>
            )}
          </div>
        </div>
        <div className="info-row">
          <span className="label">{t('about_package')}</span>
          <span style={{ fontSize: 12, fontFamily: 'monospace', color: 'var(--text-secondary)' }}>
            {appInfo?.packageName || '—'}
          </span>
        </div>
        <div style={{ marginTop: 12 }}>
          <button
            className="btn btn-secondary btn-sm"
            onClick={checkApkUpdate}
            disabled={checkingApk}
          >
            {checkingApk
              ? <><span className="spinner" style={{ width: 14, height: 14, marginRight: 6 }} />{t('about_checking_apk')}</>
              : t('about_check_apk')}
          </button>
        </div>
      </div>

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
              <span style={{
                fontFamily: 'monospace', fontSize: 13,
                color: detected ? 'var(--text-primary)' : 'var(--text-muted)',
              }}>
                {detected ? (comp?.version || '✓ installed') : t('env_not_detected')}
              </span>
            </div>
          )
        })}
      </div>

      <div className="section-title">{t('about_installation')}</div>
      <div className="card">
        {([
          { key: 'installed' as keyof BootstrapStatus, label: t('about_bootstrap_installed') },
          { key: 'openclawInstalled' as keyof BootstrapStatus, label: t('about_openclaw_installed') },
        ]).map(({ key, label }) => {
          const ok = bootstrapStatus?.[key] ?? false
          return (
            <div className="info-row" key={key}>
              <span className="label">{label}</span>
              <span className={`pill ${ok ? 'pill-success' : 'pill-error'}`}>
                {ok ? t('about_yes') : t('about_no')}
              </span>
            </div>
          )
        })}
      </div>

      <div className="section-title">{t('about_license')}</div>
      <div className="card">
        <div className="info-row">
          <span className="label">{t('about_license')}</span>
          <span>GPL v3</span>
        </div>
      </div>

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
          {t('about_github')}
        </button>
      </div>
    </div>
  )
}
