import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { dreamsApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Label, Skeleton, Stars, StatusBadge, Textarea } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { arrowBack, cn, fmtDate } from '@/lib/utils'
import type { DreamDetail, DreamMessage } from '@/api/types'

const STEPS: DreamDetail['status'][] = ['DRAFT', 'IN_REVIEW', 'INTERPRETED']
export function Stepper({ status }: { status: DreamDetail['status'] }) {
  const { t } = useTranslation()
  const idx = status === 'AWAITING_USER_REPLY' ? 1 : STEPS.indexOf(status)
  return (
    <div className="flex items-center">
      {STEPS.map((s, i) => (
        <div key={s} className={cn('flex items-center', i < STEPS.length - 1 && 'flex-1')}>
          <div className="flex flex-col items-center gap-1.5">
            <div className={cn('h-3.5 w-3.5 rounded-full border-2 transition-all', i <= idx ? 'border-gold bg-gold' : 'border-line', i === idx && status !== 'INTERPRETED' && 'pulse-ring')} />
            <span className={cn('whitespace-nowrap text-[11px] md:text-xs', i <= idx ? 'text-gold-ink' : 'text-fg-dim')}>{t(`me.status.${i === 1 && status === 'AWAITING_USER_REPLY' ? 'AWAITING_USER_REPLY' : s}`)}</span>
          </div>
          {i < STEPS.length - 1 && <div className="relative mx-2 mb-5 h-0.5 flex-1 overflow-hidden bg-line"><div className="absolute inset-0 origin-right bg-gold transition-transform duration-700" style={{ transform: `scaleX(${i < idx ? 1 : 0})` }} /></div>}
        </div>
      ))}
    </div>
  )
}

export function Conversation({ messages, meRole }: { messages: DreamMessage[]; meRole: 'USER' | 'INTERPRETER' }) {
  const locale = useAuthStore((s) => s.locale)
  if (!messages.length) return null
  return (
    <div className="flex flex-col gap-3">
      {messages.map((m) => (
        <div key={m.id} className={cn('max-w-[85%] rounded-2xl px-4 py-3 text-sm leading-relaxed', m.senderRole === meRole ? 'self-end bg-night text-pearl' : 'self-start bg-surface-2 text-fg')}>
          <div>{m.body}</div>
          <div className={cn('mt-1 text-[11px]', m.senderRole === meRole ? 'text-pearl/50' : 'text-fg-dim')}>{fmtDate(m.createdAt, locale, true)}</div>
        </div>
      ))}
    </div>
  )
}

export function DreamDetailPage() {
  const { t } = useTranslation()
  const { id } = useParams()
  const qc = useQueryClient()
  const locale = useAuthStore((s) => s.locale)
  const q = useQuery({ queryKey: ['dream', id], queryFn: () => dreamsApi.get(id!) })
  const [reply, setReply] = useState('')
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const send = useMutation({ mutationFn: () => dreamsApi.reply(id!, reply), onSuccess: () => { setReply(''); qc.invalidateQueries({ queryKey: ['dream', id] }); qc.invalidateQueries({ queryKey: ['me'] }); qc.invalidateQueries({ queryKey: ['dreams'] }) } })
  const rate = useMutation({ mutationFn: () => dreamsApi.testimonial(id!, { rating, comment }), onSuccess: () => qc.invalidateQueries({ queryKey: ['dream', id] }) })

  if (q.isLoading) return <div className="mx-auto max-w-3xl space-y-4"><Skeleton className="h-10 w-40" /><Skeleton className="h-24" /><Skeleton className="h-48" /></div>
  if (q.isError || !q.data) return <ErrorBox onRetry={() => q.refetch()} />
  const d = q.data

  return (
    <PageEnter className="mx-auto max-w-3xl space-y-5">
      <Link to="/me" className="text-sm text-fg-muted hover:text-fg">{arrowBack(locale)} {t('common.back')}</Link>
      <div className="flex items-center justify-between"><h1 className="font-display text-3xl">{t('me.detail.title')}</h1><StatusBadge status={d.status} /></div>
      {d.status !== 'DRAFT' && <div className="card p-5"><Stepper status={d.status} /></div>}

      <div className="card p-6">
        <Label>{t('me.detail.dream')}</Label>
        <p className="whitespace-pre-wrap leading-loose">{d.text}</p>
        <div className="mt-4 text-xs text-fg-dim">{d.submittedAt ? `${t('me.detail.submittedAt')} ${fmtDate(d.submittedAt, locale, true)}` : fmtDate(d.createdAt, locale)}</div>
        {d.status === 'DRAFT' && <div className="mt-4 flex gap-3"><Link to={`/me/dreams/${d.id}/edit`} className="btn btn-sm btn-ghost">{t('common.edit')}</Link><Link to={`/me?submit=${d.id}`} className="btn btn-sm btn-gold">{t('me.submitSelected', { n: 1 })}</Link></div>}
      </div>

      {(d.messages.length > 0 || d.status === 'AWAITING_USER_REPLY') && (
        <div className="card p-6">
          <Label>{t('me.detail.question')}</Label>
          <Conversation messages={d.messages} meRole="USER" />
          {d.status === 'AWAITING_USER_REPLY' && (
            <div className="mt-5">
              <Textarea rows={3} value={reply} onChange={(e) => setReply(e.target.value)} placeholder={t('me.detail.reply')} maxLength={1000} />
              <div className="mt-3 flex justify-end"><Button size="sm" disabled={reply.trim().length < 2} loading={send.isPending} onClick={() => send.mutate()}>{t('me.detail.send')}</Button></div>
            </div>
          )}
        </div>
      )}

      {d.status === 'IN_REVIEW' && (
        <div className="card flex flex-col items-center gap-2 border-warn/30 p-8 text-center">
          <span className="text-warn"><Icon name="star" size={36} strokeWidth={1.2} /></span>
          <div className="text-lg text-warn">{t('me.detail.waiting')}</div>
          <div className="text-sm font-light text-fg-muted">{t('me.detail.waitingText')}{d.expectedBy && <> · {t('me.detail.expected')} {fmtDate(d.expectedBy, locale)}</>}</div>
        </div>
      )}

      {d.interpretation && (
        <div className="card border-success/30 p-6">
          <div className="mb-4 flex items-center gap-3"><span className="text-success"><Icon name="moon" size={22} /></span><div><div className="text-sm text-success">{t('me.detail.interpretation')} — {t('interpreter')}</div><div className="text-xs text-fg-dim">{fmtDate(d.interpretation.interpretedAt, locale)}</div></div></div>
          <p className="whitespace-pre-wrap text-lg leading-[2.1] text-fg md:text-[1.2rem]">{d.interpretation.text}</p>
          <div className="mt-6 border-t border-line pt-5">
            {d.testimonial ? (
              <div className="flex items-center gap-3 text-sm text-fg-muted"><Stars value={d.testimonial.rating} size={16} /> {t('me.detail.rated')}</div>
            ) : (
              <div className="flex flex-col gap-3">
                <div className="text-sm">{t('me.detail.rate')}</div>
                <Stars value={rating} onChange={setRating} />
                <Textarea rows={2} value={comment} onChange={(e) => setComment(e.target.value)} maxLength={600} placeholder="…" />
                <div className="flex justify-end"><Button size="sm" variant="ghost" disabled={!rating} loading={rate.isPending} onClick={() => rate.mutate()}>{t('me.detail.rateCta')}</Button></div>
              </div>
            )}
          </div>
        </div>
      )}
    </PageEnter>
  )
}
