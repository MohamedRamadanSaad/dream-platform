import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Segmented, Skeleton, Stat } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { fmtMoney, fmtNum } from '@/lib/utils'
import type { AdminCountryDashboard, CountryStat } from '@/api/types'

const FLAG = (cc: string) => cc.toUpperCase().replace(/./g, (c) => String.fromCodePoint(127397 + c.charCodeAt(0)))

function CountryList({ title, rows, value, format, icon }: { title: string; rows: CountryStat[]; value: (r: CountryStat) => number; format: (n: number) => string; icon: Parameters<typeof Icon>[0]['name'] }) {
  const max = Math.max(...rows.map(value), 1)
  const [h, setH] = useState(false)
  return (
    <div className="card p-5" onMouseEnter={() => setH(true)} onMouseLeave={() => setH(false)}>
      <div className="mb-4 flex items-center gap-2 text-fg"><span className="text-gold-ink"><Icon name={icon} size={18} active={h} /></span><h3 className="font-medium">{title}</h3></div>
      <ol className="space-y-2.5">
        {rows.map((r, i) => {
          const v = value(r)
          return (
            <li key={r.countryCode}>
              <Link to={`/admin/users?country=${r.countryCode}`} className="block">
                <div className="mb-1 flex items-center justify-between text-sm"><span><span className="text-fg-dim me-2">{i + 1}.</span>{FLAG(r.countryCode)} {r.countryName}</span><span className="text-fg-muted" dir="ltr">{format(v)}</span></div>
                <div className="h-1 overflow-hidden rounded-full bg-surface-2"><div className="h-full bg-gold transition-all duration-700" style={{ width: `${(v / max) * 100}%` }} /></div>
              </Link>
            </li>
          )
        })}
      </ol>
    </div>
  )
}

export function AdminDashboard() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [period, setPeriod] = useState<AdminCountryDashboard['period']>('30d')
  const summary = useQuery({ queryKey: ['admin', 'summary'], queryFn: adminApi.summary })
  const countries = useQuery({ queryKey: ['admin', 'countries', period], queryFn: () => adminApi.countries(period) })
  const s = summary.data
  return (
    <PageEnter className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="font-display text-4xl">{t('admin.dashboard.title')}</h1>
        <Segmented value={period} onChange={setPeriod} items={(['7d', '30d', '1y', 'all'] as const).map((p) => ({ value: p, label: t(`admin.dashboard.period.${p}`) }))} />
      </div>
      {summary.isLoading ? <div className="grid gap-4 md:grid-cols-3">{[1, 2, 3].map((i) => <Skeleton key={i} className="h-24" />)}</div> : s && (
        <StaggerGroup className="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3" stagger={0.06}>
          <Link to="/admin/dreams?status=IN_REVIEW"><Stat label={t('admin.dashboard.inReview')} value={fmtNum(s.inReview, locale)} icon={<Icon name="moon" size={28} />} tone="gold" /></Link>
          <Link to="/admin/dreams?status=AWAITING_USER_REPLY"><Stat label={t('admin.dashboard.awaiting')} value={fmtNum(s.awaitingReply, locale)} icon={<Icon name="chat" size={28} />} /></Link>
          <Link to="/admin/dreams?status=IN_REVIEW"><Stat label={t('admin.dashboard.overdue')} value={fmtNum(s.overdue, locale)} icon={<Icon name="clock" size={28} />} tone={s.overdue ? 'danger' : undefined} /></Link>
          <Stat label={t('admin.dashboard.today')} value={fmtNum(s.interpretedToday, locale)} icon={<Icon name="check" size={28} />} />
          <Stat label={t('admin.dashboard.avg')} value={fmtNum(s.avgResponseHours, locale)} icon={<Icon name="clock" size={28} />} />
          <Stat tone="night" label={t('admin.dashboard.revenue')} value={<div className="flex flex-col gap-0.5 text-xl leading-snug">{s.revenue.map((r) => <span key={r.currency}>{fmtMoney(r.amount, r.currency, locale)}</span>)}</div>} icon={<Icon name="wallet" size={28} />} />
        </StaggerGroup>
      )}
      {countries.isLoading ? <div className="grid gap-4 lg:grid-cols-3">{[1, 2, 3].map((i) => <Skeleton key={i} className="h-72" />)}</div> : countries.data && (
        <StaggerGroup className="grid gap-4 lg:grid-cols-3" stagger={0.1}>
          <CountryList title={t('admin.dashboard.topVisits')} rows={countries.data.topVisits} value={(r) => r.visits} format={(n) => fmtNum(n, locale)} icon="globe" />
          <CountryList title={t('admin.dashboard.topDreams')} rows={countries.data.topDreams} value={(r) => r.dreams} format={(n) => fmtNum(n, locale)} icon="moon" />
          <CountryList title={t('admin.dashboard.topRevenue')} rows={countries.data.topRevenue} value={(r) => r.revenueBase} format={(n) => fmtMoney(n, countries.data!.baseCurrency, locale)} icon="wallet" />
        </StaggerGroup>
      )}
    </PageEnter>
  )
}
