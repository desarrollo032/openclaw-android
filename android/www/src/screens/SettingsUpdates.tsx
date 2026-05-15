import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { Package, RefreshCw, Download, Upload, ExternalLink, AlertCircle, FileDown, ChevronDown, CheckCircle2, XCircle, Play, Loader } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'
import { bridge, on, off } from '../lib/bridge'

/* ── Types ── */

interface AppInfo {
  versionName?: string
  versionCode?: number
  packageName?: string
}

interface GitHubRelease {
  tag_name: string
  name: string
  body?: string
  published_at: string
  html_url: string
  assets: Array<{
    name: string
    browser_download_url: string
    size: number
  }>
}

interface InstallProgress {
  step: number
  totalSteps: number
  extractedMB: number
  totalMB: number
  percent: number
  currentFile: string
  stepName: string
}

/* ── States ── */

type InstallState =
  | { phase: 'idle' }
  | { phase: 'selecting' }
  | { phase: 'ready'; fileUri: string; fileName: string }
  | { phase: 'installing'; progress: InstallProgress }
  | { phase: 'done' }
  | { phase: 'error'; message: string }

export function SettingsUpdates() {
  const { navigate } = useRoute()
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null)
  const [latestRelease, setLatestRelease] = useState<GitHubRelease | null>(null)
  const [checking, setChecking] = useState(false)
  const [fetchError, setFetchError] = useState<string | null>(null)
  const [showReleaseNotes, setShowReleaseNotes] = useState(false)
  const [install, setInstall] = useState<InstallState>({ phase: 'idle' })
  const [quickInstalling, setQuickInstalling] = useState(false)

  /* ── App info on mount ── */
  useEffect(() => {
    try {
      const info = bridge.callJson<AppInfo>('getAppInfo')
      if (info) setAppInfo(info)
    } catch { /* bridge not available */ }
  }, [])

  /* ── Escuchar eventos nativos de instalación ── */
  useEffect(() => {
    if (!bridge.isAvailable()) return

    const handleFilePicked = (data: unknown) => {
      // Datos pueden ser objeto { uri, filename, name } o string directo (la URI)
      const d = data as Record<string, unknown> | string | null
      if (!d) return
      if (typeof d === 'string') {
        // Caso: data es directamente la URI
        const name = d.split('/').pop() || d
        setInstall({ phase: 'ready', fileUri: d, fileName: name })
      } else {
        const uri = (d.uri as string) || (d.path as string) || ''
        const name = (d.filename as string) || (d.name as string) || uri.split('/').pop() || uri
        if (uri) {
          setInstall({ phase: 'ready', fileUri: uri, fileName: name })
        } else if (d.filename) {
          // No hay URI pero tenemos nombre — puede que el archivo ya esté cargado
          setInstall({ phase: 'ready', fileUri: d.filename as string, fileName: d.filename as string })
        }
      }
    }

    const hProgress = (data: unknown) => {
      const p = data as InstallProgress
      if (p && p.percent !== undefined) {
        if (p.percent >= 100) {
          setInstall({ phase: 'done' })
        } else {
          setInstall({ phase: 'installing', progress: p })
        }
      }
    }

    const hComplete = () => {
      setInstall({ phase: 'done' })
      setQuickInstalling(false)
    }

    const hError = (data: unknown) => {
      const d = data as { error?: string; message?: string }
      const msg = d?.error ?? d?.message ?? 'Error desconocido'
      setInstall({ phase: 'error', message: msg })
      setQuickInstalling(false)
    }

    const remove1 = on('onLocalAssetPicked', handleFilePicked)
    const remove2 = on('onMigrationFilePicked', handleFilePicked)
    const remove3 = on('install_callback', handleFilePicked)
    const remove4 = on('onInstallProgress', hProgress)
    const remove5 = on('onInstallComplete', hComplete)
    const remove6 = on('onInstallError', hError)

    return () => {
      off('onLocalAssetPicked', remove1)
      off('onMigrationFilePicked', remove2)
      off('install_callback', remove3)
      off('onInstallProgress', remove4)
      off('onInstallComplete', remove5)
      off('onInstallError', remove6)
    }
  }, [])

  /* ── Check GitHub releases ── */
  const checkForUpdates = useCallback(async () => {
    setChecking(true)
    setFetchError(null)
    try {
      const res = await fetch(
        'https://api.github.com/repos/desarrollo032/openclaw-android/releases/latest',
        { signal: AbortSignal.timeout(10000) }
      )
      if (!res.ok) throw new Error(`GitHub API: ${res.status} ${res.statusText}`)
      const release = (await res.json()) as GitHubRelease
      setLatestRelease(release)
    } catch (err) {
      if (err instanceof DOMException && err.name === 'TimeoutError') {
        setFetchError('Tiempo de espera agotado. Revisa tu conexión.')
      } else {
        setFetchError(err instanceof Error ? err.message : 'Error al buscar actualizaciones')
      }
    } finally {
      setChecking(false)
    }
  }, [])

  /* ── Select file via native picker ── */
  const handleSelectFile = useCallback(() => {
    if (!bridge.isAvailable()) return
    setInstall({ phase: 'selecting' })
    bridge.call('pickFile', 'install_callback')
    // Timeout seguro: si el usuario cancela, volver a idle
    setTimeout(() => {
      setInstall(prev => prev.phase === 'selecting' ? { phase: 'idle' } : prev)
    }, 60_000)
  }, [])

  /* ── Install from selected file URI ── */
  const handleInstallFromUri = useCallback(() => {
    if (install.phase !== 'ready') return
    const progress: InstallProgress = {
      step: 1, totalSteps: 1, percent: 0,
      extractedMB: 0, totalMB: 0,
      currentFile: install.fileName, stepName: 'Instalando...'
    }
    setInstall({ phase: 'installing', progress })
    bridge.call('installFromUri', install.fileUri, '')
  }, [install])

  /* ── Quick install (full system reinstall) ── */
  const handleQuickInstall = useCallback(() => {
    if (!bridge.isAvailable()) return
    setQuickInstalling(true)
    setInstall({ phase: 'idle' })
    bridge.call('startSetup')
  }, [])

  /* ── Derived ── */
  const currentVersion = appInfo?.versionName ?? ''
  const latestTag = latestRelease?.tag_name.replace(/^v/, '') ?? ''
  const hasUpdate = !!currentVersion && !!latestTag && currentVersion !== '-' && latestTag !== currentVersion
  const bridgeAvailable = bridge.isAvailable()

  /* ── Render helpers ── */

  const renderInstallStatus = () => {
    switch (install.phase) {
      case 'selecting':
        return (
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-accent-soft text-accent text-[11px]">
            <Loader size={12} className="animate-spin" />
            Seleccionando archivo...
          </div>
        )
      case 'ready':
        return (
          <div className="mt-3 space-y-3">
            <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-green-soft text-green text-[11px]">
              <CheckCircle2 size={12} />
              {install.fileName}
            </div>
            <button
              onClick={handleInstallFromUri}
              className="btn btn-primary text-xs px-4 py-2.5 w-full"
            >
              <Download size={14} />
              Instalar ahora
            </button>
          </div>
        )
      case 'installing': {
        const p = install.progress
        return (
          <div className="mt-3 space-y-2">
            <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-accent-soft text-accent text-[11px]">
              <Loader size={12} className="animate-spin" />
              Instalando... {p.step}/{p.totalSteps}
            </div>
            {/* Barra de progreso */}
            <div className="w-full h-1.5 rounded-full bg-glass-bg overflow-hidden">
              <div
                className="h-full rounded-full bg-accent transition-all duration-300"
                style={{ width: `${Math.min(p.percent, 100)}%` }}
              />
            </div>
            <div className="text-[9px] text-text-muted text-center">
              {Math.round(p.percent)}% — {p.currentFile || p.stepName}
            </div>
          </div>
        )
      }
      case 'done':
        return (
          <div className="mt-3 flex items-center gap-2 px-3 py-2 rounded-lg bg-green-soft text-green text-[11px]">
            <CheckCircle2 size={12} />
            ¡Instalación completada!
          </div>
        )
      case 'error':
        return (
          <div className="mt-3 space-y-3">
            <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-red-soft text-red text-[11px]">
              <XCircle size={12} />
              {install.message}
            </div>
            <button
              onClick={() => setInstall({ phase: 'idle' })}
              className="btn btn-ghost text-xs px-3 py-1.5 w-full"
            >
              Reintentar
            </button>
          </div>
        )
      default:
        return null
    }
  }

  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Actualizaciones"
        subtitle="Instalar componentes y buscar nuevas versiones"
        icon={Package}
      />

      {/* ── Barra de progreso global ── */}
      {install.phase === 'installing' && (
        <div className="card p-4">
          <div className="flex items-center gap-2.5 mb-3">
            <div className="w-9 h-9 rounded-xl bg-accent-soft flex items-center justify-center">
              <Loader size={17} className="text-accent animate-spin" />
            </div>
            <div>
              <div className="text-sm font-semibold text-text-primary">Instalando...</div>
              <div className="text-[10px] text-text-muted mt-0.5">
                Paso {install.progress.step} de {install.progress.totalSteps}
              </div>
            </div>
          </div>
          <div className="w-full h-2 rounded-full bg-glass-bg overflow-hidden">
            <div
              className="h-full rounded-full bg-accent transition-all duration-300"
              style={{ width: `${Math.min(install.progress.percent, 100)}%` }}
            />
          </div>
          <div className="text-[10px] text-text-muted text-center mt-2">
            {Math.round(install.progress.percent)}% — {install.progress.currentFile || install.progress.stepName}
          </div>
        </div>
      )}
      {install.phase === 'done' && (
        <div className="card p-4">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-green-soft flex items-center justify-center">
              <CheckCircle2 size={17} className="text-green" />
            </div>
            <div>
              <div className="text-sm font-semibold text-text-primary">¡Instalación completada!</div>
              <div className="text-[10px] text-text-muted mt-0.5">
                {quickInstalling ? 'Todos los componentes se instalaron correctamente.' : 'El archivo se instaló correctamente.'}
              </div>
            </div>
            <button
              onClick={() => { setInstall({ phase: 'idle' }); setQuickInstalling(false) }}
              className="ml-auto btn btn-ghost text-[10px] px-2 py-1"
            >
              Ok
            </button>
          </div>
        </div>
      )}
      {install.phase === 'error' && (
        <div className="card p-4">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-red-soft flex items-center justify-center">
              <XCircle size={17} className="text-red" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-sm font-semibold text-text-primary">Error de instalación</div>
              <div className="text-[10px] text-text-muted mt-0.5 truncate">{install.message}</div>
            </div>
            <button
              onClick={() => { setInstall({ phase: 'idle' }); setQuickInstalling(false) }}
              className="btn btn-ghost text-[10px] px-2 py-1"
            >
              Cerrar
            </button>
          </div>
        </div>
      )}

      {/* ── 1. Quick Install (botón principal) ── */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5 mb-3">
          <div className="w-9 h-9 rounded-xl bg-accent-soft flex items-center justify-center">
            <Download size={17} className="text-accent" />
          </div>
          <div>
            <div className="text-sm font-semibold text-text-primary">Instalar componentes</div>
            <div className="text-[10px] text-text-muted mt-0.5">
              Instalar o reinstalar todos los componentes del sistema
            </div>
          </div>
        </div>
        <button
          onClick={handleQuickInstall}
          disabled={quickInstalling || !bridgeAvailable}
          className="btn btn-primary text-xs px-4 py-2.5 w-full"
        >
          {quickInstalling ? (
            <><Loader size={14} className="animate-spin" /> Instalando...</>
          ) : (
            <><Play size={14} /> Iniciar instalación completa</>
          )}
        </button>
        {!bridgeAvailable && (
          <p className="text-[10px] text-text-dim text-center mt-2">
            Solo disponible en la app Android
          </p>
        )}
      </div>

      {/* ── 2. Versión actual + Buscar actualizaciones ── */}
      <div className="card p-5">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-accent-soft flex items-center justify-center">
              <Package size={17} className="text-accent" />
            </div>
            <div>
              <div className="text-sm font-semibold text-text-primary">Versión instalada</div>
              <div className="text-[10px] text-text-muted mt-0.5">
                {appInfo
                  ? `${appInfo.versionName ?? '—'} · código ${appInfo.versionCode ?? '—'}`
                  : 'Cargando...'}
              </div>
            </div>
          </div>
          <button
            onClick={checkForUpdates}
            disabled={checking}
            className="btn btn-ghost text-xs px-3 py-1.5"
          >
            <RefreshCw size={12} className={checking ? 'animate-spin' : ''} />
            {checking ? 'Buscando...' : 'Buscar'}
          </button>
        </div>

        {fetchError && (
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-red-soft text-red text-[11px] mt-2">
            <AlertCircle size={12} />
            {fetchError}
          </div>
        )}

        {latestRelease && (
          <div className="mt-3 rounded-xl bg-glass-bg border border-glass-border overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between px-4 py-3">
              <div className="flex items-center gap-2">
                <span className={`badge ${hasUpdate ? 'badge-warning' : 'badge-success'}`}>
                  {hasUpdate ? 'Disponible' : 'Actualizado'}
                </span>
                <span className="text-xs font-mono text-text-secondary">
                  {latestRelease.tag_name}
                </span>
              </div>
              <a
                href={latestRelease.html_url}
                target="_blank"
                rel="noopener noreferrer"
                className="text-accent text-[10px] hover:underline flex items-center gap-1"
              >
                GitHub <ExternalLink size={10} />
              </a>
            </div>

            {/* Fecha */}
            <div className="px-4 pb-2 text-[10px] text-text-muted">
              Publicado {new Date(latestRelease.published_at).toLocaleDateString('es', {
                year: 'numeric', month: 'long', day: 'numeric'
              })}
            </div>

            {/* Assets */}
            {latestRelease.assets.length > 0 && (
              <div className="px-4 pb-3 space-y-1">
                <div className="text-[10px] text-text-muted mb-1">Archivos:</div>
                {latestRelease.assets.map(asset => (
                  <div key={asset.name} className="flex items-center gap-2 text-[10px] text-text-secondary">
                    <Download size={10} className="shrink-0" />
                    <span className="font-mono truncate">{asset.name}</span>
                    <span className="text-text-dim shrink-0">
                      ({ (asset.size / 1024 / 1024).toFixed(1) } MB)
                    </span>
                  </div>
                ))}
              </div>
            )}

            {/* Release notes toggle */}
            {latestRelease.body && (
              <>
                <button
                  onClick={() => setShowReleaseNotes(!showReleaseNotes)}
                  className="flex items-center gap-1.5 w-full px-4 py-2 text-[10px] text-text-muted hover:text-text-secondary transition-colors border-t border-glass-border"
                >
                  <ChevronDown size={12} className={`transition-transform ${showReleaseNotes ? 'rotate-180' : ''}`} />
                  Notas de la versión
                </button>
                {showReleaseNotes && (
                  <div className="px-4 py-2 text-[10px] text-text-secondary leading-relaxed whitespace-pre-wrap max-h-40 overflow-y-auto border-t border-glass-border bg-overlay-dark/30">
                    {latestRelease.body}
                  </div>
                )}
              </>
            )}
          </div>
        )}

        {!latestRelease && !fetchError && !checking && (
          <div className="text-[11px] text-text-muted text-center py-2">
            Presiona "Buscar" para verificar si hay una nueva versión disponible
          </div>
        )}
      </div>

      {/* ── 3. Instalación manual (seleccionar archivo) ── */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5 mb-3">
          <div className="w-9 h-9 rounded-xl bg-accent-soft flex items-center justify-center">
            <Upload size={17} className="text-accent" />
          </div>
          <div>
            <div className="text-sm font-semibold text-text-primary">Instalación manual</div>
            <div className="text-[10px] text-text-muted mt-0.5">
              Reemplazar archivos locales manualmente
            </div>
          </div>
        </div>
        <p className="text-[11px] text-text-muted mb-3 leading-relaxed">
          Selecciona un archivo .zip o .apk desde el almacenamiento del dispositivo
          para instalar una versión específica manualmente.
        </p>
        <button
          onClick={handleSelectFile}
          disabled={!bridgeAvailable || install.phase === 'selecting'}
          className="btn btn-ghost text-xs px-4 py-2.5 w-full"
        >
          <FileDown size={13} />
          {install.phase === 'selecting' ? 'Seleccionando...' : 'Seleccionar archivo'}
        </button>

        {/* Estado de la instalación */}
        {renderInstallStatus()}

        {!bridgeAvailable && (
          <p className="text-[10px] text-text-dim text-center mt-2">
            Solo disponible en la app Android
          </p>
        )}
      </div>

      {/* ── 4. Desde la terminal ── */}
      <div className="card p-5">
        <div className="flex items-center gap-2.5 mb-3">
          <div className="w-9 h-9 rounded-xl bg-glass-bg flex items-center justify-center">
            <Package size={17} className="text-text-primary" />
          </div>
          <div>
            <div className="text-sm font-semibold text-text-primary">Desde la terminal</div>
            <div className="text-[10px] text-text-muted mt-0.5">
              Ejecuta comandos de actualización manualmente
            </div>
          </div>
        </div>
        <p className="text-[11px] text-text-muted mb-3 leading-relaxed">
          Usa el comando <span className="font-mono text-accent text-[10px]">openclaw update</span> en la terminal
          para actualizar todos los componentes.
        </p>
        <button
          onClick={() => navigate('/terminal')}
          className="btn btn-ghost text-xs px-4 py-2 w-full"
        >
          Abrir terminal
        </button>
      </div>
    </div>
  )
}
