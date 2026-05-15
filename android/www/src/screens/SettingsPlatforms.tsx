import { useRoute } from '../lib/router'
import { Settings2 } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'
import { ContentCard } from '../components/ContentCard'

export function SettingsPlatforms() {
  const { navigate } = useRoute()
  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Plataformas"
        subtitle="Gestionar plataformas instaladas"
        icon={Settings2}
      />
      <ContentCard
        icon={Settings2}
        title="Plataformas"
        subtitle="Gestiona plataformas desde la terminal"
        command="openclaw configure --section platform"
        onAction={() => navigate('/terminal')}
      />
    </div>
  )
}
