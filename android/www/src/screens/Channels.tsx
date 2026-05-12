import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { fetchGatewayConfig, patchGatewayConfig } from '../lib/gateway'

/* ── Types ─────────────────────────────────────────────── */
interface ChannelStatus {
    id: string
    name: string
    icon: string
    enabled: boolean
    configured: boolean
    description: string
    configKey: string
}

interface TelegramConfig {
    enabled?: boolean
    botToken?: string
    dmPolicy?: 'pairing' | 'allowlist' | 'open' | 'disabled'
    allowFrom?: string[]
}

/* ── Component ─────────────────────────────────────────── */
export function Channels() {
    const { navigate } = useRoute()
    const [channels, setChannels] = useState<ChannelStatus[]>([
        { id: 'telegram', name: 'Telegram', icon: 'TG', enabled: false, configured: false, description: 'Bot de Telegram para mensajes y comandos', configKey: 'channels.telegram' },
        { id: 'whatsapp', name: 'WhatsApp', icon: 'WA', enabled: false, configured: false, description: 'WhatsApp Business API', configKey: 'channels.whatsapp' },
        { id: 'discord', name: 'Discord', icon: 'DS', enabled: false, configured: false, description: 'Bot de Discord', configKey: 'channels.discord' },
        { id: 'slack', name: 'Slack', icon: 'SL', enabled: false, configured: false, description: 'Workspace de Slack', configKey: 'channels.slack' },
        { id: 'signal', name: 'Signal', icon: 'SG', enabled: false, configured: false, description: 'Mensajes cifrados Signal', configKey: 'channels.signal' },
        { id: 'msteams', name: 'Microsoft Teams', icon: 'MT', enabled: false, configured: false, description: 'Microsoft Teams', configKey: 'channels.msteams' },
    ])
    const [selectedChannel, setSelectedChannel] = useState<string | null>(null)
    const [telegramConfig, setTelegramConfig] = useState<TelegramConfig>({
        enabled: false, botToken: '', dmPolicy: 'pairing', allowFrom: []
    })
    const [saving, setSaving] = useState(false)
    const [savedMsg, setSavedMsg] = useState('')
    const [loading, setLoading] = useState(true)

    const load = useCallback(async () => {
        setLoading(true)
        try {
            const cfg = await fetchGatewayConfig()
            if (cfg) {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                const raw = cfg as any
                setChannels(prev => prev.map(ch => {
                    const chCfg = raw?.channels?.[ch.id]
                    return {
                        ...ch,
                        enabled: !!chCfg?.enabled,
                        configured: !!(chCfg?.botToken || chCfg?.apiKey || chCfg?.token || chCfg?.enabled),
                    }
                }))
                if (raw?.channels?.telegram) {
                    setTelegramConfig({
                        enabled: raw.channels.telegram.enabled ?? false,
                        botToken: raw.channels.telegram.botToken ?? '',
                        dmPolicy: raw.channels.telegram.dmPolicy ?? 'pairing',
                        allowFrom: raw.channels.telegram.allowFrom ?? [],
                    })
                }
            }
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => { load() }, [load])

    const saveTelegram = async () => {
        setSaving(true)
        try {
            const ok = await patchGatewayConfig({
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                ...({ channels: { telegram: telegramConfig } } as any)
            })
            if (ok) {
                setSavedMsg('✓ Guardado')
                setTimeout(() => setSavedMsg(''), 2000)
                load()
            }
        } finally {
            setSaving(false)
        }
    }

    if (loading) return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '60vh', flexDirection: 'column', gap: 16 }}>
            <div style={{ width: 36, height: 36, border: '3px solid #22223a', borderTopColor: '#6366f1', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />
            <span style={{ color: '#a0a0c0', fontSize: 14 }}>Cargando canales...</span>
        </div>
    )

    return (
        <div className="page modern-page" style={{ paddingBottom: 32 }}>
            <div className="page-header">
                <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
                <div className="page-title">Canales y Conexiones</div>
                {savedMsg && <span style={{ fontSize: 13, color: '#4ade80', fontWeight: 600, marginLeft: 'auto' }}>{savedMsg}</span>}
            </div>

            {/* ── Channel list ── */}
            {selectedChannel === null && (
                <>
                    <div style={S.sectionLabel}>CANALES DISPONIBLES</div>
                    <div style={S.listCard}>
                        {channels.map((ch, i) => (
                            <button key={ch.id}
                                style={{ ...S.channelRow, borderBottom: i < channels.length - 1 ? '1px solid #1a1a2e' : 'none' }}
                                onClick={() => setSelectedChannel(ch.id)}
                            >
                                <span style={{ fontSize: 11, width: 36, height: 36, display:'grid', placeItems:'center', textAlign: 'center', borderRadius: 10, background:'rgba(99,102,241,0.14)', border:'1px solid rgba(99,102,241,0.35)', fontWeight:800, letterSpacing:'.04em' }}>{ch.icon}</span>
                                <div style={S.channelInfo}>
                                    <span style={S.channelName}>{ch.name}</span>
                                    <span style={S.channelDesc}>{ch.description}</span>
                                </div>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                    {ch.configured && (
                                        <span style={{ ...S.badge, background: ch.enabled ? '#14532d' : '#1e1e35', color: ch.enabled ? '#4ade80' : '#555577' }}>
                                            {ch.enabled ? 'Activo' : 'Config'}
                                        </span>
                                    )}
                                    <span style={S.chevron}>›</span>
                                </div>
                            </button>
                        ))}
                    </div>

                    <div style={S.infoBox}>
                        <div style={{ fontSize: 13, color: '#a0a0c0', lineHeight: 1.6 }}>
                            <strong style={{ color: '#e2e8f0' }}>Flujo de Telegram:</strong><br />
                            1. Crea un bot en @BotFather y copia el token<br />
                            2. Configura el token aquí y guarda<br />
                            3. Inicia el gateway: <code style={S.code}>openclaw gateway run</code><br />
                            4. Envía un mensaje al bot desde Telegram<br />
                            5. El bot responderá con un código de pairing<br />
                            6. Aprueba: <code style={S.code}>openclaw channels approve telegram &lt;CODE&gt;</code>
                        </div>
                    </div>
                </>
            )}

            {/* ── Telegram config ── */}
            {selectedChannel === 'telegram' && (
                <TelegramSetup
                    config={telegramConfig}
                    onChange={setTelegramConfig}
                    onSave={saveTelegram}
                    onBack={() => setSelectedChannel(null)}
                    saving={saving}
                />
            )}

            {/* ── Other channels (coming soon) ── */}
            {selectedChannel !== null && selectedChannel !== 'telegram' && (
                <div style={{ padding: 24, textAlign: 'center' }}>
                    <div style={{ fontSize: 48, marginBottom: 16 }}>
                        {channels.find(c => c.id === selectedChannel)?.icon}
                    </div>
                    <div style={{ fontSize: 16, fontWeight: 700, color: '#e2e8f0', marginBottom: 8 }}>
                        {channels.find(c => c.id === selectedChannel)?.name}
                    </div>
                    <div style={{ fontSize: 13, color: '#a0a0c0', marginBottom: 24 }}>
                        Configura este canal via terminal:
                    </div>
                    <code style={{ ...S.code, display: 'block', padding: '12px 16px', background: '#12122a', borderRadius: 10, marginBottom: 16 }}>
                        openclaw configure --section channels
                    </code>
                    <button style={S.backBtn} onClick={() => setSelectedChannel(null)}>← Volver</button>
                </div>
            )}
        </div>
    )
}

/* ── Telegram Setup ─────────────────────────────────────── */
function TelegramSetup({
    config, onChange, onSave, onBack, saving
}: {
    config: TelegramConfig
    onChange: (c: TelegramConfig) => void
    onSave: () => void
    onBack: () => void
    saving: boolean
}) {
    return (
        <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20 }}>
                <button style={S.backBtn} onClick={onBack}>← Volver</button>
                <span style={{ fontSize: 18, fontWeight: 700, color: '#e2e8f0' }}>✈️ Telegram</span>
            </div>

            <div style={S.formCard}>
                {/* Enable toggle */}
                <div style={S.formRow}>
                    <div>
                        <div style={S.formLabel}>Habilitar canal</div>
                        <div style={S.formHint}>Activa el bot de Telegram</div>
                    </div>
                    <label style={S.toggle}>
                        <input type="checkbox" checked={!!config.enabled}
                            onChange={e => onChange({ ...config, enabled: e.target.checked })} />
                        <span style={{ ...S.toggleTrack, background: config.enabled ? '#6366f1' : '#1e1e35' }}>
                            <span style={{ ...S.toggleThumb, transform: config.enabled ? 'translateX(20px)' : 'translateX(2px)' }} />
                        </span>
                    </label>
                </div>

                {/* Bot token */}
                <div style={S.formGroup}>
                    <label style={S.formLabel}>Bot Token</label>
                    <div style={S.formHint}>Obtén el token de @BotFather en Telegram</div>
                    <input
                        style={S.input}
                        type="password"
                        placeholder="123456789:ABCdefGHIjklMNOpqrSTUvwxYZ"
                        value={config.botToken ?? ''}
                        onChange={e => onChange({ ...config, botToken: e.target.value })}
                    />
                </div>

                {/* DM Policy */}
                <div style={S.formGroup}>
                    <label style={S.formLabel}>Política de DMs</label>
                    <div style={S.formHint}>Quién puede enviar mensajes al bot</div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}>
                        {([
                            { value: 'pairing', label: 'Pairing (recomendado)', desc: 'Usuarios desconocidos reciben un código de pairing' },
                            { value: 'allowlist', label: 'Lista blanca', desc: 'Solo usuarios en allowFrom' },
                            { value: 'open', label: 'Abierto', desc: 'Cualquier usuario puede escribir' },
                            { value: 'disabled', label: 'Deshabilitado', desc: 'Ignorar todos los DMs' },
                        ] as const).map(opt => (
                            <button key={opt.value}
                                style={{
                                    ...S.policyBtn,
                                    border: `1px solid ${config.dmPolicy === opt.value ? '#6366f1' : '#1e1e35'}`,
                                    background: config.dmPolicy === opt.value ? 'rgba(99,102,241,0.1)' : '#12122a',
                                }}
                                onClick={() => onChange({ ...config, dmPolicy: opt.value })}
                            >
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                    <span style={{ color: config.dmPolicy === opt.value ? '#6366f1' : '#555577', fontSize: 16 }}>
                                        {config.dmPolicy === opt.value ? '●' : '○'}
                                    </span>
                                    <div style={{ textAlign: 'left' }}>
                                        <div style={{ fontSize: 14, fontWeight: 600, color: '#e2e8f0' }}>{opt.label}</div>
                                        <div style={{ fontSize: 11, color: '#555577' }}>{opt.desc}</div>
                                    </div>
                                </div>
                            </button>
                        ))}
                    </div>
                </div>

                <button
                    style={{ ...S.saveBtn, opacity: saving ? 0.7 : 1 }}
                    onClick={onSave}
                    disabled={saving}
                >
                    {saving ? 'Guardando...' : '💾 Guardar configuración'}
                </button>
            </div>

            {/* Pairing instructions */}
            <div style={S.infoBox}>
                <div style={{ fontSize: 13, fontWeight: 700, color: '#e2e8f0', marginBottom: 8 }}>
                    📋 Cómo conectar Telegram
                </div>
                <div style={{ fontSize: 12, color: '#a0a0c0', lineHeight: 1.8 }}>
                    <strong>1.</strong> Crea un bot: abre Telegram → @BotFather → /newbot<br />
                    <strong>2.</strong> Copia el token y pégalo arriba<br />
                    <strong>3.</strong> Guarda y reinicia el gateway<br />
                    <strong>4.</strong> Envía cualquier mensaje a tu bot<br />
                    <strong>5.</strong> El bot responde con un código de 8 caracteres<br />
                    <strong>6.</strong> En el terminal ejecuta:<br />
                    <code style={{ ...S.code, display: 'block', marginTop: 4, padding: '8px 12px', background: '#0a0a18', borderRadius: 8 }}>
                        openclaw channels approve telegram &lt;CÓDIGO&gt;
                    </code>
                </div>
            </div>
        </div>
    )
}

