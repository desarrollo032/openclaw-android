import { useRoute } from '../lib/router'
import { Wrench } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'
import { ContentCard } from '../components/ContentCard'

export function SettingsTools() {
  const { navigate } = useRoute()
  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Herramientas"
        subtitle="Gestionar herramientas del sistema"
        icon={Wrench}
      />
      <ContentCard
        icon={Wrench}
        title="Herramientas"
        subtitle="Gestiona herramientas desde la terminal"
        command="openclaw configure --section tools"
        onAction={() => navigate('/terminal')}
      />
    </div>
  )
}
