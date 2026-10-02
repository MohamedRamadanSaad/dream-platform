// "Recommendations for you" and "Your activity" — both from GET /admin/analytics/insights (server-localised).
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { ErrorBox, Skeleton } from '@/components/ui'
import { Icon, type IconName } from '@/components/icons/Icon'
import { StaggerGroup } from '@/components/motion'
import { arrowNext, cn, fmtHour, fmtNum, fmtPct } from '@/lib/utils'
import type { Insight, InsightKind, MyActivity } from '@/api/types'
import { LegendKey, Panel, SectionHeader } from './DashboardParts'

/** Keyed by locale: a language switch refetches the server-written texts. */
export function useInsights() {
  const locale = useAuthStore((s) => s.locale)
  return useQuery({ queryKey: ['admin', 'insights', locale], queryFn: adminApi.insights, staleTime: 60_000 })
}

const KIND: Record<InsightKind, { icon: IconName; rail: string; badge: string }> = {
  WARNING: { icon: 'alert', rail: 'bg-danger', badge: 'bg-danger/10 text-bad-ink' },
  TIP: { icon: 'bulb', rail: 'bg-gold', badge: 'bg-gold/15 text-gold-deep dark:text-gold' },
  SUCCESS: { icon: 'check', rail: 'bg-success', badge: 'bg-success/10 text-ok-ink' },
  INFO: { icon: 'info', rail: 'bg-info', badge: 'bg-info/10 text-info-ink' },
}

// specific wording for the routes the server links to; anything else gets a plain "Open"
const LINK_LABELS: [prefix: string, key: string][] = [
  ['/admin/queue', 'queue'], ['/admin/dreams', 'queue'], ['/admin/wait-time', 'waitTime'],
  ['/admin/testimonials', 'testimonials'], ['/admin/pricing', 'pricing'], ['/admin/users', 'users'],
]

function InsightCard({ item }: { item: Insight }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [hover, setHover] = useState(false)
  const k = KIND[item.kind] ?? KIND.INFO
  const linkKey = item.link ? LINK_LABELS.find(([p]) => item.link!.startsWith(p))?.[1] : undefined
  return (
    <article className="card relative flex h-full gap-4 overflow-hidden p-5 ps-6" onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}>
      <span aria-hidden="true" className={cn('absolute inset-y-0 start-0 w-1', k.rail)} />
      <span className={cn('flex h-10 w-10 shrink-0 items-center justify-center rounded-full', k.badge)}><Icon name={k.icon} size={20} active={hover} /></span>
      <div className="min-w-0 flex-1">
        <div className="mb-1 text-[11px] font-medium tracking-wide text-fg-muted">{t(`admin.insights.kind.${item.kind}`)}</div>
        <h3 className="font-medium leading-snug">{item.title}</h3>
        <p className="mt-1.5 text-sm font-light leading-relaxed text-fg-muted">{item.body}</p>
        {item.link && (
          <Link to={item.link} className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-gold-deep hover:underline dark:text-gold">
            {t(linkKey ? `admin.insights.links.${linkKey}` : 'admin.insights.open')} <span aria-hidden="true">{arrowNext(locale)}</span>
          </Link>
        )}
      </div>
    </article>
  )
}

