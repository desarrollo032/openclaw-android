import { useRoute } from '../lib/router'
import { Sliders } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'
import { ContentCard } from '../components/ContentCard'

export function SettingsAdvanced() {
  const { navigate } = useRoute()
  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Avanzado"
        subtitle="Configuración avanzada del sistema"
        icon={Sliders}
      />
      <ContentCard
        icon={Sliders}
        title="Avanzado"
        subtitle="Usa la terminal para configuración avanzada"
        command="openclaw configure --edit"
        actionLabel="Ir a terminal"
        onAction={() => navigate('/terminal')}
      />
    </div>
  )
}
