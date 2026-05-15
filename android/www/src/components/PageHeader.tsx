import { ArrowLeft } from 'lucide-react'
import { useRoute } from '../lib/router'

interface PageHeaderProps {
  title: string
  subtitle?: string
  icon?: React.ElementType
  iconBg?: string
  iconColor?: string
  backTo?: string
  backLabel?: string
}

export function PageHeader({
  title,
  subtitle,
  icon: Icon,
  iconBg = 'bg-accent-soft',
  iconColor = 'text-accent',
  backTo = '/settings',
  backLabel = 'Ajustes',
}: PageHeaderProps) {
  const { navigate } = useRoute()

  return (
    <div className="sticky top-0 z-30 -mx-4 px-4 pt-3 pb-4 bg-glass-strong-bg backdrop-blur-2xl -webkit-backdrop-blur-2xl border-b border-glass-strong-border rounded-b-2xl mb-2 animate-slide-up">
      <button
        onClick={() => navigate(backTo)}
        className="flex items-center gap-1.5 text-text-muted hover:text-text-primary transition-all w-fit group active:scale-95"
      >
        <div className="w-7 h-7 rounded-lg bg-glass-bg flex items-center justify-center group-hover:bg-accent-soft transition-colors">
          <ArrowLeft size={14} className="group-hover:text-accent transition-colors" />
        </div>
        <span className="text-[11px] font-medium">{backLabel}</span>
      </button>

      <div className="flex items-center gap-3.5 mt-2.5">
        {Icon && (
          <div className={`w-11 h-11 rounded-xl ${iconBg} flex items-center justify-center shrink-0 shadow-sm`}>
            <Icon size={22} className={iconColor} />
          </div>
        )}
        <div className="min-w-0">
          <h1 className="text-xl sm:text-2xl font-bold text-text-primary tracking-tight leading-tight">
            {title}
          </h1>
          {subtitle && (
            <p className="text-[12px] sm:text-[13px] text-text-muted mt-0.5 leading-snug">{subtitle}</p>
          )}
        </div>
      </div>
    </div>
  )
}
