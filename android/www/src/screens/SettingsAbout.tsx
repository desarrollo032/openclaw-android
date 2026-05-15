import { useState, useEffect } from 'react'
import { PageHeader } from '../components/PageHeader'
import { callJson } from '../lib/bridge'

interface AppInfo {
  versionName?: string
  versionCode?: number
  packageName?: string
}

export function SettingsAbout() {
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null)

  useEffect(() => {
    try {
      const info = callJson<AppInfo>('getAppInfo')
      if (info) setAppInfo(info)
    } catch { /* bridge not available */ }
  }, [])

  const versionDisplay = appInfo?.versionName
    ? `${appInfo.versionName} (código ${appInfo.versionCode ?? '?'})`
    : '—'

  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Acerca de"
        subtitle="Información de OpenClaw"
      />

      <div className="card p-6 text-center">
        <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 120 120" className="w-14 h-14">
            <defs>
              <linearGradient id="openclaw-logo-grad" x1="0%" x2="100%" y1="0%" y2="100%">
                <stop offset="0%" stop-color="#ff4d4d"/>
                <stop offset="100%" stop-color="#991b1b"/>
              </linearGradient>
            </defs>
            <path fill="url(#openclaw-logo-grad)" d="M60 10c-30 0-45 25-45 45s15 40 30 45v10h10v-10s5 2 10 0v10h10v-10c15-5 30-25 30-45S90 10 60 10"/>
            <path fill="url(#openclaw-logo-grad)" d="M20 45C5 40 0 50 5 60s15 5 20-5c3-7 0-10-5-10M100 45c15-5 20 5 15 15s-15 5-20-5c-3-7 0-10 5-10"/>
            <path stroke="#ff4d4d" stroke-linecap="round" stroke-width="3" d="M45 15Q35 5 30 8M75 15Q85 5 90 8"/>
            <circle cx="45" cy="35" r="6" fill="#050810"/>
            <circle cx="75" cy="35" r="6" fill="#050810"/>
            <circle cx="46" cy="34" r="2.5" fill="#00e5cc"/>
            <circle cx="76" cy="34" r="2.5" fill="#00e5cc"/>
          </svg>
        </div>
        <h3 className="text-lg font-bold text-text-primary">OpenClaw</h3>
        <p className="text-xs text-text-muted mt-1">Asistente inteligente para Android</p>
        <div className="gradient-divider my-4" />
        <div className="space-y-2 text-left">
          {[
            { label: 'Versión', value: versionDisplay },
            { label: 'Framework', value: 'React + Vite' },
            { label: 'Estilos', value: 'TailwindCSS v4 + Lucide' },
            { label: 'Plataforma', value: 'Android' },
            ...(appInfo?.packageName
              ? [{ label: 'Paquete', value: appInfo.packageName }]
              : []),
          ].map(item => (
            <div key={item.label} className="flex items-center justify-between px-3 py-2 rounded-xl bg-glass-bg">
              <span className="text-xs text-text-muted">{item.label}</span>
              <span className="text-xs font-semibold text-text-primary">{item.value}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
