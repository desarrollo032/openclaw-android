import { useState, useEffect, useCallback } from 'react'
import { bridge } from '../lib/bridge'
import { Check, Loader, Terminal as TerminalIcon, Shield } from 'lucide-react'

interface Props { onComplete: () => void }

const STEPS = [
  { key: 'bootstrap', label: 'Bootstrap',       icon: Shield },
  { key: 'payload',   label: 'Payload',         icon: Loader },
  { key: 'platform',  label: 'Plataforma',      icon: TerminalIcon },
  { key: 'tools',     label: 'Herramientas',     icon: Check },
  { key: 'done',      label: 'Listo',           icon: Check },
]

export function Setup({ onComplete }: Props) {
  const [stepIdx, setStepIdx] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [platforms, setPlatforms] = useState<{ id: string; name: string; installed: boolean }[]>([])
  const [selectedTools, setSelectedTools] = useState<string[]>([])
  const [animKey, setAnimKey] = useState(0)

  const nextAnim = () => setAnimKey(k => k + 1)

  const current = STEPS[stepIdx]

  useEffect(() => {
    if (!bridge.isAvailable()) {
      setLoading(false)
      return
    }
    if (stepIdx === 0) {
      // bootstrap check
      bridge.call('checkBootstrap')
      const id = setInterval(() => {
        const s = bridge.callJson<{ installed?: boolean; installing?: boolean; error?: string }>('getBootstrapStatus')
        if (s?.error) { setError(s.error); setLoading(false); clearInterval(id); return }
        if (s?.installed) { setLoading(false); setStepIdx(1); nextAnim(); clearInterval(id) }
      }, 600)
      return () => clearInterval(id)
    }
    if (stepIdx === 1) {
      // payload
      bridge.call('checkPayload')
      const id = setInterval(() => {
        const s = bridge.callJson<{ installed?: boolean }>('getPayloadStatus')
        if (s?.installed) { setLoading(false); setStepIdx(2); nextAnim(); clearInterval(id) }
      }, 600)
      setLoading(true)
      return () => clearInterval(id)
    }
    if (stepIdx === 2) {
      // platforms
      setLoading(false)
      setPlatforms([
        { id: 'openclaw', name: 'OpenClaw Core', installed: false },
        { id: 'code', name: 'Code Server', installed: false },
        { id: 'browser', name: 'Chromium Browser', installed: false },
      ])
    }
    if (stepIdx === 3) {
      // tools
      setLoading(false)
      setSelectedTools([])
    }
  }, [stepIdx])

  const installPlatform = useCallback((id: string) => {
    bridge.call('installPlatform', id)
    setPlatforms(prev => prev.map(p => p.id === id ? { ...p, installed: true } : p))
  }, [])

  const toggleTool = (t: string) => {
    setSelectedTools(prev => prev.includes(t) ? prev.filter(x => x !== t) : [...prev, t])
  }

  const installSelectedTools = () => {
    selectedTools.forEach(t => bridge.call('installTool', t))
    setStepIdx(4)
    nextAnim()
    setTimeout(() => onComplete(), 1500)
  }

  const installAllPlatforms = () => {
    platforms.forEach(p => bridge.call('installPlatform', p.id))
    setPlatforms(prev => prev.map(p => ({ ...p, installed: true })))
    setStepIdx(3)
    nextAnim()
  }

  return (
    <div className="min-h-screen bg-bg flex flex-col items-center justify-center p-4">
      <div className="w-full max-w-md mx-auto animate-fade-in" key={animKey}>
        {/* ── Logo / Header ── */}
        <div className="text-center mb-8">
          <div className="relative w-16 h-16 mx-auto mb-4">
            <div className="absolute inset-0 rounded-2xl bg-accent-soft animate-pulse" />
            <div className="absolute inset-0 rounded-2xl flex items-center justify-center">
              <Shield size={32} className="text-accent" />
            </div>
          </div>
          <h1 className="text-xl font-bold text-text-primary tracking-tight">OpenClaw</h1>
          <p className="text-[13px] text-text-muted mt-1">Configuración inicial</p>
        </div>

        {/* ── Step progress ── */}
        <div className="flex items-center justify-center gap-3 mb-8">
          {STEPS.map((s, i) => (
            <div key={s.key} className="flex items-center gap-3">
              <div className={`step-dot ${i <= stepIdx ? i < stepIdx ? 'done' : 'active' : ''}`} />
              {i < STEPS.length - 1 && (
                <div className={`w-8 h-[2px] rounded-full ${i < stepIdx ? 'bg-accent-light' : 'bg-glass-bg'}`} />
              )}
            </div>
          ))}
        </div>

        {/* ── Step content ── */}
        <div className="card p-6">
          <div className="text-center mb-6">
            <div className="w-12 h-12 rounded-xl bg-accent-soft flex items-center justify-center mx-auto mb-3">
              <current.icon size={24} className="text-accent" />
            </div>
            <h3 className="text-base font-bold text-text-primary">{current.label}</h3>
            <p className="text-xs text-text-muted mt-1">
              {stepIdx === 0 && 'Verificando instalación base...'}
              {stepIdx === 1 && 'Cargando payload del sistema...'}
              {stepIdx === 2 && 'Selecciona las plataformas a instalar'}
              {stepIdx === 3 && 'Elige herramientas adicionales'}
              {stepIdx === 4 && '¡Todo listo! Redirigiendo...'}
            </p>
          </div>

          {error && (
            <div className="px-4 py-3 rounded-xl bg-red-soft border border-red/10 text-xs text-red mb-4">{error}</div>
          )}

          {/* Step: bootstrap/payload loading */}
          {(stepIdx === 0 || stepIdx === 1) && loading && (
            <div className="flex items-center justify-center py-4">
              <div className="flex items-center gap-3">
                <div className="w-5 h-5 rounded-full border-2 border-accent/30 border-t-accent animate-spin" />
                <span className="text-xs text-text-muted">
                  {stepIdx === 0 ? 'Verificando bootstrap...' : 'Cargando payload...'}
                </span>
              </div>
            </div>
          )}

          {/* Step: Platforms */}
          {stepIdx === 2 && (
            <div className="space-y-2">
              {platforms.map(p => (
                <div key={p.id}
                  className="flex items-center justify-between px-4 py-3 rounded-xl bg-glass-bg border border-glass-border">
                  <div>
                    <div className="text-sm font-semibold text-text-primary">{p.name}</div>
                    <div className="text-[11px] text-text-muted">{p.id}</div>
                  </div>
                  {p.installed ? (
                    <Check size={18} className="text-green" />
                  ) : (
                    <button onClick={() => installPlatform(p.id)}
                      className="btn btn-primary text-[11px] px-3 py-1.5">
                      Instalar
                    </button>
                  )}
                </div>
              ))}
              {platforms.every(p => p.installed) && (
                <button onClick={installAllPlatforms}
                  className="w-full btn btn-primary text-xs mt-4">
                  Continuar
                </button>
              )}
            </div>
          )}

          {/* Step: Tools */}
          {stepIdx === 3 && (
            <div className="space-y-2">
              {['code-server', 'glibc-tools', 'chromium'].map(t => (
                <button key={t} onClick={() => toggleTool(t)}
                  className={`w-full flex items-center justify-between px-4 py-3 rounded-xl border transition-all ${
                    selectedTools.includes(t)
                      ? 'bg-accent-soft border-accent/20'
                      : 'bg-glass-bg border border-glass-border'
                  }`}>
                  <span className="text-sm font-medium text-text-primary">{t}</span>
                  <div className={`w-5 h-5 rounded-md border-2 flex items-center justify-center transition-all ${
                    selectedTools.includes(t)
                      ? 'bg-accent border-accent'
                      : 'border border-glass-border'
                  }`}>
                    {selectedTools.includes(t) && <Check size={12} className="text-white" />}
                  </div>
                </button>
              ))}
              <button onClick={installSelectedTools}
                disabled={selectedTools.length === 0}
                className={`w-full btn text-xs mt-4 ${
                  selectedTools.length > 0 ? 'btn-primary' : 'bg-glass-bg text-text-dim cursor-not-allowed'
                }`}>
                {selectedTools.length > 0 ? `Instalar (${selectedTools.length}) y finalizar` : 'Selecciona herramientas'}
              </button>
            </div>
          )}

          {/* Step: Done */}
          {stepIdx === 4 && (
            <div className="text-center py-6">
              <div className="w-16 h-16 rounded-2xl bg-green-soft flex items-center justify-center mx-auto mb-4 animate-scale-in">
                <Check size={32} className="text-green" />
              </div>
              <p className="text-sm text-text-secondary">OpenClaw está listo para usar</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
