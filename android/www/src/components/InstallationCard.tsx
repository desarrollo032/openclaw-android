import { useState, useCallback, useEffect } from 'react'
import { bridge } from '../lib/bridge'
import { CheckSquare, Package, Wrench, Rocket, AlertCircle, RefreshCw, Play } from 'lucide-react'

interface AssetStatus {
  bootstrap: boolean
  payload: boolean
  platform: boolean
  tools: boolean
}

export function InstallationCard() {
  const [assetStatus, setAssetStatus] = useState<AssetStatus | null>(null)
  const [checking, setChecking] = useState(false)
  const [installing, setInstalling] = useState(false)

  const check = useCallback(() => {
    if (!bridge.isAvailable()) return
    setChecking(true)
    try {
      const raw = bridge.callJson<AssetStatus>('getAssetStatus')
      if (raw) setAssetStatus(raw)
    } catch { /* */ }
    setChecking(false)
  }, [])

  useEffect(() => { check() }, [])

  const startInstall = useCallback(() => {
    if (!bridge.isAvailable()) return
    setInstalling(true)
    bridge.call('startSetup')
    // Poll para detectar cuando termina
    const id = setInterval(() => {
      const raw = bridge.callJson<AssetStatus>('getAssetStatus')
      if (raw) {
        setAssetStatus(raw)
        if (raw.bootstrap && raw.payload && raw.platform && raw.tools) {
          setInstalling(false)
          clearInterval(id)
        }
      }
    }, 2000)
    // Timeout de seguridad (5 min)
    setTimeout(() => { clearInterval(id); setInstalling(false) }, 300_000)
  }, [])

  if (!bridge.isAvailable()) return null

  const steps = [
    { key: 'bootstrap' as const, icon: Package, label: 'Bootstrap', done: assetStatus?.bootstrap },
    { key: 'payload' as const, icon: Rocket, label: 'Payload', done: assetStatus?.payload },
    { key: 'platform' as const, icon: Wrench, label: 'Plataforma', done: assetStatus?.platform },
    { key: 'tools' as const, icon: CheckSquare, label: 'Herramientas', done: assetStatus?.tools },
  ]

  const allDone = steps.every(s => s.done)
  const somePending = !allDone

  return (
    <div className="card p-4">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="w-9 h-9 rounded-xl bg-accent-soft flex items-center justify-center">
            <Package size={17} className="text-accent" />
          </div>
          <div>
            <div className="text-sm font-semibold text-text-primary">Instalación</div>
            <div className="text-[10px] text-text-muted mt-0.5">
              {installing
                ? 'Instalando...'
                : allDone
                  ? 'Completada'
                  : assetStatus
                    ? `${steps.filter(s => s.done).length}/${steps.length}`
                    : 'Verificando...'}
            </div>
          </div>
        </div>
        <button onClick={check}
          className="p-2 rounded-xl text-text-muted hover:text-text-primary hover:bg-glass-bg transition-all">
          <RefreshCw size={13} className={checking ? 'animate-spin' : ''} />
        </button>
      </div>

      {assetStatus ? (
        <div className="space-y-1.5">
          {steps.map(step => (
            <div key={step.key} className="flex items-center gap-2.5 text-xs">
              <div className={`w-5 h-5 rounded-md flex items-center justify-center ${
                step.done ? 'bg-green-soft' : 'bg-glass-bg'
              }`}>
                {step.done ? (
                  <CheckSquare size={11} className="text-green" />
                ) : (
                  <AlertCircle size={11} className="text-text-dim" />
                )}
              </div>
              <span className={step.done ? 'text-text-secondary' : 'text-text-muted'}>{step.label}</span>
            </div>
          ))}
        </div>
      ) : (
        <div className="flex items-center justify-center py-3">
          <div className="flex items-center gap-2 text-xs text-text-dim">
            <RefreshCw size={12} className="animate-spin" />
            Verificando estado...
          </div>
        </div>
      )}

      {/* Botón Iniciar instalación */}
      {somePending && !installing && (
        <button
          onClick={startInstall}
          className="btn btn-primary text-xs w-full mt-3 px-4 py-2"
        >
          <Play size={13} />
          Iniciar instalación
        </button>
      )}
      {installing && (
        <div className="flex items-center justify-center gap-2 mt-3 text-xs text-accent">
          <RefreshCw size={12} className="animate-spin" />
          Instalando componentes...
        </div>
      )}
      {allDone && assetStatus && (
        <div className="flex items-center justify-center gap-1.5 mt-3 text-xs text-green">
          <CheckSquare size={12} />
          Todos los componentes instalados
        </div>
      )}
    </div>
  )
}
