import { useEffect, useMemo, useState, type KeyboardEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useErrorText } from '@/lib/apiErrors'
import { useAuthStore } from '@/app/auth-store'
import { Button, Empty, ErrorBox, Input, Skeleton, StatusBadge, Tabs } from '@/components/ui'
import { Pager, Chevron } from '@/components/ui/Pager'
import { Avatar } from '@/components/ui/Avatar'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { cn, flagEmoji, fmtDate, fmtMoney, fmtNum, timeAgo } from '@/lib/utils'
import type { AdminOrderFilters, AdminOrderRow, OrderScope } from '@/api/types'
import { OrderFilterBar, activeCount, fitToTab, readFilters, writeFilters, type OrdersTab } from './OrderFilters'
import { hoursLeft } from '@/lib/utils'

type Tab = OrdersTab
const TABS: Tab[] = ['REVIEW', 'DOMESTIC', 'INTERNATIONAL', 'UNPAID']
const PAGE_SIZE = 20

/** A payment the buyer sent again after the interpreter undid her approval: worth a second look before approving. */
const resentAfterUndo = (o: { revokedAt?: string | null; status: string }) => !!o.revokedAt && o.status === 'PENDING_REVIEW'

export function ResentChip() {
  const { t } = useTranslation()
  return <span className="chip bg-danger/10 font-medium text-danger"><Icon name="undo" size={12} flipRtl />{t('admin.orders.resentAfterUndo')}</span>
}

/** "Expires in 5 hours" for a booking still open, "Expired" once past (or EXPIRED). */
function ExpiryChip({ o }: { o: AdminOrderRow }) {
  const { t } = useTranslation()
  const h = o.expiresAt ? hoursLeft(o.expiresAt) : null
  if (o.status === 'EXPIRED' || (h != null && h <= 0)) return <span className="chip bg-surface-2 text-fg-dim"><span className="h-1.5 w-1.5 rounded-full bg-current" />{t('admin.orders.expired')}</span>
  if (h == null) return null
  const span = h < 48 ? t('common.hourCount', { count: Math.max(1, Math.round(h)) }) : t('common.dayCount', { count: Math.round(h / 24) })
  return <span className={cn('chip font-medium', h < 6 ? 'bg-warn/10 text-warn' : 'bg-info/10 text-info')}><span className="h-1.5 w-1.5 rounded-full bg-current" />{t('admin.orders.expiresIn', { x: span })}</span>
}

/** CSV of the orders of the current tab and filters, fetched page by page. Failure reasons in the page's language. */
async function exportCsv(t: TFunction, scope: OrderScope, q: string, filters: AdminOrderFilters) {
  const rows: AdminOrderRow[] = []
  for (let page = 0; ; page++) {
    const res = await adminApi.orders(scope, page, 100, q, filters)
    rows.push(...res.items)
    if (res.items.length === 0 || rows.length >= res.total) break
  }
  const head = 'orderNumber,id,user,email,package,amount,currency,settledEgp,status,failureReason,resentAfterUndo,revokedAt,provider,ref,transferRef,sender,country,createdAt,paidAt'
  const failure = (r?: string | null) => (!r ? '' : r === 'TRANSFER_REJECTED' || r === 'APPROVAL_REVOKED' ? t(`admin.order.failure.${r}`) : t('admin.order.failure.other', { reason: r }))
  const csv = [head, ...rows.map((o) => [o.orderNumber, o.id, o.userName, o.userEmail, o.packageName, o.amount, o.currency, o.settledAmountEgp ?? '', o.status, failure(o.failureReason), resentAfterUndo(o) ? t('admin.orders.resentAfterUndo') : '', o.revokedAt ?? '', o.provider, o.providerRef ?? '', o.transferReference ?? '', o.transferSenderName ?? '', o.countryCode, o.createdAt, o.paidAt ?? ''].map((x) => `"${String(x).replace(/"/g, '""')}"`).join(','))].join('\n')
  const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv' })); a.download = 'orders.csv'; a.click()
}

/** Amount in the buyer's currency, with the settled EGP under it when the order was not paid in EGP. */
function Amount({ o }: { o: AdminOrderRow }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  return (
    <span className="flex flex-col">
      <span dir="ltr" className="text-start font-medium tabular-nums">{fmtMoney(o.amount, o.currency, locale)}</span>
      {o.currency !== 'EGP' && o.settledAmountEgp != null && <span className="whitespace-nowrap text-[11px] text-fg-dim" title={`${t('admin.order.settled')} · ${t('admin.order.fxEstimate')}`}>{t('admin.orders.settled')} <span dir="ltr">≈ {fmtMoney(o.settledAmountEgp, 'EGP', locale)}</span></span>}
    </span>
  )
}

