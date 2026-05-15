import { useState, useEffect } from 'react'
import { bridge } from '../lib/bridge'
import { Zap, RefreshCw, Terminal as TerminalIcon } from 'lucide-react'
import { Skeleton } from '../components/Skeleton'
import { EmptyState } from '../components/EmptyState'
import { ListItem } from '../components/ListItem'
import { SectionHeader } from '../components/SectionHeader'

const BUILT_IN_SKILLS = [
  { name: 'bash', description: 'Ejecución de comandos Bash en el terminal', builtin: true },
  { name: 'code', description: 'Creación y edición de archivos de código', builtin: true },
  { name: 'edit', description: 'Edición precisa de secciones de archivos', builtin: true },
  { name: 'glob', description: 'Búsqueda de archivos por patrones glob', builtin: true },
  { name: 'read', description: 'Lectura de archivos y directorios útiles', builtin: true },
  { name: 'web', description: 'Búsqueda y navegación web', builtin: true },
]

export function Skills() {
  const [installedSkills, setInstalledSkills] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [actionMsg, setActionMsg] = useState('')

  useEffect(() => {
    if (!bridge.isAvailable()) { setLoading(false); return }
    try {
      const s = bridge.callJson<string>('getBootstrapStatus') ?? ''
      setInstalledSkills(s ? ['bash', 'code', 'edit', 'glob', 'read'] : [])
    } catch { /* */ }
    setLoading(false)
  }, [])

  const refresh = () => {
    setLoading(true)
    setActionMsg('')
    if (!bridge.isAvailable()) { setLoading(false); return }
    try {
      const s = bridge.callJson<string>('getBootstrapStatus') ?? ''
      setInstalledSkills(s ? ['bash', 'code', 'edit', 'glob', 'read'] : [])
      setActionMsg('Skills actualizadas')
      setTimeout(() => setActionMsg(''), 2500)
    } catch { /* */ }
    setLoading(false)
  }

  const allSkills = [
    ...BUILT_IN_SKILLS,
    ...(installedSkills.filter(s => !BUILT_IN_SKILLS.find(b => b.name === s)).map(name => ({ name, description: 'Skill instalada', builtin: false })))
  ]

  return (
    <div className="page-container flex flex-col gap-4 pt-6 pb-4 animate-fade-in">
      {/* ── Header ── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-text-primary tracking-tight">Skills</h1>
          <p className="text-[13px] text-text-muted mt-0.5">Capacidades del sistema</p>
        </div>
        <button onClick={refresh}
          className="p-2 rounded-xl text-text-muted hover:text-text-primary hover:bg-glass-bg transition-all"
          aria-label="Actualizar skills">
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
        </button>
      </div>

      {/* ── Action feedback ── */}
      {actionMsg && (
        <div className="px-4 py-2.5 rounded-xl bg-green-soft border border-green/10 text-xs text-green font-medium animate-slide-up">
          {actionMsg}
        </div>
      )}

      {/* ── Skills list ── */}
      <div className="card divide-y divide-glass-border overflow-hidden">
        {/* Loading state */}
        {loading && (
          <div className="p-4">
            <Skeleton variant="card" count={3} />
          </div>
        )}

        {/* Empty state */}
        {!loading && allSkills.length === 0 && (
          <EmptyState icon={Zap} title="No hay skills disponibles" />
        )}

        {/* Skills list */}
        {!loading && allSkills.map(skill => (
          <ListItem
            key={skill.name}
            icon={Zap}
            iconBg={skill.builtin ? 'bg-accent-soft' : 'bg-green-soft'}
            iconColor={skill.builtin ? 'text-accent' : 'text-green'}
            title={skill.name}
            subtitle={skill.description}
            badge={skill.builtin ? 'Nativo' : 'Extra'}
            badgeVariant={skill.builtin ? 'accent' : 'success'}
          />
        ))}
      </div>

      {/* ── Add skills section ── */}
      <div className="card p-4">
        <SectionHeader icon={TerminalIcon} title="Añadir skills" />
        <p className="text-xs text-text-muted leading-relaxed mt-2">Usa el comando en la terminal para instalar más skills:</p>
        <div className="mt-2 px-3 py-2 rounded-xl bg-input-bg border border-glass-border font-mono text-[11px] text-text-secondary overflow-x-auto">
          openclaw skills
        </div>
      </div>
    </div>
  )
}
