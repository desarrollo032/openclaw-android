import { useState, useCallback, useEffect } from 'react'
import { bridge } from '../lib/bridge'
import { CheckSquare, Package, Wrench, AlertCircle, RefreshCw, Play } from 'lucide-react'

interface AlpineStatus {
  alpine: boolean       // Alpine rootfs installed
  node: boolean         // Node.js installed inside Alpine
  openclaw: boolean     // openclaw npm package installed
  onboard: boolean      // onboard completed
}

export function InstallationCard() {
  const [status, setStatus] = useState<AlpineStatus | null>(null)
  const [checking, setChecking] = useState(false)
  const [installing, setInstalling] = useState(false)

  const check = useCallback(() => {
    if (!bridge.isAvailable()) return
    setChecking(true)
    try {
      const raw = bridge.callJson<{ bootstrapInstalled?: boolean; alpineReady?: boolean; onboardComplete?: boolean }>('getSetupStatus')
      if (raw) {
        setStatus({
          alpine: raw.bootstrapInstalled ?? false,
          node: raw.alpineReady ?? false,
          openclaw: raw.alpineReady ?? false,
          onboard: raw.onboardComplete ?? false,
        })
      }
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
      const raw = bridge.callJson<{ bootstrapInstalled?: boolean; alpineReady?: boolean }>('getSetupStatus')
      if (raw) {
        setStatus({
          alpine: raw.bootstrapInstalled ?? false,
          node: raw.alpineReady ?? false,
          openclaw: raw.alpineReady ?? false,
          onboard: false,
        })
        if (raw.bootstrapInstalled && raw.alpineReady) {
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
    { key: 'alpine' as const,   icon: Package,    label: 'Alpine Linux',    done: status?.alpine },
    { key: 'node' as const,     icon: Package,    label: 'Node.js',         done: status?.node },
    { key: 'openclaw' as const, icon: Wrench,     label: 'OpenClaw CLI',    done: status?.openclaw },
    { key: 'onboard' as const,  icon: CheckSquare, label: 'Onboard',        done: status?.onboard },
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
                  : status
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

      {status ? (
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
          Instalar componentes
        </button>
      )}
      {installing && (
        <div className="flex items-center justify-center gap-2 mt-3 text-xs text-accent">
          <RefreshCw size={12} className="animate-spin" />
          Instalando componentes...
        </div>
      )}
      {allDone && status && (
        <div className="flex items-center justify-center gap-1.5 mt-3 text-xs text-green">
          <CheckSquare size={12} />
          Todos los componentes instalados
        </div>
      )}
    </div>
  )
}
