import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '@/app/auth-store'
import { Skeleton } from '@/components/ui'
import { Icon, type IconName } from '@/components/icons/Icon'
import { CountUp, StaggerGroup } from '@/components/motion'
import { arrowNext, cn, fmtDate, fmtMoney, fmtNum, timeAgo } from '@/lib/utils'
import type { DashboardSummary } from '@/api/types'

// The user's dashboard on top of "My dreams": three status tiles that open the matching tab, then the account
// tiles (balance, credits used, last package, last visit) that open the page behind each number.

export type OverviewTab = 'DRAFT' | 'IN_REVIEW' | 'INTERPRETED'

function Tile({ icon, label, children, sub, cta, to, onClick, active, tone, bottom }: {
  icon: IconName; label: string; children: ReactNode; sub?: ReactNode; cta?: string
  to?: string; onClick?: () => void; active?: boolean; tone?: 'night'
  /** keep the value on the bottom line, so a label that wraps on a phone does not push it out of line */
  bottom?: boolean
}) {
  const locale = useAuthStore((s) => s.locale)
  const [hover, setHover] = useState(false)
  const night = tone === 'night'
  const body = (
    <>
      <div className={cn('flex items-start justify-between gap-2 text-xs', night ? 'text-pearl/70' : 'text-fg-muted')}>
        <span>{label}</span>
        <span className={cn('shrink-0', night ? 'text-gold' : 'text-gold-ink')}><Icon name={icon} size={18} active={hover || active} /></span>
      </div>
      <div className={cn('min-w-0', bottom ? 'mt-auto pt-2' : 'mt-2')}>{children}</div>
      {(sub || cta) && (
        <div className={cn('mt-auto flex flex-wrap items-center justify-between gap-x-2 gap-y-1 pt-3 text-xs', night ? 'text-pearl/70' : 'text-fg-dim')}>
          {sub && <span className="min-w-0">{sub}</span>}
          {cta && <span className={cn('ms-auto shrink-0 font-medium', night ? 'text-gold' : 'text-gold-ink')}>{cta} {arrowNext(locale)}</span>}
        </div>
      )}
    </>
  )
  const cls = cn(
    'card card-hover flex h-full min-w-0 flex-col p-4 text-start transition-colors duration-300 md:p-5',
    night && 'border-navy bg-night text-pearl',
    active && 'border-gold bg-gold/5',
  )
  const events = { onMouseEnter: () => setHover(true), onMouseLeave: () => setHover(false) }
  if (to) return <Link to={to} className={cls} {...events}>{body}</Link>
  return <button type="button" onClick={onClick} aria-pressed={active} className={cls} {...events}>{body}</button>
}

const Big = ({ value, night }: { value: number; night?: boolean }) => {
  const locale = useAuthStore((s) => s.locale)
  return <div className={cn('font-display text-3xl leading-tight md:text-4xl', night && 'text-gold-soft')}><CountUp to={value} format={(n) => fmtNum(n, locale)} /></div>
}

export function UserOverview({ data, loading, tab, onTab }: {
  data: DashboardSummary | undefined; loading: boolean; tab: string; onTab: (t: OverviewTab) => void
}) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)

  if (loading || !data) {
    return (
      <div className="mb-8 space-y-3" aria-hidden="true">
        <div className="grid grid-cols-3 gap-3">{[1, 2, 3].map((i) => <Skeleton key={i} className="h-24 md:h-28" />)}</div>
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">{[1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-32" />)}</div>
      </div>
    )
  }

  const statuses: { tab: OverviewTab; icon: IconName; label: string; value: number }[] = [
    { tab: 'DRAFT', icon: 'scroll', label: t('me.overview.drafts'), value: data.drafts },
    { tab: 'IN_REVIEW', icon: 'clock', label: t('me.overview.inReview'), value: data.inReview },
    { tab: 'INTERPRETED', icon: 'check', label: t('me.overview.interpreted'), value: data.interpreted },
  ]
  const pack = data.lastPackage

  return (
    <section aria-labelledby="overview-title" className="mb-8">
      <h2 id="overview-title" className="sr-only">{t('me.overview.title')}</h2>
      <StaggerGroup className="grid grid-cols-3 gap-3" stagger={0.05}>
        {statuses.map((s) => (
          <Tile key={s.tab} icon={s.icon} label={s.label} active={tab === s.tab} onClick={() => onTab(s.tab)} bottom>
            <Big value={s.value} />
          </Tile>
        ))}
      </StaggerGroup>

      <StaggerGroup className="mt-3 grid grid-cols-2 gap-3 lg:grid-cols-4" stagger={0.05}>
        <Tile icon="star" tone="night" label={t('me.overview.balance')} to="/me/packages" cta={t('me.overview.buyMore')}>
          <Big value={data.credits} night />
        </Tile>

        <Tile icon="moon" label={t('me.overview.used')} to="/me/payments#ledger" cta={t('me.overview.usedLink')}>
          <Big value={data.usedCredits} />
        </Tile>

        <Tile icon="gift" label={t('me.overview.lastPackage')} to={pack ? '/me/payments#history' : '/me/packages'}
          sub={pack ? fmtDate(pack.paidAt, locale) : undefined}
          cta={pack ? t('me.overview.paymentsLink') : t('me.overview.choosePackage')}>
          {pack ? (
            <>
              <div className="line-clamp-2 font-medium leading-snug text-fg">{pack.name}</div>
              <div className="mt-1 text-xs text-fg-muted">{t('me.overview.packageCredits', { count: pack.credits, n: fmtNum(pack.credits, locale) })} · <span dir="ltr">{fmtMoney(pack.amount, pack.currency, locale)}</span></div>
            </>
          ) : <div className="text-sm text-fg-muted">{t('me.overview.noPackage')}</div>}
        </Tile>

        <Tile icon="calendar" label={t('me.overview.lastVisit')} to="/me/notifications"
          sub={data.unreadNotifications > 0 ? t('me.overview.unread', { count: data.unreadNotifications, n: fmtNum(data.unreadNotifications, locale) }) : t('me.overview.noUnread')}
          cta={t('me.overview.notificationsLink')}>
          {data.lastVisitAt ? (
            <>
              <div className="font-medium leading-snug text-fg">{timeAgo(data.lastVisitAt, locale)}</div>
              <div className="mt-1 text-xs text-fg-muted">{fmtDate(data.lastVisitAt, locale, true)}</div>
            </>
          ) : <div className="font-medium leading-snug text-fg">{t('me.overview.firstVisit')}</div>}
        </Tile>
      </StaggerGroup>
    </section>
  )
}
