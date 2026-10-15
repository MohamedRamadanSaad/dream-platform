import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { ErrorBox, Segmented, Skeleton, Stat } from '@/components/ui'
import { Icon, type IconName } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { cn, flagEmoji, fmtDay, fmtMoney, fmtNum, toISODay } from '@/lib/utils'
import type { AdminCountryDashboard, CountryStat, Currency } from '@/api/types'
import { Pager } from '@/components/ui/Pager'
import { SectionHeader } from './DashboardParts'
import { ActivitySection, InsightsSection } from './DashboardInsights'
import { TrafficSection } from './DashboardTraffic'

// Interpreter dashboard: the queue at a glance → recommendations → top countries → her activity →
// visits scoreboard → all-time score records.

const COUNTRY_PAGE = 7

/** One ranked country list: 7 rows a page, rank = place in the whole list, bars relative to the overall top row. */
function CountryList({ title, rows, value, format, sub, icon }: { title: string; rows: CountryStat[]; value: (r: CountryStat) => number; format: (n: number) => string; sub?: (r: CountryStat) => string | null; icon: IconName }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const max = Math.max(...rows.map(value), 1)
  const [h, setH] = useState(false)
  const [page, setPage] = useState(0)
  const pages = Math.max(1, Math.ceil(rows.length / COUNTRY_PAGE))
  const sig = rows.map((r) => r.countryCode).join('|')
  useEffect(() => { setPage(0) }, [sig])
  const cur = Math.min(page, pages - 1)
  const start = cur * COUNTRY_PAGE
  const shown = rows.slice(start, start + COUNTRY_PAGE)
  return (
    <div className="card flex min-w-0 flex-col p-5" onMouseEnter={() => setH(true)} onMouseLeave={() => setH(false)}>
      <div className="mb-4 flex items-center gap-2 text-fg"><span className="text-gold-ink"><Icon name={icon} size={18} active={h} /></span><h3 className="font-medium">{title}</h3></div>
      {rows.length === 0 ? (
        <p className="flex flex-1 items-center justify-center py-8 text-center text-sm text-fg-muted">{t('admin.dashboard.countriesEmpty')}</p>
      ) : (
        <>
          {/* the list keeps a full page's height, so the pager does not jump on the last page */}
          <ol className="space-y-2.5" style={{ minHeight: pages > 1 ? `${COUNTRY_PAGE * 2.6}rem` : undefined }}>
            {shown.map((r, i) => {
              const v = value(r)
              const note = sub?.(r)
              return (
                <li key={r.countryCode}>
                  <Link to={`/admin/users?country=${r.countryCode}`} className="block rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold">
                    <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
                      <span className="min-w-0 truncate"><span className="me-2 tabular-nums text-fg-dim">{fmtNum(start + i + 1, locale)}.</span><span aria-hidden="true">{flagEmoji(r.countryCode)}</span> {r.countryName}</span>
                      <span className="flex shrink-0 flex-col items-end">
                        <span className="text-fg-muted" dir="ltr">{format(v)}</span>
                        {note && <span className="text-[11px] text-fg-dim">{note}</span>}
                      </span>
                    </div>
                    <div className="h-1 overflow-hidden rounded-full bg-surface-2"><div className="h-full bg-gold transition-all duration-700" style={{ width: `${(v / max) * 100}%` }} /></div>
                  </Link>
                </li>
              )
            })}
          </ol>
          <Pager className="mt-4 border-t border-line pt-3" page={cur} pages={pages} onChange={setPage} label={t('admin.dashboard.countriesPager', { title })} />
        </>
      )}
    </div>
  )
}

/** "SAR 1,240 · USD 96": the buyers' own currencies, shown small as a reference. */
function currencyList(list: { currency: Currency; amount: number }[], locale: string) {
  // EGP is the main figure already: only the other currencies are listed
  return list.filter((r) => r.currency !== 'EGP').map((r) => `\u2068${fmtMoney(r.amount, r.currency, locale)}\u2069`).join(' · ')
}

