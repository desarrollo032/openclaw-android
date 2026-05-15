/**
 * CopyButton
 * Botón para copiar texto al portapapeles con feedback visual.
 *
 * Props:
 * @param text      - Texto a copiar
 * @param label     - Texto del botón (def: 'Copiar')
 * @param copiedLabel - Texto cuando se copia (def: '¡Copiado!')
 * @param icon      - Icono a usar (def: Copy de lucide)
 * @param className - Clases adicionales
 * @param onCopy    - Callback después de copiar
 *
 * @example
 *   <CopyButton text="openclaw update" />
 *   <CopyButton text={longText} label="Copiar todo" />
 */
import { useState, useCallback } from 'react'
import { Copy, Check } from 'lucide-react'

interface CopyButtonProps {
  text: string
  label?: string
  copiedLabel?: string
  showIcon?: boolean
  className?: string
  onCopy?: () => void
}

export function CopyButton({
  text,
  label = 'Copiar',
  copiedLabel = '¡Copiado!',
  showIcon = true,
  className = '',
  onCopy,
}: CopyButtonProps) {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      onCopy?.()
      setTimeout(() => setCopied(false), 2000)
    })
  }, [text, onCopy])

  return (
    <button
      onClick={handleCopy}
      className={`p-1.5 rounded-lg transition-all ${
        copied
          ? 'text-green bg-green-soft'
          : 'text-text-muted hover:text-text-primary hover:bg-glass-bg'
      } ${className}`}
      title={label}
      aria-label={copied ? copiedLabel : label}
    >
      {copied ? (
        <span className="flex items-center gap-1">
          <Check size={13} />
          {label && <span className="text-[10px] font-medium">{copiedLabel}</span>}
        </span>
      ) : (
        <span className="flex items-center gap-1">
          {showIcon && <Copy size={13} />}
          {label && <span className="text-[10px] font-medium">{label}</span>}
        </span>
      )}
    </button>
  )
}
