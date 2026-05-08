import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { t, getLocale, setLocale, availableLocales } from '../i18n'
import {
  fetchModels, fetchGatewayConfig, patchGatewayConfig, setActiveModel,
  getProviderMeta, type ModelEntry,
} from '../lib/gateway'

interface LocalPrefs {
  notifications: boolean
}

export function Settings() {
  const { navigate } = useRoute()

  const [models, setModels] = useState<ModelEntry[]>([])
  const [modelsLoading, setModelsLoading] = useState(true)
  const [activeModel, setActiveModelState] = useState<string>('')
  const [temperature, setTemperature] = useState(0.7)
  const [contextSize, setContextSize] = useState(32000)

  const [prefs, setPrefs] = useState<LocalPrefs>({ notifications: true })
  const [saving, setSaving] = useState(false)
  const [savedMsg, setSavedMsg] = useState('')
  const [modelSearch, setModelSearch] = useState('')
  const [showAllModels, setShowAllModels] = useState(false)
  const [providerFilter, setProviderFilter] = useState<string>('all')

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

  return (
    <div style={S.page}>
      <div style={S.header}>
        <div style={S.title}>Configuración</div>
        {savedMsg && <span style={S.savedMsg}>{savedMsg}</span>}
      </div>

      {/* ── Active model banner ── */}
      {activeEntry && (
        <div style={S.activeModelCard}>
          <span style={{ fontSize: 28, filter: 'drop-shadow(0 2px 4px rgba(0,0,0,0.5))' }}>{getProviderMeta(activeEntry.provider).icon}</span>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 11, color: '#a5b4fc', textTransform: 'uppercase', letterSpacing: '0.5px', fontWeight: 700, marginBottom: 2 }}>Modelo activo</div>
            <div style={{ fontWeight: 800, fontSize: 16, color: '#fff', letterSpacing: '-0.2px' }}>{activeEntry.name}</div>
            <div style={{ fontSize: 11, color: '#c7d2fe', marginTop: 2, fontFamily: "'JetBrains Mono', monospace" }}>
              {activeEntry.providerLabel}
              {activeEntry.contextWindow ? ` · ${(activeEntry.contextWindow / 1000).toFixed(0)}K ctx` : ''}
              {activeEntry.reasoning ? ' · 🧠' : ''}
            </div>
          </div>
          {saving && <div style={S.spinner} />}
        </div>
      )}

      {/* ── Model selector ── */}
      <div style={S.sectionHeader}>
        <span style={S.sectionLabel}>MODELO DE IA</span>
        <button style={S.iconBtn} onClick={load}>{modelsLoading ? '↻' : '↻ Actualizar'}</button>
      </div>

      <div style={S.card}>
        <div style={{ padding: 12 }}>
          {modelsLoading ? (
            <div style={S.emptyState}>
              <div style={S.spinnerDark} />
              <span style={{ marginTop: 8 }}>Cargando modelos...</span>
            </div>
          ) : models.length === 0 ? (
            <div style={S.emptyState}>
              <span style={{ fontSize: 24, marginBottom: 8 }}>⚠️</span>
              <span style={{ textAlign: 'center' }}>No se encontraron modelos. Verifica el gateway.</span>
              <button style={{ ...S.actionBtn, width: 'auto', padding: '6px 16px', marginTop: 12 }} onClick={load}>Reintentar</button>
            </div>
          ) : (
            <>
              <input
                style={S.searchInput}
                placeholder="Buscar modelo o provider..."
                value={modelSearch}
                onChange={e => setModelSearch(e.target.value)}
              />

              <div style={S.filterRow}>
                {providers.map(p => {
                  const meta = p === 'all' ? { label: 'Todos', icon: '🌐' } : getProviderMeta(p)
                  const active = providerFilter === p
                  return (
                    <button key={p} onClick={() => setProviderFilter(p)} style={{ ...S.filterChip, ...(active ? S.filterChipActive : {}) }}>
                      <span>{meta.icon}</span>
                      <span>{meta.label}</span>
                    </button>
                  )
                })}
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                {displayedModels.map(m => {
                  const meta = getProviderMeta(m.provider)
                  const isActive = m.id === activeModel
                  return (
                    <button key={m.id} onClick={() => handleSetModel(m.id)} style={{ ...S.modelItem, ...(isActive ? S.modelItemActive : {}) }}>
                      <span style={{ fontSize: 22, width: 28, textAlign: 'center' }}>{meta.icon}</span>
                      <div style={{ flex: 1, textAlign: 'left', minWidth: 0 }}>
                        <div style={{ fontSize: 14, fontWeight: isActive ? 700 : 600, color: isActive ? '#fff' : 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                          {m.name}
                        </div>
                        <div style={{ fontSize: 11, color: isActive ? 'rgba(255,255,255,0.7)' : 'var(--text3)', fontFamily: "'JetBrains Mono', monospace", marginTop: 2 }}>
                          {m.providerLabel} {m.contextWindow ? `· ${(m.contextWindow / 1000).toFixed(0)}K` : ''} {m.reasoning ? '· 🧠' : ''}
                        </div>
                      </div>
                      {isActive && <span style={{ color: '#fff', fontSize: 18, fontWeight: 800 }}>✓</span>}
                    </button>
                  )
                })}

                {filteredModels.length > 12 && (
                  <button style={S.showMoreBtn} onClick={() => setShowAllModels(v => !v)}>
                    {showAllModels ? 'Mostrar menos' : `Ver ${filteredModels.length - 12} modelos más`}
                  </button>
                )}

                {filteredModels.length === 0 && (
                  <div style={{ textAlign: 'center', color: 'var(--text3)', padding: 16, fontSize: 13 }}>
                    Sin resultados para "{modelSearch}"
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      {/* ── AI Parameters ── */}
      <div style={S.sectionLabel}>PARÁMETROS DE IA</div>
      <div style={S.card}>
        <div style={{ padding: 16 }}>
          <div style={S.sliderGroup}>
            <div style={S.sliderHeader}>
              <span>Temperatura</span>
              <span style={S.sliderValue}>{temperature.toFixed(1)}</span>
            </div>
            <input type="range" style={S.slider} min="0" max="2" step="0.1" value={temperature} onChange={e => setTemperature(parseFloat(e.target.value))} />
            <div style={S.sliderLabels}><span>Preciso (0)</span><span>Creativo (2)</span></div>
          </div>

          <div style={S.sliderGroup}>
            <div style={S.sliderHeader}>
              <span>Tokens de contexto</span>
              <span style={S.sliderValue}>{contextSize.toLocaleString()}</span>
            </div>
            <input type="range" style={S.slider} min="4096" max="200000" step="4096" value={contextSize} onChange={e => setContextSize(parseInt(e.target.value))} />
            <div style={S.sliderLabels}><span>4K</span><span>200K</span></div>
          </div>

          <button style={S.primaryBtn} onClick={handleSaveParams} disabled={saving}>
            {saving ? 'Guardando...' : 'Guardar parámetros'}
          </button>
        </div>
      </div>

      {/* ── Preferences ── */}
      <div style={S.sectionLabel}>PREFERENCIAS Y GESTIÓN</div>
      <div style={S.card}>
        <NavRow icon="🔔" title="Notificaciones" desc="Avisos de tareas completadas"
          right={
            <div style={{ ...S.toggle, background: prefs.notifications ? '#6366f1' : 'var(--surface3)' }} onClick={() => setPrefs(p => ({ ...p, notifications: !p.notifications }))}>
              <div style={{ ...S.toggleKnob, transform: prefs.notifications ? 'translateX(18px)' : 'translateX(0)' }} />
            </div>
          } />
        <NavRow icon="💾" title={t('settings_storage')} desc={t('settings_storage_desc')} onClick={() => navigate('/settings/storage')} />
        <NavRow icon="📡" title="Canales" desc="Telegram, WhatsApp, Discord..." onClick={() => navigate('/settings/channels')} />
        <NavRow icon="🔌" title="Plataformas" desc="Gestionar providers de IA" onClick={() => navigate('/settings/platforms')} />
        <NavRow icon="⚡" title="Avanzado" desc="Token API y openclaw.json" onClick={() => navigate('/settings/advanced')} />
        <NavRow icon="🔧" title="Herramientas" desc="tmux, SSH, code-server..." onClick={() => navigate('/settings/tools')} />
        <NavRow icon="🔋" title="Keep Alive" desc="Batería y Phantom Process Killer" onClick={() => navigate('/settings/keepalive')} />
        <NavRow icon="⬆️" title="Actualizaciones" desc="Verificar nuevas versiones" onClick={() => navigate('/settings/updates')} />
        <NavRow icon="ℹ️" title="Acerca de" desc="Versión, licencia, runtime" onClick={() => navigate('/settings/about')} last />
      </div>

      {/* ── Language ── */}
      <div style={S.sectionLabel}>IDIOMA</div>
      <div style={{ display: 'flex', gap: 10 }}>
        {availableLocales.map(loc => {
          const active = getLocale() === loc.code
          return (
            <button key={loc.code} style={{ ...S.langBtn, ...(active ? S.langBtnActive : {}) }}
              onClick={() => setLocale(loc.code)}>
              {loc.label}
            </button>
          )
        })}
      </div>

      {/* ── Danger zone ── */}
      <div style={{ marginTop: 40, marginBottom: 20 }}>
        <button style={S.dangerBtn} onClick={() => {
          if (confirm('¿Borrar todo el historial local?')) { localStorage.clear(); window.location.reload() }
        }}>
          🗑️ Borrar historial y datos locales
        </button>
      </div>
    </div>
  )
}

function NavRow({ icon, title, desc, onClick, right, last }: { icon: string; title: string; desc: string; onClick?: () => void; right?: React.ReactNode; last?: boolean }) {
  return (
    <div onClick={onClick} style={{ ...S.navRow, borderBottom: last ? 'none' : '1px solid var(--border)', cursor: onClick ? 'pointer' : 'default' }}>
      <div style={S.navIcon}>{icon}</div>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)' }}>{title}</span>
        <span style={{ fontSize: 11, color: 'var(--text3)' }}>{desc}</span>
      </div>
      {right ? right : <span style={{ color: 'var(--text4)', fontSize: 18 }}>›</span>}
    </div>
  )
}

const S: Record<string, React.CSSProperties> = {
  page: { padding: '12px 14px 32px', maxWidth: 600, margin: '0 auto', overflowY: 'auto' },
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20, paddingTop: 8 },
  title: { fontSize: 22, fontWeight: 800, color: 'var(--text)' },
  savedMsg: { fontSize: 12, fontWeight: 700, color: '#4ade80', background: 'rgba(74,222,128,0.1)', padding: '4px 10px', borderRadius: 12 },
  
  activeModelCard: { background: 'linear-gradient(135deg, #4f46e5, #7c3aed)', borderRadius: 'var(--r-xl)', padding: '16px 20px', display: 'flex', alignItems: 'center', gap: 16, marginBottom: 24, boxShadow: '0 8px 24px rgba(99,102,241,0.25)' },
  spinner: { width: 20, height: 20, border: '2px solid rgba(255,255,255,0.3)', borderTopColor: '#fff', borderRadius: '50%', animation: 'spin 0.8s linear infinite' },
  spinnerDark: { width: 20, height: 20, border: '2px solid var(--border)', borderTopColor: 'var(--purple)', borderRadius: '50%', animation: 'spin 0.8s linear infinite' },
  
  sectionHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10, marginTop: 24 },
  sectionLabel: { fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', color: 'var(--text3)', marginBottom: 10, marginTop: 24, paddingLeft: 2, textTransform: 'uppercase' },
  iconBtn: { background: 'transparent', border: 'none', color: 'var(--purple)', fontSize: 11, fontWeight: 700, cursor: 'pointer' },
  
  card: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-xl)', overflow: 'hidden', boxShadow: 'var(--sh-inset)' },
  
  searchInput: { width: '100%', background: 'rgba(0,0,0,0.2)', border: '1px solid var(--border)', borderRadius: 12, padding: '10px 14px', color: 'var(--text)', fontSize: 13, marginBottom: 12, outline: 'none' },
  
  filterRow: { display: 'flex', gap: 6, overflowX: 'auto', paddingBottom: 8, marginBottom: 12 },
  filterChip: { display: 'flex', alignItems: 'center', gap: 6, padding: '6px 14px', borderRadius: 20, border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text2)', fontSize: 12, fontWeight: 600, cursor: 'pointer', whiteSpace: 'nowrap', flexShrink: 0 },
  filterChipActive: { background: 'rgba(99,102,241,0.15)', borderColor: 'rgba(99,102,241,0.3)', color: '#a5b4fc' },
  
  modelItem: { width: '100%', display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px', borderRadius: 12, border: '1px solid var(--border)', background: 'transparent', cursor: 'pointer', transition: 'all 0.15s' },
  modelItemActive: { background: 'linear-gradient(to right, rgba(99,102,241,0.2), rgba(139,92,246,0.1))', borderColor: '#6366f1' },
  
  showMoreBtn: { width: '100%', padding: '10px', background: 'var(--surface2)', border: '1px dashed var(--border)', color: 'var(--text2)', borderRadius: 12, fontSize: 12, fontWeight: 600, cursor: 'pointer', marginTop: 4 },
  
  emptyState: { padding: 32, display: 'flex', flexDirection: 'column', alignItems: 'center', color: 'var(--text3)', fontSize: 13 },
  
  sliderGroup: { marginBottom: 20 },
  sliderHeader: { display: 'flex', justifyContent: 'space-between', marginBottom: 8, fontSize: 13, fontWeight: 600, color: 'var(--text)' },
  sliderValue: { color: '#a5b4fc', fontFamily: "'JetBrains Mono', monospace" },
  slider: { width: '100%', height: 4, borderRadius: 2, background: 'var(--surface3)', outline: 'none', appearance: 'none' },
  sliderLabels: { display: 'flex', justifyContent: 'space-between', fontSize: 10, color: 'var(--text4)', marginTop: 6 },
  
  primaryBtn: { width: '100%', background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', border: 'none', color: '#fff', padding: '12px', borderRadius: 12, fontSize: 14, fontWeight: 700, cursor: 'pointer', boxShadow: '0 4px 12px rgba(99,102,241,0.3)' },
  actionBtn: { width: '100%', background: 'var(--surface2)', border: '1px solid var(--border)', color: 'var(--text)', padding: '12px', borderRadius: 12, fontSize: 13, fontWeight: 600, cursor: 'pointer' },
  dangerBtn: { width: '100%', background: 'rgba(248,113,113,0.1)', border: '1px solid rgba(248,113,113,0.2)', color: '#fca5a5', padding: '14px', borderRadius: 12, fontSize: 13, fontWeight: 700, cursor: 'pointer' },
  
  navRow: { display: 'flex', alignItems: 'center', gap: 14, padding: '14px 16px', background: 'transparent' },
  navIcon: { width: 36, height: 36, borderRadius: 10, background: 'rgba(255,255,255,0.04)', border: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 16 },
  
  toggle: { width: 44, height: 24, borderRadius: 12, position: 'relative', cursor: 'pointer', transition: 'background 0.2s' },
  toggleKnob: { position: 'absolute', top: 2, left: 2, width: 20, height: 20, borderRadius: '50%', background: '#fff', transition: 'transform 0.2s', boxShadow: '0 2px 4px rgba(0,0,0,0.2)' },
  
  langBtn: { flex: 1, padding: '10px', borderRadius: 12, background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text2)', fontSize: 13, fontWeight: 600, cursor: 'pointer', textAlign: 'center' },
  langBtnActive: { background: 'rgba(99,102,241,0.15)', borderColor: '#6366f1', color: '#a5b4fc' },
}
