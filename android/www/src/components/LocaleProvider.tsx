/**
 * LocaleProvider
 * Proveedor de contexto para reactividad del cambio de idioma.
 * Escucha cambios via subscribeLocale y fuerza re-renderizado
 * de los children cuando el locale cambia.
 *
 * @example
 *   <LocaleProvider>
 *     <App />
 *   </LocaleProvider>
 */
import { useState, useEffect } from 'react'
import { LocaleContext, getLocale, subscribeLocale } from '../i18n'

export function LocaleProvider({ children }: { children: React.ReactNode }) {
  const [locale, setLocale] = useState(getLocale())

  useEffect(() => {
    return subscribeLocale(() => {
      setLocale(getLocale())
    })
  }, [])

  return (
    <LocaleContext.Provider value={locale}>
      {children}
    </LocaleContext.Provider>
  )
}
