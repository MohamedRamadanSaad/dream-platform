import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Empty, Skeleton, Stars, Tabs } from '@/components/ui'
import { PageEnter } from '@/components/motion'
import { arrowNext, fmtDate } from '@/lib/utils'

export function TestimonialsAdminPage() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const locale = useAuthStore((s) => s.locale)
  const [tab, setTab] = useState<'pending' | 'approved'>('pending')
  const q = useQuery({ queryKey: ['admin', 'testimonials', tab], queryFn: () => adminApi.testimonials(tab === 'approved') })
  const set = useMutation({ mutationFn: ({ id, approved }: { id: string; approved: boolean }) => adminApi.setTestimonial(id, approved), onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin', 'testimonials'] }); qc.invalidateQueries({ queryKey: ['public'] }) } })
  return (
    <PageEnter>
      <h1 className="mb-6 font-display text-4xl">{t('admin.testimonials.title')}</h1>
      <Tabs value={tab} onChange={setTab} items={[{ value: 'pending', label: t('admin.testimonials.pending') }, { value: 'approved', label: t('admin.testimonials.approved') }]} />
      <div className="mt-6 space-y-3">
        {q.isLoading ? <Skeleton className="h-40" /> : !q.data?.items.length ? <Empty text={t('common.none')} /> : q.data.items.map((x) => (
          <div key={x.id} className="card flex flex-col gap-3 p-5 md:flex-row md:items-center">
            <div className="flex-1"><div className="mb-1 flex items-center gap-3"><Stars value={x.rating} size={14} /><span className="text-sm">{x.userName}</span><span className="text-xs text-fg-dim">{fmtDate(x.createdAt, locale)}</span></div><p className="text-sm font-light">{x.comment}</p><Link to={`/admin/dreams/${x.dreamId}`} className="text-xs text-gold-ink">{t('me.detail.dream')} {arrowNext(locale)}</Link></div>
            <Button size="sm" variant={x.approved ? 'ghost' : 'gold'} loading={set.isPending} onClick={() => set.mutate({ id: x.id, approved: !x.approved })}>{x.approved ? t('admin.testimonials.hide') : t('admin.testimonials.approve')}</Button>
          </div>
        ))}
      </div>
    </PageEnter>
  )
}
