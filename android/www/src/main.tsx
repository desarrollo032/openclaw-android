import { StrictMode, useState, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { Router } from './lib/router'
import { App } from './App'
import { LocaleContext, getLocale, subscribeLocale } from './i18n'
import { notifyReady, onTokenRefresh, getToken } from './utils/androidBridge'
import './styles/global.css'

// Dark mode Tailwind v4 — siempre oscuro en Android
document.documentElement.classList.add('dark')

// Inicializar el token desde AndroidBridge al arrancar
window.__OPENCLAW_TOKEN = getToken()

// Escuchar refresh de token (Android puede regenerarlo)
onTokenRefresh(() => {
  // El nuevo token ya se actualizó en window.__OPENCLAW_TOKEN
  // apiFetch lo lee en cada request, no necesitamos re-renderizar
})

// Global event emitter para eventos nativos Kotlin → React
window.__oc = {
  emit: (type: string, data: unknown) => {
    window.dispatchEvent(new CustomEvent('native:' + type, { detail: data }))
  }
}

export function Root() {
  const [locale, setLocaleState] = useState(getLocale)

  // Suscribirse a cambios de idioma (manuales o del sistema)
  useEffect(() => subscribeLocale(() => setLocaleState(getLocale())), [])

  return (
    <StrictMode>
      <LocaleContext.Provider value={locale}>
        <Router>
          <App />
        </Router>
      </LocaleContext.Provider>
    </StrictMode>
  )
}

createRoot(document.getElementById('root')!).render(<Root />)

// Señalizar a Android que React cargó OK (una vez el render inicial completa)
setTimeout(notifyReady, 0)
