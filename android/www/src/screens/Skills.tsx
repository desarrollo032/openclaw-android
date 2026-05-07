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
      setSkills(data || [])
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
      const result = await api.toggleSkill(id)
      setSkills(prev => prev.map(s => s.id === id ? { ...s, active: !s.active } : s))
    } catch (e) {
      console.error(e)
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div className="page-title">Habilidades y Skills</div>
      </div>

      <div className="settings-group">
        <div className="settings-group-title">Capacidades Instaladas</div>
        {loading ? (
          <div className="text-center p-4">Cargando skills...</div>
        ) : skills.length === 0 ? (
          <div className="card text-center" style={{ color: 'var(--text-secondary)' }}>
            No se encontraron skills instaladas.
          </div>
        ) : (
          <div className="settings-list">
            {skills.map(skill => (
              <div key={skill.id} className="settings-item">
                <div className="card-icon" style={{ background: skill.active ? 'var(--bg-primary)' : 'var(--bg-tertiary)' }}>
                  {skill.active ? '✅' : '💤'}
                </div>
                <div className="card-content">
                  <div className="card-label">{skill.name}</div>
                  <div className="card-desc">{skill.description}</div>
                </div>
                <div 
                  className="status-badge" 
                  onClick={() => toggleSkill(skill.id)}
                  style={{ 
                    cursor: 'pointer',
                    background: skill.active ? 'var(--success)' : 'var(--bg-tertiary)',
                    color: skill.active ? '#fff' : 'var(--text-secondary)',
                    padding: '6px 12px',
                    borderRadius: 20,
                    fontSize: 12,
                    fontWeight: 'bold'
                  }}
                >
                  {skill.active ? 'ACTIVO' : 'INACTIVO'}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="card mt-4" style={{ background: 'var(--bg-primary)', borderStyle: 'dashed' }}>
        <div style={{ textAlign: 'center', color: 'var(--text-secondary)', fontSize: 14 }}>
          Puedes instalar nuevas habilidades desde la terminal con el comando <code style={{ color: 'var(--primary)' }}>oa --install</code>.
        </div>
      </div>
    </div>
  )
}
