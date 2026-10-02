// "Visits" scoreboard (GET /admin/analytics/traffic) with one filter row that scopes everything below it,
// followed by the all-time score records that come with the same report.
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { ErrorBox, Input, Skeleton } from '@/components/ui'
import { Icon, type IconName } from '@/components/icons/Icon'
import { StaggerGroup } from '@/components/motion'
import { addDays, cn, flagEmoji, fmtDay, fmtDayRange, fmtNum, fmtPct, parseDay, toISODay } from '@/lib/utils'
import type { DeviceType, TrafficKpis, TrafficQuery, TrafficReport } from '@/api/types'
import { BarList, DeltaChip, LegendKey, Panel, SectionHeader } from './DashboardParts'
import { DailyChart, HourlyBars } from './DashboardCharts'
import { RecordsBoard } from './DashboardRecords'

type Preset = 'month' | '7d' | '30d' | '3m' | 'custom'
const PRESETS: Preset[] = ['month', '7d', '30d', '3m', 'custom']
const DEVICES: DeviceType[] = ['MOBILE', 'DESKTOP', 'TABLET']
const DEVICE_COLOR: Record<DeviceType, string> = { MOBILE: 'var(--viz-1)', DESKTOP: 'var(--viz-2)', TABLET: 'var(--viz-3)' }

interface Filters { preset: Preset; from: string; to: string; country: string; device: '' | DeviceType; path: string }
const INITIAL: Filters = { preset: 'month', from: '', to: '', country: '', device: '', path: '' }

/** Date range of a preset; null while a custom range is incomplete or reversed (the last valid query stays). */
function rangeOf(f: Filters): Pick<TrafficQuery, 'from' | 'to'> | null {
  const today = toISODay(new Date())
  switch (f.preset) {
    case 'month': return {} // server default: this calendar month up to today
    case '7d': return { from: addDays(today, -6), to: today }
    case '30d': return { from: addDays(today, -29), to: today }
    case '3m': { const d = parseDay(today); d.setMonth(d.getMonth() - 3); return { from: addDays(toISODay(d), 1), to: today } }
    case 'custom': return f.from && f.to && f.from <= f.to ? { from: f.from, to: f.to } : null
  }
}

// friendly names for the site's routes (the raw path is shown under them)
const PAGE_KEYS: [RegExp, string][] = [
  [/^\/$/, 'home'], [/^\/login$/, 'login'], [/^\/onboarding$/, 'onboarding'], [/^\/auth\/callback$/, 'callback'],
  [/^\/me$/, 'myDreams'], [/^\/me\/new$/, 'newDream'], [/^\/me\/dreams\/[^/]+\/edit$/, 'dreamEdit'], [/^\/me\/dreams\/[^/]+$/, 'dreamDetail'],
  [/^\/me\/packages$/, 'packages'], [/^\/me\/checkout$/, 'checkout'], [/^\/checkout\//, 'checkout'], [/^\/me\/payments$/, 'payments'],
  [/^\/me\/profile$/, 'profile'], [/^\/me\/notifications$/, 'notifications'], [/^\/me\/courses$/, 'myCourses'], [/^\/courses$/, 'courses'],
  [/^\/terms$/, 'terms'], [/^\/privacy$/, 'privacy'],
]
function usePageName() {
  const { t } = useTranslation()
  return (path: string) => {
    const key = PAGE_KEYS.find(([re]) => re.test(path))?.[1]
    return key ? t(`admin.traffic.page.${key}`) : null
  }
}

const KPIS: { key: keyof TrafficKpis; icon: IconName }[] = [
  { key: 'views', icon: 'eye' }, { key: 'visitors', icon: 'user' }, { key: 'signups', icon: 'plus' },
  { key: 'dreams', icon: 'moon' }, { key: 'paidOrders', icon: 'wallet' }, { key: 'conversionRate', icon: 'chart' },
]

function KpiTiles({ r }: { r: TrafficReport }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const fmt = (k: keyof TrafficKpis, v: number) => (k === 'conversionRate' ? fmtPct(v, locale) : fmtNum(v, locale))
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6">
      {KPIS.map(({ key, icon }) => (
        <div key={key} className="card flex min-w-0 flex-col p-4">
          <div className="flex items-start justify-between gap-2 text-xs text-fg-muted"><span>{t(`admin.traffic.kpi.${key}`)}</span><span className="shrink-0 text-gold-ink"><Icon name={icon} size={16} /></span></div>
          <div className="mt-2 font-display text-2xl leading-tight md:text-3xl">{fmt(key, r.current[key])}</div>
          <div className="mt-auto flex flex-wrap items-center gap-x-2 gap-y-1 pt-3">
            <DeltaChip current={r.current[key]} previous={r.previous[key]} />
            <span className="text-[11px] text-fg-dim">{t('admin.traffic.before', { v: fmt(key, r.previous[key]) })}</span>
          </div>
        </div>
      ))}
    </div>
  )
}

