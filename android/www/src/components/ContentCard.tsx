/**
 * ContentCard
 * Componente para tarjetas de contenido informativo con icono, texto, comando y acción.
 * Ideal para pantallas de "en desarrollo" o "configura desde terminal".
 *
 * Props:
 * @param icon      - Icono Lucide
 * @param iconBg    - Clase de fondo del icono (def: 'bg-accent-soft')
 * @param iconColor - Clase de color del icono (def: 'text-accent')
 * @param title     - Título de la tarjeta
 * @param subtitle  - Subtítulo / descripción
 * @param command   - Comando a mostrar en el bloque de código
 * @param actionLabel - Texto del botón (def: 'Ir a terminal')
 * @param onAction  - Callback del botón
 * @param children  - Contenido adicional debajo del comando
 *
 * @example
 *   <ContentCard
 *     icon={Wrench}
 *     title="Herramientas"
 *     subtitle="Gestiona herramientas desde la terminal"
 *     command="openclaw configure --section tools"
 *     onAction={() => navigate('/terminal')}
 *   />
 */
import { type LucideIcon } from 'lucide-react'
import { Terminal as TerminalIcon } from 'lucide-react'
import { useRoute } from '../lib/router'

interface ContentCardProps {
  icon: LucideIcon
  iconBg?: string
  iconColor?: string
  title: string
  subtitle?: string
  command?: string
  actionLabel?: string
  onAction?: () => void
  children?: React.ReactNode
}

export function ContentCard({
  icon: Icon,
  iconBg = 'bg-accent-soft',
  iconColor = 'text-accent',
  title,
  subtitle,
  command,
  actionLabel = 'Ir a terminal',
  onAction,
  children,
}: ContentCardProps) {
  const { navigate } = useRoute()
  const handleAction = onAction ?? (() => navigate('/terminal'))

  return (
    <div className="card p-6 text-center">
      <div className={`w-14 h-14 rounded-2xl ${iconBg} flex items-center justify-center mx-auto mb-4`}>
        <Icon size={28} className={iconColor} />
      </div>
      <p className="text-sm font-semibold text-text-primary mb-1">{title}</p>
      {subtitle && <p className="text-sm text-text-secondary mb-4">{subtitle}</p>}
      {command && (
        <div className="px-4 py-3 rounded-xl bg-input-bg border border-glass-border font-mono text-[11px] text-text-secondary text-left mb-4 overflow-x-auto">
          {command}
        </div>
      )}
      {children}
      <button onClick={handleAction}
        className="btn btn-primary text-xs px-5 py-2.5">
        <TerminalIcon size={14} /> {actionLabel}
      </button>
    </div>
  )
}
