import { useState, useCallback, useEffect, useRef } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'

interface Props { onComplete: () => void }
type Phase = 'welcome' | 'verifying' | 'installing' | 'done' | 'error'
interface LogLine { id: number; text: string; type: 'info' | 'success' | 'warn' | 'error' }

const STEPS = [
  { id: 'verify',  label: 'Verificando integridad',  icon: '🔐' },
  { id: 'extract', label: 'Extrayendo payload',       icon: '📦' },
  { id: 'config',  label: 'Restaurando configuración',icon: '⚙️' },
  { id: 'busybox', label: 'Configurando BusyBox',     icon: '🔧' },
  { id: 'ready',   label: 'Gateway listo',            icon: '🚀' },
]

export function Setup({ onComplete }: Props) {
  const [phase, setPhase]       = useState<Phase>('welcome')
  const [manual, setManual]     = useState(false)
  const [pickedPayload, setPickedPayload] = useState<string | null>(null)
  const [pickedConfig,  setPickedConfig]  = useState<string | null>(null)
  const [progress, setProgress] = useState(0)
  const [logs, setLogs]         = useState<LogLine[]>([])
  const [dots, setDots]         = useState('')
  const [currentStep, setCurrentStep] = useState(0)
  const [errorMsg, setErrorMsg] = useState('')
  const logRef     = useRef<HTMLDivElement>(null)
  const logCounter = useRef(0)

  // Animated dots
  useEffect(() => {
    if (phase !== 'installing' && phase !== 'verifying') return
    const id = setInterval(() => setDots(d => d.length >= 3 ? '' : d + '.'), 400)
    return () => clearInterval(id)
  }, [phase])

  // Auto-scroll logs
  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight, behavior: 'smooth' })
  }, [logs])

  const addLog = useCallback((text: string, type: LogLine['type'] = 'info') => {
    setLogs(prev => [...prev.slice(-60), { id: logCounter.current++, text, type }])
  }, [])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string; error?: string }
    if (d.error) {
      setPhase('error')
      setErrorMsg(d.error)
      addLog(d.error, 'error')
      return
    }
    if (d.progress !== undefined) {
      setProgress(d.progress)
      // Map progress to steps
      if (d.progress < 0.1)       setCurrentStep(0)
      else if (d.progress < 0.5)  setCurrentStep(1)
      else if (d.progress < 0.75) setCurrentStep(2)
      else if (d.progress < 0.95) setCurrentStep(3)
      else                         setCurrentStep(4)
    }
    if (d.message) {
      const type = d.progress === 1.0 ? 'success' : d.message.includes('Error') || d.message.includes('⚠') ? 'warn' : 'info'
      addLog(d.message, type)
    }
    if (d.progress !== undefined && d.progress >= 1) {
      setCurrentStep(4)
      setTimeout(() => setPhase('done'), 1400)
    }
  }, [addLog])

  useNativeEvent('onInstallProgress', onProgress)
  useNativeEvent('onInstallError', (data: unknown) => {
    const error = (data as { error?: string })?.error ?? 'Error desconocido'
    setPhase('error')
    setErrorMsg(error)
    addLog(error, 'error')
  })
  useNativeEvent('onInstallComplete', () => {
    setProgress(1)
    setCurrentStep(4)
    setTimeout(() => setPhase('done'), 700)
  })

  const handleStart = () => {
    if (manual) {
      if (!pickedPayload) return
      setPhase('installing')
      addLog('▶ Iniciando instalación manual...', 'info')
      bridge.call('installFromUri', pickedPayload, pickedConfig ?? '')
    } else {
      setPhase('verifying')
      setProgress(0.03)
      addLog('🔐 Verificando integridad SHA-256...', 'info')
      setTimeout(() => {
        setPhase('installing')
        addLog('▶ Iniciando instalación de OpenClaw...', 'info')
        bridge.call('startSetup')
      }, 800)
    }
  }

  const handlePick = (type: 'payload' | 'config') => {
    const id = Math.random().toString(36).slice(2)
    const listener = (e: Event) => {
      const d = (e as CustomEvent).detail as { uri: string; success: boolean }
      if (d.success) {
        if (type === 'payload') setPickedPayload(d.uri)
        else setPickedConfig(d.uri)
        addLog(`✓ Archivo ${type} seleccionado`, 'success')
      }
      window.removeEventListener('native:file_picked_' + id, listener)
    }
    window.addEventListener('native:file_picked_' + id, listener)
    bridge.call('pickFile', id)
  }

  const pct = Math.round(progress * 100)

  return (
    <div style={S.root}>
      {/* Background blobs */}
      <div style={S.blob1} />
      <div style={S.blob2} />
      <div style={S.blob3} />

      <div style={S.card}>

        {/* ── WELCOME ── */}
        {phase === 'welcome' && (
          <div style={S.fadeIn}>
            <div style={S.logoWrap}>
              <div style={S.logoRing} />
              <span style={{ fontSize: 64, position: 'relative', zIndex: 1 }}>🦀</span>
              <div style={S.logoPulse} />
            </div>
            <h1 style={S.title}>OpenClaw</h1>
            <p style={S.subtitle}>Tu asistente de IA autónomo para Android.<br />Vamos a configurar tu entorno local.</p>

            <div style={S.featureList}>
              {[
                { icon: '⚡', label: 'Gateway Node.js nativo', color: '#facc15' },
                { icon: '🧠', label: 'IA autónoma sin nube',    color: '#c4b5fd' },
                { icon: '🔒', label: 'Privado y local',         color: '#4ade80' },
                { icon: '🔐', label: 'Verificación SHA-256',    color: '#22d3ee' },
              ].map(f => (
                <div key={f.icon} style={S.featureItem}>
                  <div style={{ ...S.featureIcon, background: `${f.color}15`, border: `1px solid ${f.color}30` }}>
                    {f.icon}
                  </div>
                  <span style={S.featureText}>{f.label}</span>
                  <span style={{ color: f.color, fontSize: 14 }}>✓</span>
                </div>
              ))}
            </div>

            {manual ? (
              <div style={{ ...S.featureList, marginTop: 0 }}>
                <button style={S.pickerBtn} onClick={() => handlePick('payload')}>
                  {pickedPayload ? '✅ payload.tar.xz' : '📦 Seleccionar Payload (~186MB)'}
                </button>
                <button style={S.pickerBtn} onClick={() => handlePick('config')}>
                  {pickedConfig ? '✅ migration.tar.gz' : '⚙️ Seleccionar Configuración'}
                </button>
                <button style={S.linkBtn} onClick={() => setManual(false)}>
                  Volver a instalación automática
                </button>
              </div>
            ) : (
              <div style={{ width: '100%' }}>
                <button style={S.btnPrimary} onClick={handleStart}>
                  <span>Instalar ahora</span>
                  <span style={{ fontSize: 18 }}>→</span>
                </button>
                <button style={S.linkBtn} onClick={() => setManual(true)}>
                  O realizar instalación manual (cargar archivos)
                </button>
              </div>
            )}
            
            {manual && (
              <button 
                style={{ ...S.btnPrimary, opacity: pickedPayload ? 1 : 0.5, marginTop: 12 }} 
                onClick={handleStart}
                disabled={!pickedPayload}>
                <span>Iniciar instalación manual</span>
              </button>
            )}
            <p style={{ fontSize: 11, color: 'var(--text4)', marginTop: 12 }}>
              ~200 MB · Solo se instala una vez
            </p>
          </div>
        )}

        {/* ── VERIFYING / INSTALLING ── */}
        {(phase === 'verifying' || phase === 'installing') && (
          <div style={S.fadeIn}>
            {/* Spinner */}
            <div style={S.spinnerWrap}>
              <div style={S.spinOuter} />
              <div style={S.spinInner} />
              <span style={{ fontSize: 30, position: 'relative', zIndex: 1 }}>🦀</span>
            </div>

            <h2 style={S.installTitle}>
              {phase === 'verifying' ? `Verificando${dots}` : `Instalando${dots}`}
            </h2>

            {/* Progress bar */}
            <div style={S.progressTrack}>
              <div style={{ ...S.progressFill, width: `${pct}%` }} />
              <div style={{ ...S.progressGlow, left: `${Math.max(0, pct - 6)}%` }} />
            </div>
            <div style={S.progressMeta}>
              <span style={{ color: 'var(--text3)', fontSize: 11 }}>
                {STEPS[currentStep]?.label ?? ''}
              </span>
              <span style={{ color: 'var(--purple)', fontWeight: 700 }}>{pct}%</span>
            </div>

            {/* Step indicators */}
            <div style={S.stepRow}>
              {STEPS.map((s, i) => (
                <div key={s.id} style={S.stepItem}>
                  <div style={{
                    ...S.stepDot,
                    background: i < currentStep ? 'var(--green)' : i === currentStep ? 'var(--purple)' : 'var(--surface3)',
                    boxShadow: i === currentStep ? '0 0 10px var(--purple-glow)' : 'none',
                    transform: i === currentStep ? 'scale(1.2)' : 'scale(1)',
                  }}>
                    {i < currentStep ? '✓' : s.icon}
                  </div>
                  {i < STEPS.length - 1 && (
                    <div style={{ ...S.stepLine, background: i < currentStep ? 'var(--green)' : 'var(--surface3)' }} />
                  )}
                </div>
              ))}
            </div>

            {/* Live log terminal */}
            <div style={S.terminal} ref={logRef}>
              {logs.length === 0
                ? <div style={{ color: 'var(--text4)', fontSize: 12 }}>Esperando salida...</div>
                : logs.map(l => (
                  <div key={l.id} style={{ display: 'flex', gap: 6, lineHeight: 1.6, wordBreak: 'break-all', fontSize: 12 }}>
                    <span style={{ flexShrink: 0, width: 14, color: l.type === 'success' ? '#4ade80' : l.type === 'warn' ? '#facc15' : l.type === 'error' ? '#f87171' : '#6366f1' }}>
                      {l.type === 'success' ? '✓' : l.type === 'error' ? '✗' : l.type === 'warn' ? '!' : '›'}
                    </span>
                    <span style={{ color: l.type === 'success' ? '#4ade80' : l.type === 'error' ? '#f87171' : l.type === 'warn' ? '#facc15' : '#a5b4fc' }}>
                      {l.text}
                    </span>
                  </div>
                ))
              }
            </div>

            <p style={{ fontSize: 11, color: 'var(--text4)', marginTop: 8, textAlign: 'center' }}>
              No cierres la app durante la instalación
            </p>
          </div>
        )}

        {/* ── ERROR ── */}
        {phase === 'error' && (
          <div style={S.fadeIn}>
            <div style={{ fontSize: 56, marginBottom: 16 }}>⚠️</div>
            <h2 style={{ ...S.installTitle, color: 'var(--red)' }}>Instalación fallida</h2>
            <div style={{ background: 'var(--red-dim)', border: '1px solid rgba(248,113,113,0.25)', borderRadius: 12, padding: '12px 14px', marginBottom: 20, fontSize: 13, color: 'var(--red)', textAlign: 'center' }}>
              {errorMsg || 'Error desconocido'}
            </div>
            <button style={{ ...S.btnPrimary, background: 'var(--red-dim)', boxShadow: 'none', border: '1px solid rgba(248,113,113,0.3)', color: 'var(--red)' }}
              onClick={() => { setPhase('welcome'); setProgress(0); setLogs([]) }}>
              Reintentar
            </button>
          </div>
        )}

        {/* ── DONE ── */}
        {phase === 'done' && (
          <div style={S.fadeIn}>
            <div style={S.doneWrap}>
              <div style={S.doneRing} />
              <span style={{ fontSize: 64, position: 'relative', zIndex: 1 }}>🚀</span>
            </div>
            <h2 style={S.doneTitle}>¡Instalación completa!</h2>
            <p style={S.doneSubtitle}>OpenClaw está listo para usarse.<br />El gateway iniciará automáticamente.</p>

            <div style={S.checkList}>
              {[
                { label: 'Integridad SHA-256 verificada', icon: '🔐' },
                { label: 'Payload instalado',              icon: '📦' },
                { label: 'Configuración restaurada',       icon: '⚙️' },
                { label: 'BusyBox configurado',            icon: '🔧' },
              ].map(item => (
                <div key={item.label} style={S.checkItem}>
                  <span style={{ fontSize: 16 }}>{item.icon}</span>
                  <span style={{ flex: 1, fontSize: 13, color: '#c4e0c4', fontWeight: 500 }}>{item.label}</span>
                  <span style={{ color: '#4ade80', fontWeight: 700 }}>✓</span>
                </div>
              ))}
            </div>

            <button style={S.btnPrimary} onClick={() => {
              bridge.call('startGateway')
              onComplete()
            }}>
              <span>Ir al Dashboard</span>
              <span style={{ fontSize: 18 }}>→</span>
            </button>
          </div>
        )}
      </div>

      <style>{`
        @keyframes spin     { to { transform: rotate(360deg); } }
        @keyframes pulse    { 0%,100%{transform:scale(1);opacity:.4}50%{transform:scale(1.15);opacity:.15} }
        @keyframes blobFloat{ 0%,100%{transform:translateY(0)scale(1)}50%{transform:translateY(-18px)scale(1.04)} }
        @keyframes fadeUp   { from{opacity:0;transform:translateY(20px)}to{opacity:1;transform:translateY(0)} }
        @keyframes ringPop  { 0%{transform:scale(.7);opacity:0}60%{transform:scale(1.08);opacity:.5}100%{transform:scale(1.3);opacity:0} }
      `}</style>
    </div>
  )
}

