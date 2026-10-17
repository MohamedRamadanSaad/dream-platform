// Filters of the payments page (docs/PAYMENTS_FILTERS_CONTRACT.md §3–§4). Everything lives in the URL query (with
// ?tab= and ?q=), so Back and shared links keep them; a change always goes back to the first page.
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Input, Modal } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { cn, flagEmoji, fmtDate, fmtNum } from '@/lib/utils'
import type { AdminOrderFilters, Currency, OrderFlag, OrderScope, OrderStatus, PaymentProvider } from '@/api/types'

export type OrdersTab = Exclude<OrderScope, 'ALL'>

const ALL_STATUSES: OrderStatus[] = ['INITIATED', 'PENDING_REVIEW', 'SUCCESS', 'FAILED', 'EXPIRED', 'REFUNDED', 'SUSPICIOUS']
/** The statuses each tab can hold: unpaid bookings live only in UNPAID, the review tab is one status. */
export const STATUS_BY_TAB: Record<OrdersTab, OrderStatus[]> = {
  REVIEW: [],
  DOMESTIC: ['PENDING_REVIEW', 'SUCCESS', 'FAILED', 'REFUNDED', 'SUSPICIOUS'],
  INTERNATIONAL: ['PENDING_REVIEW', 'SUCCESS', 'FAILED', 'REFUNDED', 'SUSPICIOUS'],
  // bookings never paid, and card checkouts that failed at the gateway
  UNPAID: ['INITIATED', 'EXPIRED', 'FAILED'],
}
const PROVIDERS: (PaymentProvider | 'MOCK')[] = ['INSTAPAY', 'MOR', 'KASHIER', 'MOCK']
const CURRENCIES: Currency[] = ['EGP', 'SAR', 'USD']
const FLAGS: OrderFlag[] = ['REVOKED', 'RESENT', 'LEFTOVER_WITHDRAWN']
const DAY = /^\d{4}-\d{2}-\d{2}$/
/** A real calendar day (2026-13-40 or 2026-02-30 are not): never trusted from the address as is. */
export const isDay = (v: string | null | undefined): v is string => {
  if (!v || !DAY.test(v)) return false
  const d = new Date(`${v}T00:00:00Z`)
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === v
}
/** The URL keys the filters use (the API's own names). */
const KEYS = ['status', 'provider', 'country', 'currency', 'from', 'to', 'minAmount', 'maxAmount', 'packageId', 'flag'] as const

const shows = (tab: OrdersTab) => ({
  status: STATUS_BY_TAB[tab].length > 0,
  provider: tab !== 'REVIEW',
  country: tab !== 'DOMESTIC',
  flag: tab !== 'UNPAID',
})

/** The filters in the address (unknown values are ignored, never sent). */
export function readFilters(p: URLSearchParams): AdminOrderFilters {
  const num = (k: string) => { const v = p.get(k); if (v == null || v === '') return undefined; const n = Number(v); return Number.isFinite(n) && n >= 0 ? n : undefined }
  const pick = <X extends string>(k: string, list: readonly X[]) => { const v = p.get(k) as X | null; return v && list.includes(v) ? v : undefined }
  const status = (p.get('status') ?? '').split(',').filter((s): s is OrderStatus => (ALL_STATUSES as string[]).includes(s))
  const day = (k: string) => { const v = p.get(k); return isDay(v) ? v : undefined }
  const country = p.get('country')
  return {
    status: status.length ? status : undefined,
    provider: pick('provider', PROVIDERS), country: country && /^[A-Z]{2}$/.test(country) ? country : undefined, currency: pick('currency', CURRENCIES),
    from: day('from'), to: day('to'),
    // an amount only means something in one currency
    minAmount: pick('currency', CURRENCIES) ? num('minAmount') : undefined, maxAmount: pick('currency', CURRENCIES) ? num('maxAmount') : undefined,
    packageId: p.get('packageId') || undefined, flag: pick('flag', FLAGS),
  }
}

