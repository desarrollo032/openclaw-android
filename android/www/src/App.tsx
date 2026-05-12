import { useState, useEffect } from 'react'
import { Route, useRoute } from './lib/router'
import { bridge } from './lib/bridge'
import { api } from './lib/api'

import { Setup }            from './screens/Setup'
import { Dashboard }        from './screens/Dashboard'
import { Chat }             from './screens/Chat'
import { Memory }           from './screens/Memory'
import { Logs }             from './screens/Logs'
import { Settings }         from './screens/Settings'
import { Skills }           from './screens/Skills.tsx'
import { SettingsStorage }  from './screens/SettingsStorage'
import { SettingsPlatforms} from './screens/SettingsPlatforms'
import { SettingsAbout }    from './screens/SettingsAbout'
import { SettingsKeepAlive }from './screens/SettingsKeepAlive'
import { SettingsTools }    from './screens/SettingsTools'
import { SettingsUpdates }  from './screens/SettingsUpdates'
import { Channels }         from './screens/Channels'
import { SettingsAdvanced } from './screens/SettingsAdvanced'

type Tab = 'chat' | 'dashboard' | 'terminal' | 'skills' | 'memory'

const TABS: { id: Tab; icon: string; label: string; path: string }[] = [
  { id: 'chat',      icon: 'chat',      label: 'Chat',     path: '/chat' },
  { id: 'dashboard', icon: 'dashboard', label: 'Inicio',   path: '/dashboard' },
  { id: 'terminal',  icon: 'terminal',  label: 'Terminal', path: '/terminal' },
  { id: 'skills',    icon: 'skills',    label: 'Skills',   path: '/skills' },
  { id: 'memory',    icon: 'memory',    label: 'Memoria',  path: '/memory' },
]

function Icon({ name }: { name: string }) {
  const common = { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }
  if (name === 'chat') return <svg {...common}><path d="M6 18.5 3.5 20v-3.7A7.5 7.5 0 1 1 19 9.5"/><path d="M8 9.5h8M8 13h5"/></svg>
  if (name === 'dashboard') return <svg {...common}><rect x="3.5" y="3.5" width="7" height="7" rx="1.4"/><rect x="13.5" y="3.5" width="7" height="4.5" rx="1.4"/><rect x="13.5" y="10.8" width="7" height="9.7" rx="1.4"/><rect x="3.5" y="13.3" width="7" height="7.2" rx="1.4"/></svg>
  if (name === 'terminal') return <svg {...common}><path d="m6 8 4 4-4 4"/><path d="M12.5 16.5h5.5"/><rect x="3.5" y="4.5" width="17" height="15" rx="2.2"/></svg>
  if (name === 'skills') return <svg {...common}><path d="M12 3.5 8.8 8.7l-5.9 1.4 4 4.4-.7 6 5.8-2.5 5.8 2.5-.7-6 4-4.4-5.9-1.4z"/></svg>
  if (name === 'memory') return <svg {...common}><rect x="5" y="7" width="14" height="10" rx="2"/><path d="M8 5v2m4-2v2m4-2v2m-8 10v2m4-2v2m4-2v2M3 10h2m-2 4h2m14-4h2m-2 4h2"/></svg>
  if (name === 'logs') return <svg {...common}><path d="M7 6.8h10M7 11.8h10M7 16.8h6"/><rect x="3.5" y="3.8" width="17" height="16.5" rx="2.5"/></svg>
  if (name === 'settings') return <svg {...common}><path d="M12 8.8a3.2 3.2 0 1 0 0 6.4 3.2 3.2 0 0 0 0-6.4Z"/><path d="m19.2 15.4 1.2 2.1-2.1 2.1-2.1-1.2a7.7 7.7 0 0 1-2 .8l-.6 2.3h-3l-.6-2.3a7.7 7.7 0 0 1-2-.8l-2.1 1.2-2.1-2.1 1.2-2.1a7.7 7.7 0 0 1-.8-2l-2.3-.6v-3l2.3-.6a7.7 7.7 0 0 1 .8-2L4.8 4.6l2.1-2.1L9 3.7a7.7 7.7 0 0 1 2-.8l.6-2.3h3l.6 2.3a7.7 7.7 0 0 1 2 .8l2.1-1.2 2.1 2.1-1.2 2.1a7.7 7.7 0 0 1 .8 2l2.3.6v3l-2.3.6a7.7 7.7 0 0 1-.8 2"/></svg>
  if (name === 'play') return <svg {...common}><path d="M8 6.2 17.6 12 8 17.8z"/></svg>
  return <svg {...common}><path d="M12 6.2a5.8 5.8 0 1 0 0 11.6 5.8 5.8 0 0 0 0-11.6Z"/><path d="M9.5 6V4m5 2V4M9.5 20v-2m5 2v-2"/></svg>
}

