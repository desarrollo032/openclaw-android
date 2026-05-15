import { useGatewayStatus } from '../hooks/useGatewayStatus'
import { bridge } from '../lib/bridge'
import { Activity, Play, RotateCcw } from 'lucide-react'
import { Skeleton } from './Skeleton'
import { GatewayMetrics } from './GatewayMetrics'

function formatUptime(seconds: number): string {
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const parts: string[] = []
  if (d > 0) parts.push(`${d}d`)
  if (h > 0) parts.push(`${h}h`)
  if (m > 0) parts.push(`${m}m`)
  parts.push(`${s}s`)
  return parts.join(' ')
}

export function GatewayStatus() {
  const { health, reachability, isLoading } = useGatewayStatus()
  const online = reachability === 'online'

  if (isLoading) {
    return <Skeleton variant="card" width="w-20" height="" />
  }

  if (!online) {
    return (
      <div className="card p-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-red-soft flex items-center justify-center">
              <Activity size={17} className="text-red" />
            </div>
            <div>
              <div className="text-sm font-semibold text-text-primary">Gateway</div>
              <div className="text-[10px] text-text-muted mt-0.5">Desconectado</div>
            </div>
          </div>
          <button onClick={() => bridge.call('startGateway')}
            className="btn btn-primary text-[10px] px-2.5 py-1.5">
            <Play size={11} /> Iniciar
          </button>
        </div>
      </div>
    )
  }

  const uptime = health?.uptime ? formatUptime(health.uptime) : '—'
  const h = health as unknown as Record<string, unknown>
  const memUsage = (h.memory as Record<string, number> | undefined)?.usage
  const memTotal = (h.memory as Record<string, number> | undefined)?.total
  const memPct = memUsage && memTotal ? Math.round((memUsage / memTotal) * 100) : null
  const version = (h.version as string) ?? '—'

  return (
    <div className="card p-4">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="w-9 h-9 rounded-xl bg-green-soft flex items-center justify-center">
            <Activity size={17} className="text-green" />
          </div>
          <div>
            <div className="text-sm font-semibold text-text-primary">Gateway activo</div>
            <div className="text-[10px] text-text-muted mt-0.5">v{version}</div>
          </div>
        </div>
        <button onClick={() => bridge.call('stopGateway')}
          className="p-2 rounded-xl text-text-muted hover:text-text-primary hover:bg-glass-bg transition-all"
          aria-label="Reiniciar gateway">
          <RotateCcw size={13} />
        </button>
      </div>

      <GatewayMetrics
        uptime={uptime}
        status="Online"
        memPct={memPct}
        memUsed={memUsage ? memUsage / 1024 / 1024 : undefined}
        memTotal={memTotal ? memTotal / 1024 / 1024 : undefined}
      />
    </div>
  )
}