/** The same query with these filters (empty ones removed) and back on the first page. */
export function writeFilters(p: URLSearchParams, f: AdminOrderFilters): URLSearchParams {
  const n = new URLSearchParams(p)
  for (const k of KEYS) n.delete(k)
  const set = (k: string, v: string | number | undefined) => { if (v !== undefined && v !== '') n.set(k, String(v)) }
  set('status', f.status?.length ? f.status.join(',') : undefined)
  set('provider', f.provider); set('country', f.country); set('currency', f.currency); set('from', f.from); set('to', f.to)
  set('minAmount', f.minAmount); set('maxAmount', f.maxAmount); set('packageId', f.packageId); set('flag', f.flag)
  n.delete('page')
  return n
}

/** Only what the tab can use (a status of another tab, a flag on unpaid bookings) is kept when the tab changes. */
export function fitToTab(f: AdminOrderFilters, tab: OrdersTab): AdminOrderFilters {
  const s = shows(tab)
  const status = f.status?.filter((x) => STATUS_BY_TAB[tab].includes(x))
  return { ...f, status: status?.length ? status : undefined, provider: s.provider ? f.provider : undefined, country: s.country ? f.country : undefined, flag: s.flag ? f.flag : undefined }
}

export type FilterErrors = { amount?: string; date?: string }
/** Combinations the server refuses (400 INVALID_FILTER): the list is not asked for while one is shown. */
export function validateFilters(f: AdminOrderFilters): FilterErrors {
  const e: FilterErrors = {}
  if (f.minAmount != null && f.maxAmount != null && f.minAmount > f.maxAmount) e.amount = 'admin.orders.filters.errMinMax'
  if (f.from && f.to && f.from > f.to) e.date = 'admin.orders.filters.errFromTo'
  return e
}
export const hasErrors = (e: FilterErrors) => !!(e.amount || e.date)

export const activeCount = (f: AdminOrderFilters) =>
  (f.status?.length ?? 0) + KEYS.filter((k) => k !== 'status' && f[k] !== undefined).length

/** Countries and packages for the selects (the countries list endpoint; packages from the admin list). */
function useOptions(enabled: boolean) {
  const locale = useAuthStore((s) => s.locale)
  const countries = useQuery({ queryKey: ['admin', 'countries-list'], queryFn: adminApi.countries_list, staleTime: 10 * 60_000, enabled })
  const packages = useQuery({ queryKey: ['admin', 'packages'], queryFn: adminApi.packages, staleTime: 10 * 60_000, enabled })
  return useMemo(() => ({
    countries: [...(countries.data ?? [])].sort((a, b) => (locale === 'ar' ? a.nameAr.localeCompare(b.nameAr, 'ar') : a.nameEn.localeCompare(b.nameEn, 'en'))).map((c) => ({ code: c.code, name: locale === 'ar' ? c.nameAr : c.nameEn })),
    packages: (packages.data ?? []).map((p) => ({ id: p.id, name: locale === 'ar' ? p.nameAr : p.nameEn })),
  }), [countries.data, packages.data, locale])
}

/** The amount fields wait for a short pause in typing before the list reloads. */
function AmountInput({ id, value, onCommit, label, disabled, invalid }: { id: string; value?: number; onCommit: (v?: number) => void; label: string; disabled?: boolean; invalid?: boolean }) {
  const { t } = useTranslation()
  const [text, setText] = useState(value != null ? String(value) : '')
  const negative = text.trim() !== '' && Number(text) < 0
  useEffect(() => { setText(value != null ? String(value) : '') }, [value])
  useEffect(() => {
    const v = text.trim() === '' ? undefined : Number(text)
    if (v === value || (v !== undefined && (!Number.isFinite(v) || v < 0))) return
    const timer = window.setTimeout(() => onCommit(v), 450)
    return () => window.clearTimeout(timer)
  }, [text]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <label htmlFor={id} className="min-w-0"><span className="label">{label}</span>
      <Input id={id} type="number" inputMode="decimal" min={0} dir="ltr" value={text} onChange={(e) => setText(e.target.value)} disabled={disabled}
        aria-invalid={negative || invalid} aria-describedby={negative ? `${id}-err` : undefined} className={cn('h-10 disabled:opacity-50', (negative || invalid) && 'border-danger')} />
      {negative && <span id={`${id}-err`} role="alert" className="mt-1 block text-xs text-bad-ink">{t('admin.orders.filters.errNegative')}</span>}
    </label>
  )
}

