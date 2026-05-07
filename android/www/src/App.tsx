import { useState, useEffect } from 'react'
import { Route, useRoute } from './lib/router'
import { bridge } from './lib/bridge'
import { api } from './lib/api'

import { Setup } from './screens/Setup'
import { Dashboard } from './screens/Dashboard'
import { Chat } from './screens/Chat'
import { Memory } from './screens/Memory'
import { Logs } from './screens/Logs'
import { Settings } from './screens/Settings'
import { Skills } from './screens/Skills.tsx'
import { Terminal } from './screens/Terminal'
import { SettingsStorage } from './screens/SettingsStorage'
import { SettingsPlatforms } from './screens/SettingsPlatforms'

type Tab = 'chat' | 'dashboard' | 'terminal' | 'skills' | 'memory' | 'logs' | 'settings'

const TABS: { id: Tab; icon: string; label: string; path: string }[] = [
  { id: 'chat', icon: '💬', label: 'Chat', path: '/chat' },
  { id: 'dashboard', icon: '🏠', label: 'Inicio', path: '/dashboard' },
  { id: 'terminal', icon: '💻', label: 'Terminal', path: '/terminal' },
  { id: 'skills', icon: '⚡', label: 'Skills', path: '/skills' },
  { id: 'memory', icon: '🧠', label: 'Memoria', path: '/memory' },
]

export function App() {
  const { path, navigate } = useRoute()
  const [online, setOnline] = useState(false)
  const [setupDone, setSetupDone] = useState<boolean | null>(null)

  // Gateway health polling
  useEffect(() => {
    const check = async () => {
      const h = await api.getHealth()
      setOnline(h.status === 'ok' || h.status === 'online')
    }
    check()
    const id = setInterval(check, 10_000)
    return () => clearInterval(id)
  }, [])

  // Setup status
  useEffect(() => {
    const status = bridge.callJson<{ bootstrapInstalled?: boolean; platformInstalled?: string }>('getSetupStatus')
    if (status) {
      setSetupDone(!!status.bootstrapInstalled && !!status.platformInstalled)
    } else {
      setSetupDone(true) // dev / browser mode
    }
  }, [])

  const activeTab: Tab = path.startsWith('/chat') ? 'chat'
    : path.startsWith('/dashboard') ? 'dashboard'
      : path.startsWith('/terminal') ? 'terminal'
        : path.startsWith('/skills') ? 'skills'
          : path.startsWith('/memory') ? 'memory'
            : path.startsWith('/logs') ? 'logs'
              : 'settings'

  if (setupDone === null) return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100vh', background: '#0d0d12' }}>
      <div style={{ width: 36, height: 36, border: '3px solid #22223a', borderTopColor: '#6366f1', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />
    </div>
  )

  if (!setupDone && !path.startsWith('/setup')) {
    navigate('/setup')
  }

  const isSetup = path.startsWith('/setup')

  return (
    <div className="app-container">

      {/* ── Header (hidden on setup) ── */}
      {!isSetup && (
        <header className="app-header">
          <div className={`status-dot ${online ? 'online' : 'offline'}`} />
          <div className="title">OpenClaw</div>
          <div style={{ display: 'flex', gap: 8 }}>
            <button className="header-btn" onClick={() => navigate('/logs')} title="Logs">📝</button>
            <button className="header-btn" onClick={() => navigate('/settings')} title="Ajustes">⚙️</button>
          </div>
        </header>
      )}

      {/* ── Content ── */}
      <main className="content" style={isSetup ? { paddingTop: 0, paddingBottom: 0 } : undefined}>
        <Route path="/setup">
          <Setup onComplete={() => { setSetupDone(true); navigate('/dashboard') }} />
        </Route>
        <Route path="/chat">      <Chat />      </Route>
        <Route path="/dashboard"> <Dashboard /> </Route>
        <Route path="/terminal">  <Terminal />  </Route>
        <Route path="/skills">    <Skills />    </Route>
        <Route path="/memory">    <Memory />    </Route>
        <Route path="/logs">      <Logs />      </Route>
        <Route path="/settings">  <SettingsRouter /> </Route>
      </main>

      {/* ── Bottom nav (hidden on setup) ── */}
      {!isSetup && (
        <nav className="tab-bar">
          {TABS.map(t => (
            <button
              key={t.id}
              className={`tab-bar-item ${activeTab === t.id ? 'active' : ''}`}
              onClick={() => navigate(t.path)}
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
  if (path === '/settings/storage') return <SettingsStorage />
  if (path === '/settings/platforms') return <SettingsPlatforms />
  return <Settings />
}
