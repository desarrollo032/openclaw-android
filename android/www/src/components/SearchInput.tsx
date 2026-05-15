/**
 * SearchInput
 * Campo de búsqueda reutilizable con botón de limpiar.
 *
 * Props:
 * @param value     - Valor actual del input
 * @param onChange  - Handler de cambio
 * @param placeholder - Placeholder (def: 'Buscar...')
 * @param onClear   - Handler al limpiar (si no se provee, usa onChange(''))
 * @param className - Clases adicionales
 *
 * @example
 *   <SearchInput value={search} onChange={setSearch} placeholder="Filtrar logs..." />
 */
import { Search, X } from 'lucide-react'

interface SearchInputProps {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  onClear?: () => void
  className?: string
}

export function SearchInput({
  value,
  onChange,
  placeholder = 'Buscar...',
  onClear,
  className = '',
}: SearchInputProps) {
  return (
    <div className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-input-bg border border-glass-border focus-within:border-accent/20 transition-all ${className}`}>
      <Search size={12} className="text-text-dim shrink-0" />
      <input
        className="flex-1 bg-transparent border-none outline-none text-xs text-text-primary placeholder-text-muted"
        placeholder={placeholder}
        value={value}
        onChange={e => onChange(e.target.value)}
      />
      {value && (
        <button
          onClick={() => {
            onChange('')
            onClear?.()
          }}
          className="text-text-dim hover:text-text-primary transition-colors"
          aria-label="Limpiar búsqueda"
        >
          <X size={12} />
        </button>
      )}
    </div>
  )
}
