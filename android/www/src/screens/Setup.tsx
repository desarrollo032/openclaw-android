import { useState, useEffect, useCallback, useRef } from 'react'
import { bridge, on, off } from '../lib/bridge'
import {
  Check,
  Terminal as TerminalIcon,
  Shield,
  Play,
  Package,
  AlertCircle,
  RefreshCw,
} from 'lucide-react'

interface Props { onComplete: () => void }

const STEPS = [
  { key: 'setup',     label: 'Instalación',  icon: Shield },
  { key: 'platform',  label: 'Plataforma',    icon: TerminalIcon },
  { key: 'done',      label: 'Listo',         icon: Check },
]

interface SetupStatus {
  bootstrapInstalled?: boolean
  platformInstalled?: string
  alpineReady?: boolean
  alpineAvailable?: boolean
  alpineSizeBytes?: number
  alpineSource?: string
  canDownloadRemotely?: boolean
  onboardComplete?: boolean
  freeSpaceMB?: number
  requiredSpaceMB?: number
  hasEnoughSpace?: boolean
}

interface InstallProgress {
  step: number
  totalSteps: number
  percent: number
  extractedMB: number
  totalMB: number
  currentFile: string
  stepName: string
}

export function Setup({ onComplete }: Props) {
  const [stepIdx, setStepIdx] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [platforms, setPlatforms] = useState<{ id: string; name: string; installed: boolean }[]>([])
  const [animKey, setAnimKey] = useState(0)
  const [setupStatus, setSetupStatus] = useState<SetupStatus | null>(null)
  const [installing, setInstalling] = useState(false)
  const [progress, setProgress] = useState<InstallProgress | null>(null)

  const installingRef = useRef(false)
  installingRef.current = installing

  const nextAnim = () => setAnimKey(k => k + 1)
  const current = STEPS[stepIdx]

  const refreshStatus = useCallback((): SetupStatus | null => {
    if (!bridge.isAvailable()) return null
    const s = bridge.callJson<SetupStatus>('getSetupStatus')
    if (s) setSetupStatus(s)
    return s
  }, [])

  // ── Initial setup detection ────────────────────────────────────────────
  useEffect(() => {
    if (!bridge.isAvailable()) {
      setLoading(false)
      return
    }
    if (stepIdx !== 0) return
    const s = refreshStatus()
    setLoading(false)
    if (s?.bootstrapInstalled && s?.alpineReady) {
      // Alpine + openclaw already installed → skip to platform step
      setStepIdx(1)
      nextAnim()
    }
  }, [stepIdx, refreshStatus])

  // ── Step 0 poll: wait for setup to complete ────────────────────────────
  useEffect(() => {
    if (stepIdx !== 0) return
    if (!installing) return
    const id = setInterval(() => {
      const s = refreshStatus()
      if (s?.bootstrapInstalled && s?.alpineReady) {
        setInstalling(false)
        setStepIdx(1)
        nextAnim()
        clearInterval(id)
      }
    }, 1000)
    return () => clearInterval(id)
  }, [stepIdx, installing, refreshStatus])

  // ── Step 1 (platform) init ─────────────────────────────────────────────
  useEffect(() => {
    if (stepIdx === 1) {
      setLoading(false)
      setPlatforms([
        { id: 'openclaw', name: 'OpenClaw Core', installed: false },
        { id: 'code', name: 'Code Server', installed: false },
        { id: 'browser', name: 'Chromium Browser', installed: false },
      ])
    }
  }, [stepIdx])

  // ── Listen to native install events ────────────────────────────────────
  useEffect(() => {
    if (!bridge.isAvailable()) return

    const hProgress = (data: unknown) => {
      const p = data as InstallProgress
      if (p && typeof p.percent === 'number') {
        setProgress(p)
        setInstalling(true)
      }
    }
    const hComplete = () => {
      setInstalling(false)
      setProgress(null)
      setError('')
      refreshStatus()
      // Setup finished → go to platform step
      setStepIdx(1)
      nextAnim()
    }
    const hError = (data: unknown) => {
      const d = data as { error?: string }
      setInstalling(false)
      setError(d?.error ?? 'Error desconocido durante la instalación')
    }

    const r1 = on('onInstallProgress', hProgress)
    const r2 = on('onInstallComplete', hComplete)
    const r3 = on('onInstallError', hError)

    return () => {
      off('onInstallProgress', r1)
      off('onInstallComplete', r2)
      off('onInstallError', r3)
    }
  }, [refreshStatus])

  // ── Install actions ────────────────────────────────────────────────────
  const startInstall = useCallback(() => {
    if (!bridge.isAvailable() || installing) return
    setError('')
    setInstalling(true)
    setProgress({
      step: 1, totalSteps: 2, percent: 0,
      extractedMB: 0, totalMB: 0,
      currentFile: '', stepName: 'Iniciando...',
    })
    bridge.call('startSetup')
  }, [installing])

  const installPlatform = useCallback((id: string) => {
    bridge.call('installPlatform', id)
    setPlatforms(prev => prev.map(p => p.id === id ? { ...p, installed: true } : p))
  }, [])

  const installAllPlatforms = () => {
    platforms.forEach(p => bridge.call('installPlatform', p.id))
    setPlatforms(prev => prev.map(p => ({ ...p, installed: true })))
    setStepIdx(2)
    nextAnim()
    setTimeout(() => onComplete(), 1500)
  }

  // ── Setup step renderer ────────────────────────────────────────────────
  const renderSetupStep = () => {
    const needsSpace = setupStatus && setupStatus.hasEnoughSpace === false
    const isAlreadyInstalled = setupStatus?.bootstrapInstalled && setupStatus?.alpineReady

    if (loading && !setupStatus) {
      return (
        <div className="flex items-center justify-center py-4">
          <div className="flex items-center gap-3">
            <div className="w-5 h-5 rounded-full border-2 border-accent/30 border-t-accent animate-spin" />
            <span className="text-xs text-text-muted">Verificando instalación...</span>
          </div>
        </div>
      )
    }

    return (
      <div className="space-y-3">
        {/* Alpine status card */}
        <div className="rounded-xl bg-glass-bg border border-glass-border p-3">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-accent-soft flex items-center justify-center shrink-0">
              <Package size={15} className="text-accent" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-xs font-semibold text-text-primary">Alpine Linux + Node.js</div>
              <div className="text-[10px] text-text-muted mt-0.5">
              {setupStatus?.bootstrapInstalled
                ? setupStatus?.alpineReady
                  ? 'Instalado ✓'
                  : 'Alpine listo, instalando OpenClaw...'
                : 'Requiere descarga (~10 MB)'}
              </div>
            </div>
            {setupStatus?.bootstrapInstalled && setupStatus?.alpineReady && (
              <div className="w-6 h-6 rounded-full bg-green-soft flex items-center justify-center">
                <Check size={12} className="text-green" />
              </div>
            )}
          </div>
        </div>

        {/* Free-space warning */}
        {needsSpace && (
          <div className="flex items-center gap-2 px-3 py-2 rounded-xl bg-red-soft border border-red/10 text-[11px] text-red">
            <AlertCircle size={12} />
            Espacio insuficiente: {setupStatus?.freeSpaceMB} MB libres,
            se requieren {setupStatus?.requiredSpaceMB} MB.
          </div>
        )}

        {/* Progress / actions */}
        {installing && progress ? (
          <div className="space-y-2">
            <div className="w-full h-2 rounded-full bg-glass-bg overflow-hidden">
              <div
                className="h-full rounded-full bg-accent transition-all duration-300"
                style={{ width: `${Math.min(progress.percent, 100)}%` }}
              />
            </div>
            <div className="text-[10px] text-text-muted text-center">
              {progress.stepName || `Paso ${progress.step}/${progress.totalSteps} · ${Math.round(progress.percent)}%`}
              {progress.currentFile ? ` · ${progress.currentFile}` : ''}
            </div>
          </div>
        ) : (
          <>
            {!isAlreadyInstalled && (
              <button
                onClick={startInstall}
                disabled={installing || needsSpace === true}
                className={`w-full btn text-xs ${needsSpace ? 'bg-glass-bg text-text-dim cursor-not-allowed' : 'btn-primary'}`}
              >
                <Play size={13} />
                Iniciar instalación
              </button>
            )}
            <button
              onClick={() => refreshStatus()}
              className="w-full btn btn-ghost text-[11px] px-3 py-1.5"
            >
              <RefreshCw size={11} />
              Revisar de nuevo
            </button>
          </>
        )}
      </div>
    )
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
              {stepIdx === 0 && (installing ? 'Instalando Alpine + Node.js + OpenClaw...' : 'Descarga Alpine Linux e instala OpenClaw')}
              {stepIdx === 1 && 'Selecciona las plataformas a instalar'}
              {stepIdx === 2 && '¡Todo listo! Redirigiendo...'}
            </p>
          </div>

          {error && (
            <div className="px-4 py-3 rounded-xl bg-red-soft border border-red/10 text-xs text-red mb-4">{error}</div>
          )}

          {stepIdx === 0 && renderSetupStep()}

          {/* Step: Platforms */}
          {stepIdx === 1 && (
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
                  Finalizar
                </button>
              )}
            </div>
          )}

          {/* Step: Done */}
          {stepIdx === 2 && (
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
