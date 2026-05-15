import { useRoute } from '../lib/router'
import { Battery } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'
import { ContentCard } from '../components/ContentCard'

export function SettingsKeepAlive() {
  const { navigate } = useRoute()
  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Keep Alive"
        subtitle="Gestión de batería y Phantom Process Killer"
        icon={Battery}
        iconBg="bg-yellow-soft"
        iconColor="text-yellow"
      />
      <ContentCard
        icon={Battery}
        iconBg="bg-yellow-soft"
        iconColor="text-yellow"
        title="Keep Alive"
        subtitle="En desarrollo — usa la terminal para configurar"
        command="openclaw configure --section system.keepalive"
        actionLabel="Ir a terminal"
        onAction={() => navigate('/terminal')}
      />
    </div>
  )
}
