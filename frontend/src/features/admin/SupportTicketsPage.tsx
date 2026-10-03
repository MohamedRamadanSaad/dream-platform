import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { ApiError } from '@/api/client'
import type { SupportTicket, SupportTicketAction, SupportTicketDetail, SupportTicketStatus } from '@/api/types'
import { useAuthStore } from '@/app/auth-store'
import { Button, Empty, ErrorBox, Modal, Skeleton, Tabs, Textarea } from '@/components/ui'
import { Avatar } from '@/components/ui/Avatar'
import { toast } from '@/components/ui/Toaster'
import { Icon } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { cn, fmtDate, fmtNum, timeAgo } from '@/lib/utils'

// Support mailbox tickets: one per e-mail a person sent to the support address (only sender, subject and time are
// kept). "In progress" (NEW, or another update on IN_PROGRESS) and "Close ticket" (NEW / IN_PROGRESS) each open a
// dialog; the message is e-mailed to the sender. Closed is final.
export const SUPPORT_KEY = ['admin', 'support'] as const
export const SUPPORT_COUNTS_KEY = [...SUPPORT_KEY, 'counts'] as const
const MESSAGE_MAX = 2000
const PAGE = 20
const TAB_KEY: Record<SupportTicketStatus, 'new' | 'inProgress' | 'closed'> = { NEW: 'new', IN_PROGRESS: 'inProgress', CLOSED: 'closed' }

type DialogState = { ticket: SupportTicket; action: SupportTicketAction } | null

export function SupportTicketsPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [tab, setTab] = useState<SupportTicketStatus>('NEW')
  const [limit, setLimit] = useState(PAGE)
  const [dialog, setDialog] = useState<DialogState>(null)
  useEffect(() => setLimit(PAGE), [tab])

  const counts = useQuery({ queryKey: SUPPORT_COUNTS_KEY, queryFn: adminApi.supportTicketCounts })
  const list = useQuery({ queryKey: [...SUPPORT_KEY, 'list', tab, limit], queryFn: () => adminApi.supportTickets(tab, 0, limit), placeholderData: (prev) => prev })
  const items = list.data?.items ?? []
  const more = !!list.data && list.data.total > items.length && limit < 100
  const c = counts.data

  return (
    <PageEnter className="mx-auto max-w-3xl">
      <div className="mb-6 flex items-start gap-4">
        <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink"><Icon name="chat" size={24} /></span>
        <div className="min-w-0">
          <h1 className="font-display text-4xl">{t('admin.support.title')}</h1>
          <p className="mt-1 text-sm font-light leading-relaxed text-fg-muted">{t('admin.support.lead')}</p>
        </div>
      </div>

      <Tabs value={tab} onChange={setTab} items={(['NEW', 'IN_PROGRESS', 'CLOSED'] as const).map((s) => {
        const n = c ? (s === 'NEW' ? c.new : s === 'IN_PROGRESS' ? c.inProgress : c.closed) : undefined
        return { value: s, label: t(`admin.support.tabs.${TAB_KEY[s]}`), count: n === undefined ? undefined : fmtNum(n, locale) }
      })} />

      <div className="mt-5" role="tabpanel">
        {list.isLoading ? (
          <div className="space-y-3"><Skeleton className="h-40" /><Skeleton className="h-40" /><Skeleton className="h-40" /></div>
        ) : list.isError ? (
          <ErrorBox onRetry={() => list.refetch()} />
        ) : !items.length ? (
          <Empty text={t(`admin.support.empty.${TAB_KEY[tab]}`)} />
        ) : (
          <>
            <StaggerGroup key={tab} className="space-y-3" stagger={0.06}>
              {items.map((x) => <TicketCard key={x.id} ticket={x} onAction={(action) => setDialog({ ticket: x, action })} />)}
            </StaggerGroup>
            {more && (
              <div className="mt-4 text-center">
                <Button variant="ghost" size="sm" loading={list.isFetching} onClick={() => setLimit((l) => Math.min(100, l + PAGE))}>{t('admin.support.loadMore')}</Button>
              </div>
            )}
          </>
        )}
      </div>

      {dialog && <ActionDialog key={`${dialog.ticket.id}-${dialog.action}`} state={dialog} onClose={() => setDialog(null)} />}
    </PageEnter>
  )
}