function QueueStrip() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const summary = useQuery({ queryKey: ['admin', 'summary'], queryFn: adminApi.summary })
  const s = summary.data
  // eight tiles: two per row on phones, four on tablets and desktops; revenue spans two columns
  const grid = 'grid grid-cols-2 gap-3 sm:grid-cols-4'
  if (summary.isLoading) return <div className={grid}>{[1, 2, 3, 4, 5, 6, 7].map((i) => <Skeleton key={i} className={cn('h-[88px]', i === 7 && 'col-span-2')} />)}</div>
  if (!s) return summary.isError ? <ErrorBox onRetry={() => summary.refetch()} /> : null
  const tile = 'block h-full rounded-xl2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gold'
  const others = s.revenue.filter((r) => r.currency !== 'EGP')
  return (
    <StaggerGroup className={grid} stagger={0.05}>
      <Link to="/admin/orders?tab=REVIEW" className={tile}><Stat className="h-full" label={t('admin.dashboard.pendingPayments')} value={fmtNum(s.pendingPayments, locale)} icon={<Icon name="wallet" size={24} />} tone={s.pendingPayments > 0 ? 'danger' : 'gold'} /></Link>
      <Link to="/admin/dreams?status=IN_REVIEW" className={tile}><Stat className="h-full" label={t('admin.dashboard.inReview')} value={fmtNum(s.inReview, locale)} icon={<Icon name="moon" size={24} />} /></Link>
      <Link to="/admin/dreams?status=IN_PROGRESS" className={tile}><Stat className="h-full" label={t('admin.dashboard.inProgress')} value={fmtNum(s.inProgress, locale)} icon={<Icon name="sparkle" size={24} />} /></Link>
      <Link to="/admin/dreams?status=AWAITING_USER_REPLY" className={tile}><Stat className="h-full" label={t('admin.dashboard.awaiting')} value={fmtNum(s.awaitingReply, locale)} icon={<Icon name="chat" size={24} />} /></Link>
      <Link to="/admin/dreams?status=IN_REVIEW" className={tile}><Stat className="h-full" label={t('admin.dashboard.overdue')} value={fmtNum(s.overdue, locale)} icon={<Icon name="clock" size={24} />} tone={s.overdue ? 'danger' : undefined} /></Link>
      <Link to="/admin/dreams?status=INTERPRETED" className={tile}><Stat className="h-full" label={t('admin.dashboard.today')} value={fmtNum(s.interpretedToday, locale)} icon={<Icon name="check" size={24} />} /></Link>
      <Link to="/admin/orders?tab=DOMESTIC" className={cn(tile, 'col-span-2')}>
        <Stat className="h-full" tone="night" label={t('admin.dashboard.revenue')} icon={<Icon name="wallet" size={24} />} value={
          <div className="min-w-0">
            <div dir="ltr" className="text-start">{fmtMoney(s.revenueEgp, 'EGP', locale)}</div>
            {others.length > 0 && <div className="mt-1 font-sans text-[11px] font-light leading-relaxed text-pearl/60">{t('admin.dashboard.revenueRef', { list: currencyList(s.revenue, locale) })}</div>}
          </div>
        } />
      </Link>
    </StaggerGroup>
  )
}

function TopCountriesSection() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [period, setPeriod] = useState<AdminCountryDashboard['period']>('30d')
  const countries = useQuery({ queryKey: ['admin', 'countries', period, locale], queryFn: () => adminApi.countries(period), placeholderData: keepPreviousData })
  const d = countries.data
  return (
    <section aria-labelledby="countries-title">
      <SectionHeader id="countries-title" icon="globe" title={t('admin.dashboard.topTitle')} lead={t('admin.dashboard.topLead')}
        action={<Segmented value={period} onChange={setPeriod} items={(['7d', '30d', '1y', 'all'] as const).map((p) => ({ value: p, label: t(`admin.dashboard.period.${p}`) }))} />} />
      {countries.isLoading ? <div className="grid gap-4 lg:grid-cols-3">{[1, 2, 3].map((i) => <Skeleton key={i} className="h-72" />)}</div>
        : !d ? <ErrorBox onRetry={() => countries.refetch()} />
        : (
          <StaggerGroup className={cn('grid items-start gap-4 transition-opacity lg:grid-cols-3', countries.isPlaceholderData && 'opacity-60')} stagger={0.1}>
            <CountryList title={t('admin.dashboard.topVisits')} rows={d.topVisits} value={(r) => r.visits} format={(n) => fmtNum(n, locale)} icon="globe" />
            <CountryList title={t('admin.dashboard.topDreams')} rows={d.topDreams} value={(r) => r.dreams} format={(n) => fmtNum(n, locale)} icon="moon" />
            <CountryList title={t('admin.dashboard.topRevenue')} rows={d.topRevenue} value={(r) => r.revenueBase} format={(n) => fmtMoney(n, d.baseCurrency, locale)} icon="wallet"
              sub={(r) => (r.revenueByCurrency?.some((x) => x.currency !== d.baseCurrency) ? currencyList(r.revenueByCurrency, locale) : null)} />
          </StaggerGroup>
        )}
    </section>
  )
}

export function AdminDashboard() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  return (
    <PageEnter className="space-y-10">
      <div className="space-y-5">
        <div>
          <h1 className="font-display text-4xl">{t('admin.dashboard.title')}</h1>
          <p className="mt-1 text-sm font-light text-fg-muted">{fmtDay(toISODay(new Date()), locale, { weekday: 'long', day: 'numeric', month: 'long' })} · {t('admin.dashboard.lead')}</p>
        </div>
        <QueueStrip />
      </div>
      <InsightsSection />
      <TopCountriesSection />
      <ActivitySection />
      <TrafficSection />
    </PageEnter>
  )
}
