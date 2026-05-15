/**
 * ListItem
 * Componente de listado reutilizable para filas con icono + texto + acción.
 *
 * Props:
 * @param icon         - Icono Lucide (opcional)
 * @param iconBg       - Clase CSS para fondo del icono (def: 'bg-accent-soft')
 * @param iconColor    - Clase CSS para color del icono (def: 'text-accent')
 * @param imgSrc       - URL de imagen alternativa al icono
 * @param title        - Texto principal
 * @param subtitle     - Texto secundario (opcional)
 * @param badge        - Badge pequeño en la esquina (opcional)
 * @param badgeVariant - Variante del badge (def: 'accent')
 * @param rightAction  - Elemento React a la derecha (botón, badge, etc.)
 * @param onClick      - Handler de clic en la fila entera
 * @param className    - Clases adicionales
 * @param disabled     - Estado deshabilitado
 *
 * @example
 *   <ListItem
 *     icon={Zap}
 *     title="Skill name"
 *     subtitle="Description"
 *     badge="Nativo"
 *     badgeVariant="accent"
 *   />
 */
import { type LucideIcon } from 'lucide-react'

interface ListItemProps {
  icon?: LucideIcon
  iconBg?: string
  iconColor?: string
  imgSrc?: string
  title: string
  subtitle?: string
  badge?: string
  badgeVariant?: 'accent' | 'success' | 'error' | 'warning' | 'info'
  rightAction?: React.ReactNode
  onClick?: () => void
  className?: string
  disabled?: boolean
}

const BADGE_CLASSES: Record<string, string> = {
  accent: 'badge-accent',
  success: 'badge-success',
  error: 'badge-error',
  warning: 'badge-warning',
  info: 'badge-info',
}

export function ListItem({
  icon: Icon,
  iconBg = 'bg-accent-soft',
  iconColor = 'text-accent',
  imgSrc,
  title,
  subtitle,
  badge,
  badgeVariant = 'accent',
  rightAction,
  onClick,
  className = '',
  disabled = false,
}: ListItemProps) {
  const Comp = onClick ? 'button' : 'div'

  return (
    <Comp
      onClick={onClick}
      disabled={disabled}
      className={`flex items-center gap-3.5 px-4 py-3.5 w-full text-left ${
        onClick ? 'cursor-pointer hover:bg-glass-bg transition-colors active:scale-[0.99]' : ''
      } ${disabled ? 'opacity-40 pointer-events-none' : ''} ${className}`}
    >
      {/* Icon or image */}
      {Icon && (
        <div className={`w-9 h-9 rounded-xl flex items-center justify-center shrink-0 ${iconBg}`}>
          <Icon size={16} className={iconColor} />
        </div>
      )}
      {imgSrc && (
        <div className="w-9 h-9 rounded-xl overflow-hidden shrink-0 bg-glass-bg">
          <img src={imgSrc} alt="" className="w-full h-full object-cover" />
        </div>
      )}

      {/* Text content */}
      <div className="flex-1 min-w-0">
        <div className="text-sm font-semibold text-text-primary truncate">{title}</div>
        {subtitle && (
          <div className="text-[11px] text-text-muted mt-0.5 line-clamp-2">{subtitle}</div>
        )}
      </div>

      {/* Badge */}
      {badge && (
        <span className={`badge text-[10px] shrink-0 ${BADGE_CLASSES[badgeVariant] ?? BADGE_CLASSES.accent}`}>
          {badge}
        </span>
      )}

      {/* Right action */}
      {rightAction && (
        <div className="shrink-0">{rightAction}</div>
      )}
    </Comp>
  )
}
