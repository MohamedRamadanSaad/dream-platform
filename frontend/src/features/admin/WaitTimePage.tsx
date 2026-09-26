import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { Button, Input, Label, Skeleton, Textarea } from '@/components/ui'
import { PageEnter } from '@/components/motion'
import { cn } from '@/lib/utils'
import type { WaitTimeSettings } from '@/api/types'

export function WaitTimePage() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['admin', 'wait-time'], queryFn: adminApi.waitTime })
  const [f, setF] = useState<WaitTimeSettings | null>(null)
  useEffect(() => { if (q.data && !f) setF(q.data) }, [q.data, f])
  const save = useMutation({ mutationFn: adminApi.saveWaitTime, onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin', 'wait-time'] }); qc.invalidateQueries({ queryKey: ['public'] }) } })
  if (!f) return <Skeleton className="h-96" />
  return (
    <PageEnter className="mx-auto max-w-2xl">
      <h1 className="font-display text-4xl">{t('admin.waitTime.title')}</h1>
      <p className="mb-6 text-sm font-light text-fg-muted">{t('admin.waitTime.lead')}</p>
      <div className="card space-y-5 p-6">
        <div className="flex items-center justify-between rounded-xl border border-line p-4">
          <div><div className="font-medium">{t('admin.waitTime.busy')}</div><div className="text-xs text-fg-dim">{f.busy ? `${f.busyMinDays}–${f.busyMaxDays} ${t('common.days')}` : `${f.normalHours} ${t('common.hours')}`}</div></div>
          <button onClick={() => setF({ ...f, busy: !f.busy })} className={cn('relative h-7 w-12 rounded-full transition-colors', f.busy ? 'bg-gold' : 'bg-surface-2')} aria-pressed={f.busy}><span className={cn('absolute top-1 h-5 w-5 rounded-full bg-white transition-all', f.busy ? 'start-6' : 'start-1')} /></button>
        </div>
        <div className="grid grid-cols-3 gap-3">
          <div><Label>{t('admin.waitTime.normal')}</Label><Input type="number" value={f.normalHours} onChange={(e) => setF({ ...f, normalHours: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.waitTime.min')}</Label><Input type="number" value={f.busyMinDays} onChange={(e) => setF({ ...f, busyMinDays: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.waitTime.max')}</Label><Input type="number" value={f.busyMaxDays} onChange={(e) => setF({ ...f, busyMaxDays: Number(e.target.value) })} /></div>
        </div>
        <div><Label>{t('admin.waitTime.msgAr')}</Label><Textarea rows={3} value={f.messageAr} onChange={(e) => setF({ ...f, messageAr: e.target.value })} /></div>
        <div><Label>{t('admin.waitTime.msgEn')}</Label><Textarea dir="ltr" rows={3} value={f.messageEn} onChange={(e) => setF({ ...f, messageEn: e.target.value })} /></div>
        <div><Label>{t('admin.waitTime.preview')}</Label><div className="card flex items-center gap-3 p-4 text-sm"><span className="pulse-ring h-2 w-2 rounded-full bg-gold" /><span className="font-light text-fg-muted">{f.busy ? f.messageAr : `الرد خلال ${f.normalHours} ساعة إن شاء الله`}</span></div></div>
        <div className="flex items-center justify-between"><span className="text-xs text-success">{save.isSuccess && '✓'}</span><Button loading={save.isPending} onClick={() => save.mutate(f)}>{t('admin.waitTime.save')}</Button></div>
      </div>
    </PageEnter>
  )
}
