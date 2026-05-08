import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { t, getLocale, setLocale, availableLocales } from '../i18n'
import {
  fetchModels, fetchGatewayConfig, patchGatewayConfig, setActiveModel,
  getProviderMeta, type ModelEntry,
} from '../lib/gateway'

/* ── Types ─────────────────────────────────────────────── */
interface LocalPrefs {
  notifications: boolean
}

/* ── Component ─────────────────────────────────────────── */
export function Settings() {
  const { navigate } = useRoute()

  // Gateway config (from WS/HTTP)
  const [models, setModels] = useState<ModelEntry[]>([])
  const [modelsLoading, setModelsLoading] = useState(true)
  const [activeModel, setActiveModelState] = useState<string>('')
  const [temperature, setTemperature] = useState(0.7)
  const [contextSize, setContextSize] = useState(32000)

  // Local prefs
  const [prefs, setPrefs] = useState<LocalPrefs>({ notifications: true })

  // UI state
  const [saving, setSaving] = useState(false)
  const [savedMsg, setSavedMsg] = useState('')
  const [modelSearch, setModelSearch] = useState('')
  const [showAllModels, setShowAllModels] = useState(false)
  const [providerFilter, setProviderFilter] = useState<string>('all')

  /* ── Load ── */
  const load = useCallback(async () => {
    setModelsLoading(true)
    try {
      const [cfg, mdls] = await Promise.all([
        fetchGatewayConfig(),
        fetchModels(),
      ])

      if (cfg) {
        const primary = cfg.agents?.defaults?.model?.primary ?? ''
        setActiveModelState(primary)
        setTemperature(cfg.agents?.defaults?.temperature ?? 0.7)
        setContextSize((cfg.agents?.defaults as Record<string, unknown>)?.['contextTokens'] as number ?? 32000)
      }

      setModels(mdls)
    } finally {
      setModelsLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  /* ── Save helpers ── */
  const showSaved = (msg = '✓ Guardado') => {
    setSavedMsg(msg)
    setTimeout(() => setSavedMsg(''), 2000)
  }

  const handleSetModel = async (id: string) => {
    setActiveModelState(id)
    setSaving(true)
    const ok = await setActiveModel(id)
    setSaving(false)
    if (ok) showSaved()
  }

  const handleSaveParams = async () => {
    setSaving(true)
    const ok = await patchGatewayConfig({
      agents: { defaults: { temperature, contextTokens: contextSize } as Record<string, unknown> }
    })
    setSaving(false)
    if (ok) showSaved()
  }

  /* ── Derived ── */
  const providers = ['all', ...Array.from(new Set(models.map(m => m.provider))).sort()]

  const filteredModels = models.filter(m => {
    const matchProvider = providerFilter === 'all' || m.provider === providerFilter
    const matchSearch = !modelSearch || m.id.toLowerCase().includes(modelSearch.toLowerCase())
      || m.name.toLowerCase().includes(modelSearch.toLowerCase())
      || m.providerLabel.toLowerCase().includes(modelSearch.toLowerCase())
    return matchProvider && matchSearch
  })

  const displayedModels = showAllModels ? filteredModels : filteredModels.slice(0, 12)
  const activeEntry = models.find(m => m.id === activeModel)

  /* ── Render ── */
  return (
    <div className="page" style={{ paddingBottom: 32 }}>
      <div className="page-header">
        <div className="page-title">Configuración</div>
        {savedMsg && (
          <span style={{ fontSize: 13, color: 'var(--oc-green)', fontWeight: 600, marginLeft: 'auto' }}>
            {savedMsg}
          </span>
        )}
      </div>

      {/* ── Active model banner ── */}
      {activeEntry && (
        <div style={{
          background: 'var(--oc-grad-subtle)',
          border: '1px solid rgba(99,102,241,0.2)',
          borderRadius: 'var(--r-xl)',
          padding: '14px 16px',
          marginBottom: 20,
          display: 'flex',
          alignItems: 'center',
          gap: 12,
        }}>
          <span style={{ fontSize: 24 }}>{getProviderMeta(activeEntry.provider).icon}</span>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 13, color: 'var(--oc-text2)', marginBottom: 2 }}>Modelo activo</div>
            <div style={{ fontWeight: 700, fontSize: 15, color: 'var(--oc-text)' }}>{activeEntry.name}</div>
            <div style={{ fontSize: 11, color: 'var(--oc-text3)' }}>
              {activeEntry.providerLabel}
              {activeEntry.contextWindow ? ` · ${(activeEntry.contextWindow / 1000).toFixed(0)}K ctx` : ''}
              {activeEntry.reasoning ? ' · 🧠 reasoning' : ''}
            </div>
          </div>
          {saving && <div style={{ width: 18, height: 18, border: '2px solid var(--oc-surface3)', borderTopColor: 'var(--oc-purple)', borderRadius: '50%', animation: 'spin 0.7s linear infinite' }} />}
        </div>
      )}

      {/* ── Model selector ── */}
      <div className="settings-group">
        <div className="section-header">
          <span className="settings-group-title">Modelo de IA</span>
          <button className="btn btn-sm btn-ghost" onClick={load} style={{ fontSize: 11 }}>
            {modelsLoading ? '↻' : '↻ Actualizar'}
          </button>
        </div>

        {modelsLoading ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '16px', background: 'var(--oc-surface)', borderRadius: 'var(--r-xl)', border: '1px solid var(--oc-border)' }}>
            <div style={{ width: 20, height: 20, border: '2px solid var(--oc-surface3)', borderTopColor: 'var(--oc-purple)', borderRadius: '50%', animation: 'spin 0.7s linear infinite' }} />
            <span style={{ fontSize: 13, color: 'var(--oc-text2)' }}>Cargando modelos del gateway...</span>
          </div>
        ) : models.length === 0 ? (
          <div style={{ padding: 16, background: 'var(--oc-surface)', borderRadius: 'var(--oc-r-xl)', border: '1px solid var(--oc-border)', textAlign: 'center' }}>
            <div style={{ fontSize: 24, marginBottom: 8 }}>⚠️</div>
            <div style={{ fontSize: 13, color: 'var(--oc-text2)' }}>
              No se encontraron modelos. Verifica que el gateway esté corriendo y tengas al menos un provider configurado.
            </div>
            <button className="btn btn-sm btn-ghost" style={{ marginTop: 12 }} onClick={load}>Reintentar</button>
          </div>
        ) : (
          <>
            {/* Search */}
            <input
              className="form-input"
              placeholder="Buscar modelo o provider..."
              value={modelSearch}
              onChange={e => setModelSearch(e.target.value)}
              style={{ marginBottom: 10 }}
            />

            {/* Provider filter chips */}
            <div style={{ display: 'flex', gap: 6, overflowX: 'auto', paddingBottom: 8, marginBottom: 10 }}>
              {providers.map(p => {
                const meta = p === 'all' ? { label: 'Todos', icon: '🌐' } : getProviderMeta(p)
                const active = providerFilter === p
                return (
                  <button
                    key={p}
                    onClick={() => setProviderFilter(p)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 5,
                      padding: '5px 12px',
                      borderRadius: 20,
                      border: `1px solid ${active ? 'var(--oc-purple)' : 'var(--oc-border)'}`,
                      background: active ? 'var(--oc-purple-dim)' : 'var(--oc-surface)',
                      color: active ? 'var(--oc-purple)' : 'var(--oc-text2)',
                      fontSize: 12, fontWeight: active ? 700 : 500,
                      whiteSpace: 'nowrap', cursor: 'pointer', flexShrink: 0,
                    }}
                  >
                    <span>{meta.icon}</span>
                    <span>{meta.label}</span>
                  </button>
                )
              })}
            </div>

            {/* Model list */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {displayedModels.map(m => {
                const meta = getProviderMeta(m.provider)
                const isActive = m.id === activeModel
                return (
                  <button
                    key={m.id}
                    onClick={() => handleSetModel(m.id)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 12,
                      padding: '12px 14px',
                      borderRadius: 'var(--r-lg)',
                      border: `1px solid ${isActive ? 'var(--oc-purple)' : 'var(--oc-border)'}`,
                      background: isActive ? 'var(--oc-purple-dim)' : 'var(--oc-surface)',
                      cursor: 'pointer', textAlign: 'left',
                      transition: 'all 0.15s',
                    }}
                  >
                    <span style={{ fontSize: 20, flexShrink: 0 }}>{meta.icon}</span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{
                        fontSize: 14, fontWeight: 600,
                        color: isActive ? 'var(--oc-purple)' : 'var(--oc-text)',
                        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                      }}>
                        {m.name}
                      </div>
                      <div style={{ fontSize: 11, color: 'var(--oc-text3)', marginTop: 1 }}>
                        {m.providerLabel}
                        {m.contextWindow ? ` · ${(m.contextWindow / 1000).toFixed(0)}K` : ''}
                        {m.reasoning ? ' · 🧠' : ''}
                      </div>
                    </div>
                    {isActive && <span style={{ color: 'var(--oc-purple)', fontSize: 16, flexShrink: 0 }}>✓</span>}
                  </button>
                )
              })}

              {filteredModels.length > 12 && (
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={() => setShowAllModels(v => !v)}
                  style={{ marginTop: 4 }}
                >
                  {showAllModels
                    ? `Mostrar menos`
                    : `Ver ${filteredModels.length - 12} modelos más`}
                </button>
              )}

              {filteredModels.length === 0 && (
                <div style={{ textAlign: 'center', color: 'var(--oc-text2)', fontSize: 13, padding: 16 }}>
                  Sin resultados para "{modelSearch}"
                </div>
              )}
            </div>

            <div style={{ fontSize: 11, color: 'var(--oc-text3)', marginTop: 8, textAlign: 'right' }}>
              {models.length} modelos disponibles
            </div>
          </>
        )}
      </div>

      {/* ── AI Parameters ── */}
      <div className="settings-group">
        <div className="settings-group-title">Parámetros de IA</div>
        <div className="card">
          <div className="form-group">
            <label className="form-label">
              Temperatura: <strong style={{ color: 'var(--oc-purple)' }}>{temperature.toFixed(1)}</strong>
            </label>
            <input
              type="range" className="slider"
              min="0" max="2" step="0.1"
              value={temperature}
              onChange={e => setTemperature(parseFloat(e.target.value))}
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--oc-text3)', marginTop: 4 }}>
              <span>Preciso (0)</span><span>Creativo (2)</span>
            </div>
          </div>

          <div className="form-group" style={{ marginBottom: 0 }}>
            <label className="form-label">
              Tokens de contexto: <strong style={{ color: 'var(--oc-purple)' }}>{contextSize.toLocaleString()}</strong>
            </label>
            <input
              type="range" className="slider"
              min="4096" max="200000" step="4096"
              value={contextSize}
              onChange={e => setContextSize(parseInt(e.target.value))}
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--oc-text3)', marginTop: 4 }}>
              <span>4K</span><span>200K</span>
            </div>
          </div>

          <button
            className="btn btn-primary btn-full"
            style={{ marginTop: 16 }}
            onClick={handleSaveParams}
            disabled={saving}
          >
            {saving ? 'Guardando...' : 'Guardar parámetros'}
          </button>
        </div>
      </div>

      {/* ── Preferences ── */}
      <div className="settings-group">
        <div className="settings-group-title">Preferencias</div>
        <div className="settings-list">
          <label className="settings-item" style={{ cursor: 'pointer' }}>
            <div className="card-icon">🔔</div>
            <div className="card-content">
              <div className="card-label">Notificaciones</div>
              <div className="card-desc">Avisos de tareas completadas</div>
            </div>
            <label className="toggle">
              <input
                type="checkbox"
                checked={prefs.notifications}
                onChange={e => setPrefs(p => ({ ...p, notifications: e.target.checked }))}
              />
              <span className="toggle-track" />
            </label>
          </label>

          <div className="settings-item" onClick={() => navigate('/settings/storage')}>
            <div className="card-icon">💾</div>
            <div className="card-content">
              <div className="card-label">{t('settings_storage')}</div>
              <div className="card-desc">{t('settings_storage_desc')}</div>
            </div>
            <div className="card-chevron">›</div>
          </div>

          <div className="settings-item" onClick={() => navigate('/settings/channels')}>
            <div className="card-icon">📡</div>
            <div className="card-content">
              <div className="card-label">Canales</div>
              <div className="card-desc">Telegram, WhatsApp, Discord, Slack...</div>
            </div>
            <div className="card-chevron">›</div>
          </div>

          <div className="settings-item" onClick={() => navigate('/settings/platforms')}>
            <div className="card-icon">🔌</div>
            <div className="card-content">
              <div className="card-label">Plataformas</div>
              <div className="card-desc">Gestionar providers y canales</div>
            </div>
            <div className="card-chevron">›</div>
          </div>

          <div className="settings-item" onClick={() => navigate('/settings/tools')}>
            <div className="card-icon">🔧</div>
            <div className="card-content">
              <div className="card-label">Herramientas adicionales</div>
              <div className="card-desc">tmux, SSH, code-server, etc.</div>
            </div>
            <div className="card-chevron">›</div>
          </div>

          <div className="settings-item" onClick={() => navigate('/settings/keepalive')}>
            <div className="card-icon">🔋</div>
            <div className="card-content">
              <div className="card-label">Keep Alive</div>
              <div className="card-desc">Batería y Phantom Process Killer</div>
            </div>
            <div className="card-chevron">›</div>
          </div>

          <div className="settings-item" onClick={() => navigate('/settings/updates')}>
            <div className="card-icon">⬆️</div>
            <div className="card-content">
              <div className="card-label">Actualizaciones</div>
              <div className="card-desc">Verificar nuevas versiones</div>
            </div>
            <div className="card-chevron">›</div>
          </div>

          <div className="settings-item" onClick={() => navigate('/settings/about')}>
            <div className="card-icon">ℹ️</div>
            <div className="card-content">
              <div className="card-label">Acerca de</div>
              <div className="card-desc">Versión, licencia, runtime</div>
            </div>
            <div className="card-chevron">›</div>
          </div>
        </div>
      </div>

      {/* ── Language ── */}
      <div className="settings-group">
        <div className="settings-group-title">Idioma</div>
        <div style={{ display: 'flex', gap: 8 }}>
          {availableLocales.map(loc => (
            <button
              key={loc.code}
              className={`btn ${getLocale() === loc.code ? 'btn-primary' : 'btn-secondary'}`}
              style={{ flex: 1 }}
              onClick={() => { setLocale(loc.code); window.location.reload() }}
            >
              {loc.label}
            </button>
          ))}
        </div>
      </div>

      {/* ── Danger zone ── */}
      <div className="settings-group">
        <div className="settings-group-title">Zona de peligro</div>
        <button
          className="btn btn-danger btn-full"
          onClick={() => {
            if (confirm('¿Borrar todo el historial local?')) {
              localStorage.clear()
              window.location.reload()
            }
          }}
        >
          🗑️ Borrar historial y datos locales
        </button>
      </div>
    </div>
  )
}
