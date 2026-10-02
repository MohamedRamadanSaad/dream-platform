import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import ar from './ar.json'
import en from './en.json'

// Start in the visitor's saved language. Starting in Arabic and switching after the first render left
// English visitors with a half-Arabic hero title (the title animation splits the words already on screen).
const saved = (() => { try { return JSON.parse(localStorage.getItem('saadat-auth') ?? '{}')?.state?.locale } catch { return undefined } })()
const initial: 'ar' | 'en' = saved === 'en' ? 'en' : 'ar'
document.documentElement.lang = initial
document.documentElement.dir = initial === 'ar' ? 'rtl' : 'ltr'

i18n.use(initReactI18next).init({
  resources: { ar: { translation: ar }, en: { translation: en } },
  lng: initial,
  fallbackLng: 'ar',
  interpolation: { escapeValue: false },
})

/** Registered by main.tsx so a language switch refetches server-localised data (packages, wait time, country names). */
let onLocaleChange: ((l: 'ar' | 'en') => void) | null = null
export function setLocaleChangeListener(fn: (l: 'ar' | 'en') => void) { onLocaleChange = fn }

export function applyLocale(l: 'ar' | 'en') {
  i18n.changeLanguage(l)
  document.documentElement.lang = l
  document.documentElement.dir = l === 'ar' ? 'rtl' : 'ltr'
  onLocaleChange?.(l)
}

export default i18n
