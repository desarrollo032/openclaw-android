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
import { Terminal }         from './screens/Terminal'
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
  { id: 'chat',      icon: '💬', label: 'Chat',     path: '/chat' },
  { id: 'dashboard', icon: '⬡',  label: 'Inicio',   path: '/dashboard' },
  { id: 'terminal',  icon: '⌨',  label: 'Terminal', path: '/terminal' },
  { id: 'skills',    icon: '⚡',  label: 'Skills',   path: '/skills' },
  { id: 'memory',    icon: '🧠',  label: 'Memoria',  path: '/memory' },
]

export function App() {
  const { path, navigate } = useRoute()
  const [online, setOnline]       = useState(false)
  const [starting, setStarting]   = useState(false)
  const [setupState, setSetupState] = useState<{ installed: boolean; onboarded: boolean } | null>(() => {
    if (!bridge.isAvailable()) return { installed: true, onboarded: true }
    const s = bridge.callJson<{ bootstrapInstalled?: boolean; platformInstalled?: string; onboardComplete?: boolean }>('getSetupStatus')
    if (s) return {
      installed: !!s.bootstrapInstalled && !!s.platformInstalled,
      onboarded: !!s.onboardComplete
    }
    return { installed: true, onboarded: true }
  })

  useEffect(() => {
    const check = async () => {
      const bridgeObj = (window as unknown as { OpenClaw?: { getGatewayState?: () => string } }).OpenClaw
      const bs = bridgeObj?.getGatewayState?.()
      if (bs === 'READY')      { setOnline(true);  setStarting(false); return }
      if (bs === 'STARTING' || bs === 'RESTARTING') { setOnline(false); setStarting(true); return }
      const h = await api.getHealth()
      const ok = h.status === 'ok'
      setOnline(ok)
      setStarting(false)
    }
    check()
    const id = setInterval(check, 8_000)
    return () => clearInterval(id)
  }, [])

  const activeTab: Tab = path.startsWith('/chat')      ? 'chat'
    : path.startsWith('/dashboard') ? 'dashboard'
    : path.startsWith('/terminal')  ? 'terminal'
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

  // 1. Redirigir a setup si no está instalado
  if (!setupState.installed && !path.startsWith('/setup')) {
    navigate('/setup')
  } 
  // 2. Redirigir a terminal nativa para onboarding si está instalado pero no configurado
  else if (setupState.installed && !setupState.onboarded && !path.startsWith('/setup')) {
    bridge.call('launchInteractiveCommand', 'openclaw onboard')
    setSetupState({ ...setupState, onboarded: true })
  }

  const isSetup = path.startsWith('/setup') || (setupState.installed && !setupState.onboarded)
  const hideNav = isSetup

  const statusClass = online ? 'online' : starting ? 'starting' : 'offline'

  return (
    <div className="app-container">
      {/* ── Header ── */}
      {!isSetup && (
        <header className="app-header">
          <div className={`status-dot ${statusClass}`} />
          <div className="title">OpenClaw</div>
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
            <button className="header-btn" onClick={() => navigate('/logs')}     title="Logs">📋</button>
            <button className="header-btn" onClick={() => navigate('/settings')} title="Ajustes">⚙️</button>
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
        <Route path="/terminal">  <Terminal />  </Route>
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
              <span className="icon">{t.icon}</span>
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
