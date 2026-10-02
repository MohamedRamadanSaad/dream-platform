import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { ErrorBox, Segmented, Skeleton, Stat } from '@/components/ui'
import { Icon, type IconName } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { cn, flagEmoji, fmtDay, fmtMoney, fmtNum, toISODay } from '@/lib/utils'
import type { AdminCountryDashboard, CountryStat } from '@/api/types'
import { SectionHeader } from './DashboardParts'
import { ActivitySection, InsightsSection } from './DashboardInsights'
import { TrafficSection } from './DashboardTraffic'

// Interpreter dashboard: the queue at a glance → recommendations → top countries → her activity →
// visits scoreboard → all-time score records.

function CountryList({ title, rows, value, format, icon }: { title: string; rows: CountryStat[]; value: (r: CountryStat) => number; format: (n: number) => string; icon: IconName }) {
  const max = Math.max(...rows.map(value), 1)
  const [h, setH] = useState(false)
  return (
    <div className="card p-5" onMouseEnter={() => setH(true)} onMouseLeave={() => setH(false)}>
      <div className="mb-4 flex items-center gap-2 text-fg"><span className="text-gold-deep"><Icon name={icon} size={18} active={h} /></span><h3 className="font-medium">{title}</h3></div>
      <ol className="space-y-2.5">
        {rows.map((r, i) => {
          const v = value(r)
          return (
            <li key={r.countryCode}>
              <Link to={`/admin/users?country=${r.countryCode}`} className="block">
                <div className="mb-1 flex items-center justify-between text-sm"><span><span className="text-fg-dim me-2">{i + 1}.</span>{flagEmoji(r.countryCode)} {r.countryName}</span><span className="text-fg-muted" dir="ltr">{format(v)}</span></div>
                <div className="h-1 overflow-hidden rounded-full bg-surface-2"><div className="h-full bg-gold transition-all duration-700" style={{ width: `${(v / max) * 100}%` }} /></div>
              </Link>
            </li>
          )
        })}
      </ol>
    </div>
  )
}

function QueueStrip() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const summary = useQuery({ queryKey: ['admin', 'summary'], queryFn: adminApi.summary })
  const s = summary.data
  // revenue (up to three currencies) takes a full row on small screens and two columns on wide ones
  const grid = 'grid grid-cols-2 gap-3 sm:grid-cols-4 xl:grid-cols-6'
  if (summary.isLoading) return <div className={grid}>{[1, 2, 3, 4, 5].map((i) => <Skeleton key={i} className={cn('h-[88px]', i === 5 && 'col-span-2 sm:col-span-4 xl:col-span-2')} />)}</div>
  if (!s) return summary.isError ? <ErrorBox onRetry={() => summary.refetch()} /> : null
  return (
    <StaggerGroup className={grid} stagger={0.05}>
      <Link to="/admin/dreams?status=IN_REVIEW"><Stat label={t('admin.dashboard.inReview')} value={fmtNum(s.inReview, locale)} icon={<Icon name="moon" size={24} />} tone="gold" /></Link>
      <Link to="/admin/dreams?status=AWAITING_USER_REPLY"><Stat label={t('admin.dashboard.awaiting')} value={fmtNum(s.awaitingReply, locale)} icon={<Icon name="chat" size={24} />} /></Link>
      <Link to="/admin/dreams?status=IN_REVIEW"><Stat label={t('admin.dashboard.overdue')} value={fmtNum(s.overdue, locale)} icon={<Icon name="clock" size={24} />} tone={s.overdue ? 'danger' : undefined} /></Link>
      <Stat label={t('admin.dashboard.today')} value={fmtNum(s.interpretedToday, locale)} icon={<Icon name="check" size={24} />} />
      <div className="col-span-2 sm:col-span-4 xl:col-span-2">
        <Stat tone="night" label={t('admin.dashboard.revenue')} value={<div className="flex flex-wrap gap-x-3 text-lg">{s.revenue.map((r) => <span key={r.currency}>{fmtMoney(r.amount, r.currency, locale)}</span>)}</div>} icon={<Icon name="wallet" size={24} />} />
      </div>
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
          <StaggerGroup className={cn('grid gap-4 transition-opacity lg:grid-cols-3', countries.isPlaceholderData && 'opacity-60')} stagger={0.1}>
            <CountryList title={t('admin.dashboard.topVisits')} rows={d.topVisits} value={(r) => r.visits} format={(n) => fmtNum(n, locale)} icon="globe" />
            <CountryList title={t('admin.dashboard.topDreams')} rows={d.topDreams} value={(r) => r.dreams} format={(n) => fmtNum(n, locale)} icon="moon" />
            <CountryList title={t('admin.dashboard.topRevenue')} rows={d.topRevenue} value={(r) => r.revenueBase} format={(n) => fmtMoney(n, d.baseCurrency, locale)} icon="wallet" />
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
