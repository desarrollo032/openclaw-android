import { useState, useCallback, useEffect, useRef } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'

interface Props {
  onComplete: () => void
}

type SetupPhase = 'welcome' | 'installing' | 'done'

interface LogLine {
  id: number
  text: string
  type: 'info' | 'success' | 'warn'
}

export function Setup({ onComplete }: Props) {
  const [phase, setPhase] = useState<SetupPhase>('welcome')
  const [progress, setProgress] = useState(0)
  const [logs, setLogs] = useState<LogLine[]>([])
  const [dots, setDots] = useState('')
  const logRef = useRef<HTMLDivElement>(null)
  const logCounter = useRef(0)

  // Animated dots for loading text
  useEffect(() => {
    if (phase !== 'installing') return
    const id = setInterval(() => {
      setDots(d => d.length >= 3 ? '' : d + '.')
    }, 400)
    return () => clearInterval(id)
  }, [phase])

  // Auto-scroll logs
  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight
    }
  }, [logs])

  const addLog = useCallback((text: string, type: LogLine['type'] = 'info') => {
    setLogs(prev => [...prev.slice(-40), { id: logCounter.current++, text, type }])
  }, [])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.message) {
      const type = d.progress === 1.0 ? 'success' : d.progress === 0.0 ? 'warn' : 'info'
      addLog(d.message, type)
    }
    if (d.progress !== undefined && d.progress >= 1) {
      setTimeout(() => setPhase('done'), 1200)
    }
  }, [addLog])

  useNativeEvent('setup_progress', onProgress)

  const handleStart = () => {
    setPhase('installing')
    setProgress(0.05)
    addLog('▶ Iniciando instalación de OpenClaw...', 'info')
    bridge.call('startSetup')
  }

  const pct = Math.round(progress * 100)

  return (
    <div style={styles.root}>
      {/* Background gradient blobs */}
      <div style={styles.blob1} />
      <div style={styles.blob2} />

      <div style={styles.card}>

        {/* ── WELCOME ── */}
        {phase === 'welcome' && (
          <div style={styles.fadeIn}>
            <div style={styles.logoWrap}>
              <span style={styles.logoEmoji}>🦀</span>
              <div style={styles.logoPulse} />
            </div>
            <h1 style={styles.title}>OpenClaw</h1>
            <p style={styles.subtitle}>
              Tu asistente de IA autónomo para Android.
              <br />Vamos a configurar tu entorno.
            </p>

            <div style={styles.featureList}>
              {[
                { icon: '⚡', text: 'Gateway Node.js nativo' },
                { icon: '🧠', text: 'IA autónoma sin nube' },
                { icon: '🔒', text: 'Privado y local' },
              ].map(f => (
                <div key={f.icon} style={styles.featureItem}>
                  <span style={styles.featureIcon}>{f.icon}</span>
                  <span style={styles.featureText}>{f.text}</span>
                </div>
              ))}
            </div>

            <button style={styles.btnPrimary} onClick={handleStart}>
              <span>Instalar ahora</span>
              <span style={{ marginLeft: 8 }}>→</span>
            </button>
          </div>
        )}

        {/* ── INSTALLING ── */}
        {phase === 'installing' && (
          <div style={styles.fadeIn}>
            <div style={styles.spinnerWrap}>
              <div style={styles.spinnerRing} />
              <span style={styles.spinnerEmoji}>🦀</span>
            </div>

            <h2 style={styles.installTitle}>Instalando{dots}</h2>

            {/* Progress bar */}
            <div style={styles.progressTrack}>
              <div style={{ ...styles.progressFill, width: `${pct}%` }} />
            </div>
            <div style={styles.progressLabel}>{pct}%</div>

            {/* Live log terminal */}
            <div style={styles.terminal} ref={logRef}>
              {logs.map(l => (
                <div key={l.id} style={{
                  ...styles.logLine,
                  color: l.type === 'success' ? '#4ade80'
                    : l.type === 'warn' ? '#facc15'
                      : '#a5b4fc'
                }}>
                  <span style={styles.logPrefix}>
                    {l.type === 'success' ? '✓' : l.type === 'warn' ? '!' : '›'}
                  </span>
                  {l.text}
                </div>
              ))}
              {logs.length === 0 && (
                <div style={{ ...styles.logLine, color: '#555' }}>Esperando salida...</div>
              )}
            </div>

            <p style={styles.hint}>No cierres la app durante la instalación</p>
          </div>
        )}

        {/* ── DONE ── */}
        {phase === 'done' && (
          <div style={styles.fadeIn}>
            <div style={styles.doneIcon}>
              <span style={{ fontSize: 64 }}>🚀</span>
              <div style={styles.doneRing} />
            </div>
            <h2 style={styles.doneTitle}>¡Listo!</h2>
            <p style={styles.doneSubtitle}>
              OpenClaw instalado correctamente.
              <br />El dashboard iniciará en segundos.
            </p>

            {/* Success checklist */}
            <div style={styles.checkList}>
              {['Payload instalado', 'Configuración restaurada', 'Gateway listo'].map(item => (
                <div key={item} style={styles.checkItem}>
                  <span style={styles.checkMark}>✓</span>
                  <span style={styles.checkText}>{item}</span>
                </div>
              ))}
            </div>

            <button style={styles.btnPrimary} onClick={onComplete}>
              <span>Ir al Dashboard</span>
              <span style={{ marginLeft: 8 }}>→</span>
            </button>
          </div>
        )}
      </div>

      <style dangerouslySetInnerHTML={{
        __html: `
        @keyframes fadeUp {
          from { opacity: 0; transform: translateY(24px); }
          to   { opacity: 1; transform: translateY(0); }
        }
        @keyframes spin {
          to { transform: rotate(360deg); }
        }
        @keyframes pulse {
          0%, 100% { transform: scale(1); opacity: 0.4; }
          50%       { transform: scale(1.15); opacity: 0.15; }
        }
        @keyframes blobFloat {
          0%, 100% { transform: translateY(0) scale(1); }
          50%       { transform: translateY(-20px) scale(1.05); }
        }
        @keyframes doneRingPop {
          0%   { transform: scale(0.6); opacity: 0; }
          60%  { transform: scale(1.1); opacity: 0.6; }
          100% { transform: scale(1); opacity: 0; }
        }
      `}} />
    </div>
  )
}

