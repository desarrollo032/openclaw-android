import { StrictMode, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Router } from './lib/router'
import { App } from './App'
import { LocaleContext, getLocale } from './i18n'
import { notifyReady, onTokenRefresh } from './utils/androidBridge'
import './styles/global.css'

// Escuchar refresh de token (Android puede regenerarlo)
onTokenRefresh(_newToken => {
  // El nuevo token ya se actualizó en window.__OPENCLAW_TOKEN
  // apiFetch lo lee en cada request, no necesitamos re-renderizar
})

// Global event emitter para eventos nativos Kotlin → React
window.__oc = {
  emit: (type: string, data: unknown) => {
    window.dispatchEvent(new CustomEvent('native:' + type, { detail: data }))
  }
}

function Root() {
  const [locale] = useState(getLocale)
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
