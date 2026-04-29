import { useState, useEffect, useCallback } from 'react'
import { Route, useRoute } from './lib/router'
import { bridge } from './lib/bridge'
import { useNativeEvent } from './lib/useNativeEvent'
import { t } from './i18n'
import { Setup } from './screens/Setup'
import { Dashboard } from './screens/Dashboard'
import { Settings } from './screens/Settings'
import { SettingsKeepAlive } from './screens/SettingsKeepAlive'
import { SettingsStorage } from './screens/SettingsStorage'
import { SettingsAbout } from './screens/SettingsAbout'
import { SettingsUpdates } from './screens/SettingsUpdates'
import { SettingsPlatforms } from './screens/SettingsPlatforms'
import { SettingsTools } from './screens/SettingsTools'

type Tab = 'terminal' | 'dashboard' | 'settings'

export function App() {
  const { path, navigate } = useRoute()
  const [hasUpdates, setHasUpdates] = useState(false)
  const [setupDone, setSetupDone] = useState<boolean | null>(null)

  useEffect(() => {
    const status = bridge.callJson<{ bootstrapInstalled?: boolean; platformInstalled?: string }>(
      'getSetupStatus'
    )
    if (status) {
      setSetupDone(!!status.bootstrapInstalled && !!status.platformInstalled)
    } else {
      // Bridge no disponible (modo dev) — asumir setup completo
      setSetupDone(true)
    }

    const updates = bridge.callJson<unknown[]>('checkForUpdates')
    if (updates && updates.length > 0) setHasUpdates(true)
  }, [])

  const onUpdateAvailable = useCallback(() => setHasUpdates(true), [])
  useNativeEvent('update_available', onUpdateAvailable)

  // Determinar pestaña activa desde la ruta
  const activeTab: Tab = path.startsWith('/settings') || path.startsWith('/setup')
    ? 'settings'
    : 'dashboard'

  function handleTabClick(tab: Tab) {
    if (tab === 'terminal') {
      bridge.call('showTerminal')
      return
    }
    bridge.call('showWebView')
    navigate(tab === 'dashboard' ? '/dashboard' : '/settings')
  }

  useEffect(() => {
    if (setupDone === false && !path.startsWith('/setup')) {
      navigate('/setup')
    }
  }, [setupDone, path, navigate])

  if (setupDone === null) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100dvh' }}>
        <div className="spinner" style={{ width: 32, height: 32, borderWidth: 3 }} />
      </div>
    )
  }

  if (!setupDone && !path.startsWith('/setup')) {
    return null // redirect in progress via useEffect
  }

  return (
    <>
      {/* Barra de pestañas */}
      <nav className="tab-bar">
        <button
          className="tab-bar-item"
          onClick={() => handleTabClick('terminal')}
        >
          <span className="tab-icon">⌨</span>
          <span className="tab-label">{t('tab_terminal').replace(/^\S+\s/, '')}</span>
        </button>
        <button
          className={`tab-bar-item${activeTab === 'dashboard' ? ' active' : ''}`}
          onClick={() => handleTabClick('dashboard')}
        >
          <span className="tab-icon">◈</span>
          <span className="tab-label">{t('tab_dashboard').replace(/^\S+\s/, '')}</span>
        </button>
        <button
          className={`tab-bar-item${activeTab === 'settings' ? ' active' : ''}`}
          onClick={() => handleTabClick('settings')}
        >
          <span className="tab-icon">⚙</span>
          <span className="tab-label">{t('tab_settings').replace(/^\S+\s/, '')}</span>
          {hasUpdates && <span className="badge" />}
        </button>
      </nav>

      {/* Rutas */}
      <Route path="/setup">
        <Setup onComplete={() => { setSetupDone(true); navigate('/dashboard') }} />
      </Route>
      <Route path="/dashboard">
        <Dashboard />
      </Route>
      <Route path="/settings">
        <SettingsRouter />
      </Route>
    </>
  )
}

function SettingsRouter() {
  const { path } = useRoute()
  if (path === '/settings/keep-alive') return <SettingsKeepAlive />
  if (path === '/settings/storage') return <SettingsStorage />
  if (path === '/settings/about') return <SettingsAbout />
  if (path === '/settings/updates') return <SettingsUpdates />
  if (path === '/settings/platforms') return <SettingsPlatforms />
  if (path === '/settings/tools') return <SettingsTools />
  return <Settings />
}
