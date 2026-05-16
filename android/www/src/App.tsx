import { useState, useEffect } from 'react'
import { Route, useRoute } from './lib/router'
import { bridge, onTokenRefresh, off } from './lib/bridge'
import { api } from './lib/api'

import { Setup }            from './screens/Setup'
import { Dashboard }        from './screens/Dashboard'
import { Chat }             from './screens/Chat'
import { Memory }           from './screens/Memory'
import { Logs }             from './screens/Logs'
import { Settings }         from './screens/Settings'
import { Skills }           from './screens/Skills'
import { SettingsStorage }  from './screens/SettingsStorage'
import { SettingsPlatforms } from './screens/SettingsPlatforms'
import { SettingsAbout }    from './screens/SettingsAbout'
import { SettingsKeepAlive }from './screens/SettingsKeepAlive'
import { SettingsTools }    from './screens/SettingsTools'
import { SettingsUpdates }  from './screens/SettingsUpdates'
import { Channels }         from './screens/Channels'
import { SettingsAdvanced } from './screens/SettingsAdvanced'
import { LocaleProvider }   from './components/LocaleProvider'

import { t } from './i18n'
import { MessageSquare, LayoutDashboard, Terminal, Zap, Brain, Sun, Moon, Settings as SettingsIcon } from 'lucide-react'

type Tab = 'chat' | 'dashboard' | 'terminal' | 'skills' | 'memory'

function useTheme() {
  const [theme, setTheme] = useState<'dark' | 'light'>(() => {
    const saved = localStorage.getItem('openclaw-theme')
    if (saved === 'dark' || saved === 'light') return saved
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
  })

  useEffect(() => {
    const isDark = theme === 'dark'
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem('openclaw-theme', theme)
    const meta = document.querySelector('meta[name="theme-color"]')
    if (meta) meta.setAttribute('content', isDark ? '#09090b' : '#f5f5f0')
  }, [theme])

  const toggle = () => setTheme(t => t === 'dark' ? 'light' : 'dark')
  return { theme, toggle }
}

