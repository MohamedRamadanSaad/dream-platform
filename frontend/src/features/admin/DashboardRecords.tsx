// "Score records": an all-time high-score board under a small night sky.
import { useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import { useAuthStore } from '@/app/auth-store'
import { Icon, type IconName } from '@/components/icons/Icon'
import { CountUp } from '@/components/motion'
import { fmtDay, fmtMonth, fmtNum } from '@/lib/utils'
import type { TrafficReport } from '@/api/types'

// [x %, y %, size px] — fixed so the sky does not jump between renders
const STARS: [number, number, number][] = [
  [4, 14, 2], [11, 72, 1.5], [19, 34, 1], [27, 88, 2], [36, 10, 1.5], [44, 58, 1], [53, 22, 2], [61, 80, 1.5],
  [69, 40, 1], [76, 12, 2], [84, 66, 1.5], [92, 28, 1], [97, 84, 2], [48, 92, 1], [15, 50, 1], [88, 48, 1],
]

function RecordTile({ icon, label, value, sub }: { icon: IconName; label: string; value: number | null; sub: string | null }) {
  const locale = useAuthStore((s) => s.locale)
  const [hover, setHover] = useState(false)
  return (
    <div className="min-w-0 rounded-2xl border border-navy bg-navy/40 p-5 transition-colors duration-500 hover:border-gold/60" onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}>
      <div className="flex items-center justify-between gap-2 text-xs text-pearl/70"><span>{label}</span><span className="text-gold"><Icon name={icon} size={18} active={hover} /></span></div>
      <div className="mt-3 font-display text-3xl text-gold-soft md:text-4xl">{value !== null ? <CountUp to={value} format={(n) => fmtNum(n, locale)} /> : '—'}</div>
      {sub && <div className="mt-1.5 text-xs text-pearl/70">{sub}</div>}
    </div>
  )
}

function RankBadge({ rank }: { rank: number }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  if (rank === 1) {
    return (
      <div className="shimmer relative flex items-center gap-2 overflow-hidden rounded-full border border-gold/60 bg-gold/15 px-4 py-2 text-sm font-medium text-gold-soft">
        <Icon name="sparkle" size={16} active />{t('admin.records.rankBest')}
      </div>
    )
  }
  return (
    <div className="flex items-center gap-2 rounded-full border border-navy bg-navy/60 px-4 py-2 text-sm text-pearl/80">
      <Icon name="star" size={15} className="text-gold" />
      <span><Trans i18nKey="admin.records.rank" values={{ n: fmtNum(rank, locale) }} components={{ b: <strong className="font-display text-base text-gold-soft" /> }} /></span>
    </div>
  )
}

export function RecordsBoard({ records }: { records: TrafficReport['records'] }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  return (
    <section aria-labelledby="records-title" className="relative overflow-hidden rounded-xl2 border border-navy bg-night p-6 text-pearl shadow-calm md:p-8">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0">
        {STARS.map(([x, y, s], i) => (
          <span key={i} className="absolute rounded-full bg-gold-soft" style={{ left: `${x}%`, top: `${y}%`, width: s, height: s, opacity: 0.7, animation: `twinkle ${2.6 + (i % 5) * 0.7}s ease-in-out ${(i * 0.37).toFixed(2)}s infinite` }} />
        ))}
        <div className="absolute -top-24 -end-16 h-64 w-64 rounded-full bg-gold/10 blur-3xl" />
      </div>
      <div className="relative">
        <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
          <div className="flex min-w-0 items-start gap-3">
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold"><Icon name="trophy" size={22} /></span>
            <div className="min-w-0">
              <h2 id="records-title" className="font-display text-2xl text-gold-soft">{t('admin.records.title')}</h2>
              <p className="mt-1 text-sm font-light text-pearl/70">{records.totalViews ? t('admin.records.lead') : t('admin.records.empty')}</p>
            </div>
          </div>
          {records.thisMonthRank !== null && records.totalViews > 0 && <RankBadge rank={records.thisMonthRank} />}
        </header>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <RecordTile icon="trophy" label={t('admin.records.bestDay')} value={records.bestDay?.views ?? null}
            sub={records.bestDay ? fmtDay(records.bestDay.date, locale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }) : null} />
          <RecordTile icon="calendar" label={t('admin.records.bestMonth')} value={records.bestMonth?.views ?? null}
            sub={records.bestMonth ? fmtMonth(records.bestMonth.month, locale) : null} />
          <RecordTile icon="eye" label={t('admin.records.totalViews')} value={records.totalViews} sub={t('admin.records.sinceStart')} />
          <RecordTile icon="user" label={t('admin.records.totalVisitors')} value={records.totalVisitors} sub={t('admin.records.sinceStart')} />
        </div>
      </div>
    </section>
  )
}
