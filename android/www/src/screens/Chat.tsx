import { useState, useRef, useCallback, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { sendChat } from '../api/gateway'
import { useGatewayStatus } from '../hooks/useGatewayStatus'
import { Send, Bot, User, Sparkles, WifiOff } from 'lucide-react'

const SUGGESTIONS = [
  { icon: '📊', label: 'Estado del sistema', cmd: '¿Cuál es el estado actual del sistema?' },
  { icon: '🛠', label: 'Diagnóstico', cmd: 'Ejecuta un diagnóstico completo' },
  { icon: '📦', label: 'Versiones', cmd: '¿Qué versiones están instaladas?' },
  { icon: '🔒', label: 'Seguridad', cmd: 'Verifica la configuración de seguridad' },
]

export function Chat() {
  const { navigate } = useRoute()
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<{ role: string; content: string }[]>([
    { role: 'assistant', content: '¡Hola! Soy tu asistente de OpenClaw. ¿En qué puedo ayudarte hoy?' }
  ])
  const [sending, setSending] = useState(false)
  const [showSuggestions, setShowSuggestions] = useState(true)
  const { reachability } = useGatewayStatus()
  const online = reachability === 'online'
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleSend = useCallback(async () => {
    const text = input.trim()
    if (!text || sending) return
    setInput('')
    setShowSuggestions(false)
    setMessages(prev => [...prev, { role: 'user', content: text }])
    setSending(true)

    if (!online) {
      setTimeout(() => {
        setMessages(prev => [...prev, {
          role: 'assistant',
          content: '⚠️ El gateway no está disponible. Inicia el gateway desde el Dashboard para usar el chat.'
        }])
        setSending(false)
      }, 500)
      return
    }

    try {
      const res = await sendChat(text)
      const reply = (res as Record<string, unknown>).reply as string ?? 'Sin respuesta'
      setMessages(prev => [...prev, { role: 'assistant', content: reply }])
    } catch {
      setMessages(prev => [...prev, {
        role: 'assistant',
        content: '❌ Error al conectar con el gateway. Verifica que esté funcionando.'
      }])
    } finally {
      setSending(false)
    }
  }, [input, sending, online])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  return (
    <div className="flex flex-col h-full bg-bg">
      {/* ── Header ── */}
      <div className="flex items-center gap-3 px-4 pt-4 pb-3 border-b border-glass-border">
        <div className="relative">
          <div className="w-10 h-10 rounded-xl bg-accent-soft flex items-center justify-center">
            <Bot size={20} className="text-accent" />
          </div>
          <span className={`absolute -top-0.5 -right-0.5 w-3 h-3 rounded-full border-2 border-bg ${
            online ? 'bg-green animate-breathe' : 'bg-red'
          }`} />
        </div>
        <div>
          <h2 className="text-sm font-bold text-text-primary">Asistente</h2>
          <span className="text-[11px] text-text-muted">{online ? 'Conectado' : 'Desconectado'}</span>
        </div>
        {!online && (
          <button onClick={() => navigate('/dashboard')}
            className="ml-auto btn btn-ghost text-[10px] px-2.5 py-1.5">
            <Sparkles size={11} /> Iniciar gateway
          </button>
        )}
      </div>

      {/* ── Messages ── */}
      <div className="flex-1 overflow-y-auto">
        <div className="page-container py-4 space-y-4">
          {messages.map((msg, i) => (
            <div key={i} className={`flex gap-3 ${msg.role === 'user' ? 'justify-end' : ''} animate-fade-in`}
              style={{ animationDelay: `${i * 0.03}s` }}>
              {msg.role === 'assistant' && (
                <div className="w-8 h-8 rounded-lg bg-accent-soft flex items-center justify-center shrink-0 mt-1">
                  <Bot size={15} className="text-accent" />
                </div>
              )}
              <div className={msg.role === 'user' ? 'bubble-user' : 'bubble-assistant'}>
                <p className="whitespace-pre-wrap leading-relaxed text-sm">{msg.content}</p>
              </div>
              {msg.role === 'user' && (
                <div className="w-8 h-8 rounded-lg bg-glass-bg flex items-center justify-center shrink-0 mt-1">
                  <User size={15} className="text-text-secondary" />
                </div>
              )}
            </div>
          ))}

          {/* ── Typing indicator ── */}
          {sending && (
            <div className="flex gap-3 animate-fade-in">
              <div className="w-8 h-8 rounded-lg bg-accent-soft flex items-center justify-center shrink-0">
                <Bot size={15} className="text-accent" />
              </div>
              <div className="bubble-assistant flex items-center gap-1.5 py-3 px-4">
                <span className="w-1.5 h-1.5 rounded-full bg-text-muted animate-[typing-dot_1.4s_ease-in-out_infinite]" />
                <span className="w-1.5 h-1.5 rounded-full bg-text-muted animate-[typing-dot_1.4s_ease-in-out_0.2s_infinite]" />
                <span className="w-1.5 h-1.5 rounded-full bg-text-muted animate-[typing-dot_1.4s_ease-in-out_0.4s_infinite]" />
              </div>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>
      </div>

      {/* ── Suggestions ── */}
      {showSuggestions && messages.length === 1 && (
        <div className="page-container pb-3">
          <div className="flex flex-wrap gap-2">
            {SUGGESTIONS.map(s => (
              <button key={s.cmd}
                onClick={() => { setInput(s.cmd); inputRef.current?.focus() }}
                className="chip text-[11px] flex items-center gap-1.5">
                <span>{s.icon}</span> {s.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* ── Input area ── */}
      <div className="page-container pb-3 pt-2">
        <div className="flex items-end gap-2 glass-strong rounded-2xl px-3 py-2 focus-within:border-accent/25 border-glass-strong-border transition-all max-w-2xl mx-auto">
          <textarea ref={inputRef}
            className="flex-1 bg-transparent border-none outline-none text-sm text-text-primary placeholder-text-muted resize-none max-h-24 py-1"
            rows={1}
            placeholder={online ? 'Escribe un mensaje...' : 'Gateway desconectado...'}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={!online}
          />
          <button onClick={handleSend}
            disabled={!input.trim() || sending || !online}
            className={`p-2 rounded-xl transition-all ${
              input.trim() && !sending && online
                ? 'bg-accent text-white hover:bg-accent-light hover:scale-105'
                : 'bg-glass-bg text-text-dim cursor-not-allowed'
            }`}>
            <Send size={16} />
          </button>
        </div>
        <div className="flex items-center justify-between mt-2 px-1 max-w-2xl mx-auto">
          <span className="text-[10px] text-text-dim">Shift+Enter para nueva línea</span>
          {!online && (
            <span className="flex items-center gap-1 text-[10px] text-yellow">
              <WifiOff size={10} /> Sin conexión al gateway
            </span>
          )}
        </div>
      </div>
    </div>
  )
}
