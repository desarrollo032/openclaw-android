import { useState, useRef, useEffect } from 'react'
import { api } from '../lib/api'
import type { ChatMessage } from '../lib/api'
import { t } from '../i18n'

export function Chat() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [inputText, setInputText] = useState('')
  const [isTyping, setIsTyping] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  // Load chat history from localStorage
  useEffect(() => {
    const saved = localStorage.getItem('chat_history')
    if (saved) setMessages(JSON.parse(saved))
  }, [])

  // Save chat history
  useEffect(() => {
    localStorage.setItem('chat_history', JSON.stringify(messages))
    scrollToBottom()
  }, [messages])

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }

  const handleSend = async () => {
    if (!inputText.trim()) return

    const userMsg: ChatMessage = {
      id: Date.now().toString(),
      role: 'user',
      content: inputText,
      timestamp: Date.now()
    }

    setMessages(prev => [...prev, userMsg])
    setInputText('')
    setIsTyping(true)

    try {
      const response = await api.chat(inputText)
      
      const botMsg: ChatMessage = {
        id: response.id || (Date.now() + 1).toString(),
        role: 'assistant',
        content: response.response || response.message || '...',
        timestamp: Date.now(),
        actions: response.actions,
        suggestions: response.suggestions
      }
      
      setMessages(prev => [...prev, botMsg])
    } catch (e) {
      console.error(e)
    } finally {
      setIsTyping(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const formatContent = (content: string) => {
    // Basic markdown: bold and code
    return content
      .split('\n')
      .map((line, i) => (
        <p key={i} style={{ marginBottom: 4 }}>
          {line.split(/(\*\*.*?\*\*|`.*?`)/g).map((part, j) => {
            if (part.startsWith('**') && part.endsWith('**')) {
              return <strong key={j}>{part.slice(2, -2)}</strong>
            }
            if (part.startsWith('`') && part.endsWith('`')) {
              return <code key={j}>{part.slice(1, -1)}</code>
            }
            return part
          })}
        </p>
      ))
  }

  return (
    <div className="chat-container">
      <div className="messages-list">
        {messages.map(msg => (
          <div key={msg.id} className={`message-wrapper ${msg.role}`}>
            <div className="message-bubble">
              {formatContent(msg.content)}
              {msg.actions && msg.actions.length > 0 && (
                <div className="actions-row" style={{ marginTop: 8, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  {msg.actions.map(action => (
                    <button 
                      key={action.command} 
                      className="btn btn-secondary btn-small"
                      style={{ padding: '4px 8px', fontSize: 12 }}
                      onClick={() => {
                        setInputText(`/${action.command}`)
                        // Optional: auto-send
                      }}
                    >
                      {action.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div className="message-time">
              {new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </div>
          </div>
        ))}
        
        {isTyping && (
          <div className="message-wrapper bot">
            <div className="message-bubble">
              <div className="typing-dots">
                <div className="dot" />
                <div className="dot" />
                <div className="dot" />
              </div>
            </div>
          </div>
        )}
        
        <div ref={messagesEndRef} />
      </div>

      <div className="input-area">
        {messages.length > 0 && messages[messages.length-1].suggestions && (
          <div className="suggestions-row" style={{ display: 'flex', gap: 8, overflowX: 'auto', marginBottom: 8 }}>
            {messages[messages.length-1].suggestions?.map(s => (
              <button 
                key={s} 
                className="btn btn-secondary btn-small" 
                style={{ whiteSpace: 'nowrap', borderRadius: 20 }}
                onClick={() => { setInputText(s); }}
              >
                {s}
              </button>
            ))}
          </div>
        )}
        
        <div className="input-row">
          <textarea
            className="message-input"
            placeholder={t('setup_open_terminal')} /* Temporarily repurpose or use raw text */
            rows={1}
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            onKeyDown={handleKeyDown}
          />
          <button className="send-btn" onClick={handleSend}>
            🚀
          </button>
        </div>
        
        <div className="input-actions">
          <div style={{ display: 'flex', gap: 4 }}>
            <button className="action-btn">📎</button>
            <button className="action-btn">🎤</button>
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            <button className="action-btn">✨</button>
            <button className="action-btn">⚙️</button>
          </div>
        </div>
      </div>
    </div>
  )
}
