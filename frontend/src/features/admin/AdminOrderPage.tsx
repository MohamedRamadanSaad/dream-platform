import { useId, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Label, NotFound, Skeleton, StatusBadge, type BadgeStatus } from '@/components/ui'
import { isNotFound } from '@/lib/apiErrors'
import { Avatar } from '@/components/ui/Avatar'
import { Chevron } from '@/components/ui/Pager'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { arrowBack, arrowNext, cn, flagEmoji, fmtDate, fmtMoney, regionName, timeAgo } from '@/lib/utils'
import type { AdminOrderDetail } from '@/api/types'
import { CopyButton, RevokeApprovalModal, TransferReviewModals } from './InstapayAdmin'

/** One "label: value" line of the summary card. */
function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 py-2.5">
      <dt className="shrink-0 text-sm text-fg-muted">{label}</dt>
      <dd className="min-w-0 text-end text-sm">{children}</dd>
    </div>
  )
}

/** A draft waiting on this payment reads "waiting for this payment" (or "payment rejected"), not "draft". */
function dreamBadge(status: AdminOrderDetail['dreams'][number]['status'], o: AdminOrderDetail): BadgeStatus {
  if (status !== 'DRAFT') return status
  if (o.status === 'PENDING_REVIEW') return 'PENDING_PAYMENT'
  if (o.status === 'FAILED' && o.failureReason === 'TRANSFER_REJECTED') return 'PAYMENT_REJECTED'
  return status
}

function DreamItem({ d, order }: { d: AdminOrderDetail['dreams'][number]; order: AdminOrderDetail }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [open, setOpen] = useState(false)
  const panel = useId()
  return (
    <li className="border-b border-line last:border-0">
      <button type="button" aria-expanded={open} aria-controls={panel} onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-3 px-5 py-4 text-start transition-colors hover:bg-surface-2/50 focus-visible:bg-gold/5 focus-visible:outline-none">
        <span className="flex min-w-0 flex-1 flex-col gap-1.5 sm:flex-row sm:items-center sm:gap-3">
          <StatusBadge status={dreamBadge(d.status, order)} audience="interpreter" />
          <span className="text-xs text-fg-dim">{fmtDate(d.submittedAt ?? d.createdAt, locale, true)}</span>
          <span className={cn('min-w-0 text-sm text-fg-muted sm:flex-1 sm:truncate', open && 'sm:invisible')}>{d.text.split('\n')[0]}</span>
        </span>
        <span className="sr-only">{open ? t('admin.order.hideText') : t('admin.order.showText')}</span>
        <span aria-hidden="true" className={cn('flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-line text-fg-muted transition-transform duration-300', open && 'rotate-180 border-gold text-gold-ink')}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M6 9l6 6 6-6" /></svg>
        </span>
      </button>
      <div id={panel} hidden={!open} className="px-5 pb-5">
        <p className="whitespace-pre-wrap rounded-xl bg-surface-2/60 p-4 leading-loose">{d.text}</p>
        {d.status !== 'DRAFT' && (
          <Link to={`/admin/dreams/${d.id}`} className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-gold-ink hover:underline dark:text-gold">
            {t('admin.order.openDream')} <span aria-hidden="true">{arrowNext(locale)}</span>
          </Link>
        )}
      </div>
    </li>
  )
}

