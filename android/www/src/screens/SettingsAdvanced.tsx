import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'


export function SettingsAdvanced() {
  const { navigate } = useRoute()
  const [jsonContent, setJsonContent] = useState('{\n  \n}')
  const [originalContent, setOriginalContent] = useState('{\n  \n}')
  const [token, setToken] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState('')

  useEffect(() => {
    const init = () => {
      setLoading(true)
      
      // Obtener token de forma segura
      if (bridge.isAvailable()) {
        let t = ''
        try {
          t = bridge.call('getAuthToken') || ''
          if (!t) t = bridge.call('getGatewayToken') || ''
          if (!t) t = window.__OPENCLAW_TOKEN || ''
        } catch {
          t = ''
        }
        setToken(t)
        
        // Leer openclaw.json usando el nuevo método directo
        try {
          const rawResult = bridge.call('readOpenclawJson')
          if (rawResult) {
            const result = JSON.parse(rawResult)
            if (result.success) {
              setJsonContent(result.content)
              setOriginalContent(result.content)
            } else {
              setMsg('Error al leer la configuración: ' + (result.error || 'Error desconocido'))
            }
          }
        } catch (e) {
          setMsg('Error al leer la configuración: ' + (e as Error).message)
        }
      }
      
      setLoading(false)
    }
    init()
  }, [])

  const handleSave = () => {
    setMsg('')
    try {
      JSON.parse(jsonContent) // Validar sintaxis
    } catch {
      setMsg('⚠️ Error: Sintaxis JSON inválida.')
      return
    }
    setSaving(true)
    
    if (bridge.isAvailable()) {
      try {
        const rawResult = bridge.call('writeOpenclawJson', jsonContent)
        if (rawResult) {
          const result = JSON.parse(rawResult)
          if (result.success) {
            setMsg('✅ Guardado correctamente. Recomendable reiniciar el gateway.')
            setOriginalContent(jsonContent)
          } else {
            setMsg('❌ Error al guardar: ' + (result.error || 'Error desconocido'))
          }
        }
      } catch (e) {
        setMsg('❌ Error al guardar: ' + (e as Error).message)
      }
    }
    setSaving(false)
  }

  const copyToken = () => {
    try { navigator.clipboard?.writeText(token) }
    catch { bridge.call('copyToClipboard', token) }
    setMsg('Token copiado al portapapeles.')
    setTimeout(() => setMsg(m => m === 'Token copiado al portapapeles.' ? '' : m), 2000)
  }

  const isChanged = jsonContent !== originalContent

  return (
    <div style={S.page}>
      <div style={S.header}>
        <button style={S.backBtn} onClick={() => navigate('/settings')}>←</button>
        <div style={S.title}>Configuración Avanzada</div>
      </div>

      {msg && (
        <div style={{ ...S.msg, background: msg.includes('Error') || msg.includes('❌') ? 'rgba(248,113,113,0.1)' : 'rgba(74,222,128,0.1)', color: msg.includes('Error') || msg.includes('❌') ? '#fca5a5' : '#86efac', border: `1px solid ${msg.includes('Error') || msg.includes('❌') ? 'rgba(248,113,113,0.3)' : 'rgba(74,222,128,0.3)'}`}}>
          {msg}
        </div>
      )}

      <div style={S.sectionLabel}>TOKEN DE AUTENTICACIÓN</div>
      <div style={S.card}>
        <div style={S.cardBody}>
          <div style={{ fontSize: 12, color: 'var(--text3)', marginBottom: 8, lineHeight: 1.4 }}>
            Este es el Bearer token asignado para acceder a la API HTTP del gateway:
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <input 
              readOnly 
              value={token || '—'} 
              style={{ flex: 1, background: 'var(--surface2)', border: '1px solid var(--border)', borderRadius: 8, padding: '8px 12px', color: 'var(--purple)', fontFamily: "'JetBrains Mono', monospace", fontSize: 12 }} 
            />
            <button style={S.copyBtn} onClick={copyToken}>Copiar</button>
          </div>
        </div>
      </div>

      <div style={S.sectionLabel}>OPENCLAW.JSON</div>
      <div style={S.card}>
        {loading ? (
          <div style={S.loadingState}>
            <div style={S.spinner} />
            <span>Cargando configuración...</span>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            <textarea
              value={jsonContent}
              onChange={e => setJsonContent(e.target.value)}
              spellCheck={false}
              style={{
                width: '100%',
                height: '350px',
                background: 'transparent',
                border: 'none',
                color: '#e2e8f0',
                fontFamily: "'JetBrains Mono', monospace",
                fontSize: 12,
                lineHeight: 1.5,
                padding: '16px',
                outline: 'none',
                resize: 'none',
                whiteSpace: 'pre',
                overflowWrap: 'normal',
                overflowX: 'auto',
              }}
            />
            <div style={{ padding: '12px 16px', borderTop: '1px solid var(--border)', background: 'var(--surface2)', display: 'flex', justifyContent: 'flex-end', gap: 12, alignItems: 'center' }}>
              {isChanged && <span style={{ color: 'var(--yellow)', fontSize: 12, fontWeight: 600 }}>Cambios sin guardar</span>}
              <button 
                style={{ ...S.saveBtn, opacity: (!isChanged || saving) ? 0.5 : 1, cursor: (!isChanged || saving) ? 'not-allowed' : 'pointer' }} 
                onClick={handleSave} 
                disabled={!isChanged || saving}
              >
                {saving ? 'Guardando...' : 'Guardar JSON'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

const S: Record<string, React.CSSProperties> = {
  page: { padding: '12px 14px 32px', maxWidth: 600, margin: '0 auto', overflowY: 'auto' },
  header: { display: 'flex', alignItems: 'center', marginBottom: 24, paddingTop: 8, gap: 12 },
  backBtn: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '50%', width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text)', cursor: 'pointer', fontSize: 18 },
  title: { fontSize: 20, fontWeight: 800, color: 'var(--text)' },
  
  msg: { padding: '10px 14px', borderRadius: 10, fontSize: 13, fontWeight: 600, marginBottom: 16 },
  
  sectionLabel: { fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', color: 'var(--text3)', marginBottom: 10, marginTop: 24, paddingLeft: 2, textTransform: 'uppercase' },
  card: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-xl)', overflow: 'hidden', boxShadow: 'var(--sh-inset)' },
  cardBody: { padding: '16px' },
  
  copyBtn: { background: 'var(--surface3)', border: '1px solid var(--border)', color: 'var(--text)', padding: '0 12px', borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: 'pointer' },
  saveBtn: { background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', border: 'none', color: '#fff', padding: '8px 16px', borderRadius: 8, fontSize: 13, fontWeight: 700, boxShadow: '0 2px 8px rgba(99,102,241,0.3)' },
  
  loadingState: { padding: 40, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', color: 'var(--text3)', fontSize: 13 },
  spinner: { width: 24, height: 24, border: '2px solid var(--border)', borderTopColor: 'var(--purple)', borderRadius: '50%', animation: 'spin 0.8s linear infinite', marginBottom: 12 },
}
