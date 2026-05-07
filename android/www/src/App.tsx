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

type Tab = 'chat' | 'dashboard' | 'skills' | 'memory' | 'logs' | 'settings' | 'terminal'

export function App() {
  const { path, navigate } = useRoute()
  const [online, setOnline] = useState(false)
  const [setupDone, setSetupDone] = useState<boolean | null>(null)

  useEffect(() => {
    // Check gateway health
    const checkHealth = async () => {
      const health = await api.getHealth()
      setOnline(health.status === 'ok' || health.status === 'online')
    }
    checkHealth()
    const interval = setInterval(checkHealth, 10000)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    const fetchStatus = () => {
      const status = bridge.callJson<{ bootstrapInstalled?: boolean; platformInstalled?: string }>(
        'getSetupStatus'
      )
      if (status) {
        setSetupDone(!!status.bootstrapInstalled && !!status.platformInstalled)
      } else {
        setSetupDone(true) // dev mode
      }
    }
    fetchStatus()
  }, [])

  // Determine active tab
  const activeTab: Tab = path.startsWith('/chat') ? 'chat'
    : path.startsWith('/dashboard') ? 'dashboard'
    : path.startsWith('/terminal') ? 'terminal'
    : path.startsWith('/skills') ? 'skills'
    : path.startsWith('/memory') ? 'memory'
    : path.startsWith('/logs') ? 'logs'
    : 'settings'

  if (setupDone === null) return null
  if (!setupDone && !path.startsWith('/setup')) {
    navigate('/setup')
  }

  return (
    <div className="app-container">
      {/* Header */}
      <header className="app-header">
        <div className={`status-indicator ${online ? 'online' : 'offline'}`} />
        <div className="title">OpenClaw</div>
        <div style={{ display: 'flex', gap: 12 }}>
          <button className="action-btn" onClick={() => navigate('/logs')}>📝</button>
          <button className="action-btn" onClick={() => navigate('/settings')}>⚙️</button>
        </div>
      </header>

      {/* Main Content */}
      <main className="content">
        <Route path="/setup">
          <Setup onComplete={() => { setSetupDone(true); navigate('/chat') }} />
        </Route>
        <Route path="/chat">
          <Chat />
        </Route>
        <Route path="/dashboard">
          <Dashboard />
        </Route>
        <Route path="/terminal">
          <Terminal />
        </Route>
        <Route path="/skills">
          <Skills />
        </Route>
        <Route path="/memory">
          <Memory />
        </Route>
        <Route path="/logs">
          <Logs />
        </Route>
        <Route path="/settings">
          <SettingsRouter />
        </Route>
      </main>

      {/* Bottom Navigation */}
      <nav className="tab-bar">
        <TabItem icon="💬" label="Chat" active={activeTab === 'chat'} onClick={() => navigate('/chat')} />
        <TabItem icon="📊" label="Home" active={activeTab === 'dashboard'} onClick={() => navigate('/dashboard')} />
        <TabItem icon="💻" label="Terminal" active={activeTab === 'terminal'} onClick={() => navigate('/terminal')} />
        <TabItem icon="⚡" label="Skills" active={activeTab === 'skills'} onClick={() => navigate('/skills')} />
        <TabItem icon="🧠" label="Memoria" active={activeTab === 'memory'} onClick={() => navigate('/memory')} />
      </nav>
    </div>
  )
}

function TabItem({ icon, label, active, onClick }: { icon: string, label: string, active: boolean, onClick: () => void }) {
  return (
    <button className={`tab-bar-item ${active ? 'active' : ''}`} onClick={onClick}>
      <span className="icon">{icon}</span>
      <span>{label}</span>
    </button>
  )
}

function SettingsRouter() {
  const { path } = useRoute()
  if (path === '/settings') return <Settings />
  if (path === '/settings/storage') return <SettingsStorage />
  if (path === '/settings/platforms') return <SettingsPlatforms />
  return <Settings />
}
