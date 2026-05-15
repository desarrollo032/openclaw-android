/**
 * SectionHeader
 * Encabezado de sección reutilizable con icono y texto uppercase.
 *
 * Props:
 * @param icon  - Icono Lucide opcional
 * @param title - Texto del encabezado
 * @param className - Clases adicionales
 *
 * @example
 *   <SectionHeader icon={Zap} title="ACCESO RÁPIDO" />
 */
import { type LucideIcon } from 'lucide-react'

interface SectionHeaderProps {
  icon?: LucideIcon
  title: string
  className?: string
  rightElement?: React.ReactNode
}

export function SectionHeader({ icon: Icon, title, className = '', rightElement }: SectionHeaderProps) {
  return (
    <div className={`flex items-center justify-between px-0.5 ${className}`}>
      <div className="flex items-center gap-1.5">
        {Icon && <Icon size={12} className="text-text-muted" />}
        <span className="text-[10px] font-semibold text-text-muted tracking-widest uppercase">{title}</span>
      </div>
      {rightElement}
    </div>
  )
}
