/**
 * GatewayMetrics
 * Componente para mostrar métricas del gateway en grid de 3 columnas.
 *
 * Props:
 * @param uptime   - Texto con el uptime
 * @param status   - Estado actual (online, offline, etc.)
 * @param memPct   - Porcentaje de memoria usado (0-100) o null
 * @param memUsed  - MB usados (para detalle)
 * @param memTotal - MB totales (para detalle)
 *
 * @example
 *   <GatewayMetrics uptime="5d 12h 30m" status="Online" memPct={45} memUsed={256} memTotal={1024} />
 */
import { Clock, Wifi, HardDrive } from 'lucide-react'

interface GatewayMetricsProps {
  uptime: string
  status: string
  statusColor?: string
  memPct: number | null
  memUsed?: number
  memTotal?: number
}

export function GatewayMetrics({ uptime, status, statusColor = 'text-green', memPct, memUsed, memTotal }: GatewayMetricsProps) {
  return (
    <>
      <div className="grid grid-cols-3 gap-2">
        <MetricTile icon={Clock} label="Uptime" value={uptime} iconClassName="text-text-dim" />
        <MetricTile icon={Wifi} label="Status" value={status} iconClassName={statusColor} />
        <MetricTile icon={HardDrive} label="Memoria" value={memPct !== null ? `${memPct}%` : '—'} iconClassName="text-text-dim" />
      </div>

      {memPct !== null && memUsed !== undefined && memTotal !== undefined && (
        <div className="mt-2">
          <div className="progress-track">
            <div className="progress-fill" style={{ width: `${Math.min(memPct, 100)}%` }} />
          </div>
          <div className="flex justify-between text-[9px] text-text-dim mt-0.5">
            <span>{memUsed.toFixed(0)} MB usado</span>
            <span>{memTotal.toFixed(0)} MB total</span>
          </div>
        </div>
      )}
    </>
  )
}

interface MetricTileProps {
  icon: React.ElementType
  label: string
  value: string
  iconClassName?: string
}

function MetricTile({ icon: Icon, label, value, iconClassName = 'text-text-dim' }: MetricTileProps) {
  return (
    <div className="rounded-xl bg-glass-bg p-2.5 text-center">
      <Icon size={12} className={`mx-auto mb-1 ${iconClassName}`} />
      <div className="text-[11px] font-semibold text-text-primary truncate">{value}</div>
      <div className="text-[9px] text-text-muted">{label}</div>
    </div>
  )
}
