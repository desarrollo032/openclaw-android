/**
 * Skeleton
 * Componente de carga reutilizable con variantes.
 * Soporta: texto, avatar, card, custom shapes.
 *
 * @example
 *   <Skeleton variant="text" width="w-24" />
 *   <Skeleton variant="avatar" />
 *   <Skeleton variant="card" count={4} />
 */
interface SkeletonProps {
  variant?: 'text' | 'avatar' | 'card' | 'circle' | 'custom'
  width?: string
  height?: string
  count?: number
  className?: string
}

function SkeletonItem({ variant, width, height, className = '' }: Omit<SkeletonProps, 'count'>) {
  const base = 'skeleton'

  if (variant === 'avatar') {
    return <div className={`w-9 h-9 rounded-xl ${base} shrink-0 ${className}`} />
  }

  if (variant === 'circle') {
    return <div className={`w-9 h-9 rounded-full ${base} shrink-0 ${className}`} />
  }

  if (variant === 'card') {
    return (
      <div className={`card p-4 ${className}`}>
        <div className="flex items-center gap-3">
          <SkeletonItem variant="avatar" />
          <div className="flex-1 space-y-1.5">
            <div className={`h-3 ${width ?? 'w-20'} skeleton rounded`} />
            <div className={`h-2.5 ${width ? `w-${parseInt(width.replace('w-', '')) + 12}` : 'w-32'} skeleton rounded`} />
          </div>
        </div>
      </div>
    )
  }

  // Default: text line
  return <div className={`h-3 ${width ?? 'w-full'} ${base} rounded ${className}`} style={height ? { height } : undefined} />
}

/**
 * Skeleton loader para estados de carga.
 * Uso: <Skeleton count={3} variant="text" /> o <Skeleton variant="card" />
 */
export function Skeleton({ variant = 'text', width, height, count = 1, className = '' }: SkeletonProps) {
  return (
    <>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className={i > 0 ? 'mt-3' : ''}>
          {variant === 'card' ? (
            <div className={`card p-4 ${className}`}>
              <div className="flex items-center gap-3">
                <SkeletonItem variant="avatar" />
                <div className="flex-1 space-y-1.5">
                  <div className={`h-3 ${width ?? 'w-20'} skeleton rounded`} />
                  <div className={`h-2.5 w-${width ? parseInt(width.replace('w-', '')) + 12 : '32'} skeleton rounded`} />
                </div>
              </div>
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <SkeletonItem variant={variant} />
              {variant !== 'avatar' && variant !== 'circle' && (
                <div className="flex-1 space-y-1.5">
                  <div className={`h-3 ${width ?? 'w-24'} skeleton rounded`} />
                  {height && <div className={`h-2.5 ${width ?? 'w-40'} skeleton rounded`} />}
                </div>
              )}
            </div>
          )}
        </div>
      ))}
    </>
  )
}