export function AdminOrderPage() {
  const { t } = useTranslation()
  const { id } = useParams()
  const locale = useAuthStore((s) => s.locale)
  const q = useQuery({ queryKey: ['admin', 'order', id], queryFn: () => adminApi.order(id!), retry: (n, e) => !isNotFound(e) && n < 1 })
  const [mode, setMode] = useState<'approve' | 'reject' | null>(null)
  const [revoking, setRevoking] = useState(false)

  const back = <Link to="/admin/orders" className="inline-flex items-center gap-1 text-sm text-fg-muted hover:text-fg">{arrowBack(locale)} {t('admin.order.back')}</Link>
  if (q.isLoading) return <div className="space-y-4"><Skeleton className="h-6 w-32" /><Skeleton className="h-10 w-64" /><div className="grid gap-5 lg:grid-cols-3"><Skeleton className="h-80 lg:col-span-2" /><Skeleton className="h-64" /></div></div>
  if (isNotFound(q.error)) return <PageEnter><NotFound text={t('admin.order.notFound')} back={<Link to="/admin/orders" className="btn btn-sm btn-ghost">{arrowBack(locale)} {t('admin.order.back')}</Link>} /></PageEnter>
  if (q.isError || !q.data) return <div className="space-y-4">{back}<ErrorBox onRetry={() => q.refetch()} /></div>
  const o = q.data
  const failure = o.failureReason ? (o.failureReason === 'TRANSFER_REJECTED' || o.failureReason === 'APPROVAL_REVOKED' ? t(`admin.order.failure.${o.failureReason}`) : t('admin.order.failure.other', { reason: o.failureReason })) : null
  const revoked = o.status === 'FAILED' && o.failureReason === 'APPROVAL_REVOKED'
  // "Undo approval": every approved InstaPay order shows it; the server says whether it is still possible
  const approvedInstapay = o.provider === 'INSTAPAY' && o.status === 'SUCCESS'
  const heldDreams = o.dreams.filter((d) => d.status === 'IN_REVIEW' || d.status === 'IN_PROGRESS' || d.status === 'AWAITING_USER_REPLY').length
  const blocked = approvedInstapay && !o.canRevoke ? t(`admin.order.revokeBlocked.${o.revokeBlockedReason ?? 'other'}`) : null

  return (
    <PageEnter className="space-y-5">
      {back}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="text-xs text-fg-muted">{t('admin.order.title')}</div>
          <h1 className="flex items-center gap-1">
            <span dir="ltr" className="select-all font-mono text-3xl font-semibold tracking-wide md:text-4xl">{o.orderNumber}</span>
            <CopyButton text={o.orderNumber} label={t('admin.order.copyNumber')} />
          </h1>
        </div>
        <div className="flex flex-wrap items-center gap-2"><StatusBadge status={o.status} kind="order" />{failure && <span className="text-xs text-bad-ink">{failure}</span>}</div>
      </div>

      {revoked && (
        <div role="status" className="card flex items-start gap-3 border-warn/40 bg-warn/5 p-5">
          <span className="mt-0.5 shrink-0 text-warn"><Icon name="clock" size={22} /></span>
          <div className="min-w-0">
            <div className="font-medium">{t('admin.order.revokedBanner')}</div>
            <p className="mt-1 text-sm font-light leading-relaxed text-fg-muted">{t('admin.order.revokedBannerLead')}</p>
          </div>
        </div>
      )}

      <div className="grid items-start gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          {o.canReview && o.transfer && (
            <section aria-labelledby="review-title" className="card border-gold/50 p-5 md:p-6">
              <h2 id="review-title" className="flex items-center gap-2 font-display text-2xl"><span className="text-gold-ink"><Icon name="wallet" size={22} /></span>{t('admin.instapay.title')}</h2>
              <p className="mt-1 text-sm font-light text-fg-muted">{t('admin.order.reviewHint')}</p>
              <div className="mt-4 grid gap-4 sm:grid-cols-3">
                <div>
                  <div className="text-xs text-fg-dim">{t('admin.instapay.amount')}</div>
                  <div dir="ltr" className="text-start font-mono text-2xl font-semibold">{fmtMoney(o.amount, o.currency, locale)}</div>
                </div>
                <div>
                  <div className="text-xs text-fg-dim">{t('admin.instapay.reference')}</div>
                  <div className="flex items-center gap-1"><span dir="ltr" className="select-all font-mono text-lg tracking-wider">{o.transfer.reference}</span><CopyButton text={o.transfer.reference} label={t('admin.instapay.copyRef')} /></div>
                </div>
                <div>
                  <div className="text-xs text-fg-dim">{t('admin.instapay.sender')}</div>
                  <div dir="ltr" className="text-start">{o.transfer.senderName}</div>
                  <div className="text-[11px] text-fg-dim">{t('admin.instapay.submitted', { time: timeAgo(o.transfer.submittedAt, locale) })}</div>
                </div>
              </div>
              <div className="mt-5 flex flex-col gap-2 sm:flex-row">
                <Button className="min-h-11 sm:flex-1" onClick={() => setMode('approve')}><Icon name="check" size={16} />{t('admin.instapay.accept')}</Button>
                <Button variant="ghost" className="min-h-11 sm:w-40" onClick={() => setMode('reject')}>{t('admin.instapay.reject')}</Button>
              </div>
            </section>
          )}

          <section aria-labelledby="summary-title" className="card p-5 md:p-6">
            <h2 id="summary-title" className="sr-only">{t('admin.order.summary')}</h2>
            <Link to={`/admin/users/${o.user.id}`} className="group -m-2 mb-3 flex items-center gap-3 rounded-xl p-2 transition-colors hover:bg-surface-2/60">
              <Avatar name={o.user.name || o.user.email} size={44} />
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium group-hover:text-gold-ink">{o.user.name || o.user.email}</span>
                <span dir="ltr" className="block truncate text-start text-xs text-fg-dim">{o.user.email}</span>
                <span className="block text-xs text-fg-dim"><span aria-hidden="true">{flagEmoji(o.user.countryCode)}</span> {regionName(o.user.countryCode, locale)}{o.user.age != null && <> · {t('admin.order.age', { n: o.user.age })}</>}</span>
              </span>
              <span className="text-fg-dim group-hover:text-gold-ink" aria-hidden="true"><Chevron next /></span>
              <span className="sr-only">{t('admin.order.userProfile')}</span>
            </Link>
            <dl className="divide-y divide-line border-t border-line">
              <Row label={t('admin.order.package')}>{o.packageName}<div className="text-xs text-fg-dim">{t('admin.order.credits', { count: o.credits, n: o.credits })}</div></Row>
              <Row label={t('admin.order.amount')}><span dir="ltr" className="font-medium">{fmtMoney(o.amount, o.currency, locale)}</span></Row>
              <Row label={t('admin.order.settled')}>{o.settledAmountEgp != null ? (
                o.currency === 'EGP' ? <span dir="ltr">{fmtMoney(o.settledAmountEgp, 'EGP', locale)}</span>
                  : <><span dir="ltr" title={t('admin.order.fxEstimate')}>≈ {fmtMoney(o.settledAmountEgp, 'EGP', locale)}</span><div className="text-xs text-fg-dim">{t('admin.order.fxEstimate')}</div></>
              ) : <span className="text-fg-dim">{t('admin.order.notSettled')}</span>}</Row>
              {(o.discount > 0 || o.couponCode) && <Row label={t('admin.order.discount')}><span dir="ltr">{fmtMoney(o.discount, o.currency, locale)}</span>{o.couponCode && <div dir="ltr" className="font-mono text-xs text-fg-dim">{o.couponCode}</div>}</Row>}
              <Row label={t('admin.order.country')}><span aria-hidden="true">{flagEmoji(o.countryCode)}</span> {regionName(o.countryCode, locale)}{o.countrySource && <div className="text-xs text-fg-dim">{t('admin.order.countrySource')}</div>}</Row>
              <Row label={t('admin.order.provider')}><span dir="ltr">{o.provider}</span>{o.providerRef && <div dir="ltr" className="select-all break-all font-mono text-xs text-fg-dim">{o.providerRef}</div>}</Row>
              <Row label={t('admin.order.created')}>{fmtDate(o.createdAt, locale, true)}</Row>
              {o.paidAt && <Row label={t('admin.order.paidAt')}>{fmtDate(o.paidAt, locale, true)}</Row>}
              {!o.paidAt && o.expiresAt && o.status === 'INITIATED' && <Row label={t('admin.order.expiresAt')}>{fmtDate(o.expiresAt, locale, true)}</Row>}
            </dl>
          </section>

          <section aria-labelledby="dreams-title" className="card overflow-hidden p-0">
            <div className="px-5 pt-5 md:px-6">
              <h2 id="dreams-title" className="flex items-center gap-2 font-display text-2xl"><span className="text-gold-ink"><Icon name="moon" size={20} /></span>{t('admin.order.dreams')} <span className="text-base text-fg-dim">({o.dreams.length})</span></h2>
              <p className="mt-1 pb-4 text-sm font-light text-fg-muted">{t('admin.order.dreamsLead')}</p>
            </div>
            {o.dreams.length === 0 ? <p className="border-t border-line px-5 py-6 text-center text-sm text-fg-muted">{t('admin.order.dreamsEmpty')}</p> : (
              <ul className="border-t border-line">{o.dreams.map((d) => <DreamItem key={d.id} d={d} order={o} />)}</ul>
            )}
          </section>
        </div>

        <aside className="space-y-5">
          {approvedInstapay && (
            <section aria-labelledby="revoke-title" className="card p-5">
              <h2 id="revoke-title" className="font-medium">{t('admin.order.revokeSection')}</h2>
              <p className="mt-1 text-sm font-light leading-relaxed text-fg-muted">{t('admin.order.revokeHint')}</p>
              <Button variant="ghost" className="mt-4 min-h-11 w-full border-danger/60 text-danger hover:bg-danger/5" disabled={!o.canRevoke} aria-describedby={blocked ? 'revoke-blocked' : undefined} aria-haspopup="dialog" onClick={() => setRevoking(true)}>
                <Icon name="undo" size={16} flipRtl />{t('admin.order.revoke')}
              </Button>
              {blocked && <p id="revoke-blocked" className="mt-2 text-xs leading-relaxed text-bad-ink">{blocked}</p>}
            </section>
          )}
          <div className="card p-5">
            <Label>{t('admin.order.transfer')}</Label>
            {o.transfer ? (
              <dl className="divide-y divide-line">
                <Row label={t('admin.instapay.reference')}><span className="inline-flex items-center gap-1"><span dir="ltr" className="select-all font-mono tracking-wider">{o.transfer.reference}</span><CopyButton text={o.transfer.reference} label={t('admin.instapay.copyRef')} /></span></Row>
                <Row label={t('admin.instapay.sender')}><span dir="ltr">{o.transfer.senderName}</span></Row>
                <Row label={t('admin.order.submitted')}>{fmtDate(o.transfer.submittedAt, locale, true)}</Row>
                {o.transfer.reviewedAt && <Row label={t('admin.order.reviewed')}>{fmtDate(o.transfer.reviewedAt, locale, true)}{o.transfer.reviewedByName && <div className="text-xs text-fg-dim">{t('admin.order.reviewedBy', { name: o.transfer.reviewedByName })}</div>}</Row>}
                {o.transfer.reviewNote && <div className="py-2.5"><div className="text-sm text-fg-muted">{t('admin.order.note')}</div><p className="mt-1 whitespace-pre-wrap text-sm">{o.transfer.reviewNote}</p></div>}
              </dl>
            ) : <p className="text-sm text-fg-muted">{t('admin.order.noTransfer')}</p>}
          </div>
          <Link to={`/admin/users/${o.user.id}`} className="card card-hover flex items-center gap-3 p-5 text-sm"><span className="text-gold-ink"><Icon name="user" size={22} /></span>{t('admin.order.userProfile')} <span aria-hidden="true">{arrowNext(locale)}</span></Link>
        </aside>
      </div>

      {approvedInstapay && <RevokeApprovalModal orderId={o.id} orderNumber={o.orderNumber} heldDreams={heldDreams} open={revoking} onClose={() => setRevoking(false)} />}
      {o.transfer && <TransferReviewModals orderId={o.id} orderNumber={o.orderNumber} amount={o.amount} currency={o.currency} reference={o.transfer.reference} mode={mode} onClose={() => setMode(null)} />}
    </PageEnter>
  )
}
