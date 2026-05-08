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
    <div style={S.page}>
      <div style={S.header}>
        <div style={S.title}>Memoria y Contexto</div>
      </div>

      <div style={S.sectionLabel}>RECUERDOS DEL AGENTE</div>
      
      {loading ? (
        <div style={S.emptyState}>
          <div style={S.spinner} />
          <span style={{ marginTop: 12 }}>{t('storage_loading') || 'Cargando memoria...'}</span>
        </div>
      ) : memories.length === 0 ? (
        <div style={S.emptyState}>
          <span style={{ fontSize: 32, marginBottom: 8 }}>🧠</span>
          <span>No hay recuerdos guardados aún.</span>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {memories.map(m => (
            <div key={m.id} style={S.memoryCard}>
              <div style={S.memoryContent}>{m.content}</div>
              <div style={S.memoryMeta}>
                <span style={S.memoryDate}>
                  {new Date(m.timestamp).toLocaleDateString()}
                </span>
                <button style={S.deleteBtn} onClick={() => handleDelete(m.id)}>
                  Eliminar
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      <div style={S.sectionLabel}>CONTEXTO ACTUAL</div>
      <div style={S.card}>
        <div style={S.cardBody}>
          <div style={S.contextText}>
            El agente mantiene un contexto activo de la conversación actual para proporcionar respuestas coherentes.
          </div>
          <button style={S.clearBtn} onClick={() => {
            localStorage.removeItem('oc_chat_v3')
            window.location.reload()
          }}>
            Limpiar contexto actual
          </button>
        </div>
      </div>
    </div>
  )
}

const S: Record<string, React.CSSProperties> = {
  page: { padding: '12px 14px 32px', maxWidth: 600, margin: '0 auto', overflowY: 'auto' },
  header: { display: 'flex', alignItems: 'center', marginBottom: 24, paddingTop: 8 },
  title: { fontSize: 22, fontWeight: 800, color: 'var(--text)' },
  
  sectionLabel: { fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', color: 'var(--text3)', marginBottom: 10, marginTop: 24, paddingLeft: 2, textTransform: 'uppercase' },
  card: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-xl)', overflow: 'hidden', boxShadow: 'var(--sh-inset)' },
  cardBody: { padding: 16 },
  
  memoryCard: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 10 },
  memoryContent: { fontSize: 14, color: 'var(--text)', lineHeight: 1.5 },
  memoryMeta: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' },
  memoryDate: { fontSize: 11, color: 'var(--text3)' },
  
  deleteBtn: { background: 'rgba(248,113,113,0.1)', border: '1px solid rgba(248,113,113,0.2)', color: '#fca5a5', padding: '4px 10px', borderRadius: 6, fontSize: 11, fontWeight: 600, cursor: 'pointer' },
  clearBtn: { width: '100%', background: 'var(--surface2)', border: '1px solid var(--border2)', color: 'var(--text2)', padding: '10px', borderRadius: 'var(--r-lg)', fontSize: 13, fontWeight: 600, cursor: 'pointer', marginTop: 16 },
  
  contextText: { fontFamily: "'JetBrains Mono', monospace", fontSize: 12, color: 'var(--text3)', lineHeight: 1.5 },
  
  emptyState: { padding: 40, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', color: 'var(--text3)', fontSize: 14, background: 'var(--surface)', borderRadius: 'var(--r-xl)', border: '1px dashed var(--border)' },
  spinner: { width: 24, height: 24, border: '2px solid var(--border)', borderTopColor: 'var(--purple)', borderRadius: '50%', animation: 'spin 0.8s linear infinite' },
}
