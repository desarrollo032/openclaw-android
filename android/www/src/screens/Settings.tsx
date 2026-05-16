import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { getLocale, setLocale, availableLocales, t } from '../i18n'
import { fetchModels, fetchGatewayConfig, patchGatewayConfig, getProviderMeta, type ModelEntry } from '../lib/gateway'
import { useGatewayStatus } from '../hooks/useGatewayStatus'
import { bridge, callJson } from '../lib/bridge'
import { RefreshCw, Search, Check, Thermometer, Braces, AlertCircle, Settings2, Wrench, Battery, Package, Info, Globe, Trash2, ChevronRight, Sliders, Cpu, HardDrive, Bell, Database, Settings as SettingsIcon, Shield } from 'lucide-react'
import { SectionHeader } from '../components/SectionHeader'

export function Settings() {
  const { navigate } = useRoute()
  const [models, setModels] = useState<ModelEntry[]>([])
  const [search, setSearch] = useState('')
  const [selectedProvider, setSelectedProvider] = useState('all')
  const [temperature, setTemperature] = useState(0.7)
  const [contextTokens, setContextTokens] = useState(4096)
  const [activeModelId, setActiveModelId] = useState('')
  const [loading, setLoading] = useState(true)
  const [saved, setSaved] = useState('')
  const [locale, setLocaleState] = useState(getLocale())
  const { health } = useGatewayStatus()
  const [alpineReady, setAlpineReady] = useState<boolean | null>(null)
  const [alpineAvailable, setAlpineAvailable] = useState(false)
  const [confirmReinstall, setConfirmReinstall] = useState(false)
  const [reinstalling, setReinstalling] = useState(false)

  useEffect(() => {
    if (!bridge.isAvailable()) return
    const s = callJson<{ bootstrapInstalled: boolean; alpineReady: boolean; alpineAvailable: boolean }>('getSetupStatus')
    if (s) {
      setAlpineReady(s.alpineReady)
      setAlpineAvailable(s.alpineAvailable)
    }
  }, [])

  const handleReinstall = () => {
    if (!confirmReinstall) {
      setConfirmReinstall(true)
      setTimeout(() => setConfirmReinstall(false), 4000)
      return
    }
    setConfirmReinstall(false)
    setReinstalling(true)
    bridge.call('reinstallAlpine')
  }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [m, cfg] = await Promise.all([fetchModels(), fetchGatewayConfig()])
      setModels(m ?? [])
      setActiveModelId(cfg?.agents?.defaults?.model?.primary ?? '')
      setTemperature(cfg?.agents?.defaults?.temperature ?? 0.7)
      setContextTokens(cfg?.agents?.defaults?.contextTokens ?? 4096)
    } catch { /* */ }
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  const saveConfig = async (patch: Record<string, unknown>) => {
    const ok = await patchGatewayConfig(patch as never)
    if (ok) { setSaved('✓ Guardado'); setTimeout(() => setSaved(''), 2000) }
  }

  const selectModel = async (id: string) => {
    setActiveModelId(id)
    await saveConfig({ agents: { defaults: { model: { primary: id } } } } as Record<string, unknown>)
  }

  const providers = models.reduce((acc: { id: string; label: string; icon: string; modelCount: number }[], m) => {
    if (!acc.find(p => p.id === m.provider)) {
      const meta = getProviderMeta(m.provider)
      acc.push({ id: m.provider, label: meta.label, icon: meta.icon, modelCount: models.filter(x => x.provider === m.provider).length })
    }
    return acc
  }, [])

  const filteredModels = models.filter(m =>
    (selectedProvider === 'all' || m.provider === selectedProvider) &&
    (m.name.toLowerCase().includes(search.toLowerCase()) || m.id.toLowerCase().includes(search.toLowerCase()))
  )

  function SettingRow({ icon: Icon, label, desc, onClick }: { icon: React.ElementType; label: string; desc: string; onClick: () => void }) {
    return (
      <button onClick={onClick}
        className="w-full flex items-center gap-3.5 px-4 py-3.5 rounded-xl hover:bg-glass-bg transition-all text-left group">
        <div className="w-9 h-9 rounded-xl bg-glass-bg flex items-center justify-center group-hover:bg-accent-soft transition-colors">
          <Icon size={16} className="text-text-secondary group-hover:text-accent transition-colors" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-sm font-semibold text-text-primary group-hover:text-accent transition-colors">{label}</div>
          <div className="text-[11px] text-text-muted mt-0.5">{desc}</div>
        </div>
        <ChevronRight size={14} className="text-text-dim group-hover:text-text-secondary transition-colors" />
      </button>
    )
  }

  const gatewayVer = health ? `${(health as unknown as Record<string, unknown>)?.version ?? '—'}` : '—'

  return (
    <div className="page-container flex flex-col gap-5 pt-6 pb-4 animate-fade-in">
      <div className="flex items-center gap-3.5">
        <div className="w-11 h-11 rounded-xl bg-accent-soft flex items-center justify-center shrink-0 shadow-sm">
          <SettingsIcon size={22} className="text-accent" />
        </div>
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-text-primary tracking-tight">{t('settings_title')}</h1>
          <p className="text-[12px] sm:text-[13px] text-text-muted mt-0.5">{t('settings_subtitle')}</p>
        </div>
      </div>

      {/* ── Active Model ── */}
      <div className="card p-4">
        <div className="flex items-center gap-3 mb-3">
          <div className="w-10 h-10 rounded-xl bg-accent-soft flex items-center justify-center">
            <Cpu size={20} className="text-accent" />
          </div>
          <div className="flex-1 min-w-0">
            <div className="text-sm font-semibold text-text-primary truncate">{activeModelId || 'Sin modelo activo'}</div>
            <div className="text-[11px] text-text-muted mt-0.5">
              {loading ? 'Cargando...' : `${models.length} modelos disponibles`}
            </div>
          </div>
          <button onClick={load} className="p-2 rounded-xl text-text-muted hover:text-text-primary hover:bg-glass-bg transition-all">
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          </button>
        </div>

        {/* ── Model search & filter ── */}
        <div className="flex items-center gap-2 mb-3">
          <div className="flex-1 flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-input-bg border border-glass-border focus-within:border-accent/20 transition-all">
            <Search size={13} className="text-text-dim" />
            <input className="flex-1 bg-transparent border-none outline-none text-xs text-text-primary placeholder-text-muted"
              placeholder="Buscar modelo..." value={search} onChange={e => setSearch(e.target.value)} />
            {search && <button onClick={() => setSearch('')} className="text-text-dim hover:text-text-primary text-xs">✕</button>}
          </div>
          {saved && <span className="text-[11px] text-green font-medium animate-fade-in">{saved}</span>}
        </div>

        {/* ── Provider filter chips ── */}
        <div className="flex gap-1.5 overflow-x-auto scrollbar-none pb-1 mb-2">
          <button onClick={() => setSelectedProvider('all')}
            className={`chip text-[10px] shrink-0 ${selectedProvider === 'all' ? 'active' : ''}`}>Todos</button>
          {providers.map(p => (
            <button key={p.id} onClick={() => setSelectedProvider(p.id)}
              className={`chip text-[10px] shrink-0 flex items-center gap-1.5 ${selectedProvider === p.id ? 'active' : ''}`}>
              <span>{p.icon}</span> {p.label} <span className="opacity-50">({p.modelCount})</span>
            </button>
          ))}
        </div>

        {/* ── Model list ── */}
        <div className="space-y-1 max-h-48 overflow-y-auto">
          {filteredModels.length === 0 && (
            <div className="py-6 text-center text-xs text-text-muted">Sin resultados</div>
          )}
          {filteredModels.map(m => (
            <button key={m.id} onClick={() => selectModel(m.id)}
              className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all ${
                activeModelId === m.id ? 'bg-accent-soft border border-accent/20' : 'hover:bg-glass-bg border border-transparent'
              }`}>
              <span className="text-sm">{getProviderMeta(m.provider).icon}</span>
              <div className="flex-1 text-left min-w-0">
                <div className="text-xs font-semibold text-text-primary truncate">{m.name}</div>
                <div className="text-[10px] text-text-muted truncate">{m.id}</div>
              </div>
              {activeModelId === m.id && <Check size={13} className="text-accent shrink-0" />}
            </button>
          ))}
        </div>

        {/* ── AI Parameters ── */}
        <div className="mt-4 pt-4 border-t border-glass-border space-y-4">
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <div className="flex items-center gap-1.5">
                <Thermometer size={11} className="text-text-muted" />
                <span className="text-[10px] font-semibold text-text-muted tracking-wider uppercase">Temperatura</span>
              </div>
              <span className="text-[11px] font-mono text-accent">{temperature.toFixed(1)}</span>
            </div>
            <input type="range" min={0} max={2} step={0.1} value={temperature}
              onChange={e => { const v = parseFloat(e.target.value); setTemperature(v); saveConfig({ agents: { defaults: { temperature: v } } } as Record<string, unknown>) }}
              className="w-full h-1.5 rounded-full appearance-none bg-glass-bg accent-accent cursor-pointer" />
            <div className="flex justify-between text-[9px] text-text-dim mt-1">
              <span>Preciso</span>
              <span>Creativo</span>
            </div>
          </div>
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <div className="flex items-center gap-1.5">
                <Braces size={11} className="text-text-muted" />
                <span className="text-[10px] font-semibold text-text-muted tracking-wider uppercase">Contexto máximo</span>
              </div>
              <span className="text-[11px] font-mono text-accent">{contextTokens.toLocaleString()}</span>
            </div>
            <input type="range" min={1024} max={32768} step={1024} value={contextTokens}
              onChange={e => { const v = parseInt(e.target.value); setContextTokens(v); saveConfig({ agents: { defaults: { contextTokens: v } } } as Record<string, unknown>) }}
              className="w-full h-1.5 rounded-full appearance-none bg-glass-bg accent-accent cursor-pointer" />
          </div>
        </div>
      </div>

      {/* ── Settings sections ── */}
      <SectionHeader icon={Bell} title="Notificaciones" />
      <div className="card divide-y divide-glass-border overflow-hidden">
        <SettingRow icon={Bell} label="Canales" desc="Telegram, Discord, WhatsApp" onClick={() => navigate('/settings/channels')} />
      </div>

      <SectionHeader icon={HardDrive} title="Almacenamiento" />
      <div className="card divide-y divide-glass-border overflow-hidden">
        <SettingRow icon={Database} label="Almacenamiento" desc="Gestión de espacio y caché" onClick={() => navigate('/settings/storage')} />
      </div>

      <SectionHeader icon={Cpu} title="Sistema" />
      <div className="card divide-y divide-glass-border overflow-hidden">
        <SettingRow icon={Sliders} label="Avanzado" desc="Configuración avanzada del sistema" onClick={() => navigate('/settings/advanced')} />
        <SettingRow icon={Settings2} label="Plataformas" desc="Gestionar plataformas instaladas" onClick={() => navigate('/settings/platforms')} />
        <SettingRow icon={Wrench} label="Herramientas" desc="Gestionar herramientas" onClick={() => navigate('/settings/tools')} />
        <SettingRow icon={Battery} label="Keep Alive" desc="Gestión de batería y procesos" onClick={() => navigate('/settings/keepalive')} />
        <SettingRow icon={Package} label="Actualizaciones" desc="Buscar y aplicar updates" onClick={() => navigate('/settings/updates')} />
      </div>

      <SectionHeader icon={Globe} title="Idioma" />
      <div className="card p-1 flex gap-1">
        {availableLocales.map(l => (
          <button key={l.code} onClick={() => { setLocale(l.code); setLocaleState(l.code) }}
            className={`flex-1 py-2.5 rounded-xl text-xs font-semibold transition-all ${
              locale === l.code ? 'bg-accent text-white shadow-sm' : 'text-text-muted hover:text-text-secondary'
            }`}>
            {l.label}
          </button>
        ))}
      </div>

      <SectionHeader icon={Info} title="Información" />
      <div className="card divide-y divide-glass-border overflow-hidden">
        <SettingRow icon={Info} label="Acerca de" desc={`Gateway v${gatewayVer}`} onClick={() => navigate('/settings/about')} />
      </div>

      {/* ── Alpine status ── */}
      {alpineReady !== null && (
        <>
          <SectionHeader icon={Shield} title="Alpine Linux" />
          <div className="card p-4">
            <div className="flex items-center gap-3">
              <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${alpineReady ? 'bg-green-soft' : 'bg-red-soft'}`}>
                {alpineReady
                  ? <Check size={20} className="text-green" />
                  : <AlertCircle size={20} className="text-red" />}
              </div>
              <div className="flex-1 min-w-0">
                <div className="text-sm font-semibold text-text-primary">
                  {alpineReady ? 'Alpine instalado ✓' : 'Alpine no instalado'}
                </div>
                <div className="text-[11px] text-text-muted mt-0.5">
                  {alpineReady
                    ? 'Node.js + OpenClaw listos'
                    : alpineAvailable
                      ? 'La instalación falló o no se ha completado'
                      : 'libproot.so no disponible — APK incorrecta'
                  }
                </div>
              </div>
            </div>
          </div>
        </>
      )}

      {/* ── Danger zone ── */}
      <div className="card p-4 border-red/10">
        <div className="flex items-center gap-2.5 mb-2">
          <AlertCircle size={14} className="text-red" />
          <span className="text-[10px] font-semibold text-text-muted tracking-widest uppercase">Zona de peligro</span>
        </div>
        <p className="text-xs text-text-muted mb-3 leading-relaxed">
          Estas acciones son destructivas y no se pueden deshacer.
        </p>

        {/* ── Reinstalar Alpine ── */}
        {(alpineReady === false || reinstalling) && (
          <div className="mb-3 pb-3 border-b border-glass-border">
            <p className="text-xs text-text-muted mb-3 leading-relaxed">
              La instalación de Alpine no se completó correctamente.
              Puedes reintentar la descarga e instalación desde cero.
            </p>
            <button
              onClick={handleReinstall}
              disabled={reinstalling}
              className="btn btn-danger w-full text-xs py-2.5"
            >
              {reinstalling ? (
                <><RefreshCw size={13} className="animate-spin" /> Reinstalando...</>
              ) : confirmReinstall ? (
                <><AlertCircle size={13} /> ¿Confirmar reinstalación?</>
              ) : (
                <><RefreshCw size={13} /> Reinstalar Alpine</>
              )}
            </button>
          </div>
        )}

        {/* ── Reinstalar Alpine (ya instalado) ── */}
        {alpineReady && !reinstalling && (
          <div className="mb-3 pb-3 border-b border-glass-border">
            <p className="text-xs text-text-muted mb-3 leading-relaxed">
              Reinstala Alpine Linux desde cero. Útil si algo está corrupto
              o quieres empezar de nuevo.
            </p>
            <button
              onClick={handleReinstall}
              className="btn btn-danger w-full text-xs py-2.5 opacity-70 hover:opacity-100"
            >
              {confirmReinstall ? (
                <><AlertCircle size={13} /> ¿Confirmar reinstalación?</>
              ) : (
                <><RefreshCw size={13} /> Reinstalar Alpine</>
              )}
            </button>
          </div>
        )}

        <p className="text-xs text-text-muted mb-3 leading-relaxed">
          Esto eliminará toda la configuración local y recargará la aplicación.
        </p>
        <button onClick={() => { localStorage.clear(); location.reload() }}
          className="btn btn-danger w-full text-xs py-2.5">
          <Trash2 size={13} /> Limpiar todo y reiniciar
        </button>
      </div>
    </div>
  )
}
