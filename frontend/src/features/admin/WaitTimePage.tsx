import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Input, Label, Skeleton, Textarea } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { cn } from '@/lib/utils'
import { replyTimeText } from '@/lib/waitTime'
import type { WaitTimeSettings } from '@/api/types'

type Unit = 'hours' | 'days'
const num = (v: string) => (v === '' ? 0 : Math.max(0, Math.floor(Number(v))))

/**
 * Reply time shown to visitors (home page hero) and users (their dreams page).
 * Stored as before: hours → busy=false + normalHours; days → busy=true + busyMinDays..busyMaxDays.
 * Saving clears autoResetAt so what the interpreter picks is exactly what the site shows.
 */
export function WaitTimePage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['admin', 'wait-time'], queryFn: adminApi.waitTime })
  const [f, setF] = useState<WaitTimeSettings | null>(null)
  useEffect(() => { if (q.data && !f) setF(q.data) }, [q.data, f])
  const save = useMutation({
    mutationFn: adminApi.saveWaitTime,
    onSuccess: (saved) => { setF(saved); qc.setQueryData(['admin', 'wait-time'], saved); qc.invalidateQueries({ queryKey: ['public'] }); qc.invalidateQueries({ queryKey: ['me'] }) },
  })
  if (!f) return <Skeleton className="h-96" />

  const unit: Unit = f.busy ? 'days' : 'hours'
  const badHours = !f.busy && f.normalHours < 1
  const badDays = f.busy && (f.busyMinDays < 1 || f.busyMaxDays < f.busyMinDays)
  const dirty = !q.data || JSON.stringify({ ...q.data, autoResetAt: null }) !== JSON.stringify({ ...f, autoResetAt: null })
  const text = replyTimeText(t, { busy: f.busy, hours: f.normalHours, minDays: f.busyMinDays, maxDays: f.busyMaxDays })
  const note = (locale === 'ar' ? f.messageAr : f.messageEn).trim()

  return (
    <PageEnter className="mx-auto max-w-2xl">
      <h1 className="font-display text-4xl">{t('admin.waitTime.title')}</h1>
      <p className="mb-6 text-sm font-light text-fg-muted">{t('admin.waitTime.lead')}</p>
      <form className="card space-y-6 p-6" onSubmit={(e) => { e.preventDefault(); if (!badHours && !badDays) save.mutate({ ...f, autoResetAt: null }) }}>
        <div>
          <Label>{t('admin.waitTime.unit')}</Label>
          <div role="radiogroup" aria-label={t('admin.waitTime.unit')} className="grid grid-cols-2 gap-2">
            {(['hours', 'days'] as Unit[]).map((u) => (
              <button key={u} type="button" role="radio" aria-checked={unit === u} onClick={() => setF({ ...f, busy: u === 'days' })}
                className={cn('flex items-center gap-3 rounded-xl border p-4 text-start transition-colors', unit === u ? 'border-gold bg-gold/10' : 'border-line hover:border-gold/50')}>
                <span className={cn('flex h-9 w-9 shrink-0 items-center justify-center rounded-full', unit === u ? 'bg-gold text-night' : 'bg-surface-2 text-fg-muted')}><Icon name={u === 'hours' ? 'clock' : 'calendar'} size={18} /></span>
                <span><span className="block font-medium">{t(`admin.waitTime.units.${u}`)}</span><span className="block text-xs text-fg-dim">{t(`admin.waitTime.units.${u}Hint`)}</span></span>
              </button>
            ))}
          </div>
        </div>

        {unit === 'hours' ? (
          <label className="block max-w-[14rem]"><span className="label">{t('admin.waitTime.hours')}</span>
            <Input type="number" inputMode="numeric" min={1} max={720} value={f.normalHours || ''} aria-invalid={badHours} onChange={(e) => setF({ ...f, normalHours: num(e.target.value) })} />
          </label>
        ) : (
          <div className="grid grid-cols-2 gap-3">
            <label><span className="label">{t('admin.waitTime.from')}</span><Input type="number" inputMode="numeric" min={1} max={60} value={f.busyMinDays || ''} aria-invalid={badDays} onChange={(e) => setF({ ...f, busyMinDays: num(e.target.value) })} /></label>
            <label><span className="label">{t('admin.waitTime.to')}</span><Input type="number" inputMode="numeric" min={1} max={60} value={f.busyMaxDays || ''} aria-invalid={badDays} onChange={(e) => setF({ ...f, busyMaxDays: num(e.target.value) })} /></label>
            <p className="col-span-2 -mt-1 text-xs text-fg-dim">{t('admin.waitTime.sameHint')}</p>
          </div>
        )}
        {(badHours || badDays) && <p role="alert" className="text-xs text-danger">{t(badHours ? 'admin.waitTime.badHours' : 'admin.waitTime.badDays')}</p>}

        <div>
          <Label>{t('admin.waitTime.preview')}</Label>
          <div className="rounded-xl2 bg-night p-5 text-center text-sm text-pearl/70">
            <span className="inline-flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-gold shadow-[0_0_10px_rgba(212,175,55,.9)]" />{t('hero.badges.reply')}: <span className="font-medium text-pearl">{text}</span></span>
          </div>
          {note && <div className="card mt-2 flex items-center gap-3 p-4 text-sm"><span className="pulse-ring h-2 w-2 shrink-0 rounded-full bg-gold" /><span className="font-light text-fg-muted">{text} · {note}</span></div>}
        </div>

        <details className="group rounded-xl border border-line p-4">
          <summary className="cursor-pointer list-none text-sm font-medium">{t('admin.waitTime.note')} <span className="text-xs font-normal text-fg-dim">· {t('admin.waitTime.optional')}</span></summary>
          <div className="mt-4 space-y-4">
            <div><Label>{t('admin.waitTime.msgAr')}</Label><Textarea rows={2} value={f.messageAr} onChange={(e) => setF({ ...f, messageAr: e.target.value })} /></div>
            <div><Label>{t('admin.waitTime.msgEn')}</Label><Textarea dir="ltr" rows={2} value={f.messageEn} onChange={(e) => setF({ ...f, messageEn: e.target.value })} /></div>
          </div>
        </details>

        <div className="flex justify-end"><Button type="submit" disabled={!dirty || badHours || badDays} loading={save.isPending}>{t('admin.waitTime.save')}</Button></div>
      </form>
    </PageEnter>
  )
}
