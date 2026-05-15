import { useState } from 'react'
import { bridge } from '../lib/bridge'
import { HardDrive, Trash2, RefreshCw } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'

export function SettingsStorage() {
  const [storage, setStorage] = useState<{ used: number; total: number; cache: number } | null>(null)
  const [clearing, setClearing] = useState(false)

  useState(() => {
    if (!bridge.isAvailable()) return
    try {
      const s = bridge.callJson<{ used: number; total: number; cache: number }>('getStorageInfo')
      if (s) setStorage(s)
    } catch { /* */ }
  })

  const clearCache = () => {
    if (!bridge.isAvailable()) return
    setClearing(true)
    try {
      bridge.call('clearCache')
      if (storage) setStorage({ ...storage, cache: 0 })
    } catch { /* */ }
    setClearing(false)
  }

  const pct = storage ? Math.round((storage.used / storage.total) * 100) : 0

  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Almacenamiento"
        subtitle="Gestión de espacio y caché"
        icon={HardDrive}
      />

      <div className="card p-5">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 rounded-xl bg-accent-soft flex items-center justify-center">
            <HardDrive size={20} className="text-accent" />
          </div>
          <div>
            <div className="text-sm font-semibold text-text-primary">Almacenamiento</div>
            <div className="text-[11px] text-text-muted">
              {storage ? `${(storage.used / 1024).toFixed(1)} GB / ${(storage.total / 1024).toFixed(1)} GB` : 'Cargando...'}
            </div>
          </div>
        </div>

        {storage && (
          <>
            <div className="progress-track mb-2">
              <div className="progress-fill" style={{ width: `${Math.min(pct, 100)}%` }} />
            </div>
            <div className="text-[10px] text-text-dim mb-4">{pct}% utilizado</div>

            <div className="pt-4 border-t border-glass-border">
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-medium text-text-primary">Caché</span>
                <span className="text-[11px] text-text-muted">{(storage.cache / 1024).toFixed(1)} MB</span>
              </div>
              <button onClick={clearCache} disabled={clearing}
                className="btn btn-danger text-xs w-full py-2">
                {clearing ? <RefreshCw size={13} className="animate-spin" /> : <Trash2 size={13} />}
                {clearing ? 'Limpiando...' : 'Limpiar caché'}
              </button>
            </div>
          </>
        )}

        {!storage && (
          <div className="flex items-center justify-center py-4 text-xs text-text-dim">
            <RefreshCw size={12} className="animate-spin mr-2" /> Cargando...
          </div>
        )}
      </div>
    </div>
  )
}