export function InsightsSection() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const q = useInsights()
  const [all, setAll] = useState(false)
  const items = q.data?.items ?? []
  const shown = all ? items : items.slice(0, 4)
  return (
    <section aria-labelledby="insights-title">
      <SectionHeader id="insights-title" icon="sparkle" title={t('admin.insights.title')} lead={t('admin.insights.lead')} />
      {q.isLoading ? (
        <div className="grid gap-4 md:grid-cols-2">{[1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-36" />)}</div>
      ) : q.isError ? (
        <ErrorBox onRetry={() => q.refetch()} />
      ) : !items.length ? (
        <div className="card flex items-center gap-4 p-6 text-fg-muted"><span className="text-gold-deep dark:text-gold"><Icon name="moon" size={26} /></span>{t('admin.insights.empty')}</div>
      ) : (
        <>
          <StaggerGroup className="grid gap-4 md:grid-cols-2" stagger={0.07}>
            {shown.map((it) => <InsightCard key={it.id} item={it} />)}
          </StaggerGroup>
          {items.length > 4 && (
            <div className="mt-3 flex justify-center">
              <button type="button" onClick={() => setAll((v) => !v)} aria-expanded={all} className="rounded-full px-4 py-2 text-sm text-gold-deep hover:bg-gold/10 dark:text-gold">
                {all ? t('admin.insights.showLess') : t('admin.insights.showMore', { n: fmtNum(items.length - 4, locale) })}
              </button>
            </div>
          )}
        </>
      )}
    </section>
  )
}

/** Circular gauge for a percentage. */
function Ring({ value, tone }: { value: number; tone: string }) {
  const r = 26, c = 2 * Math.PI * r
  const v = Math.max(0, Math.min(100, value))
  return (
    <svg width="64" height="64" viewBox="0 0 64 64" aria-hidden="true" className="shrink-0 -rotate-90 rtl:-scale-y-100">
      <circle cx="32" cy="32" r={r} fill="none" stroke="var(--surface-2)" strokeWidth="6" />
      <circle cx="32" cy="32" r={r} fill="none" stroke={tone} strokeWidth="6" strokeLinecap="round" strokeDasharray={`${(v / 100) * c} ${c}`} className="transition-[stroke-dasharray] duration-1000 ease-out" />
    </svg>
  )
}

function Meter({ value, max, tone }: { value: number; max: number; tone: string }) {
  return (
    <div className="h-2 overflow-hidden rounded-full bg-surface-2" role="presentation">
      <div className="h-full rounded-full transition-[width] duration-1000 ease-out" style={{ width: `${max > 0 ? Math.max(3, Math.min(100, (value / max) * 100)) : 0}%`, background: tone }} />
    </div>
  )
}

function Tile({ icon, label, children }: { icon: IconName; label: string; children: ReactNode }) {
  return (
    <div className="card flex min-w-0 flex-col p-5">
      <div className="mb-3 flex items-center justify-between gap-2 text-xs text-fg-muted"><span>{label}</span><span className="text-gold-deep dark:text-gold"><Icon name={icon} size={18} /></span></div>
      {children}
    </div>
  )
}

const OK = 'var(--ok-ink)', GOLD = 'var(--viz-line)', BAD = 'var(--bad-ink)'

/** 24 cells: gold = the hours she interprets most, blue ring = the hours visitors are most active. */
function HoursStrip({ mine, users }: { mine: number[]; users: number[] }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const list = (hs: number[]) => new Intl.ListFormat(locale === 'ar' ? 'ar' : 'en', { type: 'conjunction' }).format([...hs].sort((a, b) => a - b).map((h) => fmtHour(h, locale)))
  return (
    <div>
      <div className="flex gap-[3px]" role="list" aria-label={t('admin.activity.hoursTitle')}>
        {Array.from({ length: 24 }, (_, h) => {
          const m = mine.includes(h), u = users.includes(h)
          const what = [m && t('admin.activity.mine'), u && t('admin.activity.users')].filter(Boolean).join(' · ')
          return (
            <div key={h} role="listitem" title={`${fmtHour(h, locale)}${what ? ` — ${what}` : ''}`} aria-label={`${fmtHour(h, locale)}${what ? `: ${what}` : ''}`}
              className={cn('h-9 min-w-0 flex-1 rounded-[5px] transition-colors', m ? 'bg-gold' : 'bg-surface-2')}
              style={u ? { boxShadow: 'inset 0 0 0 2px var(--viz-2)' } : undefined} />
          )
        })}
      </div>
      <div aria-hidden="true" className="mt-1.5 flex gap-[3px] text-[10px] text-fg-dim">
        {Array.from({ length: 24 }, (_, h) => <span key={h} className="relative min-w-0 flex-1">{h % 6 === 0 && <span className="absolute start-0 top-0 whitespace-nowrap">{fmtHour(h, locale)}</span>}</span>)}
      </div>
      <div className="mt-6 flex flex-wrap gap-x-5 gap-y-2">
        <LegendKey swatch color="var(--gold)" label={t('admin.activity.mine')} />
        <LegendKey ring color="var(--viz-2)" label={t('admin.activity.users')} />
      </div>
      <div className="mt-3 space-y-1 text-sm text-fg-muted">
        {mine.length > 0 && <p>{t('admin.activity.mineAt', { hours: list(mine) })}</p>}
        {users.length > 0 && <p>{t('admin.activity.usersAt', { hours: list(users) })}</p>}
      </div>
    </div>
  )
}

function ActivityTiles({ a }: { a: MyActivity }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const n = (v: number) => fmtNum(v, locale)
  const replyTone = a.avgResponseHours <= a.slaHours / 2 ? OK : a.avgResponseHours <= a.slaHours ? GOLD : BAD
  const replyNote = a.avgResponseHours <= a.slaHours / 2 ? 'replyFast' : a.avgResponseHours <= a.slaHours ? 'replyOk' : 'replySlow'
  const onTimeTone = a.onTimeRate >= 90 ? OK : a.onTimeRate >= 70 ? GOLD : BAD
  const ahead = a.interpretedThisMonth >= a.interpretedLastMonth && a.interpretedLastMonth > 0
  return (
    <StaggerGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4" stagger={0.07}>
      <Tile icon="moon" label={t('admin.activity.interpreted')}>
        <div className="font-display text-4xl">{n(a.interpretedThisMonth)}</div>
        <div className="mt-3"><Meter value={a.interpretedThisMonth} max={Math.max(1, a.interpretedLastMonth)} tone={ahead ? OK : GOLD} /></div>
        <div className="mt-2 text-xs text-fg-muted">{ahead ? t('admin.activity.moreThanLast', { n: n(a.interpretedLastMonth) }) : t('admin.activity.lastMonth', { n: n(a.interpretedLastMonth) })}</div>
      </Tile>
      <Tile icon="clock" label={t('admin.activity.avgReply')}>
        <div className="font-display text-4xl">{t('admin.activity.hours', { count: Math.round(a.avgResponseHours), n: n(Math.round(a.avgResponseHours)) })}</div>
        <div className="mt-3"><Meter value={a.avgResponseHours} max={a.slaHours} tone={replyTone} /></div>
        <div className="mt-2 text-xs text-fg-muted">{t(`admin.activity.${replyNote}`)} · {t('admin.activity.promised', { v: t('admin.activity.hours', { count: a.slaHours, n: n(a.slaHours) }) })}</div>
      </Tile>
      <Tile icon="check" label={t('admin.activity.onTime')}>
        <div className="flex items-center gap-4">
          <Ring value={a.onTimeRate} tone={onTimeTone} />
          <div className="font-display text-4xl">{fmtPct(a.onTimeRate, locale, 0)}</div>
        </div>
        <div className="mt-2 text-xs text-fg-muted">{t('admin.activity.onTimeHint')}</div>
      </Tile>
      <Tile icon="flame" label={t('admin.activity.streak')}>
        {a.streakDays > 0 ? (
          <>
            <div className="flex items-baseline gap-2"><span className="font-display text-4xl">{n(a.streakDays)}</span><span className="text-sm text-fg-muted">{t('admin.activity.streakDays', { count: a.streakDays })}</span></div>
            <div className="mt-3 flex gap-1" aria-hidden="true">
              {Array.from({ length: Math.min(a.streakDays, 7) }, (_, i) => <span key={i} className="h-2 flex-1 rounded-full bg-gold" style={{ opacity: 0.45 + (0.55 * (i + 1)) / Math.min(a.streakDays, 7) }} />)}
            </div>
            <div className="mt-2 text-xs text-fg-muted">{t('admin.activity.streakHint')}</div>
          </>
        ) : (
          <p className="text-sm text-fg-muted">{t('admin.activity.streakNone')}</p>
        )}
      </Tile>
    </StaggerGroup>
  )
}

export function ActivitySection() {
  const { t } = useTranslation()
  const q = useInsights()
  if (q.isError) return null // the recommendations section above already shows the error and a retry
  const a = q.data?.myActivity
  return (
    <section aria-labelledby="activity-title">
      <SectionHeader id="activity-title" icon="heart" title={t('admin.activity.title')} lead={t('admin.activity.lead')} />
      {!a ? (
        <div className="space-y-4"><div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{[1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-40" />)}</div><Skeleton className="h-44" /></div>
      ) : (
        <div className="space-y-4">
          <ActivityTiles a={a} />
          <Panel title={t('admin.activity.hoursTitle')}>
            <p className="-mt-2 mb-4 text-sm font-light text-fg-muted">{t('admin.activity.hoursLead')}</p>
            <HoursStrip mine={a.myBusiestHours} users={a.usersPeakHours} />
          </Panel>
        </div>
      )}
    </section>
  )
}
