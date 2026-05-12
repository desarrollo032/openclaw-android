/**
 * src/screens/Chat.tsx — v3
 * Chat con el gateway OpenClaw. Diseño premium glass-dark.
 * - Burbujas con markdown básico (bold, code, bloques)
 * - Sugerencias rápidas como chips
 * - Indicador de typing animado
 * - Estado de conexión visible
 * - Historial persistente en localStorage
 * - Copiar mensajes individuales
 * - i18n completo
 */

import { useState, useRef, useEffect, useCallback } from 'react'
import { sendChat } from '../api/gateway'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'
import { useGatewayStatus } from '../hooks/useGatewayStatus'

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
  actions?: { label: string; command: string }[]
  suggestions?: string[]
  error?: boolean
}

const QUICK_PROMPTS = [
  { label: t('chat_quick_status'),  text: 'openclaw status' },
  { label: t('chat_quick_models'),  text: 'openclaw models' },
  { label: t('chat_quick_help'),    text: 'openclaw --help' },
  { label: t('chat_quick_tasks'),   text: 'openclaw tasks' },
]

const STORAGE_KEY = 'oc_chat_v3'

// ── Markdown renderer ─────────────────────────────────────────────────────────

function renderMarkdown(text: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = []
  const lines = text.split('\n')
  let codeBlock: string[] = []
  let inCode = false

  lines.forEach((line, li) => {
    if (line.startsWith('```')) {
      if (inCode) {
        nodes.push(
          <pre key={`cb-${li}`} style={S.codeBlock}>
            <code>{codeBlock.join('\n')}</code>
          </pre>
        )
        codeBlock = []
        inCode = false
      } else {
        inCode = true
      }
      return
    }
    if (inCode) { codeBlock.push(line); return }

    // Inline rendering: **bold** and `code`
    const parts = line.split(/(\*\*.*?\*\*|`[^`]+`)/g)
    const inline = parts.map((p, pi) => {
      if (p.startsWith('**') && p.endsWith('**')) return <strong key={pi}>{p.slice(2, -2)}</strong>
      if (p.startsWith('`')  && p.endsWith('`'))  return <code key={pi} style={S.inlineCode}>{p.slice(1, -1)}</code>
      return p
    })

    nodes.push(<div key={li} style={{ marginBottom: line ? 3 : 6 }}>{inline}</div>)
  })

  return nodes
}

// ── Component ─────────────────────────────────────────────────────────────────

export function Chat() {
  const [messages, setMessages] = useState<ChatMessage[]>(() => {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]') } catch { return [] }
  })
  const [input,     setInput]     = useState('')
  const [typing,    setTyping]    = useState(false)
  const [copiedId,  setCopiedId]  = useState<string | null>(null)
  const bottomRef   = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const { reachability } = useGatewayStatus()
  const online = reachability === 'online'

  // Persist + auto-scroll
  useEffect(() => {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(messages.slice(-100))) } catch { /* ignore */ }
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  // Auto-grow textarea
  useEffect(() => {
    const ta = textareaRef.current
    if (!ta) return
    ta.style.height = 'auto'
    ta.style.height = Math.min(ta.scrollHeight, 120) + 'px'
  }, [input])

  const addMessage = useCallback((msg: ChatMessage) => {
    setMessages(prev => [...prev, msg])
  }, [])

  const handleSend = useCallback(async (text = input) => {
    const trimmed = text.trim()
    if (!trimmed || typing) return

    const userMsg: ChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: trimmed,
      timestamp: Date.now(),
    }
    addMessage(userMsg)
    setInput('')
    setTyping(true)

    try {
      const res = await sendChat(trimmed)
      addMessage({
        id: `a-${Date.now()}`,
        role: 'assistant',
        content: res.reply ?? (res as any).response ?? (res as any).message ?? '...',
        timestamp: Date.now(),
        actions: (res as any).actions,
        suggestions: (res as any).suggestions,
      })
    } catch (err) {
      const detail = err instanceof Error ? err.message : String(err)
      addMessage({
        id: `e-${Date.now()}`,
        role: 'assistant',
        content: `${t('chat_error')}\n\n${detail}`,
        timestamp: Date.now(),
        error: true,
        actions: [
          { label: 'Abrir terminal', command: trimmed },
          { label: 'Gateway status', command: 'openclaw status' },
        ],
      })
    } finally {
      setTyping(false)
    }
  }, [input, typing, addMessage])

  const copyMsg = useCallback((msg: ChatMessage) => {
    const text = msg.content
    try {
      const write = navigator.clipboard?.writeText(text)
      if (write && typeof write.catch === 'function') {
        write.catch(() => bridge.call('copyToClipboard', text))
      } else {
        bridge.call('copyToClipboard', text)
      }
    } catch { bridge.call('copyToClipboard', text) }
    setCopiedId(msg.id)
    setTimeout(() => setCopiedId(null), 1500)
  }, [])

  const clearChat = () => {
    setMessages([])
    try { localStorage.removeItem(STORAGE_KEY) } catch { /* ignore */ }
  }

  const lastMsg = messages[messages.length - 1]
  const hasSuggestions = lastMsg?.role === 'assistant' && lastMsg.suggestions?.length

  return (
    <div className="modern-page" style={S.root}>
      {/* ── Offline banner ── */}
      {!online && (
        <div style={S.offlineBanner}>
          <span style={{ fontSize: 13 }}>⚡</span>
          {t('chat_offline')}
        </div>
      )}

      {/* ── Messages area ── */}
      <div style={S.messages}>
        {/* Empty state */}
        {messages.length === 0 && (
          <div style={S.emptyState}>
            <div style={S.emptyLogo}>
              <div style={S.emptyRing} />
              <span style={{ fontSize: 18, position: 'relative', zIndex: 1, fontWeight: 800, letterSpacing: '.08em' }}>OC</span>
            </div>
            <div style={S.emptyTitle}>{t('chat_empty_title')}</div>
            <div style={S.emptySub}>{t('chat_empty_sub')}</div>
            {/* Quick prompt chips */}
            <div style={S.quickChips}>
              {QUICK_PROMPTS.map(q => (
                <button key={q.text} style={S.quickChip}
                  onClick={() => handleSend(q.text)}>
                  {q.label}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Messages */}
        {messages.map(msg => (
          <div key={msg.id} style={{ ...S.msgWrapper, justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start' }}>
            {/* Avatar (only for assistant) */}
            {msg.role === 'assistant' && (
              <div style={S.avatar}>OC</div>
            )}

            <div style={{ maxWidth: '80%', display: 'flex', flexDirection: 'column', alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start' }}>
              <div style={{
                ...S.bubble,
                ...(msg.role === 'user' ? S.bubbleUser : S.bubbleBot),
                ...(msg.error ? S.bubbleError : {}),
              }}>
                {renderMarkdown(msg.content)}

                {/* Action buttons */}
                {msg.actions?.length && (
                  <div style={S.actionsRow}>
                    {msg.actions.map(a => (
                      <button key={a.command} style={S.actionBtn}
                        onClick={() => bridge.call('launchInteractiveCommand', a.command)}>
                        {a.label}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {/* Timestamp + copy */}
              <div style={S.msgMeta}>
                <span style={S.metaTime}>
                  {new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </span>
                <button style={S.copyBtn} onClick={() => copyMsg(msg)}>
                  {copiedId === msg.id ? t('chat_copied') : t('chat_copy')}
                </button>
              </div>
            </div>
          </div>
        ))}

        {/* Typing indicator */}
        {typing && (
          <div style={{ ...S.msgWrapper, justifyContent: 'flex-start' }}>
            <div style={S.avatar}>OC</div>
            <div style={{ ...S.bubble, ...S.bubbleBot, ...S.typingBubble }}>
              <div style={S.typingDots}>
                <span style={{ ...S.dot, animationDelay: '0ms' }}   />
                <span style={{ ...S.dot, animationDelay: '160ms' }} />
                <span style={{ ...S.dot, animationDelay: '320ms' }} />
              </div>
              <span style={{ fontSize: 11, color: 'var(--text3)', marginLeft: 6 }}>{t('chat_typing')}</span>
            </div>
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {/* ── Suggestion chips (from last reply) ── */}
      {hasSuggestions && (
        <div style={S.suggestionsRow}>
          {lastMsg.suggestions!.map(s => (
            <button key={s} style={S.suggestionChip}
              onClick={() => handleSend(s)}>
              {s}
            </button>
          ))}
        </div>
      )}

      {/* ── Quick prompts if no messages ── */}
      {messages.length > 0 && (
        <div style={S.quickBar}>
          {QUICK_PROMPTS.map(q => (
            <button key={q.text} style={S.quickBarBtn}
              onClick={() => handleSend(q.text)}>
              {q.label}
            </button>
          ))}
        </div>
      )}

      {/* ── Input area ── */}
      <div style={S.inputArea}>
        <div style={S.inputRow}>
          <textarea
            ref={textareaRef}
            style={S.textarea}
            placeholder={t('chat_placeholder')}
            value={input}
            rows={1}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend() }
            }}
          />
          <button
            style={S.attachBtn}
            onClick={() => {
              const id = Math.random().toString(36).slice(2)
              const listener = (e: Event) => {
                const d = (e as CustomEvent).detail as { uri: string; success: boolean }
                if (d.success) {
                  // En el futuro, esto subiría el archivo al gateway.
                  // Por ahora, solo insertamos la referencia en el chat.
                  setInput(prev => prev + (prev ? ' ' : '') + `[Archivo: ${d.uri}]`)
                }
                window.removeEventListener('native:file_picked_' + id, listener)
              }
              window.addEventListener('native:file_picked_' + id, listener)
              bridge.call('pickFile', id)
            }}>
            📎
          </button>
          <button
            style={{ 
              ...S.sendBtn, 
              opacity: (!input.trim() || typing) ? 0.4 : 1,
              transform: typing ? 'scale(0.95)' : undefined,
              cursor: (!input.trim() || typing) ? 'not-allowed' : 'pointer',
            }}
            onClick={() => handleSend()}
            disabled={!input.trim() || typing}
            aria-label={typing ? 'Enviando mensaje...' : 'Enviar mensaje'}
            aria-busy={typing}
          >
            {typing ? (
              <span style={{ display: 'inline-block', animation: 'spin 1s linear infinite' }}>⟳</span>
            ) : (
              <span style={{ fontSize: 18 }}>↑</span>
            )}
          </button>
        </div>

        {/* Bottom toolbar */}
        <div style={S.inputToolbar}>
          <span style={{ fontSize: 10, color: 'var(--text4)' }}>
            {online
              ? <><span style={{ color: '#4ade80' }}>●</span> OpenClaw online</>
              : <><span style={{ color: '#f87171' }}>●</span> Gateway inactivo</>}
          </span>
          {messages.length > 0 && (
            <button 
              style={S.clearBtn} 
              onClick={clearChat}
              disabled={typing}
              aria-label="Borrar historial de chat"
            >
              🗑 {t('chat_clear')}
            </button>
          )}
        </div>
      </div>

      <style>{`
        @keyframes typingPulse {
          0%,60%,100% { transform: scale(1); opacity:.4 }
          30%          { transform: scale(1.4); opacity:1 }
        }
      `}</style>
    </div>
  )
}

// ── Styles ────────────────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex', flexDirection: 'column', height: '100%',
    background: 'var(--bg)', overflow: 'hidden',
  },
  offlineBanner: {
    display: 'flex', alignItems: 'center', gap: 8,
    padding: '8px 14px', background: 'rgba(250,204,21,0.1)',
    borderBottom: '1px solid rgba(250,204,21,0.2)',
    fontSize: 12, color: '#facc15', fontWeight: 600, flexShrink: 0,
  },

  // Messages
  messages: {
    flex: 1, overflowY: 'auto', padding: '14px 12px 6px',
    display: 'flex', flexDirection: 'column', gap: 10,
    WebkitOverflowScrolling: 'touch' as any,
  },

  // Empty state
  emptyState: {
    display: 'flex', flexDirection: 'column', alignItems: 'center',
    justifyContent: 'center', flex: 1, gap: 12, padding: '40px 20px',
    textAlign: 'center',
  },
  emptyLogo: {
    position: 'relative', width: 80, height: 80,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
  },
  emptyRing: {
    position: 'absolute', inset: 0, borderRadius: '50%',
    border: '2px solid rgba(99,102,241,0.25)',
    animation: 'spin 8s linear infinite',
  },
  emptyTitle: { fontSize: 20, fontWeight: 800, color: 'var(--text)' },
  emptySub:   { fontSize: 13, color: 'var(--text3)', lineHeight: 1.6 },
  quickChips: { display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'center', marginTop: 4 },
  quickChip: {
    padding: '8px 14px', borderRadius: 'var(--r-full)',
    background: 'var(--surface)', border: '1px solid var(--border)',
    color: 'var(--text2)', fontSize: 12, fontWeight: 600, cursor: 'pointer',
    transition: 'all 0.2s',
  },

  // Message bubbles
  msgWrapper: { display: 'flex', alignItems: 'flex-end', gap: 8 },
  avatar: {
    width: 28, height: 28, borderRadius: '50%',
    background: 'rgba(99,102,241,0.15)', border: '1px solid rgba(99,102,241,0.25)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 14, flexShrink: 0,
  },
  bubble: {
    padding: '10px 14px', borderRadius: 18,
    fontSize: 14, lineHeight: 1.55, wordBreak: 'break-word',
  },
  bubbleUser: {
    background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
    color: '#fff',
    borderBottomRightRadius: 4,
    boxShadow: '0 4px 16px rgba(99,102,241,0.3)',
  },
  bubbleBot: {
    background: 'var(--surface)', border: '1px solid var(--border)',
    color: 'var(--text)',
    borderBottomLeftRadius: 4,
    boxShadow: 'var(--sh-sm)',
  },
  bubbleError: {
    background: 'var(--red-dim)', border: '1px solid rgba(248,113,113,0.25)',
    color: 'var(--red)',
  },

  // Typing
  typingBubble: { display: 'flex', alignItems: 'center', padding: '10px 16px', gap: 4 },
  typingDots:   { display: 'flex', gap: 4, alignItems: 'center' },
  dot: {
    display: 'inline-block', width: 7, height: 7, borderRadius: '50%',
    background: '#6366f1', animation: 'typingPulse 1.2s ease-in-out infinite',
  },

  // Actions & suggestions
  actionsRow: { display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 10 },
  actionBtn: {
    padding: '5px 12px', borderRadius: 8,
    background: 'rgba(99,102,241,0.15)', border: '1px solid rgba(99,102,241,0.3)',
    color: '#a5b4fc', fontSize: 12, fontWeight: 600, cursor: 'pointer',
  },
  suggestionsRow: {
    display: 'flex', gap: 6, padding: '4px 12px 4px',
    overflowX: 'auto', flexShrink: 0,
  },
  suggestionChip: {
    padding: '6px 14px', borderRadius: 'var(--r-full)', whiteSpace: 'nowrap',
    background: 'var(--surface)', border: '1px solid var(--border2)',
    color: 'var(--purple)', fontSize: 12, fontWeight: 600, cursor: 'pointer', flexShrink: 0,
  },

  // Meta
  msgMeta:  { display: 'flex', alignItems: 'center', gap: 8, marginTop: 4, paddingLeft: 4 },
  metaTime: { fontSize: 10, color: 'var(--text4)' },
  copyBtn:  { background: 'none', border: 'none', color: 'var(--text4)', fontSize: 10, cursor: 'pointer', padding: 0, fontWeight: 600 },

  // Markdown
  codeBlock: {
    background: 'rgba(0,0,0,0.4)', border: '1px solid var(--border)',
    borderRadius: 8, padding: '10px 12px', margin: '6px 0',
    overflowX: 'auto', fontSize: 12,
    fontFamily: "'JetBrains Mono', monospace", color: '#a5f3fc',
    whiteSpace: 'pre',
  },
  inlineCode: {
    background: 'rgba(0,0,0,0.3)', borderRadius: 4,
    padding: '1px 5px', fontSize: 12,
    fontFamily: "'JetBrains Mono', monospace", color: '#c4b5fd',
  },

  // Quick bar
  quickBar: {
    display: 'flex', gap: 5, padding: '5px 12px',
    overflowX: 'auto', flexShrink: 0, alignItems: 'center',
  },
  quickBarBtn: {
    padding: '5px 12px', borderRadius: 'var(--r-full)', whiteSpace: 'nowrap',
    background: 'var(--glass)', border: '1px solid var(--border)',
    color: 'var(--text3)', fontSize: 11, fontWeight: 600, cursor: 'pointer', flexShrink: 0,
  },

  // Input
  inputArea: {
    padding: '8px 10px 10px', borderTop: '1px solid var(--border)',
    background: 'rgba(8,8,16,0.95)', backdropFilter: 'blur(16px)',
    flexShrink: 0,
  },
  inputRow: { display: 'flex', gap: 8, alignItems: 'flex-end' },
  textarea: {
    flex: 1, background: 'var(--surface)', border: '1px solid var(--border2)',
    borderRadius: 16, color: 'var(--text)', fontSize: 14, lineHeight: 1.5,
    padding: '10px 14px', outline: 'none', resize: 'none', overflow: 'hidden',
    fontFamily: 'inherit', maxHeight: 120, WebkitUserSelect: 'text', userSelect: 'text',
    boxShadow: 'var(--sh-inset)',
  },
  sendBtn: {
    boxShadow: '0 4px 14px rgba(99,102,241,0.4)', flexShrink: 0,
    transition: 'opacity 0.2s, transform 0.1s',
  },
  attachBtn: {
    width: 40, height: 40, borderRadius: '50%',
    background: 'rgba(255,255,255,0.05)', border: '1px solid var(--border)',
    color: 'var(--text3)', cursor: 'pointer',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    flexShrink: 0, transition: 'background 0.2s',
  },
  inputToolbar: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    paddingTop: 6, paddingLeft: 2,
  },
  clearBtn: {
    background: 'none', border: 'none', color: 'var(--text4)',
    fontSize: 11, cursor: 'pointer', fontWeight: 600, padding: 0,
  },
}
