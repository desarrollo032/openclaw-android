import { createContext, useContext } from 'react'
import { en } from './en'
import { es } from './es'

export type TranslationKey = keyof typeof en
type Translations = Record<TranslationKey, string>

const locales: Record<string, Translations> = { en, es }

function detectLocale(): string {
  // 1. Check saved preference
  try {
    const saved = localStorage.getItem('locale')
    if (saved && locales[saved]) return saved
  } catch {
    // localStorage may not be available
  }

  // 2. Detect from browser/system language
  const langs = navigator.languages || [navigator.language || '']
  const lang = langs.find(l => l) || ''
  if (lang.toLowerCase().startsWith('es')) return 'es'
  return 'en'
}

let currentLocale = detectLocale()
let currentTranslations = locales[currentLocale] || en

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
  let text = currentTranslations[key] || en[key] || key
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      text = text.replace(`{${k}}`, v)
    }
  }
  return text
}

// Context for triggering re-renders on locale change
export const LocaleContext = createContext<string>(currentLocale)
export const useLocale = () => useContext(LocaleContext)

export const availableLocales = [
  { code: 'en', label: 'English' },
  { code: 'es', label: 'Español' },
]
