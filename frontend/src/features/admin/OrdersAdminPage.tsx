import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Input, Skeleton, StatusBadge } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { fmtDate, fmtMoney } from '@/lib/utils'
import { InstapaySettingsCard, InstapayTransfersPanel } from './InstapayAdmin'

export function OrdersAdminPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [search, setSearch] = useState('')
  const [term, setTerm] = useState('')
  // the server searches as the interpreter types (a short pause first)
  useEffect(() => { const id = window.setTimeout(() => setTerm(search.trim()), 300); return () => window.clearTimeout(id) }, [search])
  const q = useQuery({ queryKey: ['admin', 'orders', term], queryFn: () => adminApi.orders(0, 100, term), placeholderData: keepPreviousData })
  const exportCsv = async () => {
    const rows = []
    for (let page = 0; ; page++) {
      const res = await adminApi.orders(page, 100)
      rows.push(...res.items)
      if (res.items.length === 0 || rows.length >= res.total) break
    }
    const csv = ['orderNumber,id,user,package,amount,currency,status,provider,ref,country,createdAt,paidAt', ...rows.map((o) => [o.orderNumber, o.id, o.userName, o.packageName, o.amount, o.currency, o.status, o.provider, o.providerRef ?? '', o.countryCode, o.createdAt, o.paidAt ?? ''].map((x) => `"${String(x).replace(/"/g, '""')}"`).join(','))].join('\n')
    const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv' })); a.download = 'orders.csv'; a.click()
  }
  return (
    <PageEnter>
      <div className="mb-6 flex items-center justify-between"><h1 className="font-display text-4xl">{t('admin.orders.title')}</h1><Button size="sm" variant="ghost" onClick={exportCsv}>{t('admin.orders.export')}</Button></div>
      <div className="mb-8 space-y-6">
        <InstapayTransfersPanel />
        <InstapaySettingsCard />
      </div>
      <div className="relative mb-3 max-w-md">
        <span className="pointer-events-none absolute inset-y-0 start-3 flex items-center text-fg-dim"><Icon name="search" size={18} /></span>
        <Input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t('admin.orders.search')} aria-label={t('admin.orders.search')} className="ps-10" />
      </div>
      {q.isLoading ? <Skeleton className="h-64" /> : (
        <div className="card overflow-x-auto p-0"><table className="w-full text-sm">
          <thead className="text-xs text-fg-dim"><tr className="border-b border-line">{[t('admin.orders.number'), t('admin.orders.user'), t('admin.orders.package'), t('admin.orders.amount'), t('admin.orders.status'), t('admin.orders.provider'), t('common.country'), t('admin.orders.date')].map((h) => <th key={h} className="px-4 py-3 text-start font-normal">{h}</th>)}</tr></thead>
          <tbody>{q.data!.items.map((o) => <tr key={o.id} className="border-b border-line last:border-0 hover:bg-surface-2/60"><td className="whitespace-nowrap px-4 py-3 font-mono text-xs font-medium" dir="ltr">{o.orderNumber}</td><td className="px-4 py-3">{o.userName}</td><td className="px-4 py-3">{o.packageName}</td><td className="px-4 py-3">{fmtMoney(o.amount, o.currency, locale)}</td><td className="px-4 py-3"><StatusBadge status={o.status} kind="order" /></td><td className="px-4 py-3 text-xs" dir="ltr">{o.provider} {o.providerRef ?? ''}</td><td className="px-4 py-3">{o.countryCode}</td><td className="px-4 py-3 text-xs text-fg-dim">{fmtDate(o.createdAt, locale, true)}</td></tr>)}
            {q.data!.items.length === 0 && <tr><td colSpan={8} className="px-4 py-8 text-center text-fg-muted">{t('admin.orders.empty')}</td></tr>}
          </tbody>
        </table></div>
      )}
    </PageEnter>
  )
}
