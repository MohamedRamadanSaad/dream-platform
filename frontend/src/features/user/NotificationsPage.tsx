import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { notificationsApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Empty, Skeleton } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { cn, timeAgo } from '@/lib/utils'
import type { NotificationType } from '@/api/types'

const icon: Record<NotificationType, Parameters<typeof Icon>[0]['name']> = { DREAM_SUBMITTED: 'moon', DREAM_RECEIVED: 'check', INTERPRETER_QUESTION: 'chat', USER_REPLIED: 'chat', INTERPRETATION_READY: 'star', PAYMENT_SUCCESS: 'wallet', PROMOTION: 'gift', YOUTUBE_VIDEO: 'youtube' }

export function NotificationsPage() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const locale = useAuthStore((s) => s.locale)
  const q = useQuery({ queryKey: ['notifications', 0], queryFn: () => notificationsApi.list(0, 50) })
  const readAll = useMutation({ mutationFn: notificationsApi.readAll, onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }) })
  const read = useMutation({ mutationFn: notificationsApi.read, onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }) })
  return (
    <PageEnter className="mx-auto max-w-2xl">
      <div className="mb-6 flex items-center justify-between"><h1 className="font-display text-4xl">{t('me.notifications.title')}</h1><Button variant="ghost" size="sm" onClick={() => readAll.mutate()}>{t('me.notifications.readAll')}</Button></div>
      {q.isLoading ? <div className="space-y-3">{[1, 2, 3].map((i) => <Skeleton key={i} className="h-20" />)}</div>
        : !q.data?.items.length ? <Empty text={t('me.notifications.empty')} />
        : <div className="space-y-3">
          {q.data.items.map((n) => (
            <Link key={n.id} to={n.link ?? '#'} onClick={() => !n.readAt && read.mutate(n.id)} className={cn('card card-hover flex gap-4 p-4', !n.readAt && 'border-gold/50 bg-gold/5')}>
              <span className={cn('mt-0.5', n.readAt ? 'text-fg-dim' : 'text-gold-deep')}><Icon name={icon[n.type]} size={20} active={!n.readAt} /></span>
              <div className="min-w-0 flex-1"><div className="flex items-center justify-between gap-2"><div className="font-medium">{n.title}</div><div className="text-xs text-fg-dim">{timeAgo(n.createdAt, locale)}</div></div><div className="text-sm font-light text-fg-muted">{n.body}</div></div>
            </Link>
          ))}
        </div>}
    </PageEnter>
  )
}