function DeviceSplit({ devices }: { devices: TrafficReport['devices'] }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const rows = DEVICES.map((d) => devices.find((x) => x.device === d)).filter((x): x is TrafficReport['devices'][number] => !!x)
  const total = rows.reduce((a, r) => a + r.views, 0)
  if (!total) return <p className="py-8 text-center text-sm text-fg-muted">{t('admin.traffic.noData')}</p>
  return (
    <div>
      <div className="flex h-3 gap-[2px] overflow-hidden rounded-full" aria-hidden="true">
        {rows.filter((r) => r.views > 0).map((r) => <div key={r.device} className="h-full min-w-[3px]" style={{ flexGrow: r.views, flexBasis: 0, background: DEVICE_COLOR[r.device] }} />)}
      </div>
      <ul className="mt-5 space-y-3">
        {rows.map((r) => (
          <li key={r.device} className="flex items-center justify-between gap-3 text-sm">
            <span className="flex items-center gap-2.5"><span aria-hidden="true" className="h-3 w-3 rounded-[4px]" style={{ background: DEVICE_COLOR[r.device] }} />{t(`admin.traffic.device.${r.device}`)}</span>
            <span className="flex items-baseline gap-1.5 text-fg-muted"><span className="font-medium text-fg">{fmtPct((r.views / total) * 100, locale, 0)}</span><span aria-hidden="true" className="text-fg-dim">·</span><span>{fmtNum(r.views, locale)}</span></span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function DailyTable({ r }: { r: TrafficReport }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  return (
    <details className="mt-4">
      <summary className="cursor-pointer select-none text-xs text-fg-muted hover:text-fg">{t('admin.traffic.showTable')}</summary>
      <div className="mt-3 max-h-72 overflow-auto rounded-xl border border-line">
        <table className="w-full text-xs">
          <thead className="sticky top-0 bg-surface-2 text-fg-muted">
            <tr>{[t('admin.traffic.date'), t('admin.traffic.kpi.views'), t('admin.traffic.kpi.visitors'), t('admin.traffic.previous')].map((h, i) => <th key={h} className={cn('px-3 py-2 font-normal', i ? 'text-end' : 'text-start')}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {r.daily.map((d, i) => (
              <tr key={d.date} className="border-t border-line">
                <td className="px-3 py-1.5">{fmtDay(d.date, locale, { weekday: 'short', day: 'numeric', month: 'short' })}</td>
                <td className="px-3 py-1.5 text-end tabular-nums">{fmtNum(d.views, locale)}</td>
                <td className="px-3 py-1.5 text-end tabular-nums">{fmtNum(d.visitors, locale)}</td>
                <td className="px-3 py-1.5 text-end tabular-nums text-fg-muted">{fmtNum(d.previousViews, locale)} <span className="text-fg-dim">({fmtDay(addDays(r.compareFrom, i), locale)})</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  )
}

export function TrafficSection() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const pageName = usePageName()
  const [filters, setFilters] = useState<Filters>(INITIAL)
  const [params, setParams] = useState<TrafficQuery>({})
  const [paths, setPaths] = useState<string[]>([])
  const q = useQuery({ queryKey: ['admin', 'traffic', locale, params], queryFn: () => adminApi.traffic(params), placeholderData: keepPreviousData, staleTime: 60_000 })
  const countries = useQuery({ queryKey: ['admin', 'countries-list'], queryFn: adminApi.countries_list, staleTime: 10 * 60_000 })
  const r = q.data
  const today = toISODay(new Date())

  // page options come from the report's top pages and are kept, so picking one page does not empty the list
  useEffect(() => {
    if (r && !params.path) setPaths((old) => [...new Set([...old, ...r.topPages.map((p) => p.path)])])
  }, [r, params.path])

  // while a custom range is incomplete or reversed, the last valid range stays (other filters still apply)
  const lastRange = useRef<Pick<TrafficQuery, 'from' | 'to'>>({})
  const update = (patch: Partial<Filters>) => {
    const next = { ...filters, ...patch }
    if (patch.preset === 'custom' && !next.from && !next.to && r) Object.assign(next, { from: r.from, to: r.to })
    setFilters(next)
    const range = rangeOf(next)
    if (range) lastRange.current = range
    setParams({ ...(range ?? lastRange.current), country: next.country || undefined, device: next.device || undefined, path: next.path || undefined })
  }
  const clear = () => { lastRange.current = {}; setFilters(INITIAL); setParams({}) }
  const dirty = filters.preset !== 'month' || !!filters.country || !!filters.device || !!filters.path
  const badRange = filters.preset === 'custom' && !!filters.from && !!filters.to && filters.from > filters.to
  const countryOptions = [...(countries.data ?? [])].sort((a, b) => (locale === 'ar' ? a.nameAr.localeCompare(b.nameAr, 'ar') : a.nameEn.localeCompare(b.nameEn, 'en')))

  return (
    <>
      <section aria-labelledby="traffic-title">
        <SectionHeader id="traffic-title" icon="chart" title={t('admin.traffic.title')} lead={t('admin.traffic.lead')} />

        <div className="card mb-4 space-y-3 p-4">
          <div role="group" aria-label={t('admin.traffic.rangeLabel')} className="flex flex-wrap gap-1.5">
            {PRESETS.map((p) => (
              <button key={p} type="button" aria-pressed={filters.preset === p} onClick={() => update({ preset: p })}
                className={cn('rounded-full px-3.5 py-1.5 text-xs transition-colors', filters.preset === p ? 'bg-night text-pearl dark:bg-gold dark:text-night' : 'bg-surface-2 text-fg-muted hover:text-fg')}>
                {t(`admin.traffic.range.${p}`)}
              </button>
            ))}
          </div>
          {filters.preset === 'custom' && (
            <div className="grid max-w-md grid-cols-2 gap-2">
              <label className="text-xs text-fg-muted">{t('admin.traffic.from')}
                <Input type="date" dir="ltr" className="mt-1 py-2" value={filters.from} max={filters.to || today} onChange={(e) => update({ from: e.target.value })} />
              </label>
              <label className="text-xs text-fg-muted">{t('admin.traffic.to')}
                <Input type="date" dir="ltr" className="mt-1 py-2" value={filters.to} min={filters.from || undefined} max={today} onChange={(e) => update({ to: e.target.value })} />
              </label>
              {badRange && <p role="alert" className="col-span-2 text-xs text-bad-ink">{t('admin.traffic.badRange')}</p>}
            </div>
          )}
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            <select aria-label={t('common.country')} className="input min-w-0 py-2" value={filters.country} onChange={(e) => update({ country: e.target.value })}>
              <option value="">{t('admin.traffic.allCountries')}</option>
              {countryOptions.map((c) => <option key={c.code} value={c.code}>{flagEmoji(c.code)} {locale === 'ar' ? c.nameAr : c.nameEn}</option>)}
            </select>
            <select aria-label={t('admin.traffic.deviceLabel')} className="input min-w-0 py-2" value={filters.device} onChange={(e) => update({ device: e.target.value as Filters['device'] })}>
              <option value="">{t('admin.traffic.allDevices')}</option>
              {DEVICES.map((d) => <option key={d} value={d}>{t(`admin.traffic.device.${d}`)}</option>)}
            </select>
            <select aria-label={t('admin.traffic.pageLabel')} className="input col-span-2 min-w-0 py-2 sm:col-span-1" value={filters.path} onChange={(e) => update({ path: e.target.value })}>
              <option value="">{t('admin.traffic.allPages')}</option>
              {paths.map((p) => <option key={p} value={p}>{pageName(p) ? `${pageName(p)} — ${p}` : p}</option>)}
            </select>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-fg-muted">
            <span className="flex items-center gap-2">
              {q.isFetching && <span aria-hidden="true" className="h-3 w-3 animate-spin rounded-full border-2 border-gold border-t-transparent" />}
              {r && <span>{fmtDayRange(r.from, r.to, locale)} · {t('admin.traffic.comparedWith', { range: fmtDayRange(r.compareFrom, r.compareTo, locale) })}</span>}
            </span>
            {dirty && <button type="button" onClick={clear} className="text-gold-ink hover:underline dark:text-gold">{t('admin.traffic.clear')}</button>}
          </div>
        </div>

        {!r ? (
          q.isError ? <ErrorBox onRetry={() => q.refetch()} /> : (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6">{KPIS.map((k) => <Skeleton key={k.key} className="h-32" />)}</div>
              <Skeleton className="h-72" />
              <Skeleton className="h-48" />
            </div>
          )
        ) : (
          <div className={cn('space-y-4 transition-opacity duration-300', q.isPlaceholderData && 'opacity-60')} aria-busy={q.isFetching}>
            <KpiTiles r={r} />
            <Panel title={t('admin.traffic.daily')} aside={
              <div className="flex flex-wrap gap-x-4 gap-y-1">
                <LegendKey color="var(--viz-line)" label={t('admin.traffic.current')} />
                <LegendKey color="var(--viz-prev)" dashed label={t('admin.traffic.previous')} />
              </div>
            }>
              <DailyChart rows={r.daily} compareFrom={r.compareFrom} />
              <DailyTable r={r} />
            </Panel>
            <Panel title={t('admin.traffic.hourly')} aside={<LegendKey swatch color="var(--viz-line)" label={t('admin.traffic.busiest')} />}>
              <HourlyBars hourly={r.hourly} />
            </Panel>
            {/* two stacked columns keep the cards' heights balanced (pages are tall, devices short) */}
            <div className="grid items-start gap-4 lg:grid-cols-2">
              <StaggerGroup className="space-y-4" stagger={0.08}>
                <Panel title={t('admin.traffic.topPages')}>
                  <BarList empty={t('admin.traffic.noData')} rows={r.topPages.map((p) => {
                    const name = pageName(p.path)
                    return {
                      key: p.path, value: p.views, valueText: fmtNum(p.views, locale), sub: t('admin.traffic.visitorsN', { n: fmtNum(p.visitors, locale) }),
                      label: name ? <span className="flex min-w-0 flex-col"><span className="truncate">{name}</span><span dir="ltr" className="max-w-full self-start truncate text-[11px] text-fg-dim">{p.path}</span></span> : <span dir="ltr">{p.path}</span>,
                    }
                  })} />
                </Panel>
                <Panel title={t('admin.traffic.devices')}><DeviceSplit devices={r.devices} /></Panel>
              </StaggerGroup>
              <StaggerGroup className="space-y-4" stagger={0.08}>
                <Panel title={t('admin.traffic.topCountries')}>
                  <BarList empty={t('admin.traffic.noData')} rows={r.topCountries.map((c) => ({
                    key: c.countryCode, value: c.views, valueText: fmtNum(c.views, locale), sub: t('admin.traffic.visitorsN', { n: fmtNum(c.visitors, locale) }),
                    label: <><span aria-hidden="true" className="me-2">{flagEmoji(c.countryCode)}</span>{c.countryName}</>,
                  }))} />
                </Panel>
                <Panel title={t('admin.traffic.referrers')}>
                  <BarList empty={t('admin.traffic.noData')} rows={r.referrers.map((x) => ({
                    key: x.host || '(direct)', value: x.views, valueText: fmtNum(x.views, locale),
                    label: x.host ? <span dir="ltr">{x.host}</span> : t('admin.traffic.direct'),
                  }))} />
                </Panel>
              </StaggerGroup>
            </div>
          </div>
        )}
      </section>

      {r ? <RecordsBoard records={r.records} /> : !q.isError && <Skeleton className="h-64" />}
    </>
  )
}