// ── Styles ──────────────────────────────────────────────────────────────────

const styles: Record<string, React.CSSProperties> = {
  root: {
    position: 'relative',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    background: '#08080f',
    overflow: 'hidden',
    padding: '24px 20px',
  },
  blob1: {
    position: 'absolute',
    top: '-80px',
    right: '-80px',
    width: 280,
    height: 280,
    borderRadius: '50%',
    background: 'radial-gradient(circle, rgba(99,102,241,0.25) 0%, transparent 70%)',
    animation: 'blobFloat 6s ease-in-out infinite',
    pointerEvents: 'none',
  },
  blob2: {
    position: 'absolute',
    bottom: '-60px',
    left: '-60px',
    width: 220,
    height: 220,
    borderRadius: '50%',
    background: 'radial-gradient(circle, rgba(168,85,247,0.2) 0%, transparent 70%)',
    animation: 'blobFloat 8s ease-in-out infinite reverse',
    pointerEvents: 'none',
  },
  card: {
    position: 'relative',
    zIndex: 1,
    width: '100%',
    maxWidth: 400,
    background: 'rgba(18,18,30,0.92)',
    border: '1px solid rgba(99,102,241,0.2)',
    borderRadius: 28,
    padding: '36px 28px',
    backdropFilter: 'blur(20px)',
    boxShadow: '0 24px 64px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.04)',
  },
  fadeIn: {
    animation: 'fadeUp 0.5s ease-out',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    textAlign: 'center',
  },

  // Welcome
  logoWrap: {
    position: 'relative',
    marginBottom: 20,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoEmoji: {
    fontSize: 72,
    lineHeight: 1,
    position: 'relative',
    zIndex: 1,
  },
  logoPulse: {
    position: 'absolute',
    inset: -16,
    borderRadius: '50%',
    background: 'rgba(99,102,241,0.15)',
    animation: 'pulse 2.5s ease-in-out infinite',
  },
  title: {
    fontSize: 32,
    fontWeight: 800,
    color: '#fff',
    margin: '0 0 10px',
    letterSpacing: '-0.5px',
  },
  subtitle: {
    fontSize: 15,
    color: '#8888aa',
    lineHeight: 1.6,
    margin: '0 0 28px',
  },
  featureList: {
    width: '100%',
    display: 'flex',
    flexDirection: 'column',
    gap: 10,
    marginBottom: 32,
  },
  featureItem: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    background: 'rgba(99,102,241,0.08)',
    border: '1px solid rgba(99,102,241,0.15)',
    borderRadius: 12,
    padding: '12px 16px',
    textAlign: 'left',
  },
  featureIcon: { fontSize: 20 },
  featureText: { fontSize: 14, color: '#c4c4e0', fontWeight: 500 },

  btnPrimary: {
    width: '100%',
    padding: '16px 24px',
    borderRadius: 16,
    border: 'none',
    background: 'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
    color: '#fff',
    fontSize: 17,
    fontWeight: 700,
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    boxShadow: '0 8px 24px rgba(99,102,241,0.4)',
    letterSpacing: '0.2px',
  },

  // Installing
  spinnerWrap: {
    position: 'relative',
    width: 88,
    height: 88,
    marginBottom: 24,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  spinnerRing: {
    position: 'absolute',
    inset: 0,
    borderRadius: '50%',
    border: '4px solid rgba(99,102,241,0.15)',
    borderTopColor: '#6366f1',
    animation: 'spin 0.9s linear infinite',
  },
  spinnerEmoji: {
    fontSize: 36,
    position: 'relative',
    zIndex: 1,
  },
  installTitle: {
    fontSize: 22,
    fontWeight: 700,
    color: '#fff',
    margin: '0 0 20px',
    minWidth: 180,
  },
  progressTrack: {
    width: '100%',
    height: 8,
    background: 'rgba(99,102,241,0.15)',
    borderRadius: 4,
    overflow: 'hidden',
    marginBottom: 8,
  },
  progressFill: {
    height: '100%',
    background: 'linear-gradient(90deg, #6366f1, #a78bfa)',
    borderRadius: 4,
    transition: 'width 0.5s cubic-bezier(0.4,0,0.2,1)',
    boxShadow: '0 0 12px rgba(99,102,241,0.6)',
  },
  progressLabel: {
    fontSize: 13,
    color: '#6366f1',
    fontWeight: 700,
    marginBottom: 16,
    alignSelf: 'flex-end',
  },
  terminal: {
    width: '100%',
    height: 140,
    background: 'rgba(0,0,0,0.5)',
    border: '1px solid rgba(99,102,241,0.15)',
    borderRadius: 12,
    padding: '10px 12px',
    overflowY: 'auto',
    fontFamily: 'monospace',
    fontSize: 12,
    textAlign: 'left',
    marginBottom: 14,
    scrollBehavior: 'smooth',
  },
  logLine: {
    display: 'flex',
    gap: 6,
    lineHeight: 1.6,
    wordBreak: 'break-all',
  },
  logPrefix: {
    flexShrink: 0,
    width: 14,
  },
  hint: {
    fontSize: 12,
    color: '#555',
    margin: 0,
  },

  // Done
  doneIcon: {
    position: 'relative',
    marginBottom: 20,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  doneRing: {
    position: 'absolute',
    inset: -20,
    borderRadius: '50%',
    border: '3px solid #4ade80',
    animation: 'doneRingPop 1s ease-out forwards',
  },
  doneTitle: {
    fontSize: 30,
    fontWeight: 800,
    color: '#fff',
    margin: '0 0 10px',
  },
  doneSubtitle: {
    fontSize: 15,
    color: '#8888aa',
    lineHeight: 1.6,
    margin: '0 0 24px',
  },
  checkList: {
    width: '100%',
    display: 'flex',
    flexDirection: 'column',
    gap: 8,
    marginBottom: 28,
  },
  checkItem: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    background: 'rgba(74,222,128,0.07)',
    border: '1px solid rgba(74,222,128,0.2)',
    borderRadius: 10,
    padding: '10px 14px',
    textAlign: 'left',
  },
  checkMark: {
    color: '#4ade80',
    fontWeight: 700,
    fontSize: 16,
  },
  checkText: {
    fontSize: 14,
    color: '#c4e0c4',
    fontWeight: 500,
  },
}
