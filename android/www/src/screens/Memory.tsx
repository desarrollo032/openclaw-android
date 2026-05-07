import { useState, useEffect } from 'react'
import { api } from '../lib/api'
import { t } from '../i18n'

interface MemoryItem {
  id: string
  content: string
  timestamp: number
  importance: number
}

export function Memory() {
  const [memories, setMemories] = useState<MemoryItem[]>([])
  const [loading, setLoading] = useState(true)

  const loadMemory = async () => {
    setLoading(true)
    try {
      const data = await api.getMemory()
      setMemories(data || [])
    } catch (e) {
      console.error(e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadMemory()
  }, [])

  const handleDelete = async (id: string) => {
    if (!confirm('¿Eliminar este recuerdo?')) return
    try {
      await api.deleteMemory(id)
      setMemories(prev => prev.filter(m => m.id !== id))
    } catch (e) {
      console.error(e)
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div className="page-title">Memoria y Contexto</div>
      </div>

      <div className="settings-group">
        <div className="settings-group-title">Recuerdos del Agente</div>
        {loading ? (
          <div className="text-center p-4">{t('storage_loading')}</div>
        ) : memories.length === 0 ? (
          <div className="card text-center" style={{ color: 'var(--text-secondary)' }}>
            No hay recuerdos guardados aún.
          </div>
        ) : (
          memories.map(m => (
            <div key={m.id} className="card">
              <div style={{ fontSize: 14, marginBottom: 8 }}>{m.content}</div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
                  {new Date(m.timestamp).toLocaleDateString()}
                </span>
                <button 
                  className="btn btn-secondary btn-small" 
                  style={{ color: 'var(--error)', border: 'none' }}
                  onClick={() => handleDelete(m.id)}
                >
                  Eliminar
                </button>
              </div>
            </div>
          ))
        )}
      </div>

      <div className="settings-group">
        <div className="settings-group-title">Contexto Actual</div>
        <div className="card">
          <div style={{ fontFamily: 'monospace', fontSize: 12, color: 'var(--text-secondary)' }}>
            El agente mantiene un contexto activo de la conversación actual para proporcionar respuestas coherentes.
          </div>
          <button className="btn btn-secondary mt-4" style={{ width: '100%' }} onClick={() => {
            localStorage.removeItem('chat_history')
            window.location.reload()
          }}>
            Limpiar contexto actual
          </button>
        </div>
      </div>
    </div>
  )
}
