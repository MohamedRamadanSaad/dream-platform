import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi, reportsApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Label, Modal, Skeleton, Stars, StatusBadge, Textarea } from '@/components/ui'
import { DownloadButton } from '@/components/ui/DownloadButton'
import { Icon } from '@/components/icons/Icon'
import { Avatar } from '@/components/ui/Avatar'
import { PageEnter } from '@/components/motion'
import { Conversation } from '@/features/user/DreamDetailPage'
import { PausedChip, SlaChip } from './QueuePage'
import { arrowBack, arrowNext, fmtDate, fmtMoney } from '@/lib/utils'

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
  const inv = () => { qc.invalidateQueries({ queryKey: ['admin'] }); qc.invalidateQueries({ queryKey: ['notifications'] }) }
  const ask = useMutation({ mutationFn: () => adminApi.ask(id!, question), meta: { toast: 'common.sent' }, onSuccess: () => { setQuestion(''); inv() } })
  const publish = useMutation({ mutationFn: () => adminApi.interpret(id!, { text }), meta: { toast: 'common.published' }, onSuccess: () => { setConfirm(false); inv(); navigate('/admin/dreams') } })

  if (q.isLoading) return <div className="space-y-4"><Skeleton className="h-10 w-48" /><Skeleton className="h-48" /><Skeleton className="h-64" /></div>
  if (q.isError || !q.data) return <ErrorBox onRetry={() => q.refetch()} />
  const d = q.data
  const open = d.status === 'IN_REVIEW' || d.status === 'AWAITING_USER_REPLY'

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
        <div className="card p-6"><Label>{t('me.detail.dream')}</Label><p className="whitespace-pre-wrap text-lg leading-loose">{d.text}</p><div className="mt-3 text-xs text-fg-dim">{d.submittedAt && fmtDate(d.submittedAt, locale, true)}</div></div>

        <div className="card p-6">
          <Label>{t('admin.dream.conversation')}</Label>
          <Conversation messages={d.messages} meRole="INTERPRETER" />
          {d.status === 'IN_REVIEW' && (
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
              <div className="flex justify-between"><dt className="text-fg-muted">{t('admin.dream.ref')}</dt><dd dir="ltr" className="text-xs">{d.payment.provider} · {d.payment.providerRef}</dd></div>
            </dl>
          ) : <p className="text-sm text-fg-muted">{t('admin.dream.noOrder')}</p>}
        </div>
        <Link to={`/admin/users/${d.user.id}`} className="card card-hover flex items-center gap-3 p-5 text-sm"><span className="text-gold-ink"><Icon name="user" size={22} /></span>{t('admin.dream.user360')} {arrowNext(locale)}</Link>
      </aside>

      <Modal open={confirm} onClose={() => setConfirm(false)} title={t('admin.dream.publish')} size="sm"
        footer={<><Button variant="ghost" onClick={() => setConfirm(false)}>{t('common.cancel')}</Button><Button loading={publish.isPending} onClick={() => publish.mutate()}>{t('common.confirm')}</Button></>}>
        <p className="text-sm text-fg-muted">{t('admin.dream.confirm')}</p>
      </Modal>
    </PageEnter>
  )
}
