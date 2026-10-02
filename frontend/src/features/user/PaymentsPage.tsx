import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { meApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { ErrorBox, Skeleton, StatusBadge } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { arrowNext, fmtDate, fmtMoney } from '@/lib/utils'

export function PaymentsPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const credits = useQuery({ queryKey: ['me', 'credits'], queryFn: meApi.credits })
  const orders = useQuery({ queryKey: ['me', 'orders'], queryFn: () => meApi.orders(0, 50) })
  const reason = (k: string) => t(`me.payments.reason.${k}`)
  return (
    <PageEnter className="space-y-6">
      <h1 className="font-display text-4xl">{t('me.payments.title')}</h1>
      <div className="card flex items-center justify-between bg-night p-6 text-pearl">
        <div><div className="text-xs text-pearl/60">{t('me.payments.balance')}</div><div className="font-display text-4xl">{credits.data ? t('me.credits', { count: credits.data.balance, n: credits.data.balance }) : '…'}</div></div>
        <div className="text-gold"><Icon name="star" size={40} active strokeWidth={1.2} /></div>
        <Link to="/me/packages" className="btn btn-sm btn-gold">{t('me.nav.packages')}</Link>
      </div>
      <section>
        <h2 className="mb-3 font-display text-2xl">{t('me.payments.history')}</h2>
        {orders.isLoading ? <Skeleton className="h-40" /> : orders.isError ? <ErrorBox onRetry={() => orders.refetch()} /> : (
          <div className="card divide-y divide-line p-0">
            {orders.data!.items.map((o) => (
              <div key={o.id} className="flex items-center justify-between gap-4 p-4">
                <div className="min-w-0"><div className="text-sm">{o.packageName} · {fmtMoney(o.amount, o.currency, locale)}</div><div className="text-xs text-fg-dim" dir="ltr">{o.providerRef ?? o.id} · {fmtDate(o.createdAt, locale, true)}</div></div>
                <div className="text-end"><StatusBadge status={o.status} kind="order" />{(o.status === 'FAILED' || o.status === 'EXPIRED') && <div><Link to="/me/packages" className="text-xs text-gold-ink">{t('me.payments.retry')} {arrowNext(locale)}</Link></div>}</div>
              </div>
            ))}
            {orders.data!.items.length === 0 && <div className="p-6 text-center text-fg-muted">{t('common.none')}</div>}
          </div>
        )}
      </section>
      <section>
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