export function App() {
  const { path, navigate } = useRoute()
  const [nativeConnected] = useState(bridge.isAvailable())
  const [online, setOnline]       = useState(false)
  const [starting, setStarting]   = useState(false)
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
          onboarded: !!s.onboardComplete
        })
      } else {
        setSetupState({ installed: true, onboarded: true })
      }
    }

    const parseEventDetail = (detail: unknown): { state?: string } | null => {
      if (typeof detail === 'string') {
        try { return JSON.parse(detail) as { state?: string } } catch { return null }
      }
      return detail as { state?: string }
    }

    const handleGatewayState = (event: Event) => {
      const payload = parseEventDetail((event as CustomEvent).detail)
      if (payload?.state) {
        const state = payload.state
        setStarting(state === 'STARTING' || state === 'RESTARTING')
        setOnline(state === 'READY')
      }
    }

    const handleGatewayReady = () => {
      setStarting(false)
      setOnline(true)
    }

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

  useEffect(() => {
    if (!setupState) return

    if (!setupState.installed && !path.startsWith('/setup')) {
      navigate('/setup')
      return
    }

    if (setupState.installed && !setupState.onboarded && !path.startsWith('/setup')) {
      bridge.call('launchInteractiveCommand', 'openclaw onboard')
      setSetupState({ ...setupState, onboarded: true })
    }
  }, [setupState, path, navigate])

  useEffect(() => {
    const check = async () => {
      if (!bridge.isAvailable()) {
        setOnline(false)
        setStarting(false)
        return
      }

      const rawState = bridge.call('getGatewayState')
      const state = typeof rawState === 'string' ? rawState : undefined
      if (state === 'READY') {
        setOnline(true)
        setStarting(false)
        return
      }
      if (state === 'STARTING' || state === 'RESTARTING') {
        setOnline(false)
        setStarting(true)
        return
      }

      const h = await api.getHealth()
      setOnline(h.status === 'ok')
      setStarting(false)
    }

    check()
    const id = setInterval(check, 12_000)
    return () => clearInterval(id)
  }, [])

  const activeTab: Tab = path.startsWith('/chat')      ? 'chat'
    : path.startsWith('/dashboard') ? 'dashboard'
    : path.startsWith('/skills')    ? 'skills'
    : path.startsWith('/memory')    ? 'memory'
    : 'dashboard'

  if (setupState === null) return (
    <div style={{ display:'flex', alignItems:'center', justifyContent:'center',
                  height:'100vh', background:'#080810', flexDirection:'column', gap:16 }}>
      <div style={{ position:'relative', width:64, height:64 }}>
        <div style={{ position:'absolute', inset:0, borderRadius:'50%',
                      border:'3px solid rgba(99,102,241,0.15)',
                      borderTopColor:'#6366f1', animation:'spin 0.8s linear infinite' }} />
        <div style={{ position:'absolute', inset:8, borderRadius:'50%',
                      border:'2px solid rgba(139,92,246,0.1)',
                      borderBottomColor:'#8b5cf6', animation:'spin 1.2s linear infinite reverse' }} />
        <span style={{ position:'absolute', inset:0, display:'flex', alignItems:'center',
                       justifyContent:'center', fontSize:24 }}>🦀</span>
      </div>
      <div style={{ color:'#50506a', fontSize:13, fontWeight:500 }}>Iniciando OpenClaw...</div>
    </div>
  )

  const isSetup = path.startsWith('/setup') || (setupState?.installed && !setupState.onboarded)
  const hideNav = isSetup

  const statusClass = online ? 'online' : starting ? 'starting' : 'offline'

  return (
    <div className="app-container">
      {/* ── Header ── */}
      {!isSetup && (
        <header className="app-header">
          <div className={`status-dot ${statusClass}`} />
          <div className="title">OpenClaw</div>
          <div className={`native-badge ${nativeConnected ? 'ok' : 'web'}`}>
            {nativeConnected ? 'KOTLIN LINK' : 'WEB MODE'}
          </div>
          {/* Uptime badge when online */}
          {online && (
            <div style={{ fontSize:10, fontWeight:700, color:'var(--green)',
                          background:'var(--green-dim)', border:'1px solid rgba(74,222,128,0.2)',
                          borderRadius:'var(--r-full)', padding:'2px 8px', letterSpacing:'0.3px' }}>
              ACTIVO
            </div>
          )}
          {starting && !online && (
            <div style={{ fontSize:10, fontWeight:700, color:'var(--yellow)',
                          background:'var(--yellow-dim)', border:'1px solid rgba(250,204,21,0.2)',
                          borderRadius:'var(--r-full)', padding:'2px 8px', letterSpacing:'0.3px' }}>
              INICIANDO
            </div>
          )}
          <div style={{ display:'flex', gap:6, marginLeft:'auto' }}>
            {!online && !starting && (
              <button className="header-btn" onClick={() => bridge.call('startGateway')} title="Iniciar Gateway"><Icon name="play" /></button>
            )}
            <button className="header-btn" onClick={() => navigate('/logs')} title="Logs"><Icon name="logs" /></button>
            <button className="header-btn" onClick={() => navigate('/settings')} title="Ajustes"><Icon name="settings" /></button>
          </div>
        </header>
      )}

      {/* ── Content ── */}
      <main className="content" style={isSetup ? { paddingTop:0, paddingBottom:0 } : undefined}>
        <Route path="/setup">
          <Setup onComplete={() => { 
            setSetupState({ installed: true, onboarded: true })
            bridge.call('launchInteractiveCommand', 'openclaw onboard')
            navigate('/dashboard')
          }} />
        </Route>
        <Route path="/chat">      <Chat />      </Route>
        <Route path="/dashboard"> <Dashboard /> </Route>
        <Route path="/skills">    <Skills />    </Route>
        <Route path="/memory">    <Memory />    </Route>
        <Route path="/logs">      <Logs />      </Route>
        <Route path="/settings">  <SettingsRouter /> </Route>
      </main>

      {/* ── Bottom nav ── */}
      {!hideNav && (
        <nav className="tab-bar">
          {TABS.map(t => (
            <button
              key={t.id}
              className={`tab-bar-item ${activeTab === t.id ? 'active' : ''}`}
              onClick={() => {
                if (t.id === 'terminal') bridge.call('showTerminal')
                else navigate(t.path)
              }}
            >
              <span className="icon"><Icon name={t.icon} /></span>
              <span>{t.label}</span>
            </button>
          ))}
        </nav>
      )}
    </div>
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
