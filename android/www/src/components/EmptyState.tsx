/**
 * EmptyState
 * Componente para estados vacíos o sin datos.
 *
 * Props:
 * @param icon       - Icono Lucide (opcional)
 * @param title      - Título del estado vacío
 * @param subtitle   - Subtítulo opcional
 * @param action     - Botón/elemento de acción opcional
 * @param className  - Clases adicionales
 *
 * @example
 *   <EmptyState icon={Zap} title="Sin skills" subtitle="No hay skills disponibles" />
 */
import { type LucideIcon } from 'lucide-react'

interface EmptyStateProps {
  icon?: LucideIcon
  title?: string
  subtitle?: string
  action?: React.ReactNode
  className?: string
}

export function EmptyState({ icon: Icon, title, subtitle, action, className = '' }: EmptyStateProps) {
  return (
    <div className={`p-8 text-center ${className}`}>
      {Icon && (
        <div className="w-14 h-14 rounded-2xl bg-glass-bg flex items-center justify-center mx-auto mb-3">
          <Icon size={28} className="text-text-dim" />
        </div>
      )}
      {title && <p className="text-sm font-semibold text-text-muted">{title}</p>}
      {subtitle && <p className="text-xs text-text-dim mt-1">{subtitle}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}
