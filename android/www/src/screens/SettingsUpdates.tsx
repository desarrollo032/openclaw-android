import { useRoute } from '../lib/router'
import { Package } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'
import { ContentCard } from '../components/ContentCard'

export function SettingsUpdates() {
  const { navigate } = useRoute()
  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Actualizaciones"
        subtitle="Buscar y aplicar updates"
        icon={Package}
      />
      <ContentCard
        icon={Package}
        title="Actualizaciones"
        subtitle="Gestiona actualizaciones desde la terminal"
        command="openclaw update"
        actionLabel="Ir a terminal"
        onAction={() => navigate('/terminal')}
      />
    </div>
  )
}
