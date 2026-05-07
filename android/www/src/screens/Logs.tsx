import { useState, useEffect } from 'react'
import { api } from '../lib/api'

interface LogEntry {
  timestamp: number
  level: 'info' | 'warn' | 'error' | 'debug'
  message: string
  source: string
}

export function Logs() {
  const [logs, setLogs] = useState<LogEntry[]>([])
  const [filter, setFilter] = useState<string>('all')

  const loadLogs = async () => {
    try {
      const data = await api.getLogs()
      setLogs(data || [])
    } catch (e) {
      console.error(e)
    }
  }

  useEffect(() => {
    loadLogs()
    const interval = setInterval(loadLogs, 5000)
    return () => clearInterval(interval)
  }, [])

  const filteredLogs = logs.filter(l => filter === 'all' || l.level === filter)

  return (
    <div className="page">
      <div className="page-header">
        <div className="page-title">Actividad y Logs</div>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 16, overflowX: 'auto', paddingBottom: 8 }}>
        {['all', 'info', 'warn', 'error', 'debug'].map(l => (
          <button 
            key={l}
            className={`btn btn-small ${filter === l ? 'btn-primary' : 'btn-secondary'}`}
            style={{ borderRadius: 20, textTransform: 'capitalize' }}
            onClick={() => setFilter(l)}
          >
            {l}
          </button>
        ))}
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ 
          background: '#000', 
          color: '#0f0', 
          fontFamily: 'monospace', 
          fontSize: 12, 
          padding: 12, 
          minHeight: '60vh',
          maxHeight: '70vh',
          overflowY: 'auto'
        }}>
          {filteredLogs.map((log, i) => (
            <div key={i} style={{ marginBottom: 4, whiteSpace: 'pre-wrap' }}>
              <span style={{ color: '#888' }}>[{new Date(log.timestamp).toLocaleTimeString()}]</span>{' '}
              <span style={{ 
                color: log.level === 'error' ? '#f44' : log.level === 'warn' ? '#fa0' : '#0f0',
                fontWeight: 'bold'
              }}>
                {log.level.toUpperCase()}
              </span>:{' '}
              {log.message}
            </div>
          ))}
          {filteredLogs.length === 0 && <div style={{ color: '#555' }}>No hay logs disponibles...</div>}
        </div>
      </div>

      <button className="btn btn-secondary mt-4" style={{ width: '100%' }} onClick={loadLogs}>
        Actualizar Logs
      </button>
    </div>
  )
}
