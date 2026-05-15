import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { fetchGatewayConfig, patchGatewayConfig } from '../lib/gateway'
import { Globe, MessageCircle, Smartphone, Wifi, Users, Check, ChevronRight } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'
import { ListItem } from '../components/ListItem'

const CHANNELS_LIST = [
  { id: 'telegram', label: 'Telegram', desc: 'Chat de notificaciones y comandos', icon: MessageCircle, color: '#26A5E4' },
  { id: 'whatsapp', label: 'WhatsApp', desc: 'Integración con WhatsApp', icon: Smartphone, color: '#25D366' },
  { id: 'discord',  label: 'Discord',  desc: 'Canal de servidor Discord', icon: Wifi, color: '#5865F2' },
  { id: 'slack',    label: 'Slack',    desc: 'Workspace de Slack', icon: Globe, color: '#4A154B' },
  { id: 'signal',   label: 'Signal',   desc: 'Mensajería Signal', icon: MessageCircle, color: '#3A76F0' },
  { id: 'teams',    label: 'Teams',    desc: 'Microsoft Teams', icon: Users, color: '#6264A7' },
]

export function Channels() {
  const { navigate } = useRoute()
  const [editingTelegram, setEditingTelegram] = useState(false)
  const [teleCfg, setTeleCfg] = useState({ enabled: false, botToken: '', dmPolicy: 'pairing', allowlist: '' })
  const [globalEnabled, setGlobalEnabled] = useState(false)

  useEffect(() => {
    const load = async () => {
      const cfg = await fetchGatewayConfig()
      if (!cfg) return
      const cfgRecord = cfg as unknown as Record<string, unknown>
      const channelsRecord = (cfgRecord?.channels ?? {}) as Record<string, unknown>
      const tele = (channelsRecord?.telegram ?? {}) as Record<string, unknown>
      setTeleCfg({
        enabled: (tele.enabled as boolean) ?? false,
        botToken: (tele.botToken as string) ?? '',
        dmPolicy: (tele.dmPolicy as string) ?? 'pairing',
        allowlist: Array.isArray(tele.allowlist) ? (tele.allowlist as string[]).join(', ') : '',
      })
      setGlobalEnabled((channelsRecord?.enabled as boolean) ?? false)
    }
    load()
  }, [])

  const saveTelegram = async () => {
    const allowlist = teleCfg.allowlist.split(',').map(s => s.trim()).filter(Boolean)
    await patchGatewayConfig({
      channels: {
        enabled: globalEnabled,
        telegram: { enabled: teleCfg.enabled, botToken: teleCfg.botToken, dmPolicy: teleCfg.dmPolicy, allowlist },
      },
    } as never)
  }

  // ── Telegram edit view ──
  if (editingTelegram) {
    return (
      <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
        <PageHeader
          title="Telegram"
          subtitle="Configuración del canal"
          icon={MessageCircle}
          backTo="/settings/channels"
          backLabel="Canales"
          iconBg="bg-cyan-soft"
          iconColor="text-cyan"
        />

        <div className="card p-5 space-y-4">
          <label className="flex items-center justify-between">
            <span className="text-sm font-semibold text-text-primary">Habilitado</span>
            <div onClick={() => setTeleCfg(p => ({ ...p, enabled: !p.enabled }))}
              className={`toggle ${teleCfg.enabled ? 'active' : ''}`}>
              <div className="toggle-knob" />
            </div>
          </label>

          <div>
            <label className="text-xs font-semibold text-text-muted mb-1.5 block">Bot Token</label>
            <input className="input-field text-xs"
              placeholder="123456:ABC-DEF..."
              value={teleCfg.botToken}
              onChange={e => setTeleCfg(p => ({ ...p, botToken: e.target.value }))} />
          </div>

          <div>
            <label className="text-xs font-semibold text-text-muted mb-1.5 block">DM Policy</label>
            <div className="flex gap-1.5">
              {['pairing', 'allowlist', 'open', 'disabled'].map(p => (
                <button key={p} onClick={() => setTeleCfg(prev => ({ ...prev, dmPolicy: p }))}
                  className={`flex-1 py-2 rounded-lg text-[10px] font-semibold transition-all ${
                    teleCfg.dmPolicy === p ? 'bg-accent text-white' : 'bg-glass-bg text-text-muted hover:text-text-secondary'
                  }`}>{p}</button>
              ))}
            </div>
          </div>

          <div>
            <label className="text-xs font-semibold text-text-muted mb-1.5 block">Allowlist (IDs separados por coma)</label>
            <input className="input-field text-xs"
              placeholder="123456789, 987654321"
              value={teleCfg.allowlist}
              onChange={e => setTeleCfg(p => ({ ...p, allowlist: e.target.value }))} />
          </div>

          <button onClick={saveTelegram}
            className="btn btn-primary w-full text-xs py-2.5">
            <Check size={14} /> Guardar configuración
          </button>
        </div>
      </div>
    )
  }

  // ── Channels list view ──
  return (
    <div className="page-container flex flex-col gap-5 pb-4 animate-fade-in">
      <PageHeader
        title="Canales"
        subtitle="Notificaciones y comunicación"
        icon={Globe}
      />

      <div className="card divide-y divide-glass-border overflow-hidden">
        {CHANNELS_LIST.map(ch => (
          <ListItem
            key={ch.id}
            title={ch.label}
            subtitle={ch.desc}
            imgSrc={`https://ui-avatars.com/api/?name=${ch.label}&background=${ch.color.replace('#', '')}22&color=${ch.color.replace('#', '')}`}
            rightAction={ch.id === 'telegram' ? (
              <button onClick={() => setEditingTelegram(true)}
                className="btn btn-ghost text-[10px] px-2.5 py-1.5">
                Configurar
              </button>
            ) : (
              <button onClick={() => navigate('/terminal')}
                className="btn btn-ghost text-[10px] px-2.5 py-1.5">
                <ChevronRight size={12} />
              </button>
            )}
          />
        ))}
      </div>

      <div className="card p-4">
        <p className="text-xs text-text-muted leading-relaxed">
          Para configurar otros canales, usa el comando en la terminal:
        </p>
        <div className="mt-2 px-3 py-2 rounded-xl bg-input-bg border border-glass-border font-mono text-[11px] text-text-secondary overflow-x-auto">
          openclaw configure --section channels
        </div>
      </div>
    </div>
  )
}
