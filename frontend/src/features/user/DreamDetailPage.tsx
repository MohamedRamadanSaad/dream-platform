import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { dreamsApi, reportsApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Label, NotFound, Skeleton, Stars, StatusBadge, Textarea } from '@/components/ui'
import { DownloadButton } from '@/components/ui/DownloadButton'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { arrowBack, cn, fmtDate } from '@/lib/utils'
import { dreamBadge, paymentBadge, paymentLine } from './DreamsPage'
import { DreamPaymentActions } from './DreamPaymentActions'
import { isNotFound } from '@/lib/apiErrors'
import type { DreamDetail, DreamMessage } from '@/api/types'

const STEPS: DreamDetail['status'][] = ['DRAFT', 'IN_REVIEW', 'IN_PROGRESS', 'INTERPRETED']
export function Stepper({ status, started }: { status: DreamDetail['status']; started?: boolean }) {
  const { t } = useTranslation()
  // a question (or a payment waiting for confirmation) pauses the current step: before the start it is "in review",
  // after it "being interpreted"
  const pausedAt = started ? 2 : 1
  const idx = status === 'AWAITING_USER_REPLY' || status === 'AWAITING_PAYMENT' ? pausedAt : STEPS.indexOf(status)
  return (
    <div className="flex items-center">
      {STEPS.map((s, i) => (
        <div key={s} className={cn('flex items-center', i < STEPS.length - 1 && 'flex-1')}>
          <div className="flex flex-col items-center gap-1.5">
            <div className={cn('h-3.5 w-3.5 rounded-full border-2 transition-all', i <= idx ? 'border-gold bg-gold' : 'border-line', i === idx && status !== 'INTERPRETED' && 'pulse-ring')} />
            <span className={cn('whitespace-nowrap text-[11px] md:text-xs', i <= idx ? 'text-gold-ink' : 'text-fg-dim')}>{t(`me.status.${i === pausedAt && status === 'AWAITING_USER_REPLY' ? 'AWAITING_USER_REPLY' : s}`)}</span>
          </div>
          {i < STEPS.length - 1 && <div className="relative mx-1.5 mb-5 h-0.5 min-w-3 flex-1 overflow-hidden bg-line sm:mx-2"><div className="absolute inset-0 bg-gold ltr:origin-left rtl:origin-right transition-transform duration-700" style={{ transform: `scaleX(${i < idx ? 1 : 0})` }} /></div>}
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

/** The payment a dream waits on: under review, rejected, or revoked (a held dream must have its payment reviewed). */
function PaymentCard({ d, onGone }: { d: DreamDetail; onGone: () => void }) {
  const { t } = useTranslation()
  const p = d.payment!
  const held = d.status === 'AWAITING_PAYMENT'
  const line = paymentLine(d)
  const bad = line.tone === 'bad'
  const title = p.status === 'REVOKED' ? 'me.detail.revokedTitle' : p.status === 'REJECTED' ? 'me.detail.rejectedTitle' : held ? 'me.detail.heldPendingTitle' : 'me.detail.pendingTitle'
  return (
    <div className={cn('card flex flex-col gap-3 p-5 sm:flex-row sm:items-start', bad ? 'border-danger/30 bg-danger/5' : 'border-warn/30 bg-warn/5')}>
      <span className={bad ? 'text-bad-ink' : 'text-warn'}><Icon name="wallet" size={26} /></span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2"><span className="font-medium">{t(title)}</span>{held && <StatusBadge status={paymentBadge(p)} />}</div>
        <p className="mt-0.5 text-sm font-light text-fg-muted">{t(line.key)}</p>
        {bad && p.reviewNote && <p className="mt-1 text-sm text-fg-muted">{t('me.dreamPayment.reason', { note: p.reviewNote })}</p>}
        {held && <p className="mt-1 text-xs text-fg-dim">{t('me.detail.heldKept')}</p>}
      </div>
      <div className="sm:max-w-[55%]"><DreamPaymentActions dreamId={d.id} payment={p} held={held} onGone={onGone} /></div>
    </div>
  )
}

export function DreamDetailPage() {
  const { t } = useTranslation()
  const { id } = useParams()
  const qc = useQueryClient()
  const locale = useAuthStore((s) => s.locale)
  const navigate = useNavigate()
  const q = useQuery({ queryKey: ['dream', id], queryFn: () => dreamsApi.get(id!), retry: (n, e) => !isNotFound(e) && n < 1 })
  const [reply, setReply] = useState('')
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const send = useMutation({ mutationFn: () => dreamsApi.reply(id!, reply), onSuccess: () => { setReply(''); qc.invalidateQueries({ queryKey: ['dream', id] }); qc.invalidateQueries({ queryKey: ['me'] }); qc.invalidateQueries({ queryKey: ['dreams'] }) } })
  const rate = useMutation({ mutationFn: () => dreamsApi.testimonial(id!, { rating, comment }), onSuccess: () => qc.invalidateQueries({ queryKey: ['dream', id] }) })

  if (q.isLoading) return <div className="mx-auto max-w-3xl space-y-4"><Skeleton className="h-10 w-40" /><Skeleton className="h-24" /><Skeleton className="h-48" /></div>
  if (isNotFound(q.error)) return <PageEnter className="mx-auto max-w-3xl"><NotFound text={t('me.detail.notFound')} back={<Link to="/me" className="btn btn-sm btn-ghost">{arrowBack(locale)} {t('me.nav.dreams')}</Link>} /></PageEnter>
  if (q.isError || !q.data) return <ErrorBox onRetry={() => q.refetch()} />
  const d = q.data

  return (
    <PageEnter className="mx-auto max-w-3xl space-y-5">
      <Link to="/me" className="text-sm text-fg-muted hover:text-fg">{arrowBack(locale)} {t('common.back')}</Link>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="font-display text-3xl">{t('me.detail.title')}</h1>
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={dreamBadge(d)} />
          {d.status !== 'DRAFT' && <DownloadButton label={t('reports.pdf')} run={() => reportsApi.dreamPdf(d.id)} />}
        </div>
      </div>
      {d.status !== 'DRAFT' && d.status !== 'CANCELLED' && <div className="card overflow-x-auto p-5"><Stepper status={d.status} started={!!d.startedAt} /></div>}

      {d.payment && <PaymentCard d={d} onGone={() => navigate('/me?tab=PENDING_PAYMENT', { replace: true })} />}

      <div className="card p-6">
        <Label>{t('me.detail.dream')}</Label>
        <p className="whitespace-pre-wrap leading-loose">{d.text}</p>
        <div className="mt-4 text-xs text-fg-dim">{d.submittedAt ? `${t('me.detail.submittedAt')} ${fmtDate(d.submittedAt, locale, true)}` : fmtDate(d.createdAt, locale)}</div>
        {d.editable && (
          <div className="mt-4 flex flex-wrap items-center gap-3">
            <Link to={`/me/dreams/${d.id}/edit`} className="btn btn-sm btn-ghost">{t('common.edit')}</Link>
            {d.status === 'DRAFT' && !d.payment && <Link to={`/me?submit=${d.id}`} className="btn btn-sm btn-gold">{t('me.submitSelected', { n: 1 })}</Link>}
            {(d.status === 'IN_REVIEW' || d.status === 'AWAITING_PAYMENT') && <span className="text-xs text-fg-dim">{t('me.detail.editHint')}</span>}
          </div>
        )}
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

      {d.status === 'IN_PROGRESS' && (
        <div className="card flex flex-col items-center gap-2 border-gold/40 p-8 text-center">
          <span className="text-gold-ink"><Icon name="sparkle" size={36} strokeWidth={1.2} /></span>
          <div className="text-lg text-gold-ink">{t('me.detail.inProgressTitle')}</div>
          {d.expectedDays != null && d.expectedBy && <div className="text-sm text-fg">{t('me.detail.inProgressText', { days: t('common.dayCount', { count: d.expectedDays }), date: fmtDate(d.expectedBy, locale) })}</div>}
          <div className="text-xs font-light text-fg-muted">{t('me.detail.inProgressFinal')}</div>
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
          <div className="mb-4 flex items-center gap-3"><span className="text-success"><Icon name="moon" size={22} /></span><div><div className="text-sm text-success">{t('me.detail.interpretation')} · {t('interpreter')}</div><div className="text-xs text-fg-dim">{fmtDate(d.interpretation.interpretedAt, locale)}</div></div></div>
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