const S: Record<string, React.CSSProperties> = {
  root: {
    position:'relative', display:'flex', flexDirection:'column',
    alignItems:'center', justifyContent:'center',
    minHeight:'100vh', background:'#06060f', overflow:'hidden', padding:'24px 16px',
  },
  blob1: {
    position:'absolute', top:'-100px', right:'-80px', width:300, height:300,
    borderRadius:'50%', background:'radial-gradient(circle, rgba(99,102,241,0.22) 0%, transparent 70%)',
    animation:'blobFloat 7s ease-in-out infinite', pointerEvents:'none',
  },
  blob2: {
    position:'absolute', bottom:'-80px', left:'-80px', width:250, height:250,
    borderRadius:'50%', background:'radial-gradient(circle, rgba(139,92,246,0.18) 0%, transparent 70%)',
    animation:'blobFloat 9s ease-in-out infinite reverse', pointerEvents:'none',
  },
  blob3: {
    position:'absolute', top:'40%', left:'50%', transform:'translate(-50%,-50%)',
    width:200, height:200, borderRadius:'50%',
    background:'radial-gradient(circle, rgba(34,211,238,0.08) 0%, transparent 70%)',
    pointerEvents:'none',
  },
  card: {
    position:'relative', zIndex:1, width:'100%', maxWidth:400,
    background:'rgba(14,14,24,0.94)',
    border:'1px solid rgba(99,102,241,0.2)',
    borderRadius:28, padding:'32px 24px 28px',
    backdropFilter:'blur(24px)',
    boxShadow:'0 24px 80px rgba(0,0,0,0.7), 0 0 0 1px rgba(255,255,255,0.04), inset 0 1px 0 rgba(255,255,255,0.07)',
  },
  fadeIn: { animation:'fadeUp 0.45s ease-out', display:'flex', flexDirection:'column', alignItems:'center', textAlign:'center' },

  logoWrap: { position:'relative', width:100, height:100, display:'flex', alignItems:'center', justifyContent:'center', marginBottom:20 },
  logoRing: { position:'absolute', inset:0, borderRadius:'50%', border:'2px solid rgba(99,102,241,0.3)', animation:'spin 8s linear infinite' },
  logoPulse: { position:'absolute', inset:-12, borderRadius:'50%', background:'rgba(99,102,241,0.1)', animation:'pulse 2.5s ease-in-out infinite' },
  title: { fontSize:30, fontWeight:900, color:'#fff', margin:'0 0 10px', letterSpacing:'-0.5px' },
  subtitle: { fontSize:14, color:'#7878a0', lineHeight:1.65, margin:'0 0 24px' },

  featureList: { width:'100%', display:'flex', flexDirection:'column', gap:8, marginBottom:28 },
  featureItem: { display:'flex', alignItems:'center', gap:12, borderRadius:12, padding:'11px 14px', background:'rgba(255,255,255,0.03)', border:'1px solid rgba(255,255,255,0.06)', textAlign:'left' },
  featureIcon: { width:34, height:34, borderRadius:9, display:'flex', alignItems:'center', justifyContent:'center', fontSize:17, flexShrink:0 },
  featureText: { flex:1, fontSize:13, color:'#c4c4e0', fontWeight:500 },

  btnPrimary: {
    width:'100%', padding:'15px 20px', borderRadius:16, border:'none',
    background:'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color:'#fff', fontSize:16, fontWeight:700, cursor:'pointer',
    display:'flex', alignItems:'center', justifyContent:'center', gap:10,
    boxShadow:'0 8px 28px rgba(99,102,241,0.45)', letterSpacing:'0.2px',
  },

  spinnerWrap: { position:'relative', width:90, height:90, display:'flex', alignItems:'center', justifyContent:'center', marginBottom:20 },
  spinOuter: { position:'absolute', inset:0, borderRadius:'50%', border:'3px solid rgba(99,102,241,0.12)', borderTopColor:'#6366f1', animation:'spin 0.9s linear infinite' },
  spinInner: { position:'absolute', inset:10, borderRadius:'50%', border:'2px solid rgba(139,92,246,0.1)', borderBottomColor:'#8b5cf6', animation:'spin 1.3s linear infinite reverse' },
  installTitle: { fontSize:21, fontWeight:700, color:'#fff', margin:'0 0 18px', minWidth:180 },

  progressTrack: { width:'100%', height:8, background:'rgba(99,102,241,0.12)', borderRadius:4, overflow:'hidden', marginBottom:6, position:'relative' },
  progressFill:  { height:'100%', background:'linear-gradient(90deg,#6366f1,#a78bfa)', borderRadius:4, transition:'width 0.6s cubic-bezier(0.4,0,0.2,1)', boxShadow:'0 0 14px rgba(99,102,241,0.6)' },
  progressGlow:  { position:'absolute', top:0, width:40, height:'100%', background:'rgba(255,255,255,0.25)', borderRadius:4, filter:'blur(4px)', transition:'left 0.6s ease' },
  progressMeta:  { width:'100%', display:'flex', justifyContent:'space-between', marginBottom:14, fontSize:12, fontWeight:600 },

  stepRow: { display:'flex', alignItems:'center', justifyContent:'center', width:'100%', marginBottom:16 },
  stepItem: { display:'flex', alignItems:'center' },
  stepDot:  { width:30, height:30, borderRadius:'50%', display:'flex', alignItems:'center', justifyContent:'center', fontSize:13, fontWeight:700, color:'#fff', transition:'all 0.3s', flexShrink:0 },
  stepLine: { width:20, height:2, borderRadius:1, margin:'0 3px', transition:'background 0.3s' },

  terminal: {
    width:'100%', height:130, background:'rgba(0,0,0,0.55)',
    border:'1px solid rgba(99,102,241,0.12)', borderRadius:12,
    padding:'10px 12px', overflowY:'auto', textAlign:'left',
    marginBottom:10, scrollBehavior:'smooth',
  },

  doneWrap: { position:'relative', display:'inline-flex', alignItems:'center', justifyContent:'center', marginBottom:18 },
  doneRing: { position:'absolute', inset:-16, borderRadius:'50%', border:'3px solid #4ade80', animation:'ringPop 1.2s ease-out forwards' },
  doneTitle: { fontSize:26, fontWeight:900, color:'#fff', margin:'0 0 10px' },
  doneSubtitle: { fontSize:13, color:'#7878a0', lineHeight:1.65, margin:'0 0 22px' },
  checkList: { width:'100%', display:'flex', flexDirection:'column', gap:7, marginBottom:24 },
  checkItem: { display:'flex', alignItems:'center', gap:10, background:'rgba(74,222,128,0.07)', border:'1px solid rgba(74,222,128,0.18)', borderRadius:10, padding:'10px 14px', textAlign:'left' },
  linkBtn: {
    background: 'none', border: 'none', color: '#8b5cf6', fontSize: 12,
    fontWeight: 600, marginTop: 16, cursor: 'pointer', textDecoration: 'underline'
  },
  pickerBtn: {
    width: '100%', padding: '12px', borderRadius: 12, background: 'rgba(255,255,255,0.03)',
    border: '1px solid rgba(255,255,255,0.1)', color: '#c4c4e0', fontSize: 13,
    textAlign: 'left', marginBottom: 8, cursor: 'pointer'
  }
}
