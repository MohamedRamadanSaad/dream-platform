import { useState } from 'react'
import type { WaitTimeSettings } from '@/api/types'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi, reportsApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Input, Label, Modal, Skeleton, Stars, StatusBadge, Textarea } from '@/components/ui'
import { DownloadButton } from '@/components/ui/DownloadButton'
import { Icon } from '@/components/icons/Icon'
import { Avatar } from '@/components/ui/Avatar'
import { PageEnter } from '@/components/motion'
import { Conversation } from '@/features/user/DreamDetailPage'
import { PausedChip, SlaChip } from './QueuePage'
import { arrowBack, arrowNext, cn, fmtDate, fmtMoney, fmtNum, timeAgo } from '@/lib/utils'

const DAY_CHIPS = [1, 2, 3, 5, 7, 10, 14]
/** The reply-time setting in days: the busy maximum, else the normal hours rounded up to days. */
const defaultDays = (w?: WaitTimeSettings) => (!w ? 3 : w.busy ? w.busyMaxDays : Math.max(1, Math.ceil(w.normalHours / 24)))

/** "Start interpreting": the text becomes final; she picks how many days she expects to need (1..30). */
function StartDialog({ open, onClose, dreamId }: { open: boolean; onClose: () => void; dreamId: string }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const qc = useQueryClient()
  const wait = useQuery({ queryKey: ['admin', 'wait-time'], queryFn: adminApi.waitTime, enabled: open, staleTime: 60_000 })
  const [days, setDays] = useState<number | null>(null)
  const value = days ?? defaultDays(wait.data)
  const valid = Number.isInteger(value) && value >= 1 && value <= 30
  const start = useMutation({
    mutationFn: () => adminApi.startDream(dreamId, value),
    meta: { toast: 'admin.dream.started' },
    onSuccess: () => { onClose(); setDays(null); qc.invalidateQueries({ queryKey: ['admin'] }) },
  })
  const by = new Date(Date.now() + (valid ? value : 0) * 864e5).toISOString()
  return (
    <Modal open={open} onClose={onClose} title={t('admin.dream.startTitle')} size="sm" focusField={false}
      footer={<><Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button><Button disabled={!valid} loading={start.isPending} onClick={() => start.mutate()}>{t('admin.dream.startConfirm')}</Button></>}>
      <p className="text-sm leading-relaxed text-fg-muted">{t('admin.dream.startBody')}</p>
      <fieldset className="mt-5">
        <legend className="label">{t('admin.dream.startDays')}</legend>
        <div className="flex flex-wrap gap-2">
          {DAY_CHIPS.map((n) => (
            <button key={n} type="button" aria-pressed={value === n} onClick={() => setDays(n)}
              className={cn('min-h-11 min-w-11 rounded-full border px-4 text-sm tabular-nums transition-colors', value === n ? 'border-gold bg-gold text-night' : 'border-line text-fg-muted hover:border-gold hover:text-fg')}>
              {fmtNum(n, locale)}
            </button>
          ))}
        </div>
      </fieldset>
      <label htmlFor="start-days" className="mt-5 block"><Label>{t('admin.dream.startCustom')}</Label></label>
      <Input id="start-days" type="number" inputMode="numeric" min={1} max={30} step={1} dir="ltr" className="max-w-[8rem] text-center" value={Number.isFinite(value) ? value : ''}
        onChange={(e) => setDays(e.target.value === '' ? NaN : Math.round(Number(e.target.value)))} aria-invalid={!valid} />
      {valid ? (
        <p className="mt-3 text-sm text-gold-ink">{t('admin.dream.expectedIn', { days: t('common.dayCount', { count: value }), date: fmtDate(by, locale) })}</p>
      ) : <p role="alert" className="mt-3 text-sm text-bad-ink">{t('admin.dream.daysOutOfRange')}</p>}
    </Modal>
  )
}

