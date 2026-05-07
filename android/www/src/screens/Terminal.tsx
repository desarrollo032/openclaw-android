import { useState, useRef, useEffect } from 'react'
import { bridge } from '../lib/bridge'

interface HistoryEntry {
  type: 'cmd' | 'out' | 'err'
  text: string
  time: number
}

export function Terminal() {
  const [history, setHistory] = useState<HistoryEntry[]>(() => [
    { type: 'out', text: 'OpenClaw Terminal v1.2.4', time: Date.now() },
    { type: 'out', text: 'Type "help" for a list of commands.', time: Date.now() }
  ])
  const [input, setInput] = useState('')
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollTo(0, scrollRef.current.scrollHeight)
  }, [history])

  const executeCommand = (cmd: string) => {
    if (!cmd.trim()) return

    const newHistory: HistoryEntry[] = [...history, { type: 'cmd', text: cmd, time: Date.now() }]
    
    // Call native bridge
    const result = bridge.callJson<{ stdout?: string; stderr?: string }>('runCommand', cmd)
    
    if (result) {
      if (result.stdout) {
        newHistory.push({ type: 'out', text: result.stdout, time: Date.now() })
      }
      if (result.stderr) {
        newHistory.push({ type: 'err', text: result.stderr, time: Date.now() })
      }
    } else {
      newHistory.push({ type: 'err', text: 'Error: Bridge not available or command failed.', time: Date.now() })
    }

    setHistory(newHistory)
    setInput('')
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      executeCommand(input)
    }
  }

  return (
    <div className="terminal-page">
      <div 
        ref={scrollRef}
        className="terminal-history"
      >
        {history.map((h, i) => (
          <div key={i} style={{ 
            marginBottom: 4, 
            color: h.type === 'cmd' ? '#0f0' : h.type === 'err' ? '#f44' : '#fff' 
          }}>
            {h.type === 'cmd' && <span>$ </span>}
            {h.text}
          </div>
        ))}
      </div>
      
      <div className="terminal-input-row">
        <span className="terminal-prompt">$</span>
        <input 
          autoFocus
          className="terminal-input"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Escribe un comando..."
        />
      </div>
    </div>
  )
}
