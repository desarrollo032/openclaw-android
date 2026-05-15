import { useState, useEffect, useCallback, useRef } from 'react'
import { bridge, on, off } from '../lib/bridge'
import {
  Check,
  Loader,
  Terminal as TerminalIcon,
  Shield,
  Play,
  Upload,
  Package,
  Rocket,
  AlertCircle,
  RefreshCw,
} from 'lucide-react'

interface Props { onComplete: () => void }

const STEPS = [
  { key: 'bootstrap', label: 'Bootstrap',     icon: Shield },
  { key: 'payload',   label: 'Payload',       icon: Loader },
  { key: 'platform',  label: 'Plataforma',    icon: TerminalIcon },
  { key: 'tools',     label: 'Herramientas',  icon: Check },
  { key: 'done',      label: 'Listo',         icon: Check },
]

type AssetSource = 'apk' | 'local' | 'remote' | 'missing'

interface SetupStatus {
  bootstrapInstalled?: boolean
  payloadReady?: boolean
  payloadAvailable?: boolean
  payloadSizeBytes?: number
  payloadSource?: AssetSource
  migrationAvailable?: boolean
  migrationSizeBytes?: number
  migrationSource?: AssetSource
  canDownloadRemotely?: boolean
  freeSpaceMB?: number
  requiredSpaceMB?: number
  hasEnoughSpace?: boolean
  onboardComplete?: boolean
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

const formatMB = (bytes?: number) => {
  if (!bytes || bytes <= 0) return '—'
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${(bytes / 1024).toFixed(0)} KB`
}

const sourceLabel = (src?: AssetSource): { text: string; tone: 'apk' | 'local' | 'remote' | 'missing' } => {
  switch (src) {
    case 'apk':     return { text: 'Incluido en APK',    tone: 'apk' }
    case 'local':   return { text: 'Archivo local',      tone: 'local' }
    case 'remote':  return { text: 'Descarga remota',    tone: 'remote' }
    default:        return { text: 'No disponible',      tone: 'missing' }
  }
}

export function Setup({ onComplete }: Props) {
  const [stepIdx, setStepIdx] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [platforms, setPlatforms] = useState<{ id: string; name: string; installed: boolean }[]>([])
  const [selectedTools, setSelectedTools] = useState<string[]>([])
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

  // ── Initial bootstrap detection ─────────────────────────────────────────
  useEffect(() => {
    if (!bridge.isAvailable()) {
      setLoading(false)
      return
    }
    if (stepIdx !== 0) return
    const s = refreshStatus()
    setLoading(false)
    if (s?.payloadReady) {
      // Bootstrap already installed → skip payload step too
      setStepIdx(2)
      nextAnim()
    }
  }, [stepIdx, refreshStatus])

  // ── Step 1 (payload) is informational only — advance once payload is ready ─
  useEffect(() => {
    if (stepIdx !== 1) return
    setLoading(true)
    const id = setInterval(() => {
      const s = refreshStatus()
      if (s?.payloadReady) {
        setLoading(false)
        setStepIdx(2)
        nextAnim()
        clearInterval(id)
      }
    }, 600)
    return () => clearInterval(id)
  }, [stepIdx, refreshStatus])

  // ── Steps 2 / 3 init ────────────────────────────────────────────────────
  useEffect(() => {
    if (stepIdx === 2) {
      setLoading(false)
      setPlatforms([
        { id: 'openclaw', name: 'OpenClaw Core', installed: false },
        { id: 'code', name: 'Code Server', installed: false },
        { id: 'browser', name: 'Chromium Browser', installed: false },
      ])
    }
    if (stepIdx === 3) {
      setLoading(false)
      setSelectedTools([])
    }
  }, [stepIdx])

  // ── Listen to native install events ─────────────────────────────────────
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
      // Bootstrap+payload installed in a single startSetup call → go to step 2.
      setStepIdx(2)
      nextAnim()
    }
    const hError = (data: unknown) => {
      const d = data as { error?: string }
      setInstalling(false)
      setError(d?.error ?? 'Error desconocido durante la instalación')
    }
    const hPicked = (data: unknown) => {
      const d = data as { type?: string; filename?: string; sizeMB?: number }
      refreshStatus()
      if (!installingRef.current && d?.filename) {
        setError('')
      }
    }

    const r1 = on('onInstallProgress', hProgress)
    const r2 = on('onInstallComplete', hComplete)
    const r3 = on('onInstallError', hError)
    const r4 = on('onLocalAssetPicked', hPicked)
    const r5 = on('onMigrationFilePicked', hPicked)

    return () => {
      off('onInstallProgress', r1)
      off('onInstallComplete', r2)
      off('onInstallError', r3)
      off('onLocalAssetPicked', r4)
      off('onMigrationFilePicked', r5)
    }
  }, [refreshStatus])

  // ── Install actions ─────────────────────────────────────────────────────
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

  const pickPayload = useCallback(() => {
    if (!bridge.isAvailable() || installing) return
    bridge.call('pickPayloadFile')
  }, [installing])

  const pickMigration = useCallback(() => {
    if (!bridge.isAvailable() || installing) return
    bridge.call('pickMigrationFile')
  }, [installing])

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

  // ── Bootstrap step renderer ─────────────────────────────────────────────
  const renderBootstrapStep = () => {
    const payloadInfo = sourceLabel(setupStatus?.payloadSource)
    const migrationInfo = sourceLabel(setupStatus?.migrationSource)
    const canStart = !!(setupStatus?.payloadAvailable || setupStatus?.canDownloadRemotely)
    const needsSpace = setupStatus && setupStatus.hasEnoughSpace === false

    if (loading && !setupStatus) {
      return (
        <div className="flex items-center justify-center py-4">
          <div className="flex items-center gap-3">
            <div className="w-5 h-5 rounded-full border-2 border-accent/30 border-t-accent animate-spin" />
            <span className="text-xs text-text-muted">Verificando bootstrap...</span>
          </div>
        </div>
      )
    }

    return (
      <div className="space-y-3">
        {/* Payload card */}
        <div className="rounded-xl bg-glass-bg border border-glass-border p-3">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-accent-soft flex items-center justify-center shrink-0">
              <Package size={15} className="text-accent" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-xs font-semibold text-text-primary">Payload (Node + libs)</div>
              <div className="text-[10px] text-text-muted mt-0.5">
                {payloadInfo.text}
                {setupStatus?.payloadSizeBytes ? ` · ${formatMB(setupStatus.payloadSizeBytes)}` : ''}
              </div>
            </div>
            <button
              onClick={pickPayload}
              disabled={installing}
              className="btn btn-ghost text-[10px] px-2 py-1 shrink-0"
              title={setupStatus?.payloadSource === 'apk' ? 'Reemplazar con archivo local' : 'Cargar archivo local'}
            >
              <Upload size={11} />
              {setupStatus?.payloadSource === 'local' ? 'Cambiar' : setupStatus?.payloadAvailable ? 'Reemplazar' : 'Cargar'}
            </button>
          </div>
        </div>

        {/* Migration card */}
        <div className="rounded-xl bg-glass-bg border border-glass-border p-3">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-accent-soft flex items-center justify-center shrink-0">
              <Rocket size={15} className="text-accent" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-xs font-semibold text-text-primary">Migración (configuración)</div>
              <div className="text-[10px] text-text-muted mt-0.5">
                {migrationInfo.text}
                {setupStatus?.migrationSizeBytes ? ` · ${formatMB(setupStatus.migrationSizeBytes)}` : ''}
              </div>
            </div>
            <button
              onClick={pickMigration}
              disabled={installing}
              className="btn btn-ghost text-[10px] px-2 py-1 shrink-0"
              title={setupStatus?.migrationSource === 'apk' ? 'Reemplazar con archivo local' : 'Cargar archivo local'}
            >
              <Upload size={11} />
              {setupStatus?.migrationSource === 'local' ? 'Cambiar' : setupStatus?.migrationAvailable ? 'Reemplazar' : 'Cargar'}
            </button>
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
              Paso {progress.step}/{progress.totalSteps} · {Math.round(progress.percent)}%
              {progress.currentFile ? ` · ${progress.currentFile}` : progress.stepName ? ` · ${progress.stepName}` : ''}
            </div>
          </div>
        ) : (
          <>
            <button
              onClick={startInstall}
              disabled={installing || !canStart || needsSpace === true}
              className={`w-full btn text-xs ${
                canStart && !needsSpace ? 'btn-primary' : 'bg-glass-bg text-text-dim cursor-not-allowed'
              }`}
            >
              <Play size={13} />
              {canStart ? 'Iniciar instalación' : 'Carga los archivos necesarios'}
            </button>
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
              {stepIdx === 0 && (installing ? 'Instalando componentes base...' : 'Verifica la instalación base')}
              {stepIdx === 1 && 'Cargando payload del sistema...'}
              {stepIdx === 2 && 'Selecciona las plataformas a instalar'}
              {stepIdx === 3 && 'Elige herramientas adicionales'}
              {stepIdx === 4 && '¡Todo listo! Redirigiendo...'}
            </p>
          </div>

          {error && (
            <div className="px-4 py-3 rounded-xl bg-red-soft border border-red/10 text-xs text-red mb-4">{error}</div>
          )}

          {stepIdx === 0 && renderBootstrapStep()}

          {/* Step: payload loading */}
          {stepIdx === 1 && loading && (
            <div className="flex items-center justify-center py-4">
              <div className="flex items-center gap-3">
                <div className="w-5 h-5 rounded-full border-2 border-accent/30 border-t-accent animate-spin" />
                <span className="text-xs text-text-muted">Cargando payload...</span>
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
