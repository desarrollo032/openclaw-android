import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

interface AppInfo {
  versionName: string
  versionCode: number
  packageName: string
}

interface SystemInfo {
  nodeVersion?: string
  npmVersion?: string
  openclawVersion?: string
  gitVersion?: string
}

export function SettingsAbout() {
  const { navigate } = useRoute()
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null)
  const [scriptVersion, setScriptVersion] = useState<string>('—')
  const [runtimeInfo, setRuntimeInfo] = useState<Record<string, string>>({})
  const [apkUpdateAvailable, setApkUpdateAvailable] = useState(false)

  useEffect(() => {
    const fetchInfo = () => {
      if (!bridge.isAvailable()) return
      const info = bridge.callJson<AppInfo>('getAppInfo')
      if (info) setAppInfo(info)

      const apkInfo = bridge.callJson<{ updateAvailable?: boolean }>('getApkUpdateInfo')
      if (apkInfo?.updateAvailable) setApkUpdateAvailable(true)

      const systemInfo = bridge.callJson<SystemInfo>('getSystemInfo') ?? {}
      const nodeV = { stdout: systemInfo.nodeVersion && systemInfo.nodeVersion !== 'unknown' ? systemInfo.nodeVersion : '' }
      const npmV = { stdout: systemInfo.npmVersion && systemInfo.npmVersion !== 'unknown' ? systemInfo.npmVersion : '' }
      const gitV = { stdout: systemInfo.gitVersion === 'no incluido' ? 'No incluido' : systemInfo.gitVersion || '' }
      const oaV = { stdout: systemInfo.openclawVersion && systemInfo.openclawVersion !== 'unknown' ? systemInfo.openclawVersion : '' }
      
      setScriptVersion(oaV?.stdout?.trim() || '—')
      setRuntimeInfo({
        'npm': npmV?.stdout?.trim() || 'No incluido',
        'Node.js': nodeV?.stdout?.trim() || '—',
        'git': gitV?.stdout?.trim()?.replace('git version ', '') || '—',
      })
    }
    fetchInfo()
  }, [])

  return (
    <div style={S.page}>
      <div style={S.header}>
        <button style={S.backBtn} onClick={() => navigate('/settings')}>←</button>
        <div style={S.title}>{t('about_title')}</div>
      </div>

      <div style={S.hero}>
        <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 80, height: 80, marginBottom: 12 }} />
        <div style={S.heroTitle}>OpenClaw</div>
      </div>

      <div style={S.sectionLabel}>{t('about_version')}</div>
      <div style={S.card}>
        <div style={S.row}>
          <span style={S.label}>{t('about_apk')}</span>
          <span style={S.valueContainer}>
            {appInfo?.versionName || '—'}
            {apkUpdateAvailable && (
              <span style={S.updateBadge}
                onClick={() => bridge.call('openUrl', 'https://github.com/AidanPark/openclaw-android/releases/latest')}
              >
                {t('about_update_available')}
              </span>
            )}
          </span>
        </div>
        <div style={S.row}>
          <span style={S.label}>{t('about_package')}</span>
          <span style={{ ...S.value, fontSize: 11 }}>{appInfo?.packageName || '—'}</span>
        </div>
        <div style={{ ...S.row, borderBottom: 'none' }}>
          <span style={S.label}>{t('about_script')}</span>
          <span style={S.value}>{scriptVersion}</span>
        </div>
      </div>

      <div style={S.sectionLabel}>{t('about_runtime')}</div>
      <div style={S.card}>
        {Object.entries(runtimeInfo).map(([key, val], i, arr) => (
          <div key={key} style={{ ...S.row, borderBottom: i < arr.length - 1 ? '1px solid var(--border)' : 'none' }}>
            <span style={S.label}>{key}</span>
            <span style={S.value}>{val}</span>
          </div>
        ))}
      </div>

      <div style={S.card}>
        <div style={{ ...S.row, borderBottom: 'none' }}>
          <span style={S.label}>{t('about_license')}</span>
          <span style={S.value}>GPL v3</span>
        </div>
      </div>

      <button
        style={S.actionBtn}
        onClick={() => bridge.call('openSystemSettings', 'app_info')}
      >
        {t('about_app_info')}
      </button>

      <div style={S.footer}>
        {t('about_made_for')}
      </div>
    </div>
  )
}

const S: Record<string, React.CSSProperties> = {
  page: { padding: '12px 14px 32px', maxWidth: 600, margin: '0 auto', overflowY: 'auto' },
  header: { display: 'flex', alignItems: 'center', marginBottom: 24, paddingTop: 8, gap: 12 },
  backBtn: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '50%', width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text)', cursor: 'pointer', fontSize: 18 },
  title: { fontSize: 20, fontWeight: 800, color: 'var(--text)' },
  
  hero: { textAlign: 'center', padding: '16px 0 32px', display: 'flex', flexDirection: 'column', alignItems: 'center' },
  heroTitle: { fontSize: 24, fontWeight: 800, letterSpacing: '-0.5px' },
  
  sectionLabel: { fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', color: 'var(--text3)', marginBottom: 10, marginTop: 24, paddingLeft: 2, textTransform: 'uppercase' },
  card: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-xl)', overflow: 'hidden', boxShadow: 'var(--sh-inset)', marginBottom: 16 },
  
  row: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '14px 16px', borderBottom: '1px solid var(--border)' },
  label: { fontSize: 13, color: 'var(--text2)', fontWeight: 600 },
  valueContainer: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--text)', fontFamily: "'JetBrains Mono', monospace" },
  value: { fontSize: 13, color: 'var(--text)', fontFamily: "'JetBrains Mono', monospace" },
  
  updateBadge: { fontSize: 10, fontWeight: 700, color: '#10b981', border: '1px solid rgba(16,185,129,0.3)', background: 'rgba(16,185,129,0.1)', borderRadius: 6, padding: '2px 8px', cursor: 'pointer', textTransform: 'uppercase' },
  
  actionBtn: { width: '100%', background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text)', padding: '14px', borderRadius: 'var(--r-lg)', fontSize: 14, fontWeight: 600, cursor: 'pointer', marginTop: 8, boxShadow: 'var(--sh-sm)' },
  footer: { textAlign: 'center', color: 'var(--text4)', fontSize: 12, marginTop: 40, paddingBottom: 20 },
}
