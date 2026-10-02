import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'
import type { Currency } from '@/api/types'

export const cn = (...i: ClassValue[]) => twMerge(clsx(i))

/**
 * One digit style everywhere: Arabic UI uses Western digits (0-9), matching the compact stats ("1M+")
 * and avoiding lines that mix ١٢٩ with 12. Arabic words, currency symbols and date order stay Arabic.
 */
const intlLocale = (locale: string, ar = 'ar-EG', en = 'en-US') => (locale === 'ar' ? `${ar}-u-nu-latn` : en)

export function fmtMoney(amount: number, currency: Currency, locale: string) {
  const nf = new Intl.NumberFormat(intlLocale(locale), { style: 'currency', currency, maximumFractionDigits: 0 })
  if (locale !== 'ar') return nf.format(amount)
  // Inside Arabic text the bidi algorithm prints a Latin symbol like "US$" as "$US": isolate it (LRI … PDI).
  return nf.formatToParts(amount).map((x) => (x.type === 'currency' && /[A-Za-z$£€]/.test(x.value) ? `\u2066${x.value}\u2069` : x.value)).join('')
}
export function fmtNum(n: number, locale: string) {
  return new Intl.NumberFormat(intlLocale(locale)).format(n)
}
export function fmtDate(iso: string, locale: string, withTime = false) {
  // Arabic spells the month ("30 سبتمبر 2026"): Western digits around slashes get reordered by the bidi algorithm.
  const opts: Intl.DateTimeFormatOptions = locale === 'ar'
    ? { day: 'numeric', month: 'long', year: 'numeric', ...(withTime ? { hour: 'numeric', minute: '2-digit' } : {}) }
    : { dateStyle: 'medium', ...(withTime ? { timeStyle: 'short' } : {}) }
  return new Intl.DateTimeFormat(intlLocale(locale, 'ar-EG', 'en-GB'), opts).format(new Date(iso))
}
export function timeAgo(iso: string, locale: string) {
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  const rtf = new Intl.RelativeTimeFormat(intlLocale(locale, 'ar', 'en'), { numeric: 'auto' })
  if (diff < 3600) return rtf.format(-Math.max(1, Math.round(diff / 60)), 'minute')
  if (diff < 86400) return rtf.format(-Math.round(diff / 3600), 'hour')
  return rtf.format(-Math.round(diff / 86400), 'day')
}
export function hoursLeft(iso: string) {
  return (new Date(iso).getTime() - Date.now()) / 36e5
}
export function countdown(iso: string) {
  const s = Math.max(0, (new Date(iso).getTime() - Date.now()) / 1000)
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60)
  return { d, h, m }
}

/** E-mails on the site domain are interpreter accounts (magic-code login only; mirrors backend setting interpreter.email_domain). */
/** Direction-aware arrows: “back” points to the reading start, “next” to the reading end. */
export const arrowBack = (locale: string) => (locale === 'ar' ? '→' : '←')
export const arrowNext = (locale: string) => (locale === 'ar' ? '←' : '→')

export const INTERPRETER_DOMAIN = (import.meta.env.VITE_INTERPRETER_DOMAIN as string | undefined) || '@saadatu-aldarein.com'