function Fields({ tab, f, set, idPrefix }: { tab: OrdersTab; f: AdminOrderFilters; set: (patch: Partial<AdminOrderFilters>) => void; idPrefix: string }) {
  const { t } = useTranslation()
  const errors = validateFilters(f)
  const opts = useOptions(true)
  const s = shows(tab)
  const statuses = STATUS_BY_TAB[tab]
  const toggle = (x: OrderStatus) => {
    const cur = f.status ?? []
    const next = cur.includes(x) ? cur.filter((y) => y !== x) : [...cur, x]
    set({ status: next.length ? next : undefined })
  }
  const select = 'input h-10 py-0'
  const id = (k: string) => `${idPrefix}-${k}`
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-6">
      {s.status && (
        <fieldset className="col-span-2 md:col-span-4 xl:col-span-6">
          <legend className="label">{t('admin.orders.filters.status')}</legend>
          <div className="flex flex-wrap gap-2">
            {statuses.map((x) => {
              const on = !!f.status?.includes(x)
              return (
                <button key={x} type="button" aria-pressed={on} onClick={() => toggle(x)}
                  className={cn('min-h-9 rounded-full border px-3.5 text-sm transition-colors', on ? 'border-gold bg-gold/15 text-gold-ink' : 'border-line text-fg-muted hover:border-gold hover:text-fg')}>
                  {on && <Icon name="check" size={13} className="me-1 inline" />}{t(`me.orderStatus.${x}`)}
                </button>
              )
            })}
          </div>
        </fieldset>
      )}
      {s.provider && (
        <label htmlFor={id('provider')} className="min-w-0"><span className="label">{t('admin.orders.filters.provider')}</span>
          <select id={id('provider')} className={select} value={f.provider ?? ''} onChange={(e) => set({ provider: (e.target.value || undefined) as AdminOrderFilters['provider'] })}>
            <option value="">{t('admin.orders.filters.any')}</option>
            {PROVIDERS.map((p) => <option key={p} value={p}>{t(`admin.orders.filters.providers.${p}`)}</option>)}
          </select>
        </label>
      )}
      {s.country && (
        <label htmlFor={id('country')} className="min-w-0"><span className="label">{t('admin.orders.filters.country')}</span>
          <select id={id('country')} className={select} value={f.country ?? ''} onChange={(e) => set({ country: e.target.value || undefined })}>
            <option value="">{t('admin.orders.filters.any')}</option>
            {opts.countries.map((c) => <option key={c.code} value={c.code}>{flagEmoji(c.code)} {c.name}</option>)}
          </select>
        </label>
      )}
      <label htmlFor={id('currency')} className="min-w-0"><span className="label">{t('admin.orders.filters.currency')}</span>
        <select id={id('currency')} className={select} value={f.currency ?? ''} onChange={(e) => set(e.target.value ? { currency: e.target.value as Currency } : { currency: undefined, minAmount: undefined, maxAmount: undefined })}>
          <option value="">{t('admin.orders.filters.any')}</option>
          {CURRENCIES.map((c) => <option key={c} value={c}>{t(`admin.orders.filters.currencies.${c}`)}</option>)}
        </select>
      </label>
      <label htmlFor={id('package')} className="min-w-0"><span className="label">{t('admin.orders.filters.package')}</span>
        <select id={id('package')} className={select} value={f.packageId ?? ''} onChange={(e) => set({ packageId: e.target.value || undefined })}>
          <option value="">{t('admin.orders.filters.any')}</option>
          {opts.packages.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      </label>
      <label htmlFor={id('from')} className="min-w-0"><span className="label">{t('admin.orders.filters.from')}</span>
        <Input id={id('from')} type="date" dir="ltr" className="h-10" value={f.from ?? ''} max={f.to || undefined} onChange={(e) => set({ from: e.target.value || undefined })} />
      </label>
      <label htmlFor={id('to')} className="min-w-0"><span className="label">{t('admin.orders.filters.to')}</span>
        <Input id={id('to')} type="date" dir="ltr" className="h-10" value={f.to ?? ''} min={f.from || undefined} onChange={(e) => set({ to: e.target.value || undefined })} />
      </label>
      <AmountInput id={id('min')} label={t('admin.orders.filters.minAmount')} value={f.minAmount} onCommit={(v) => set({ minAmount: v })} disabled={!f.currency} invalid={!!errors.amount} />
      <AmountInput id={id('max')} label={t('admin.orders.filters.maxAmount')} value={f.maxAmount} onCommit={(v) => set({ maxAmount: v })} disabled={!f.currency} invalid={!!errors.amount} />
      {(!f.currency || errors.amount || errors.date) && (
        <div className="col-span-2 space-y-1 text-xs md:col-span-4 xl:col-span-6">
          {!f.currency && <p className="text-fg-dim">{t('admin.orders.filters.currencyFirst')}</p>}
          {errors.amount && <p role="alert" className="text-bad-ink">{t(errors.amount)}</p>}
          {errors.date && <p role="alert" className="text-bad-ink">{t(errors.date)}</p>}
        </div>
      )}
      {s.flag && (
        <label htmlFor={id('flag')} className="col-span-2 min-w-0"><span className="label">{t('admin.orders.filters.flag')}</span>
          <select id={id('flag')} className={select} value={f.flag ?? ''} onChange={(e) => set({ flag: (e.target.value || undefined) as OrderFlag | undefined })}>
            <option value="">{t('admin.orders.filters.any')}</option>
            {FLAGS.map((x) => <option key={x} value={x}>{t(`admin.orders.filters.flags.${x}`)}</option>)}
          </select>
        </label>
      )}
    </div>
  )
}

