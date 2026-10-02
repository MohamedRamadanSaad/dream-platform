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
// ---------- date-only values ('YYYY-MM-DD' / 'YYYY-MM') are calendar days, parsed in local time ----------
const pad2 = (n: number) => String(n).padStart(2, '0')
const dateLocale = (locale: string) => intlLocale(locale, 'ar-EG', 'en-GB')
const numLocale = (locale: string) => intlLocale(locale)

export function parseDay(day: string) {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, (m || 1) - 1, d || 1)
}
export function toISODay(d: Date) {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}
export function addDays(day: string, n: number) {
  const d = parseDay(day)
  d.setDate(d.getDate() + n)
  return toISODay(d)
}
export function fmtDay(day: string, locale: string, opts: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short' }) {
  return new Intl.DateTimeFormat(dateLocale(locale), opts).format(parseDay(day))
}
export function fmtDayRange(from: string, to: string, locale: string) {
  const f = new Intl.DateTimeFormat(dateLocale(locale), { day: 'numeric', month: 'short', year: 'numeric' })
  return from === to ? f.format(parseDay(from)) : f.formatRange(parseDay(from), parseDay(to))
}
export function fmtMonth(month: string, locale: string) {
  return new Intl.DateTimeFormat(dateLocale(locale), { month: 'long', year: 'numeric' }).format(parseDay(`${month}-01`))
}
/** 21 → "9 PM" / "٩ م". */
export function fmtHour(hour: number, locale: string) {
  return new Intl.DateTimeFormat(numLocale(locale), { hour: 'numeric' }).format(new Date(2000, 0, 1, hour))
}
/** 12.5 → "12.5%" / "١٢٫٥٪". */
export function fmtPct(value: number, locale: string, digits = 1) {
  return new Intl.NumberFormat(numLocale(locale), { style: 'percent', maximumFractionDigits: digits }).format(value / 100)
}
export function fmtCompact(n: number, locale: string) {
  return new Intl.NumberFormat(numLocale(locale), { notation: 'compact', maximumFractionDigits: 1 }).format(n)
}
/** Relative change in %, or null when there is no base to compare with. */
export function pctChange(current: number, previous: number): number | null {
  if (!previous) return current ? null : 0
  return ((current - previous) / previous) * 100
}
/** Country code → flag emoji ("SA" → 🇸🇦). */
export const flagEmoji = (cc: string) => cc.toUpperCase().replace(/[A-Z]/g, (c) => String.fromCodePoint(127397 + c.charCodeAt(0)))
/** Country name in the page language ("EG" → "Egypt" / "مصر"); `fallback` when the browser cannot name it. */
export function regionName(cc: string | null | undefined, locale: string, fallback?: string | null): string | null {
  if (!cc) return fallback ?? null
  try {
    return new Intl.DisplayNames([locale === 'ar' ? 'ar' : 'en'], { type: 'region' }).of(cc.toUpperCase()) ?? fallback ?? cc
  } catch {
    return fallback ?? cc
  }
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
