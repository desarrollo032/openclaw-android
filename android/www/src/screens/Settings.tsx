import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { t, getLocale, setLocale, availableLocales } from '../i18n'
import { api } from '../lib/api'

interface Config {
  openai_key: string
  anthropic_key: string
  default_model: string
  context_size: number
  temperature: number
  notifications: boolean
  theme: 'light' | 'dark' | 'system'
}

export function Settings() {
  const { navigate } = useRoute()
  const [config, setConfig] = useState<Config>({
    openai_key: '',
    anthropic_key: '',
    default_model: 'gpt-4o',
    context_size: 4096,
    temperature: 0.7,
    notifications: true,
    theme: 'system'
  })

  useEffect(() => {
    const loadConfig = async () => {
      const data = await api.getConfig()
      if (data) setConfig(prev => ({ ...prev, ...data }))
    }
    loadConfig()
  }, [])

  const handleUpdate = <K extends keyof Config>(key: K, val: Config[K]) => {
    const newConfig = { ...config, [key]: val }
    setConfig(newConfig)
    api.updateConfig(newConfig)
  }

  return (
    <div className="page">
      <div className="page-header">
        <div className="page-title">{t('settings_title')}</div>
      </div>

      <div className="settings-group">
        <div className="settings-group-title">API Keys</div>
        <div className="card">
          <div className="form-group">
            <label className="form-label">OpenAI API Key</label>
            <input 
              type="password" 
              className="form-input" 
              placeholder="sk-..." 
              value={config.openai_key}
              onChange={(e) => handleUpdate('openai_key', e.target.value)}
            />
          </div>
          <div className="form-group">
            <label className="form-label">Anthropic API Key</label>
            <input 
              type="password" 
              className="form-input" 
              placeholder="sk-ant-..." 
              value={config.anthropic_key}
              onChange={(e) => handleUpdate('anthropic_key', e.target.value)}
            />
          </div>
        </div>
      </div>

      <div className="settings-group">
        <div className="settings-group-title">Modelo y Parámetros</div>
        <div className="card">
          <div className="form-group">
            <label className="form-label">Modelo por Defecto</label>
            <select 
              className="form-input"
              value={config.default_model}
              onChange={(e) => handleUpdate('default_model', e.target.value)}
            >
              <option value="gpt-4o">GPT-4o</option>
              <option value="gpt-4-turbo">GPT-4 Turbo</option>
              <option value="claude-3-5-sonnet">Claude 3.5 Sonnet</option>
              <option value="claude-3-opus">Claude 3 Opus</option>
              <option value="gemini-1.5-pro">Gemini 1.5 Pro</option>
            </select>
          </div>
          
          <div className="form-group">
            <label className="form-label">Tamaño del Contexto: {config.context_size}</label>
            <input 
              type="range" 
              className="slider" 
              min="1024" 
              max="128000" 
              step="1024"
              value={config.context_size}
              onChange={(e) => handleUpdate('context_size', parseInt(e.target.value))}
            />
          </div>

          <div className="form-group">
            <label className="form-label">Temperatura: {config.temperature}</label>
            <input 
              type="range" 
              className="slider" 
              min="0" 
              max="2" 
              step="0.1"
              value={config.temperature}
              onChange={(e) => handleUpdate('temperature', parseFloat(e.target.value))}
            />
          </div>
        </div>
      </div>

      <div className="settings-group">
        <div className="settings-group-title">Preferencias</div>
        <div className="settings-list">
          <div className="settings-item">
            <div className="card-content">
              <div className="card-label">Notificaciones</div>
              <div className="card-desc">Recibir avisos de tareas completadas</div>
            </div>
            <input 
              type="checkbox" 
              checked={config.notifications} 
              onChange={(e) => handleUpdate('notifications', e.target.checked)}
            />
          </div>
          
          <div className="settings-item" onClick={() => navigate('/settings/storage')}>
            <div className="card-content">
              <div className="card-label">{t('settings_storage')}</div>
              <div className="card-desc">{t('settings_storage_desc')}</div>
            </div>
            <div className="card-chevron">›</div>
          </div>
        </div>
      </div>

      <div className="settings-group">
        <div className="settings-group-title">Idioma</div>
        <div className="settings-list">
          <div className="settings-item">
            <div className="card-content" style={{ display: 'flex', gap: 12 }}>
              {availableLocales.map(loc => (
                <button
                  key={loc.code}
                  className={`btn ${getLocale() === loc.code ? 'btn-primary' : 'btn-secondary'}`}
                  style={{ width: 'auto', marginBottom: 0, padding: '8px 16px', fontSize: 14 }}
                  onClick={() => { setLocale(loc.code); window.location.reload() }}
                >
                  {loc.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>

      <div className="settings-group">
        <div className="settings-group-title">Privacidad</div>
        <button className="btn btn-secondary" style={{ width: '100%', color: 'var(--error)' }} onClick={() => {
          if (confirm('¿Borrar todo el historial?')) {
            localStorage.clear()
            window.location.reload()
          }
        }}>
          Borrar Historial y Datos Locales
        </button>
      </div>
    </div>
  )
}
