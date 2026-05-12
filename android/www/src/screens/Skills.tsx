import { useState, useEffect, useCallback } from 'react'
import { getSkills, toggleSkill } from '../api/gateway'
import { useGatewayStatus } from '../hooks/useGatewayStatus'

interface Skill {
  id: string
  name?: string
  description?: string
  active?: boolean
  version?: string
}

export function Skills() {
  const [skills, setSkills] = useState<Skill[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const { reachability } = useGatewayStatus()
  const online = reachability === 'online'

  const loadSkills = useCallback(async () => {
    if (!online) {
      setError('Gateway no está disponible')
      setLoading(false)
      return
    }
    
    setLoading(true)
    setError(null)
    try {
      const data = await getSkills()
      setSkills(Array.isArray(data) ? (data as Skill[]) : [])
    } catch (e) {
      console.error(e)
      setError('No se pudieron cargar los skills')
      setSkills([])
    } finally {
      setLoading(false)
    }
  }, [online])

  useEffect(() => {
    loadSkills()
  }, [loadSkills])

  const toggleSkillHandler = useCallback(async (id: string) => {
    try {
      await toggleSkill(id)
      setSkills(prev => prev.map(s => (s.id === id ? { ...s, active: !s.active } : s)))
    } catch (e) {
      console.error(e)
    }
  }, [])

  return (
    <div className="modern-page" style={S.page}>
      <div style={S.header}>
        <div style={S.title}>Skills y Capacidades</div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <div style={S.sectionLabel}>CAPACIDADES INSTALADAS</div>
        <button 
          onClick={loadSkills} 
          style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 8, padding: '6px 12px', color: 'var(--text2)', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}
        >
          ↻ Actualizar
        </button>
      </div>
      
      {error && (
        <div style={{ padding: '12px 14px', borderRadius: 10, background: 'rgba(248,113,113,0.1)', border: '1px solid rgba(248,113,113,0.3)', color: '#fca5a5', fontSize: 13, fontWeight: 600, marginBottom: 16 }}>
          {error}
        </div>
      )}
      
      {loading ? (
        <div style={S.emptyState}>
          <div style={S.spinner} />
          <span style={{ marginTop: 12 }}>Cargando skills...</span>
        </div>
      ) : skills.length === 0 ? (
        <div style={S.emptyState}>
          <span style={{ fontSize: 32, marginBottom: 8 }}>🧩</span>
          <span>No se encontraron skills instaladas.</span>
        </div>
      ) : (
        <div style={S.card}>
          {skills.map((skill, i) => (
            <div key={skill.id || i} style={{ ...S.itemRow, borderBottom: i < skills.length - 1 ? '1px solid var(--border)' : 'none' }}>
              <div style={{ ...S.iconBox, background: skill.active ? 'rgba(74,222,128,0.15)' : 'rgba(255,255,255,0.05)', border: `1px solid ${skill.active ? 'rgba(74,222,128,0.3)' : 'rgba(255,255,255,0.1)'}` }}>
                <span style={{ fontSize: 18 }}>{skill.active ? '✅' : '💤'}</span>
              </div>
              
              <div style={S.itemText}>
                <div style={S.itemName}>{skill.name || skill.id}</div>
                <div style={S.itemDesc}>{skill.description || 'Sin descripción'}</div>
              </div>

              {skill.id && (
                <button 
                  onClick={() => toggleSkillHandler(skill.id)}
                  style={{ 
                    ...S.statusBadge, 
                    background: skill.active ? '#4ade80' : 'var(--surface2)',
                    color: skill.active ? '#064e3b' : 'var(--text3)',
                    border: `1px solid ${skill.active ? '#4ade80' : 'var(--border)'}`
                  }}
                >
                  {skill.active ? 'ACTIVO' : 'INACTIVO'}
                </button>
              )}
            </div>
          ))}
        </div>
      )}
      
      <div style={{ ...S.card, marginTop: 24, padding: 16, borderStyle: 'dashed', background: 'rgba(255,255,255,0.02)' }}>
        <div style={{ textAlign: 'center', color: 'var(--text3)', fontSize: 13, lineHeight: 1.5 }}>
          Puedes instalar nuevas habilidades desde la terminal con el comando <code style={S.inlineCode}>openclaw skills --install</code>.
        </div>
      </div>
    </div>
  )
}

const S: Record<string, React.CSSProperties> = {
  page: { padding: '12px 14px 32px', maxWidth: 600, margin: '0 auto', overflowY: 'auto' },
  header: { display: 'flex', alignItems: 'center', marginBottom: 24, paddingTop: 8 },
  title: { fontSize: 22, fontWeight: 800, color: 'var(--text)' },
  
  sectionLabel: { fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', color: 'var(--text3)', marginBottom: 10, paddingLeft: 2, textTransform: 'uppercase' },
  card: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-xl)', overflow: 'hidden', boxShadow: 'var(--sh-inset)' },
  
  itemRow: { display: 'flex', alignItems: 'center', gap: 14, padding: '14px 16px', background: 'transparent' },
  iconBox: { width: 42, height: 42, borderRadius: 12, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  itemText: { flex: 1, display: 'flex', flexDirection: 'column', gap: 2 },
  itemName: { fontSize: 15, fontWeight: 700, color: 'var(--text)' },
  itemDesc: { fontSize: 12, color: 'var(--text3)', lineHeight: 1.4 },
  
  statusBadge: { cursor: 'pointer', padding: '6px 12px', borderRadius: 20, fontSize: 10, fontWeight: 800, transition: 'all 0.2s', letterSpacing: '0.5px' },
  
  emptyState: { padding: 40, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', color: 'var(--text3)', fontSize: 14, background: 'var(--surface)', borderRadius: 'var(--r-xl)', border: '1px dashed var(--border)' },
  spinner: { width: 24, height: 24, border: '2px solid var(--border)', borderTopColor: 'var(--purple)', borderRadius: '50%', animation: 'spin 0.8s linear infinite' },
  
  inlineCode: { background: 'rgba(0,0,0,0.3)', borderRadius: 4, padding: '2px 6px', fontSize: 12, fontFamily: "'JetBrains Mono', monospace", color: '#c4b5fd' },
}
