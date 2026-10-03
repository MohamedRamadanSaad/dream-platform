import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation } from 'react-router-dom'
import { meApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { ErrorBox, Skeleton, StatusBadge } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { arrowNext, fmtDate, fmtMoney } from '@/lib/utils'
import { CreditExpiryNotice } from './CreditExpiryNotice'
import { toast } from '@/components/ui/Toaster'

/** The order id is the invoice number (same as in the receipt e-mail); one tap copies it for a support e-mail. */
function InvoiceNo({ id }: { id: string }) {
  const { t } = useTranslation()
  const copy = async () => {
    try { await navigator.clipboard.writeText(id); toast.success(t('me.payments.copied')) } catch { /* clipboard blocked: the number stays selectable */ }
  }
  return (
    <div className="mt-1 flex min-w-0 items-center gap-1.5 text-xs text-fg-dim">
      <span className="shrink-0">{t('me.payments.invoiceNo')}:</span>
      <span dir="ltr" className="select-all truncate font-mono text-[11px] text-fg-muted">{id}</span>
      <button type="button" onClick={() => void copy()} aria-label={t('me.payments.copy')} title={t('me.payments.copy')} className="shrink-0 rounded-md p-1 text-fg-dim transition-colors hover:bg-surface-2 hover:text-fg">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V6a2 2 0 0 1 2-2h9" /></svg>
      </button>
    </div>
  )
}

export function PaymentsPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const credits = useQuery({ queryKey: ['me', 'credits'], queryFn: meApi.credits })
  const orders = useQuery({ queryKey: ['me', 'orders'], queryFn: () => meApi.orders(0, 50) })
  const reason = (k: string) => t(`me.payments.reason.${k}`)
  // links from the dashboard open a section (#history, #ledger) once its data is on screen
  const { hash } = useLocation()
  const ready = !!credits.data && !!orders.data
  useEffect(() => {
    if (!hash || !ready) return
    document.getElementById(hash.slice(1))?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [hash, ready])
  return (
    <PageEnter className="space-y-6">
      <h1 className="font-display text-4xl">{t('me.payments.title')}</h1>
      <div className="card flex items-center justify-between bg-night p-6 text-pearl">
        <div><div className="text-xs text-pearl/60">{t('me.payments.balance')}</div><div className="font-display text-4xl">{credits.data ? t('me.credits', { count: credits.data.balance, n: credits.data.balance }) : '…'}</div></div>
        <div className="text-gold"><Icon name="star" size={40} active strokeWidth={1.2} /></div>
        <Link to="/me/packages" className="btn btn-sm btn-gold">{t('me.nav.packages')}</Link>
      </div>
      <CreditExpiryNotice />
      <section id="history" className="scroll-mt-24">
        <h2 className="mb-3 font-display text-2xl">{t('me.payments.history')}</h2>
        {orders.isLoading ? <Skeleton className="h-40" /> : orders.isError ? <ErrorBox onRetry={() => orders.refetch()} /> : (
          <div className="card divide-y divide-line p-0">
            {orders.data!.items.map((o) => (
              <div key={o.id} className="flex items-center justify-between gap-4 p-4">
                <div className="min-w-0"><div className="text-sm">{o.packageName} · {fmtMoney(o.amount, o.currency, locale)}</div><div className="text-xs text-fg-dim">{fmtDate(o.createdAt, locale, true)}</div><InvoiceNo id={o.id} /></div>
                <div className="text-end"><StatusBadge status={o.status} kind="order" />{(o.status === 'FAILED' || o.status === 'EXPIRED') && <div><Link to="/me/packages" className="text-xs text-gold-ink">{t('me.payments.retry')} {arrowNext(locale)}</Link></div>}</div>
              </div>
            ))}
            {orders.data!.items.length === 0 && <div className="p-6 text-center text-fg-muted">{t('common.none')}</div>}
          </div>
        )}
      </section>
      <section id="ledger" className="scroll-mt-24">
        <h2 className="mb-3 font-display text-2xl">{t('me.payments.ledger')}</h2>
        {credits.isLoading ? <Skeleton className="h-32" /> : (
          <div className="card divide-y divide-line p-0">
            {credits.data?.entries.map((e) => (
              <div key={e.id} className="flex items-center justify-between p-4 text-sm">
                <div><div>{reason(e.reason)}</div><div className="text-xs text-fg-dim">{fmtDate(e.createdAt, locale, true)}{e.dreamId && <> · <Link to={`/me/dreams/${e.dreamId}`} className="text-gold-ink">{t('me.detail.dream')}</Link></>}</div></div>
                <div className={e.delta > 0 ? 'text-success' : 'text-fg-muted'} dir="ltr">{e.delta > 0 ? '+' : ''}{e.delta}</div>
              </div>
            ))}
          </div>
        )}
      </section>
    </PageEnter>
  )
}
