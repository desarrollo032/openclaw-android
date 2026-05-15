import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { Package, RefreshCw, Download, Upload, ExternalLink, AlertCircle, FileDown, ChevronDown } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'
import { bridge, callJson } from '../lib/bridge'

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

export function SettingsUpdates() {
  const { navigate } = useRoute()
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null)
  const [latestRelease, setLatestRelease] = useState<GitHubRelease | null>(null)
  const [checking, setChecking] = useState(false)
  const [fetchError, setFetchError] = useState<string | null>(null)
  const [installing, setInstalling] = useState(false)
  const [showReleaseNotes, setShowReleaseNotes] = useState(false)

  useEffect(() => {
    try {
      const info = callJson<AppInfo>('getAppInfo')
      if (info) setAppInfo(info)
    } catch { /* bridge not available */ }
  }, [])

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

  const handleManualInstall = useCallback(() => {
    if (!bridge.isAvailable()) return
    bridge.call('pickFile', 'install_callback')
    setInstalling(true)
  }, [])

  const currentVersion = appInfo?.versionName ?? ''
  const latestTag = latestRelease?.tag_name.replace(/^v/, '') ?? ''
  const hasUpdate = !!currentVersion && !!latestTag && currentVersion !== '-' && latestTag !== currentVersion

  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Actualizaciones"
        subtitle="Buscar y aplicar actualizaciones"
        icon={Package}
      />

      {/* Versión actual + Buscar actualizaciones */}
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

            {/* Botón de actualización */}
            {hasUpdate && (
              <div className="px-4 pb-3 pt-1">
                <button
                  onClick={() => navigate('/terminal')}
                  className="btn btn-primary text-xs px-4 py-2 w-full"
                >
                  <Download size={13} />
                  Ir a terminal para actualizar
                </button>
              </div>
            )}
          </div>
        )}

        {/* Sugerencia si no se ha buscado aún */}
        {!latestRelease && !fetchError && !checking && (
          <div className="text-[11px] text-text-muted text-center py-2">
            Presiona "Buscar" para verificar si hay una nueva versión disponible
          </div>
        )}
      </div>

      {/* Instalación manual */}
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
          Selecciona un archivo APK o payload desde el almacenamiento del dispositivo
          para instalar una versión específica manualmente.
        </p>
        <button
          onClick={handleManualInstall}
          disabled={installing || !bridge.isAvailable()}
          className="btn btn-ghost text-xs px-4 py-2 w-full"
        >
          <FileDown size={13} />
          {installing ? 'Seleccionando archivo...' : 'Seleccionar archivo'}
        </button>
        {!bridge.isAvailable() && (
          <p className="text-[10px] text-text-dim text-center mt-2">
            Solo disponible en la app Android
          </p>
        )}
      </div>

      {/* Terminal */}
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