export function AdminDreamPage() {
  const { t } = useTranslation()
  const { id } = useParams()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const locale = useAuthStore((s) => s.locale)
  const q = useQuery({ queryKey: ['admin', 'dream', id], queryFn: () => adminApi.dream(id!) })
  const [question, setQuestion] = useState('')
  const [text, setText] = useState('')
  const [confirm, setConfirm] = useState(false)
  const [starting, setStarting] = useState(false)
  const inv = () => { qc.invalidateQueries({ queryKey: ['admin'] }); qc.invalidateQueries({ queryKey: ['notifications'] }) }
  const ask = useMutation({ mutationFn: () => adminApi.ask(id!, question), meta: { toast: 'common.sent' }, onSuccess: () => { setQuestion(''); inv() } })
  const publish = useMutation({ mutationFn: () => adminApi.interpret(id!, { text }), meta: { toast: 'common.published' }, onSuccess: () => { setConfirm(false); inv(); navigate('/admin/dreams') } })

  if (q.isLoading) return <div className="space-y-4"><Skeleton className="h-10 w-48" /><Skeleton className="h-48" /><Skeleton className="h-64" /></div>
  if (q.isError || !q.data) return <ErrorBox onRetry={() => q.refetch()} />
  const d = q.data
  // the interpretation can be written while it waits, after she started, and while a question is open
  const open = d.status === 'IN_REVIEW' || d.status === 'IN_PROGRESS' || d.status === 'AWAITING_USER_REPLY'
  const canAsk = d.status === 'IN_REVIEW' || d.status === 'IN_PROGRESS'

  return (
    <PageEnter className="grid gap-5 lg:grid-cols-3">
      <div className="space-y-5 lg:col-span-2">
        <Link to="/admin/dreams" className="text-sm text-fg-muted">{arrowBack(locale)} {t('common.back')}</Link>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="flex items-center gap-3 font-display text-3xl"><Avatar name={d.user.name || d.user.email} size={40} />{d.user.name} <span className="text-base text-fg-dim">· {t(d.gender === 'FEMALE' ? 'auth.female' : 'auth.male')}{d.user.age != null && <> · {t('me.profile.years', { n: d.user.age })}</>} · {d.user.countryCode}</span></h1>
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge status={d.status} audience="interpreter" />{d.status === 'AWAITING_USER_REPLY' ? <PausedChip /> : open && d.expectedBy && <SlaChip deadline={d.expectedBy} overdue={new Date(d.expectedBy) < new Date()} />}
            <DownloadButton label={t('reports.pdf')} run={() => reportsApi.adminDreamPdf(d.id)} />
          </div>
        </div>

        {d.status === 'IN_REVIEW' && (
          <div className="card flex flex-col gap-4 border-gold/50 bg-gold/5 p-5 sm:flex-row sm:items-center">
            <p className="flex-1 text-sm leading-relaxed text-fg-muted">{t('admin.dream.editableHint')}</p>
            <Button className="min-h-11 shrink-0" onClick={() => setStarting(true)} aria-haspopup="dialog"><Icon name="sparkle" size={16} />{t('admin.dream.start')}</Button>
          </div>
        )}
        {d.startedAt && (
          <div className="card flex flex-wrap items-center gap-x-4 gap-y-1 p-4 text-sm">
            <span className="flex items-center gap-2 text-gold-ink"><Icon name="sparkle" size={16} />{t('admin.dream.startedAt', { time: timeAgo(d.startedAt, locale) })}</span>
            {d.expectedDays != null && d.expectedBy && <span className="text-fg-muted">{t('admin.dream.expectedIn', { days: t('common.dayCount', { count: d.expectedDays }), date: fmtDate(d.expectedBy, locale) })}</span>}
          </div>
        )}

        <div className="card p-6">
          <div className="flex flex-wrap items-center justify-between gap-2"><Label>{t('me.detail.dream')}</Label>
            {d.userEditedAt && <span className="chip mb-2 bg-warn/10 text-warn"><Icon name="info" size={13} />{t('admin.dream.editedByDreamer', { time: timeAgo(d.userEditedAt, locale) })}</span>}</div>
          <p className="whitespace-pre-wrap text-lg leading-loose">{d.text}</p><div className="mt-3 text-xs text-fg-dim">{d.submittedAt && fmtDate(d.submittedAt, locale, true)}</div>
        </div>

        <div className="card p-6">
          <Label>{t('admin.dream.conversation')}</Label>
          <Conversation messages={d.messages} meRole="INTERPRETER" />
          {canAsk && (
            <div className="mt-4"><Textarea rows={2} value={question} onChange={(e) => setQuestion(e.target.value)} placeholder={t('admin.dream.askPlaceholder')} maxLength={1000} />
              <div className="mt-2 flex justify-end"><Button size="sm" variant="ghost" disabled={question.trim().length < 3} loading={ask.isPending} onClick={() => ask.mutate()}><Icon name="chat" size={14} />{t('admin.dream.ask')}</Button></div></div>
          )}
          {d.status === 'AWAITING_USER_REPLY' && <p className="mt-3 text-xs text-fg-dim">{t('admin.dream.awaitingNote')}</p>}
        </div>

        {d.interpretation ? (
          <div className="card border-success/30 p-6"><Label>{t('me.detail.interpretation')}</Label><p className="whitespace-pre-wrap font-display text-xl leading-loose">{d.interpretation.text}</p>
            {d.testimonial && <div className="mt-4 flex items-center gap-2 border-t border-line pt-4 text-sm text-fg-muted"><Stars value={d.testimonial.rating} size={14} /> {d.testimonial.comment}</div>}</div>
        ) : open && (
          <div className="card p-6"><Label>{t('admin.dream.interpret')}</Label>
            <Textarea rows={12} value={text} onChange={(e) => setText(e.target.value)} className="font-display text-lg leading-loose" placeholder="…" />
            <div className="mt-3 flex items-center justify-between text-xs text-fg-dim"><span>{text.length}</span><Button disabled={text.trim().length < 50} onClick={() => setConfirm(true)}><Icon name="star" size={14} />{t('admin.dream.publish')}</Button></div>
          </div>
        )}
      </div>

      <aside className="space-y-5">
        <div className="card p-5">
          <Label>{t('admin.dream.payment')}</Label>
          {d.payment ? (
            <dl className="space-y-2 text-sm">
              <div className="flex justify-between"><dt className="text-fg-muted">{t('admin.dream.payer')}</dt><dd className="text-end">{d.payment.payerName}<div className="text-xs text-fg-dim" dir="ltr">{d.payment.payerEmail}</div></dd></div>
              <div className="flex justify-between"><dt className="text-fg-muted">{t('admin.dream.paidAt')}</dt><dd>{fmtDate(d.payment.paidAt, locale, true)}</dd></div>
              <div className="flex justify-between"><dt className="text-fg-muted">{t('admin.dream.package')}</dt><dd>{d.payment.packageName}</dd></div>
              <div className="flex justify-between"><dt className="text-fg-muted">{t('common.country')}</dt><dd>{d.payment.countryCode}</dd></div>
              <div className="flex justify-between"><dt className="text-fg-muted">{t('admin.dream.amount')}</dt><dd>{fmtMoney(d.payment.amount, d.payment.currency, locale)}</dd></div>
              <div className="flex justify-between gap-3"><dt className="shrink-0 text-fg-muted">{t('admin.dream.orderNo')}</dt><dd dir="ltr" className="min-w-0 select-all truncate font-mono text-sm font-medium tracking-wide">{d.payment.orderNumber}</dd></div>
              <div className="flex justify-between gap-3"><dt className="shrink-0 text-fg-muted">{t('admin.dream.orderCredits')}</dt><dd className="text-end">
                {d.payment.dreamNumber > 0 && <div>{t('admin.dream.dreamOf', { n: d.payment.dreamNumber, total: d.payment.credits })}</div>}
                <div className={d.payment.creditsLeft > 0 ? 'font-medium text-gold-ink' : 'text-fg-dim'}>{d.payment.creditsExpired ? t('admin.dream.creditsExpired') : t('admin.dream.creditsLeft', { n: d.payment.creditsLeft })}</div>
              </dd></div>
              <div className="flex justify-between"><dt className="text-fg-muted">{t('admin.dream.ref')}</dt><dd dir="ltr" className="text-xs">{d.payment.provider} · {d.payment.providerRef}</dd></div>
            </dl>
          ) : <p className="text-sm text-fg-muted">{t('admin.dream.noOrder')}</p>}
        </div>
        <Link to={`/admin/users/${d.user.id}`} className="card card-hover flex items-center gap-3 p-5 text-sm"><span className="text-gold-ink"><Icon name="user" size={22} /></span>{t('admin.dream.user360')} {arrowNext(locale)}</Link>
      </aside>

      <StartDialog open={starting} onClose={() => setStarting(false)} dreamId={d.id} />
      <Modal open={confirm} onClose={() => setConfirm(false)} title={t('admin.dream.publish')} size="sm"
        footer={<><Button variant="ghost" onClick={() => setConfirm(false)}>{t('common.cancel')}</Button><Button loading={publish.isPending} onClick={() => publish.mutate()}>{t('common.confirm')}</Button></>}>
        <p className="text-sm text-fg-muted">{t('admin.dream.confirm')}</p>
      </Modal>
    </PageEnter>
  )
}
