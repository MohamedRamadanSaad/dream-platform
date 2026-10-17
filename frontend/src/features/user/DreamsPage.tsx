import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { dreamsApi, meApi, reportsApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Empty, ErrorBox, Skeleton, StatusBadge, Tabs, type BadgeStatus } from '@/components/ui'
import { DownloadButton } from '@/components/ui/DownloadButton'
import { Icon } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { arrowNext, cn, fmtDate, timeAgo } from '@/lib/utils'
import type { DreamPaymentLink, DreamSummary } from '@/api/types'
import { replyTimeText } from '@/lib/waitTime'
import { CreditExpiryNotice } from './CreditExpiryNotice'
import { UserOverview } from './UserOverview'
import { DreamPaymentActions } from './DreamPaymentActions'
import { errorCode, invalidateDreams, useErrorText, useOnce } from '@/lib/apiErrors'
import { toast } from '@/components/ui/Toaster'

type Tab = 'DRAFT' | 'PENDING_PAYMENT' | 'IN_REVIEW' | 'IN_PROGRESS' | 'AWAITING_USER_REPLY' | 'INTERPRETED'
const TABS: Tab[] = ['DRAFT', 'PENDING_PAYMENT', 'IN_REVIEW', 'IN_PROGRESS', 'AWAITING_USER_REPLY', 'INTERPRETED']
const EMPTY_KEY: Record<Tab, string> = { DRAFT: 'drafts', PENDING_PAYMENT: 'pendingPayment', IN_REVIEW: 'inReview', IN_PROGRESS: 'inProgress', AWAITING_USER_REPLY: 'awaiting', INTERPRETED: 'interpreted' }

/** The payment's own state as a badge: under review, rejected, or revoked ("check your payment details"). */
export const paymentBadge = (p: DreamPaymentLink): BadgeStatus => (p.status === 'REVOKED' ? 'PAYMENT_REVOKED' : p.status === 'REJECTED' ? 'PAYMENT_REJECTED' : 'PENDING_PAYMENT')
/** A draft attached to a payment shows the payment's state instead of "draft"; a held dream keeps its own status. */
export const dreamBadge = (d: DreamSummary): BadgeStatus => (d.payment && d.status === 'DRAFT' ? paymentBadge(d.payment) : d.status)
/** What the payment line under a dream says: a held dream (AWAITING_PAYMENT) is kept and waits; a draft is not sent yet. */
export function paymentLine(d: DreamSummary): { key: string; tone: 'bad' | 'muted' } {
  const p = d.payment!
  if (p.status === 'REVOKED') return { key: 'me.dreamPayment.revoked', tone: 'bad' }
  const held = d.status === 'AWAITING_PAYMENT'
  if (p.status === 'REJECTED') return { key: held ? 'me.dreamPayment.heldRejected' : 'me.dreamPayment.rejected', tone: 'bad' }
  return { key: held ? 'me.dreamPayment.heldUnderReview' : 'me.dreamPayment.underReview', tone: 'muted' }
}

