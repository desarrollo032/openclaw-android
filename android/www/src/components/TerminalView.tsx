import { useState, useEffect } from 'react'

export function TerminalView() {
  const [ptyActive, setPtyActive] = useState(false)
  const [cols, setCols] = useState(80)
  const [rows, setRows] = useState(24)
  const [statusMsg, setStatusMsg] = useState('')
  const [bridgeModule, setBridgeModule] = useState<any>(null)

  useEffect(() => {
    import('../lib/bridge').then(m => setBridgeModule(m.bridge)).catch(() => {})
  }, [])

  const togglePty = () => {
    if (!bridgeModule) return
    if (ptyActive) {
      bridgeModule.call('writeToTerminal', '', '\x03')
    }
    setPtyActive(!ptyActive)
    setStatusMsg(ptyActive ? 'PTY detenido' : 'PTY iniciado')
    setTimeout(() => setStatusMsg(''), 2000)
  }

  const handleResize = () => {
    if (!bridgeModule) return
    bridgeModule.call('notifyTerminalResize', rows, cols)
    setStatusMsg(`Redimensionado: ${cols}x${rows}`)
    setTimeout(() => setStatusMsg(''), 2000)
  }

  return (
    <div className="card p-4">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className={`w-9 h-9 rounded-xl flex items-center justify-center ${
            ptyActive ? 'bg-green-soft' : 'bg-glass-bg'
          }`}>
            <div className={`w-3 h-3 rounded-full ${ptyActive ? 'bg-green animate-breathe' : 'bg-text-dim'}`} />
          </div>
          <div>
            <div className="text-sm font-semibold text-text-primary">Terminal PTY</div>
            <div className="text-[10px] text-text-muted mt-0.5">
              {ptyActive ? `Activo · ${cols}x${rows}` : 'Inactivo'}
            </div>
          </div>
        </div>
        <button onClick={togglePty}
          className={`btn text-[10px] px-2.5 py-1.5 ${
            ptyActive ? 'btn-danger' : 'btn-primary'
          }`}>
          {ptyActive ? 'Detener' : 'Iniciar'}
        </button>
      </div>

      <div className="flex items-center gap-2 mb-2">
        <div className="flex items-center gap-1.5">
          <label className="text-[10px] text-text-muted">Cols</label>
          <input type="number" min={40} max={200} value={cols}
            onChange={e => setCols(Number(e.target.value))}
            className="w-16 px-2 py-1 rounded-lg bg-input-bg border border-glass-border text-xs text-text-primary text-center font-mono outline-none focus:border-accent/20 transition-all" />
        </div>
        <div className="flex items-center gap-1.5">
          <label className="text-[10px] text-text-muted">Rows</label>
          <input type="number" min={8} max={80} value={rows}
            onChange={e => setRows(Number(e.target.value))}
            className="w-16 px-2 py-1 rounded-lg bg-input-bg border border-glass-border text-xs text-text-primary text-center font-mono outline-none focus:border-accent/20 transition-all" />
        </div>
        <button onClick={handleResize}
          className="btn btn-ghost text-[10px] px-2.5 py-1.5 ml-auto">
          Redimensionar
        </button>
      </div>

      {statusMsg && (
        <div className="text-[10px] text-accent font-medium animate-fade-in">{statusMsg}</div>
      )}
    </div>
  )
}
