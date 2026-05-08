import { useState, useEffect } from 'react'
import { api } from '../lib/api'

interface Skill {
  id: string
  name: string
  description: string
  active: boolean
  version: string
}

export function Skills() {
  const [skills, setSkills] = useState<Skill[]>([])
  const [loading, setLoading] = useState(true)

  const loadSkills = async () => {
    setLoading(true)
    try {
      const data = await api.getSkills()
      setSkills((data as Skill[]) || [])
    } catch (e) {
      console.error(e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadSkills()
  }, [])

  const toggleSkill = async (id: string) => {
    try {
      await api.toggleSkill(id)
      setSkills(prev => prev.map(s => s.id === id ? { ...s, active: !s.active } : s))
    } catch (e) {
      console.error(e)
    }
  }

  return (
    <div style={S.page}>
      <div style={S.header}>
        <div style={S.title}>Skills y Capacidades</div>
      </div>

      <div style={S.sectionLabel}>CAPACIDADES INSTALADAS</div>
      
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
            <div key={skill.id} style={{ ...S.itemRow, borderBottom: i < skills.length - 1 ? '1px solid var(--border)' : 'none' }}>
              <div style={{ ...S.iconBox, background: skill.active ? 'rgba(74,222,128,0.15)' : 'rgba(255,255,255,0.05)', border: `1px solid ${skill.active ? 'rgba(74,222,128,0.3)' : 'rgba(255,255,255,0.1)'}` }}>
                <span style={{ fontSize: 18 }}>{skill.active ? '✅' : '💤'}</span>
              </div>
              
              <div style={S.itemText}>
                <div style={S.itemName}>{skill.name}</div>
                <div style={S.itemDesc}>{skill.description}</div>
              </div>

              <button 
                onClick={() => toggleSkill(skill.id)}
                style={{ 
                  ...S.statusBadge, 
                  background: skill.active ? '#4ade80' : 'var(--surface2)',
                  color: skill.active ? '#064e3b' : 'var(--text3)',
                  border: `1px solid ${skill.active ? '#4ade80' : 'var(--border)'}`
                }}
              >
                {skill.active ? 'ACTIVO' : 'INACTIVO'}
              </button>
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