export function DreamRow({ d, selectable, selected, onToggle }: { d: DreamSummary; selectable?: boolean; selected?: boolean; onToggle?: () => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const inner = (
    <>
      <div className="mb-2 flex items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2"><StatusBadge status={dreamBadge(d)} />{d.unreadMessages > 0 && <span className="chip bg-danger/10 text-danger"><Icon name="chat" size={12} />{d.unreadMessages}</span>}</div>
        <span className="shrink-0 text-xs text-fg-dim">{timeAgo(d.createdAt, locale)}</span>
      </div>
      <p className="line-clamp-2 leading-relaxed text-fg">{d.excerpt}</p>
      {(d.status === 'IN_REVIEW' || d.status === 'IN_PROGRESS') && d.expectedBy && <div className="mt-2 text-xs text-fg-dim">{t('me.detail.expected')}: {fmtDate(d.expectedBy, locale)}</div>}
    </>
  )
  if (d.payment) {
    // not selectable: it waits for its payment; the card opens the dream, the link opens the payment
    const line = paymentLine(d)
    const bad = line.tone === 'bad'
    const held = d.status === 'AWAITING_PAYMENT'
    return (
      <div className={cn('card flex flex-col p-5', bad ? 'border-danger/30' : 'border-warn/30')}>
        <Link to={`/me/dreams/${d.id}`} className="block rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-gold">{inner}</Link>
        <div className="mt-3 space-y-2 border-t border-line pt-3 text-xs">
          {held && <StatusBadge status={paymentBadge(d.payment)} />}
          <p className={bad ? 'text-bad-ink' : 'text-fg-muted'}>{t(line.key)}</p>
          {bad && d.payment.reviewNote && <p className="text-fg-muted">{t('me.dreamPayment.reason', { note: d.payment.reviewNote })}</p>}
          <DreamPaymentActions dreamId={d.id} payment={d.payment} held={held} compact />
        </div>
      </div>
    )
  }
  if (selectable) {
    return (
      <label className={cn('card card-hover flex cursor-pointer gap-4 p-5', selected && 'border-gold bg-gold/5')}>
        <input type="checkbox" checked={!!selected} onChange={onToggle} className="mt-1 h-5 w-5 accent-gold" />
        <div className="flex-1 min-w-0">{inner}<div className="mt-3 flex gap-3 text-xs"><Link to={`/me/dreams/${d.id}/edit`} className="text-gold-ink hover:underline">{t('common.edit')}</Link></div></div>
      </label>
    )
  }
  return <Link to={`/me/dreams/${d.id}`} className="card card-hover block p-5">{inner}</Link>
}

export function UserDreamsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const user = useAuthStore((s) => s.user)
  const locale = useAuthStore((s) => s.locale)
  const [params, setParams] = useSearchParams()
  // the tab IS the address (?tab=): a link, a notification or Back/Forward always shows the matching list
  const fromUrl = params.get('tab') as Tab | null
  const tab: Tab = fromUrl && TABS.includes(fromUrl) ? fromUrl : 'DRAFT'
  const [selected, setSelected] = useState<string[]>([])
  useEffect(() => { setSelected([]) }, [tab])
  const listRef = useRef<HTMLDivElement>(null)
  const setTab = (v: Tab) => {
    if (v === tab) return
    setParams((p) => { const n = new URLSearchParams(p); if (v === 'DRAFT') n.delete('tab'); else n.set('tab', v); return n })
  }
  const openTab = (v: Tab) => {
    setTab(v)
    requestAnimationFrame(() => listRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  }

  const dash = useQuery({ queryKey: ['me', 'dashboard'], queryFn: meApi.dashboard })
  const list = useQuery({ queryKey: ['dreams', tab], queryFn: () => dreamsApi.list(tab, 0, 50) })
  const errorText = useErrorText('me.dreamErrors')
  const once = useOnce()
  const submit = useMutation({
    mutationFn: dreamsApi.submit,
    onSuccess: () => { setSelected([]); qc.invalidateQueries({ queryKey: ['dreams'] }); qc.invalidateQueries({ queryKey: ['me'] }); qc.invalidateQueries({ queryKey: ['notifications'] }); setTab('IN_REVIEW') },
    onError: (e) => { if (errorCode(e) === 'INSUFFICIENT_CREDITS') navigate(`/me/packages?dreams=${selected.join(',')}`); else { toast.error(errorText(e)); invalidateDreams(qc) } },
  })

  const credits = dash.data?.credits ?? 0
  const items = list.data?.items ?? []
  const tabs = useMemo(() => [
    { value: 'DRAFT' as Tab, label: t('me.tabs.drafts'), count: dash.data?.drafts },
    { value: 'PENDING_PAYMENT' as Tab, label: t('me.tabs.pendingPayment'), count: dash.data?.pendingPayment },
    { value: 'IN_REVIEW' as Tab, label: t('me.tabs.inReview'), count: dash.data?.inReview },
    { value: 'IN_PROGRESS' as Tab, label: t('me.tabs.inProgress'), count: dash.data?.inProgress },
    { value: 'AWAITING_USER_REPLY' as Tab, label: t('me.tabs.awaiting'), count: dash.data?.awaitingReply, tone: dash.data?.awaitingReply ? 'danger' as const : undefined },
    { value: 'INTERPRETED' as Tab, label: t('me.tabs.interpreted'), count: dash.data?.interpreted },
  ], [t, dash.data])

  const toggle = (id: string) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]))
  const need = Math.max(0, selected.length - credits)

  return (
    <PageEnter>
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-4xl">{t('me.greeting', { name: user?.name })}</h1>
          <p className="text-sm font-light text-fg-muted">{t('me.sub')}</p>
        </div>
        <Link to="/me/new" className="btn btn-md btn-gold"><Icon name="plus" size={16} />{t('me.nav.new')}</Link>
      </div>

      <UserOverview data={dash.data} loading={dash.isLoading} tab={tab} onTab={openTab} />

      <CreditExpiryNotice className="mb-4" withLink />
      {dash.data?.waitTime && (
        <div className="card mb-6 flex items-center gap-3 p-4 text-sm">
          <span className="pulse-ring h-2 w-2 shrink-0 rounded-full bg-gold" />
          <span className="font-light text-fg-muted">{t('hero.badges.reply')}: <span className="font-medium text-fg">{replyTimeText(t, dash.data.waitTime)}</span>{dash.data.waitTime.message.trim() && <> · {dash.data.waitTime.message}</>}</span>
        </div>
      )}

      {dash.data && dash.data.awaitingReply > 0 && tab !== 'AWAITING_USER_REPLY' && (
        <button onClick={() => openTab('AWAITING_USER_REPLY')} className="card mb-6 flex w-full items-center gap-3 border-danger/30 bg-danger/5 p-4 text-start text-sm">
          <span className="text-danger"><Icon name="chat" size={20} /></span>
          <span className="flex-1">{t('me.tabs.awaiting')} · {dash.data.awaitingReply}</span>
          <span className="text-gold-ink">{t('common.seeAll')} {arrowNext(locale)}</span>
        </button>
      )}

      <div ref={listRef} className="scroll-mt-24"><Tabs value={tab} onChange={setTab} items={tabs} /></div>

      {tab === 'INTERPRETED' && items.length > 0 && (
        <div className="mt-4 flex justify-end"><DownloadButton label={t('reports.allMyDreams')} run={reportsApi.myDreamsPdf} /></div>
      )}

      <div className="mt-6">
        {list.isLoading ? (
          <div className="grid gap-4 md:grid-cols-2">{[1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-36" />)}</div>
        ) : list.isError ? (
          <ErrorBox onRetry={() => list.refetch()} />
        ) : items.length === 0 ? (
          <Empty text={t(`me.empty.${EMPTY_KEY[tab]}`)} action={tab === 'DRAFT' ? <Link to="/me/new" className="btn btn-sm btn-gold">{t('me.nav.new')}</Link> : undefined} />
        ) : (
          <StaggerGroup className="grid gap-4 md:grid-cols-2" stagger={0.06}>
            {items.map((d) => <DreamRow key={d.id} d={d} selectable={tab === 'DRAFT' && !d.payment} selected={selected.includes(d.id)} onToggle={() => toggle(d.id)} />)}
          </StaggerGroup>
        )}
      </div>

      {tab === 'DRAFT' && selected.length > 0 && (
        <div className="fixed inset-x-4 bottom-20 z-40 mx-auto flex max-w-3xl items-center justify-between gap-4 rounded-xl2 bg-night p-4 text-pearl shadow-calm md:bottom-6">
          <div className="text-sm">
            {need > 0 ? <span className="text-gold">{t('me.needMore', { n: need })}</span> : t('me.submitHint', { n: selected.length, left: credits - selected.length })}
          </div>
          <Button loading={submit.isPending} onClick={() => (need > 0 ? navigate(`/me/packages?dreams=${selected.join(',')}`) : once(() => submit.mutateAsync({ dreamIds: selected })))}>{t('me.submitSelected', { n: selected.length })}</Button>
        </div>
      )}
    </PageEnter>
  )
}