function TicketCard({ ticket: x, onAction }: { ticket: SupportTicket; onAction: (a: SupportTicketAction) => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [open, setOpen] = useState(false)
  const historyId = useId()
  return (
    <article className="card p-4 sm:p-5" aria-label={t('admin.support.number', { n: x.number })}>
      <div className="flex items-start gap-3">
        <Avatar name={x.fromName || x.fromEmail} size={40} />
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              {x.fromName && <div className="truncate font-medium"><bdi>{x.fromName}</bdi></div>}
              <div className={cn('truncate', x.fromName ? 'text-xs text-fg-muted' : 'font-medium')}><bdi dir="ltr">{x.fromEmail}</bdi></div>
            </div>
            <span className="chip shrink-0 bg-surface-2 text-xs text-fg-muted" dir="ltr">#{x.number}</span>
          </div>
          <p className={cn('mt-2 break-words leading-relaxed', x.subject ? 'text-fg' : 'italic text-fg-dim')}><bdi>{x.subject || t('admin.support.noSubject')}</bdi></p>
          <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-fg-dim">
            <span className="inline-flex items-center gap-1"><Icon name="clock" size={13} />{t('admin.support.received', { date: fmtDate(x.receivedAt, locale, true) })}</span>
            {x.closedAt && <span className="inline-flex items-center gap-1"><Icon name="check" size={13} />{t('admin.support.closedOn', { date: fmtDate(x.closedAt, locale, true) })}</span>}
          </div>
        </div>
      </div>

      {x.lastMessage && (
        <div className="mt-3 rounded-xl bg-surface-2/70 p-3">
          <div className="mb-1 text-[11px] font-medium text-gold-ink">{t('admin.support.lastReply', { when: x.lastMessageAt ? timeAgo(x.lastMessageAt, locale) : '' })}</div>
          <p className="line-clamp-3 whitespace-pre-line break-words text-sm font-light leading-relaxed text-fg-muted" dir="auto">{x.lastMessage}</p>
        </div>
      )}

      {(x.status !== 'CLOSED' || x.eventsCount > 0) && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          {x.status !== 'CLOSED' && (
            <>
              <Button size="sm" variant={x.status === 'NEW' ? 'gold' : 'ghost'} onClick={() => onAction('IN_PROGRESS')}>
                <Icon name="chat" size={15} />{t(x.status === 'NEW' ? 'admin.support.actions.inProgress' : 'admin.support.actions.update')}
              </Button>
              <Button size="sm" variant={x.status === 'NEW' ? 'ghost' : 'night'} onClick={() => onAction('CLOSED')}>
                <Icon name="check" size={15} />{t('admin.support.actions.close')}
              </Button>
            </>
          )}
          {x.eventsCount > 0 && (
            <Button size="sm" variant="link" className="ms-auto" aria-expanded={open} aria-controls={historyId} onClick={() => setOpen((v) => !v)}>
              {open ? t('admin.support.history.hide') : t('admin.support.history.show', { n: fmtNum(x.eventsCount, locale) })}
            </Button>
          )}
        </div>
      )}

      {open && <div id={historyId}><History id={x.id} /></div>}
    </article>
  )
}

function History({ id }: { id: string }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const q = useQuery({ queryKey: [...SUPPORT_KEY, 'ticket', id], queryFn: () => adminApi.supportTicket(id) })
  if (q.isLoading) return <Skeleton className="mt-3 h-24" />
  if (q.isError || !q.data) return <div className="mt-3"><ErrorBox onRetry={() => q.refetch()} /></div>
  return (
    <ol className="mt-3 space-y-3 border-s-2 border-line ps-4">
      {q.data.events.map((e) => (
        <li key={e.id} className="relative">
          <span aria-hidden="true" className={cn('absolute -start-[1.3rem] top-1.5 h-2.5 w-2.5 rounded-full', e.action === 'CLOSED' ? 'bg-fg-muted' : 'bg-gold')} />
          <div className="flex flex-wrap items-center gap-x-2 text-xs">
            <span className="font-medium text-fg">{t(`admin.support.history.action.${e.action}`)}</span>
            <span className="text-fg-dim">{fmtDate(e.createdAt, locale, true)}</span>
            {e.actorName && <span className="text-fg-dim">· {t('admin.support.history.by', { name: e.actorName })}</span>}
          </div>
          <p className="mt-1 whitespace-pre-line break-words text-sm font-light leading-relaxed" dir="auto">{e.message}</p>
          {e.emailStatus && (
            <div className={cn('mt-1 inline-flex items-center gap-1 text-[11px]', e.emailStatus === 'FAILED' ? 'text-bad-ink' : e.emailStatus === 'SENT' ? 'text-ok-ink' : 'text-fg-dim')}>
              <Icon name="mail" size={12} />{t(`admin.support.history.email.${e.emailStatus}`)}
            </div>
          )}
        </li>
      ))}
    </ol>
  )
}

