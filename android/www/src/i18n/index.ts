import { createContext, useContext } from 'react'

// ── Auto-descubrimiento de archivos de idioma ──
// Simplemente agregando un archivo .json en locales/ se detecta automáticamente.
// El archivo en.json es la fuente de verdad para el tipo TranslationKey.
import type en from './locales/en.json'
export type TranslationKey = keyof typeof en
type Translations = Record<TranslationKey, string>

// Mapa de código de idioma → nombre nativo (para el selector de idioma).
// Al agregar un nuevo idioma (ej: fr.json), agregar también su entrada aquí.
const LANGUAGE_LABELS: Record<string, string> = {
  en: 'English',
  es: 'Español',
}

// Carga todos los JSON de locales/ de forma eager (se incluyen en el bundle)
const localeModules = import.meta.glob('./locales/*.json', { eager: true, import: 'default' }) as unknown as Record<string, Translations>

const locales: Record<string, Translations> = {}
const availableLocaleCodes: string[] = []

for (const [path, translations] of Object.entries(localeModules)) {
  // Extraer código: "./locales/en.json" → "en"
  const code = path.match(/\/(\w+)\.json$/)?.[1]
  if (code && path.endsWith('.json')) {
    locales[code] = translations
    availableLocaleCodes.push(code)
  }
}

// Orden alfabético para consistencia
availableLocaleCodes.sort()

function detectLocale(): string {
  // 1. Preferencia guardada por el usuario
  try {
    const saved = localStorage.getItem('locale')
    if (saved && locales[saved]) return saved
  } catch {
    // localStorage puede no estar disponible
  }

  // 2. Detectar del idioma del sistema/navegador
  const langs = navigator.languages || [navigator.language || '']
  const lang = langs.find(l => l) || ''
  for (const code of availableLocaleCodes) {
    if (lang.toLowerCase().startsWith(code)) return code
  }

  // 3. Fallback al primer idioma disponible
  return availableLocaleCodes[0] || 'en'
}

let currentLocale = detectLocale()
let currentTranslations = locales[currentLocale] || locales[availableLocaleCodes[0]] || {} as Translations

export function getLocale(): string {
  return currentLocale
}

let listeners: Array<() => void> = []

export function subscribeLocale(fn: () => void) {
  listeners.push(fn)
  return () => { listeners = listeners.filter(l => l !== fn) }
}

export function setLocale(locale: string) {
  if (locales[locale] && currentLocale !== locale) {
    currentLocale = locale
    currentTranslations = locales[locale]
    try {
      localStorage.setItem('locale', locale)
    } catch {
      // ignore
    }
    listeners.forEach(l => l())
  }
}

// Escuchar cambios de idioma del sistema operativo
if (typeof window !== 'undefined') {
  window.addEventListener('languagechange', () => {
    if (!localStorage.getItem('locale')) {
      const newLocale = detectLocale()
      if (newLocale !== currentLocale) {
        currentLocale = newLocale
        currentTranslations = locales[newLocale]
        listeners.forEach(l => l())
      }
    }
  })
}

export function t(key: TranslationKey, vars?: Record<string, string>): string {
  let text = currentTranslations[key]
  // Fallback al primer locale disponible (inglés generalmente)
  if (!text && availableLocaleCodes[0]) {
    text = locales[availableLocaleCodes[0]]?.[key] || key
  }
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      text = text.replace(`{${k}}`, v)
    }
  }
  return text
}

// Context para forzar re-renderizados cuando cambia el locale
export const LocaleContext = createContext<string>(currentLocale)
export const useLocale = () => useContext(LocaleContext)

export const availableLocales = availableLocaleCodes.map(code => ({
  code,
  label: LANGUAGE_LABELS[code] || code,
}))