/* ── Styles ─────────────────────────────────────────────── */
const S: Record<string, React.CSSProperties> = {
    sectionLabel: { fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', color: '#555577', marginBottom: 8, paddingLeft: 2 },
    listCard: { background: '#12122a', border: '1px solid #1e1e35', borderRadius: 14, marginBottom: 16, overflow: 'hidden' },
    channelRow: { width: '100%', display: 'flex', alignItems: 'center', gap: 12, padding: '14px 16px', background: 'transparent', border: 'none', cursor: 'pointer', textAlign: 'left' },
    channelInfo: { flex: 1, display: 'flex', flexDirection: 'column', gap: 2 },
    channelName: { fontSize: 15, fontWeight: 600, color: '#e2e8f0' },
    channelDesc: { fontSize: 12, color: '#555577' },
    badge: { fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 10 },
    chevron: { fontSize: 20, color: '#333355' },
    infoBox: { background: '#0d0d1a', border: '1px solid #1e1e35', borderRadius: 12, padding: '14px 16px', marginBottom: 16 },
    code: { fontFamily: 'monospace', fontSize: 12, color: '#a5b4fc' },
    backBtn: { background: '#1e1e35', border: '1px solid #2d2d4e', borderRadius: 8, color: '#a0a0c0', padding: '8px 14px', fontSize: 13, cursor: 'pointer' },
    formCard: { background: '#12122a', border: '1px solid #1e1e35', borderRadius: 14, padding: '16px', marginBottom: 16 },
    formRow: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingBottom: 14, marginBottom: 14, borderBottom: '1px solid #1e1e35' },
    formGroup: { marginBottom: 16 },
    formLabel: { fontSize: 14, fontWeight: 600, color: '#e2e8f0', marginBottom: 2 },
    formHint: { fontSize: 11, color: '#555577', marginBottom: 6 },
    input: { width: '100%', background: '#0d0d1a', border: '1px solid #2d2d4e', borderRadius: 8, color: '#e2e8f0', padding: '10px 12px', fontSize: 14, fontFamily: 'monospace', boxSizing: 'border-box' },
    toggle: { display: 'flex', alignItems: 'center', cursor: 'pointer' },
    toggleTrack: { width: 44, height: 24, borderRadius: 12, position: 'relative', transition: 'background 0.2s', display: 'inline-block' },
    toggleThumb: { position: 'absolute', top: 2, width: 20, height: 20, borderRadius: '50%', background: '#fff', transition: 'transform 0.2s' },
    policyBtn: { width: '100%', padding: '10px 14px', borderRadius: 10, cursor: 'pointer', textAlign: 'left' },
    saveBtn: { width: '100%', padding: '14px', background: '#6366f1', border: 'none', borderRadius: 10, color: '#fff', fontSize: 15, fontWeight: 700, cursor: 'pointer', marginTop: 8 },
}