function ActionDialog({ state, onClose }: { state: NonNullable<DialogState>; onClose: () => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const qc = useQueryClient()
  const fieldId = useId()
  const counterId = useId()
  const { ticket, action } = state
  const [text, setText] = useState('')
  const [touched, setTouched] = useState(false)
  const closing = action === 'CLOSED'
  const update = !closing && ticket.status === 'IN_PROGRESS'
  const trimmed = text.trim()
  const invalid = !trimmed || trimmed.length > MESSAGE_MAX

  const send = useMutation({
    meta: { toast: false },
    mutationFn: () => (closing ? adminApi.supportTicketClose(ticket.id, trimmed) : adminApi.supportTicketInProgress(ticket.id, trimmed)),
    onSuccess: (d: SupportTicketDetail) => {
      const last = d.events[d.events.length - 1]
      if (last?.emailStatus === 'FAILED') toast.error(t('admin.support.toast.emailFailed'))
      else if (last?.emailStatus === 'DISABLED') toast.success(t('admin.support.toast.emailOff'))
      else toast.success(t('admin.support.toast.sent'))
      qc.setQueryData([...SUPPORT_KEY, 'ticket', d.id], d)
      qc.invalidateQueries({ queryKey: SUPPORT_KEY })
      onClose()
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) qc.invalidateQueries({ queryKey: SUPPORT_KEY })
    },
  })
  const error = send.error instanceof ApiError && send.error.status === 409 ? t('admin.support.dialog.alreadyClosed') : send.isError ? t('common.error') : null
  const submit = () => { setTouched(true); if (!invalid && !send.isPending) send.mutate() }

  return (
    <Modal open onClose={onClose} title={t(closing ? 'admin.support.dialog.closeTitle' : update ? 'admin.support.dialog.updateTitle' : 'admin.support.dialog.inProgressTitle')}
      footer={<>
        <Button variant="ghost" size="sm" onClick={onClose} disabled={send.isPending}>{t('common.cancel')}</Button>
        <Button size="sm" variant={closing ? 'night' : 'gold'} loading={send.isPending} disabled={touched && invalid} onClick={submit}>
          {t(closing ? 'admin.support.dialog.closeSend' : 'admin.support.dialog.send')}
        </Button>
      </>}>
      <p className="mb-4 text-sm font-light leading-relaxed text-fg-muted">{t(closing ? 'admin.support.dialog.closeLead' : update ? 'admin.support.dialog.updateLead' : 'admin.support.dialog.inProgressLead')}</p>
      <div className="mb-4 rounded-xl bg-surface-2/70 p-3 text-sm">
        <div className="flex items-center gap-2">
          <span className="text-xs text-fg-dim">{t('admin.support.dialog.to')}</span>
          <span className="flex min-w-0 flex-wrap items-baseline gap-x-2">{ticket.fromName && <bdi dir="auto" className="font-medium">{ticket.fromName}</bdi>}<bdi dir="ltr" className="min-w-0 truncate text-fg-muted">{ticket.fromEmail}</bdi></span>
        </div>
        <div className="mt-1 truncate text-xs text-fg-muted"><bdi dir="ltr">#{ticket.number}</bdi> · <span dir="auto">{ticket.subject || t('admin.support.noSubject')}</span></div>
      </div>
      <label htmlFor={fieldId} className="label">{t('admin.support.dialog.messageLabel')}</label>
      <Textarea id={fieldId} rows={6} value={text} maxLength={MESSAGE_MAX} dir="auto" aria-describedby={counterId} aria-invalid={touched && invalid}
        placeholder={t('admin.support.dialog.placeholder')} className="mt-1 w-full resize-y"
        onChange={(e) => { setText(e.target.value); if (send.isError) send.reset() }}
        onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) submit() }} />
      <div className="mt-1.5 flex items-start justify-between gap-3 text-xs">
        <span role="alert" className="text-bad-ink">
          {touched && !trimmed ? t('admin.support.dialog.required') : trimmed.length > MESSAGE_MAX ? t('admin.support.dialog.tooLong', { max: fmtNum(MESSAGE_MAX, locale) }) : error}
        </span>
        <span id={counterId} className={cn('shrink-0 tabular-nums', text.length >= MESSAGE_MAX ? 'text-bad-ink' : 'text-fg-dim')} dir="ltr">
          {t('admin.support.dialog.counter', { n: fmtNum(text.length, locale), max: fmtNum(MESSAGE_MAX, locale) })}
        </span>
      </div>
    </Modal>
  )
}