export function OrdersAdminPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const fromUrl = params.get('tab') as Tab | null
  const tab: Tab = fromUrl && TABS.includes(fromUrl) ? fromUrl : 'REVIEW'
  const page = Math.max(0, Number(params.get('page') ?? 1) - 1) || 0
  const [exporting, setExporting] = useState(false)
  // order-number search inside the current tab; the server searches after a short pause in typing
  const [search, setSearch] = useState(params.get('q') ?? '')
  const term = params.get('q') ?? ''
  useEffect(() => {
    const id = window.setTimeout(() => {
      const v = search.trim()
      if (v !== term) setParams((p) => { const n = new URLSearchParams(p); if (v) n.set('q', v); else n.delete('q'); n.delete('page'); return n }, { replace: true })
    }, 300)
    return () => window.clearTimeout(id)
  }, [search]) // eslint-disable-line react-hooks/exhaustive-deps

  const filters = useMemo(() => readFilters(params), [params])
  const fkey = JSON.stringify(filters)
  const counts = useQuery({ queryKey: ['admin', 'orders', 'counts', fkey, term], queryFn: () => adminApi.orderCounts(filters, term), refetchInterval: 60_000, placeholderData: keepPreviousData })
  const q = useQuery({ queryKey: ['admin', 'orders', tab, page, term, fkey], queryFn: () => adminApi.orders(tab, page, PAGE_SIZE, term, filters), placeholderData: keepPreviousData })
  const errorText = useErrorText('admin.orders.errors')
  // a new history entry per tab and per filter change: Back returns to the view before
  const setTab = (v: Tab) => { if (v !== tab) setParams((p) => { const n = writeFilters(p, fitToTab(readFilters(p), v)); n.set('tab', v); return n }) }
  const setFilters = (f: AdminOrderFilters) => setParams((p) => writeFilters(p, f))
  const setPage = (v: number) => {
    setParams((p) => { const n = new URLSearchParams(p); if (v > 0) n.set('page', String(v + 1)); else n.delete('page'); return n })
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  const c = counts.data
  const items = q.data?.items ?? []
  const pages = Math.max(1, Math.ceil((q.data?.total ?? 0) / PAGE_SIZE))
  const open = (id: string) => navigate(`/admin/orders/${id}`)
  const onRowKey = (e: KeyboardEvent, id: string) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(id) } }
  const review = tab === 'REVIEW'
  const unpaid = tab === 'UNPAID'

  return (
    <PageEnter>
      <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-4xl">{t('admin.orders.title')}</h1>
          <p className="mt-1 text-sm font-light text-fg-muted">{t('admin.orders.lead')}</p>
        </div>
        <Button size="sm" variant="ghost" loading={exporting} onClick={async () => { setExporting(true); try { await exportCsv(t, tab, term, filters) } finally { setExporting(false) } }}>
          <Icon name="download" size={15} />{t('admin.orders.export')}
        </Button>
      </div>

      <Tabs value={tab} onChange={setTab} items={[
        { value: 'REVIEW', label: t('admin.orders.tabs.REVIEW'), count: c ? fmtNum(c.review, locale) : undefined, tone: c?.review ? 'danger' : undefined },
        { value: 'DOMESTIC', label: t('admin.orders.tabs.DOMESTIC'), count: c ? fmtNum(c.domestic, locale) : undefined },
        { value: 'INTERNATIONAL', label: t('admin.orders.tabs.INTERNATIONAL'), count: c ? fmtNum(c.international, locale) : undefined },
        { value: 'UNPAID', label: t('admin.orders.tabs.UNPAID'), count: c?.unpaid != null ? fmtNum(c.unpaid, locale) : undefined },
      ]} />

      <OrderFilterBar tab={tab} filters={filters} onChange={setFilters} search={
        <div className="relative">
          <span className="pointer-events-none absolute inset-y-0 start-3 flex items-center text-fg-dim"><Icon name="search" size={18} /></span>
          <Input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t('admin.orders.search')} aria-label={t('admin.orders.search')} className="h-11 ps-10" />
        </div>
      } />

      <div className={cn('mt-4 transition-opacity', q.isPlaceholderData && 'opacity-60')}>
        {q.isLoading ? <div className="space-y-3">{[1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-20" />)}</div>
          : q.isError ? <ErrorBox message={errorText(q.error)} onRetry={() => q.refetch()} />
          : items.length === 0 ? <Empty text={term ? t('admin.orders.noMatch') : activeCount(filters) > 0 ? t('admin.orders.noFilterMatch') : t(`admin.orders.empty.${tab}`)} />
          : (
            <>
              {/* phones: one card per order */}
              <ul className="space-y-3 md:hidden">
                {items.map((o) => (
                  <li key={o.id}>
                    <Link to={`/admin/orders/${o.id}`} className={cn('card card-hover block p-4 outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gold', review && 'border-gold/40')}>
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex min-w-0 items-center gap-3">
                          <Avatar name={o.userName || o.userEmail} size={36} />
                          <div className="min-w-0">
                            <div className="font-mono text-sm font-semibold tracking-wide"><span dir="ltr">{o.orderNumber}</span></div>
                            <div className="truncate text-xs text-fg-muted">{o.userName || o.userEmail} · {o.packageName}</div>
                          </div>
                        </div>
                        <div className="shrink-0 text-end"><Amount o={o} /></div>
                      </div>
                      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2 text-xs text-fg-dim">
                        <StatusBadge status={o.status} kind="order" />
                        {resentAfterUndo(o) && <ResentChip />}
                        {unpaid && o.status === 'INITIATED' && <ExpiryChip o={o} />}
                        <span><span aria-hidden="true">{flagEmoji(o.countryCode)}</span> {o.countryCode}</span>
                        {o.attachedDreams > 0 && <span className="inline-flex items-center gap-1 text-gold-ink"><Icon name="moon" size={13} />{t('admin.orders.dreamsN', { count: o.attachedDreams, n: o.attachedDreams })}</span>}
                        <span className="ms-auto">{review && o.transferSubmittedAt ? timeAgo(o.transferSubmittedAt, locale) : fmtDate(o.createdAt, locale)}</span>
                      </div>
                      {review && o.transferReference && (
                        <div className="mt-3 flex items-center justify-between gap-2 border-t border-line pt-3 text-xs">
                          <span className="text-fg-dim">{t('admin.orders.reference')}</span>
                          <span dir="ltr" className="font-mono tracking-wider text-fg">{o.transferReference}</span>
                        </div>
                      )}
                    </Link>
                  </li>
                ))}
              </ul>

              {/* tablets and desktops: a table whose whole row opens the order */}
              <div className="card relative hidden overflow-x-auto p-0 md:block">
                <table className="w-full text-sm">
                  <thead className="text-xs text-fg-dim">
                    <tr className="border-b border-line">
                      {[t('admin.orders.number'), t('admin.orders.user'), t('admin.orders.package'), t('admin.orders.amount'), t('admin.orders.status'), review ? t('admin.orders.reference') : unpaid ? t('admin.orders.deadline') : t('admin.orders.provider'), t('admin.orders.dreams'), t('common.country'), review ? t('admin.orders.submitted') : t('admin.orders.date')].map((h) => <th key={h} scope="col" className="whitespace-nowrap px-3 py-3 text-start font-normal">{h}</th>)}
                      <th scope="col" className="w-8"><span className="sr-only">{t('admin.orders.open')}</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {items.map((o) => (
                      <tr key={o.id} tabIndex={0} role="link" aria-label={`${t('admin.orders.open')}: ${o.orderNumber}, ${o.userName || o.userEmail}, ${fmtMoney(o.amount, o.currency, locale)}`}
                        onClick={() => open(o.id)} onKeyDown={(e) => onRowKey(e, o.id)}
                        className="group cursor-pointer border-b border-line outline-none transition-colors last:border-0 hover:bg-surface-2/60 focus-visible:bg-gold/10 focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-gold">
                        <td dir="ltr" className="whitespace-nowrap px-3 py-3 text-start font-mono text-sm font-semibold tracking-wide">{o.orderNumber}</td>
                        <td className="px-3 py-3">
                          <div className="flex items-center gap-3">
                            <Avatar name={o.userName || o.userEmail} size={30} />
                            <div className="min-w-0">
                              <div className="max-w-[14rem] truncate font-medium">{o.userName || o.userEmail}</div>
                              <div dir="ltr" className="max-w-[14rem] truncate text-start text-[11px] text-fg-dim">{o.userEmail}</div>
                            </div>
                          </div>
                        </td>
                        <td className="px-3 py-3">{o.packageName}</td>
                        <td className="px-3 py-3"><Amount o={o} /></td>
                        <td className="px-3 py-3"><div className="flex flex-col items-start gap-1"><StatusBadge status={o.status} kind="order" />{resentAfterUndo(o) && <ResentChip />}</div></td>
                        <td className="px-3 py-3 text-xs">
                          {unpaid ? <ExpiryChip o={o} /> : review ? <span dir="ltr" className="font-mono tracking-wider">{o.transferReference ?? ''}</span>
                            : <span dir="ltr" className="inline-block text-start">{o.provider}{o.providerRef ? <span className="block max-w-[10rem] truncate text-[11px] text-fg-dim">{o.providerRef}</span> : null}</span>}
                        </td>
                        <td className="px-3 py-3">{o.attachedDreams > 0 ? <span className="inline-flex items-center gap-1 text-gold-ink"><Icon name="moon" size={13} />{fmtNum(o.attachedDreams, locale)}</span> : <span className="text-fg-dim">0</span>}</td>
                        <td className="whitespace-nowrap px-3 py-3"><span aria-hidden="true">{flagEmoji(o.countryCode)}</span> {o.countryCode}</td>
                        <td className="whitespace-nowrap px-3 py-3 text-xs text-fg-dim"><span title={fmtDate(o.createdAt, locale, true)}>{review && o.transferSubmittedAt ? timeAgo(o.transferSubmittedAt, locale) : fmtDate(o.createdAt, locale)}</span></td>
                        <td className="px-2 py-3 text-fg-dim group-hover:text-gold-ink"><Chevron next /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <Pager className="mt-4" page={page} pages={pages} onChange={setPage} label={t('admin.orders.pager')} disabled={q.isFetching} />
            </>
          )}
      </div>
    </PageEnter>
  )
}
