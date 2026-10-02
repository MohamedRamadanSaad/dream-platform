import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Empty, ErrorBox, Skeleton, StatusBadge, Tabs } from '@/components/ui'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { cn, fmtDate, hoursLeft } from '@/lib/utils'
import type { DreamStatus } from '@/api/types'

type Tab = 'IN_REVIEW' | 'AWAITING_USER_REPLY' | 'INTERPRETED' | 'CANCELLED'

export function SlaChip({ deadline, overdue }: { deadline: string; overdue: boolean }) {
  const { t } = useTranslation()
  const h = hoursLeft(deadline)
  const tone = overdue || h < 0 ? 'bg-danger/10 text-danger' : h < 12 ? 'bg-warn/10 text-warn' : 'bg-success/10 text-success'
  // full words with Arabic dual/plural ("متأخرة 4 ساعات", "خلال يومين"), never "4س"
  const span = (x: number) => (x < 48 ? t('common.hourCount', { count: Math.max(1, Math.round(x)) }) : t('common.dayCount', { count: Math.round(x / 24) }))
  const label = h < 0 ? t('admin.queue.overdueBy', { x: span(-h) }) : t('admin.queue.within', { x: span(h) })
  return <span className={cn('chip font-medium', tone)}><span className="h-1.5 w-1.5 rounded-full bg-current" />{label}</span>
}

export function AdminQueuePage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [params, setParams] = useSearchParams()
  const tab = (params.get('status') as Tab) || 'IN_REVIEW'
  const q = useQuery({ queryKey: ['admin', 'dreams', tab], queryFn: () => adminApi.dreams(tab as DreamStatus, 0, 50) })
  return (
    <PageEnter>
      <h1 className="mb-6 font-display text-4xl">{t('admin.queue.title')}</h1>
      <Tabs value={tab} onChange={(v) => setParams({ status: v })} items={[
        { value: 'IN_REVIEW', label: t('me.status.IN_REVIEW') }, { value: 'AWAITING_USER_REPLY', label: t('me.status.AWAITING_USER_REPLY') },
        { value: 'INTERPRETED', label: t('me.status.INTERPRETED') }, { value: 'CANCELLED', label: t('me.status.CANCELLED') },
      ]} />
      <div className="mt-6">
        {q.isLoading ? <div className="space-y-3">{[1, 2, 3].map((i) => <Skeleton key={i} className="h-24" />)}</div>
          : q.isError ? <ErrorBox onRetry={() => q.refetch()} />
          : !q.data?.items.length ? <Empty text={t('common.none')} />
          : <StaggerGroup className="space-y-3" stagger={0.05}>
            {q.data.items.map((r) => (
              <Link key={r.id} to={`/admin/dreams/${r.id}`} className={cn('card card-hover flex flex-col gap-3 p-5 md:flex-row md:items-center', r.overdue && 'border-danger/40')}>
                <div className="min-w-0 flex-1">
                  <div className="mb-1 flex flex-wrap items-center gap-2 text-sm"><span className="font-medium">{r.userName}</span><span className="text-fg-dim">· {r.countryCode} · {t(r.gender === 'FEMALE' ? 'auth.female' : 'auth.male')}</span><StatusBadge status={r.status} /></div>
                  <p className="line-clamp-2 text-sm font-light text-fg-muted">{r.excerpt}</p>
                </div>
                <div className="flex items-center gap-3 text-xs text-fg-dim md:flex-col md:items-end">
                  <span>{t('admin.queue.submitted')} {fmtDate(r.submittedAt, locale, true)}</span>
                  {r.status !== 'INTERPRETED' && r.status !== 'CANCELLED' && <SlaChip deadline={r.slaDeadline} overdue={r.overdue} />}
                </div>
              </Link>
            ))}
          </StaggerGroup>}
      </div>
    </PageEnter>
  )
}