/** One removable chip per active filter, then "Clear all". */
function Chips({ f, set, clear }: { f: AdminOrderFilters; set: (patch: Partial<AdminOrderFilters>) => void; clear: () => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const opts = useOptions(activeCount(f) > 0)
  const chips: { key: string; label: string; drop: () => void }[] = []
  for (const s of f.status ?? []) chips.push({ key: `s-${s}`, label: t(`me.orderStatus.${s}`), drop: () => { const next = (f.status ?? []).filter((x) => x !== s); set({ status: next.length ? next : undefined }) } })
  if (f.provider) chips.push({ key: 'provider', label: t(`admin.orders.filters.providers.${f.provider}`), drop: () => set({ provider: undefined }) })
  if (f.country) chips.push({ key: 'country', label: `${flagEmoji(f.country)} ${opts.countries.find((c) => c.code === f.country)?.name ?? f.country}`, drop: () => set({ country: undefined }) })
  if (f.currency) chips.push({ key: 'currency', label: t(`admin.orders.filters.currencies.${f.currency}`), drop: () => set({ currency: undefined, minAmount: undefined, maxAmount: undefined }) })
  if (f.packageId) chips.push({ key: 'package', label: opts.packages.find((p) => p.id === f.packageId)?.name ?? t('admin.orders.filters.package'), drop: () => set({ packageId: undefined }) })
  // a bad day never reaches the date formatter (it would throw on an invalid date)
  const dayText = (v: string) => { try { return isDay(v) ? fmtDate(`${v}T12:00:00`, locale) : v } catch { return v } }
  if (f.from) chips.push({ key: 'from', label: t('admin.orders.filters.chipFrom', { date: dayText(f.from) }), drop: () => set({ from: undefined }) })
  if (f.to) chips.push({ key: 'to', label: t('admin.orders.filters.chipTo', { date: dayText(f.to) }), drop: () => set({ to: undefined }) })
  if (f.minAmount != null) chips.push({ key: 'min', label: t('admin.orders.filters.chipMin', { n: fmtNum(f.minAmount, locale) }), drop: () => set({ minAmount: undefined }) })
  if (f.maxAmount != null) chips.push({ key: 'max', label: t('admin.orders.filters.chipMax', { n: fmtNum(f.maxAmount, locale) }), drop: () => set({ maxAmount: undefined }) })
  if (f.flag) chips.push({ key: 'flag', label: t(`admin.orders.filters.flags.${f.flag}`), drop: () => set({ flag: undefined }) })
  if (!chips.length) return null
  return (
    <ul aria-label={t('admin.orders.filters.active')} className="flex flex-wrap items-center gap-2 md:mt-3">
      {chips.map((c) => (
        <li key={c.key} className="inline-flex items-center gap-1 rounded-full border border-gold/50 bg-gold/10 py-1 pe-1 ps-3 text-xs text-gold-ink">
          <span>{c.label}</span>
          <button type="button" onClick={c.drop} aria-label={t('admin.orders.filters.remove', { label: c.label })}
            className="flex h-6 w-6 items-center justify-center rounded-full hover:bg-gold/20">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </li>
      ))}
      <li><button type="button" onClick={clear} className="min-h-8 px-2 text-xs font-medium text-fg-muted underline-offset-4 hover:text-danger hover:underline">{t('admin.orders.filters.clearAll')}</button></li>
    </ul>
  )
}

/**
 * Phones: a "Filter" button opening a bottom sheet. Desktops: the fields inline in a compact bar. Both apply at once;
 * the active filters always show as chips under it.
 */
export function OrderFilterBar({ tab, filters, onPatch, onClear, search }: { tab: OrdersTab; filters: AdminOrderFilters; onPatch: (patch: Partial<AdminOrderFilters>) => void; onClear: () => void; search: ReactNode }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const n = activeCount(filters)
  // a patch, merged with the address as it is when applied (two quick edits never undo each other)
  const set = onPatch
  const clear = onClear
  return (
    <div className="mt-5">
      <div className="md:max-w-md">{search}</div>
      <section aria-label={t('admin.orders.filters.title')} className="card mt-3 hidden p-4 md:block">
        <Fields tab={tab} f={filters} set={set} idPrefix="of-d" />
      </section>
      {/* phones: the filter button leads the chips row */}
      <div className="mt-3 flex flex-wrap items-center gap-2 md:mt-0">
        <Button type="button" variant="ghost" size="sm" className={cn('min-h-10 shrink-0 md:hidden', n > 0 && 'border-gold text-gold-ink')} aria-haspopup="dialog" onClick={() => setOpen(true)}>
          <Icon name="filter" size={16} />{t('admin.orders.filters.title')}{n > 0 && <span className="rounded-full bg-gold px-1.5 text-[11px] font-semibold text-night">{n}</span>}
        </Button>
        <Chips f={filters} set={set} clear={clear} />
      </div>
      <Modal open={open} onClose={() => setOpen(false)} title={t('admin.orders.filters.title')} focusField={false}
        footer={<div className="flex w-full items-center justify-between gap-3">
          <button type="button" className="py-2 text-sm text-fg-muted hover:text-danger" onClick={clear} disabled={n === 0}>{t('admin.orders.filters.clearAll')}</button>
          <Button onClick={() => setOpen(false)}>{t('admin.orders.filters.show')}</Button>
        </div>}>
        <Fields tab={tab} f={filters} set={set} idPrefix="of-m" />
      </Modal>
    </div>
  )
}