export function App() {
  const { path, navigate } = useRoute()
  const { theme, toggle: toggleTheme } = useTheme()
  const [online, setOnline] = useState(false)
  const [starting, setStarting] = useState(false)
  const [setupState, setSetupState] = useState<{ installed: boolean; onboarded: boolean } | null>(null)

  useEffect(() => {
    const loadSetupState = async () => {
      if (!bridge.isAvailable()) {
        setSetupState({ installed: true, onboarded: true })
        return
      }
      const s = bridge.callJson<{ bootstrapInstalled?: boolean; platformInstalled?: string; onboardComplete?: boolean }>('getSetupStatus')
      if (s) {
        setSetupState({
          installed: !!s.bootstrapInstalled && !!s.platformInstalled,
          onboarded: !!s.onboardComplete,
        })
      } else {
        setSetupState({ installed: true, onboarded: true })
      }
    }

    const handleGatewayState = (event: Event) => {
      const detail = (event as CustomEvent).detail
      const data = typeof detail === 'string' ? (() => { try { return JSON.parse(detail) } catch { return null } })() : detail as { state?: string }
      if (data?.state) {
        setStarting(data.state === 'STARTING' || data.state === 'RESTARTING')
        setOnline(data.state === 'READY')
      }
    }

    const handleGatewayReady = () => { setStarting(false); setOnline(true) }

    loadSetupState()
    window.addEventListener('android:onGatewayStateChanged', handleGatewayState)
    window.addEventListener('onGatewayStateChanged', handleGatewayState)
    window.addEventListener('android:onGatewayReady', handleGatewayReady)
    window.addEventListener('onGatewayReady', handleGatewayReady)
    return () => {
      window.removeEventListener('android:onGatewayStateChanged', handleGatewayState)
      window.removeEventListener('onGatewayStateChanged', handleGatewayState)
      window.removeEventListener('android:onGatewayReady', handleGatewayReady)
      window.removeEventListener('onGatewayReady', handleGatewayReady)
    }
  }, [])

  useEffect(() => {
    if (!path.startsWith('/terminal')) return
    bridge.call('showTerminal')
    navigate('/dashboard')
  }, [path, navigate])

  // Refresh de token: cuando Kotlin reinicia el gateway, emite un nuevo token
  // por android:onTokenRefresh. Sin esta suscripción, las siguientes llamadas
  // HTTP/WS fallarían 401 hasta que el usuario recargara la WebView.
  useEffect(() => {
    const handler = onTokenRefresh((newToken) => {
      console.info('[token] refreshed (length=%d)', newToken.length)
    })
    return () => off('onTokenRefresh', handler)
  }, [])

  useEffect(() => {
    if (!setupState) return
    if (!setupState.installed && !path.startsWith('/setup')) { navigate('/setup'); return }
    if (setupState.installed && !setupState.onboarded && !path.startsWith('/setup')) {
      bridge.call('launchInteractiveCommand', 'openclaw onboard')
      setSetupState({ ...setupState, onboarded: true })
    }
  }, [setupState, path, navigate])

  useEffect(() => {
    const check = async () => {
      if (!bridge.isAvailable()) { setOnline(false); setStarting(false); return }
      const rawState = bridge.call('getGatewayState')
      const state = typeof rawState === 'string' ? rawState : undefined
      if (state === 'READY') { setOnline(true); setStarting(false); return }
      if (state === 'STARTING' || state === 'RESTARTING') { setOnline(false); setStarting(true); return }
      const h = await api.getHealth()
      setOnline(h.status === 'ok')
      setStarting(false)
    }
    check()
    const id = setInterval(check, 12_000)
    return () => clearInterval(id)
  }, [])

  const TABS: { id: Tab; icon: React.ElementType; label: string; path: string }[] = [
    { id: 'chat',      icon: MessageSquare,     label: t('tab_chat'),      path: '/chat' },
    { id: 'dashboard', icon: LayoutDashboard,   label: t('tab_dashboard'), path: '/dashboard' },
    { id: 'terminal',  icon: Terminal,           label: t('tab_terminal'),  path: '/terminal' },
    { id: 'skills',    icon: Zap,                label: t('tab_skills'),    path: '/skills' },
    { id: 'memory',    icon: Brain,              label: t('tab_memory'),    path: '/memory' },
  ]

  const activeTab: Tab = path.startsWith('/chat') ? 'chat'
    : path.startsWith('/dashboard') ? 'dashboard'
    : path.startsWith('/skills') ? 'skills'
    : path.startsWith('/memory') ? 'memory'
    : 'dashboard'

  const isSetup = path.startsWith('/setup') || (setupState?.installed && !setupState.onboarded)
  const hideNav = isSetup

  // Loading screen
  if (setupState === null) {
    return (
      <div className="flex items-center justify-center h-screen bg-bg flex-col gap-5">
        <div className="relative w-20 h-20">
          <div className="absolute inset-0 rounded-full border-2 border-accent/20 border-t-accent animate-spin" style={{ animationDuration: '0.8s' }} />
          <div className="absolute inset-2 rounded-full border-2 border-accent-light/10 border-b-accent-soft animate-spin" style={{ animationDuration: '1.2s', animationDirection: 'reverse' }} />
          <div className="absolute inset-4 rounded-full bg-gradient-to-br from-accent/5 to-accent-light/5 animate-pulse" />
        </div>
        <div className="flex flex-col items-center gap-1.5">
          <span className="text-lg font-bold text-gradient">OpenClaw</span>
          <span className="text-xs text-text-muted font-medium">Iniciando...</span>
        </div>
      </div>
    )
  }

  return (
    <LocaleProvider>
    <div className="flex flex-col h-full bg-bg antialiased">
      {/* ── Content area ── */}
      <main className="flex-1 overflow-y-auto scroll-smooth">
        <Route path="/setup">
          <Setup onComplete={() => {
            setSetupState({ installed: true, onboarded: true })
            bridge.call('launchInteractiveCommand', 'openclaw onboard')
            navigate('/dashboard')
          }} />
        </Route>
        <Route path="/chat"><Chat /></Route>
        <Route path="/dashboard"><Dashboard /></Route>
        <Route path="/skills"><Skills /></Route>
        <Route path="/memory"><Memory /></Route>
        <Route path="/logs"><Logs /></Route>
        <Route path="/settings"><SettingsRouter /></Route>
      </main>

      {/* ── Status bar (top-right) ── */}
      {!isSetup && (
        <div className="fixed top-3.5 right-3.5 z-50 flex items-center gap-1.5">
          {/* Theme toggle */}
          <button
            onClick={toggleTheme}
            className="flex items-center justify-center w-8 h-8 rounded-xl glass text-text-muted hover:text-text-primary hover:bg-accent-soft/50 transition-all active:scale-90"
            aria-label={theme === 'dark' ? t('dark_mode') : t('light_mode')}
            title={theme === 'dark' ? t('dark_mode') : t('light_mode')}
          >
            {theme === 'dark' ? <Sun size={14} /> : <Moon size={14} />}
          </button>

          {/* Health status */}
          <button
            onClick={() => navigate('/logs')}
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl glass text-[11px] font-semibold text-text-secondary hover:text-text-primary transition-all hover:glass-strong active:scale-95"
            title={online ? 'Gateway en línea' : starting ? 'Iniciando...' : 'Gateway offline'}
          >
            <span className="relative flex w-2 h-2">
              <span className={`absolute inset-0 rounded-full ${
                online ? 'bg-green' : starting ? 'bg-yellow' : 'bg-red'
              }`} />
              <span className={`absolute inset-0 rounded-full ${
                online ? 'bg-green/40 animate-ping-slow' : starting ? 'bg-yellow/40 animate-ping-slow' : ''
              }`} />
            </span>
            <span className="hidden sm:inline">{online ? t('online') : starting ? t('starting') : t('offline')}</span>
          </button>

          {/* Settings */}
          <button
            onClick={() => navigate('/settings')}
            className="flex items-center justify-center w-8 h-8 rounded-xl glass text-text-muted hover:text-text-primary hover:bg-accent-soft/50 transition-all active:scale-90"
            title="Ajustes"
          >
            <SettingsIcon size={15} />
          </button>
        </div>
      )}

      {/* ── Bottom navigation ── */}
      {!hideNav && (
        <nav className="nav-container pb-safe">
          {TABS.map(t => {
            const isActive = activeTab === t.id
            const Icon = t.icon
            return (
              <button key={t.id}
                className={`relative flex flex-col items-center justify-center gap-0.5 min-w-0 flex-1 py-1.5 rounded-xl transition-all duration-200 ${
                  isActive ? 'text-accent' : 'text-text-muted hover:text-text-secondary'
                }`}
                onClick={() => {
                  if (t.id === 'terminal') bridge.call('showTerminal')
                  else navigate(t.path)
                }}>
                {isActive && (
                  <span className="nav-indicator" />
                )}
                <Icon size={20} strokeWidth={isActive ? 2.5 : 1.8} />
                <span className={`text-[9px] font-semibold leading-none tracking-tight ${isActive ? 'opacity-100' : 'opacity-50'}`}>
                  {t.label}
                </span>
              </button>
            )
          })}
        </nav>
      )}
    </div>
    </LocaleProvider>
  )
}

function SettingsRouter() {
  const { path } = useRoute()
  if (path === '/settings/storage')   return <SettingsStorage />
  if (path === '/settings/platforms') return <SettingsPlatforms />
  if (path === '/settings/about')     return <SettingsAbout />
  if (path === '/settings/keepalive') return <SettingsKeepAlive />
  if (path === '/settings/tools')     return <SettingsTools />
  if (path === '/settings/updates')   return <SettingsUpdates />
  if (path === '/settings/channels')  return <Channels />
  if (path === '/settings/advanced')  return <SettingsAdvanced />
  return <Settings />
}
