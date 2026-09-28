import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'
import type { Currency } from '@/api/types'

export const cn = (...i: ClassValue[]) => twMerge(clsx(i))

export function fmtMoney(amount: number, currency: Currency, locale: string) {
  return new Intl.NumberFormat(locale === 'ar' ? 'ar-EG' : 'en-US', { style: 'currency', currency, maximumFractionDigits: 0 }).format(amount)
}
export function fmtNum(n: number, locale: string) {
  return new Intl.NumberFormat(locale === 'ar' ? 'ar-EG' : 'en-US').format(n)
}
export function fmtDate(iso: string, locale: string, withTime = false) {
  return new Intl.DateTimeFormat(locale === 'ar' ? 'ar-EG' : 'en-GB', { dateStyle: 'medium', ...(withTime ? { timeStyle: 'short' } : {}) }).format(new Date(iso))
}
export function timeAgo(iso: string, locale: string) {
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  const rtf = new Intl.RelativeTimeFormat(locale === 'ar' ? 'ar' : 'en', { numeric: 'auto' })
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
export const INTERPRETER_DOMAIN = (import.meta.env.VITE_INTERPRETER_DOMAIN as string | undefined) || '@saadatu-aldarein.com'
