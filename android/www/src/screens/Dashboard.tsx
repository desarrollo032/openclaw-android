import { useState, useEffect } from 'react'
import { api } from '../lib/api'
import { t } from '../i18n'

export function Dashboard() {
  const [health, setHealth] = useState<any>(null)
  const [skills, setSkills] = useState<any[]>([])
  const [storage, setStorage] = useState<any>(null)
  const [versions, setVersions] = useState<any>({
    node: '...',
    npm: '...',
    openclaw: '...',
    glibc: '...'
  })
  const [stats, setStats] = useState({
    model: '—',
    context: '0 / 4096',
    uptime: '—'
  })

  const loadData = async () => {
    const h = await api.getHealth()
    setHealth(h)
    
    const s = await api.getSkills()
    setSkills(s || [])

    const config = await api.getConfig()
    if (config) {
      setStats({
        model: config.default_model || 'gpt-4o',
        context: `124 / ${config.context_size || 4096}`,
        uptime: h.uptime || '2h 14m'
      })
    }

    // Fetch bridge data
    const storageInfo = bridge.callJson<any>('getStorageInfo')
    if (storageInfo) setStorage(storageInfo)

    const vNode = bridge.callJson<any>('runCommand', 'node -v')
    const vNpm = bridge.callJson<any>('runCommand', 'npm -v')
    const vOC = bridge.callJson<any>('runCommand', 'openclaw --version')
    const vGlibc = bridge.callJson<any>('runCommand', 'ldd --version')

    setVersions({
      node: vNode?.stdout || 'No instalado',
      npm: vNpm?.stdout || 'No instalado',
      openclaw: vOC?.stdout || 'No instalado',
      glibc: vGlibc?.stdout || 'No instalado'
    })
  }

  useEffect(() => {
    loadData()
  }, [])

  const formatSize = (bytes: number) => {
    if (!bytes) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
  }

  return (
    <div className="page">
      {/* Gateway Status Card */}
      <div className="card">
        <div className="card-title">Estado del Gateway</div>
        <div className="info-row">
          <span className="label">Conexión</span>
          <span style={{ color: health?.status === 'ok' ? 'var(--success)' : 'var(--error)', fontWeight: 'bold' }}>
            {health?.status === 'ok' ? 'Conectado' : 'Desconectado'}
          </span>
        </div>
        <div className="info-row">
          <span className="label">Uptime</span>
          <span>{stats.uptime}</span>
        </div>
      </div>

      {/* Storage Card */}
      <div className="card">
        <div className="card-title">Almacenamiento Interno</div>
        {storage && (
          <>
            <div style={{ height: 8, background: 'var(--bg-tertiary)', borderRadius: 4, overflow: 'hidden', margin: '8px 0' }}>
              <div style={{ width: `${storage.percent}%`, height: '100%', background: 'var(--primary)' }} />
            </div>
            <div className="info-row">
              <span className="label">Uso: {storage.percent}%</span>
              <span>{formatSize(storage.used)} / {formatSize(storage.total)}</span>
            </div>
          </>
        )}
      </div>

      {/* System Versions Card */}
      <div className="card">
        <div className="card-title">Versiones del Sistema</div>
        <div className="info-row">
          <span className="label">Node.js</span>
          <span className="status-badge" style={{ fontSize: 11 }}>{versions.node}</span>
        </div>
        <div className="info-row">
          <span className="label">NPM</span>
          <span className="status-badge" style={{ fontSize: 11 }}>{versions.npm}</span>
        </div>
        <div className="info-row">
          <span className="label">OpenClaw</span>
          <span className="status-badge success" style={{ fontSize: 11 }}>{versions.openclaw}</span>
        </div>
        <div className="info-row">
          <span className="label">GLIBC</span>
          <span className="status-badge" style={{ fontSize: 11 }}>{versions.glibc}</span>
        </div>
      </div>

      {/* Metrics Card */}
      <div className="card">
        <div className="card-title">Métricas de IA</div>
        <div className="info-row">
          <span className="label">Modelo Activo</span>
          <span>{stats.model}</span>
        </div>
        <div className="info-row">
          <span className="label">Uso de Contexto</span>
          <span>{stats.context}</span>
        </div>
      </div>

      {/* Skills Card */}
      <div className="card">
        <div className="card-title">Habilidades Activas</div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {skills.filter(s => s.active).map(s => (
            <div key={s.id} className="status-badge success" style={{ background: 'var(--bg-primary)', padding: '6px 12px' }}>
              {s.name}
            </div>
          ))}
          {skills.filter(s => s.active).length === 0 && (
            <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>Ninguna habilidad activa</div>
          )}
        </div>
      </div>

      {/* Recent Conversastions */}
      <div className="settings-group">
        <div className="settings-group-title">Actividad Reciente</div>
        <div className="card" style={{ cursor: 'pointer' }} onClick={() => window.location.hash = '/chat'}>
          <div style={{ fontSize: 14, fontWeight: 600 }}>Nueva Conversación</div>
          <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>Pulsa para empezar a chatear con OpenClaw</div>
        </div>
      </div>
    </div>
  )
}
