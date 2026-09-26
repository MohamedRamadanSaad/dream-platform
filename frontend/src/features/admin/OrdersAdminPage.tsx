import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Skeleton, StatusBadge } from '@/components/ui'
import { PageEnter } from '@/components/motion'
import { fmtDate, fmtMoney } from '@/lib/utils'

export function OrdersAdminPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const q = useQuery({ queryKey: ['admin', 'orders'], queryFn: () => adminApi.orders(0, 100) })
  const exportCsv = () => {
    const rows = q.data?.items ?? []
    const csv = ['id,user,package,amount,currency,status,provider,ref,country,createdAt,paidAt', ...rows.map((o) => [o.id, o.userName, o.packageName, o.amount, o.currency, o.status, o.provider, o.providerRef ?? '', o.countryCode, o.createdAt, o.paidAt ?? ''].map((x) => `"${String(x).replace(/"/g, '""')}"`).join(','))].join('\n')
    const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv' })); a.download = 'orders.csv'; a.click()
  }
  return (
    <PageEnter>
      <div className="mb-6 flex items-center justify-between"><h1 className="font-display text-4xl">{t('admin.orders.title')}</h1><Button size="sm" variant="ghost" onClick={exportCsv}>{t('admin.orders.export')}</Button></div>
      {q.isLoading ? <Skeleton className="h-64" /> : (
        <div className="card overflow-x-auto p-0"><table className="w-full text-sm">
          <thead className="text-xs text-fg-dim"><tr className="border-b border-line">{['المستخدم', 'الباقة', 'المبلغ', 'الحالة', 'المزوّد', t('common.country'), 'التاريخ'].map((h) => <th key={h} className="px-4 py-3 text-start font-normal">{h}</th>)}</tr></thead>
          <tbody>{q.data!.items.map((o) => <tr key={o.id} className="border-b border-line last:border-0 hover:bg-surface-2/60"><td className="px-4 py-3">{o.userName}</td><td className="px-4 py-3">{o.packageName}</td><td className="px-4 py-3">{fmtMoney(o.amount, o.currency, locale)}</td><td className="px-4 py-3"><StatusBadge status={o.status} kind="order" /></td><td className="px-4 py-3 text-xs" dir="ltr">{o.provider} {o.providerRef ?? ''}</td><td className="px-4 py-3">{o.countryCode}</td><td className="px-4 py-3 text-xs text-fg-dim">{fmtDate(o.createdAt, locale, true)}</td></tr>)}</tbody>
        </table></div>
      )}
    </PageEnter>
  )
}
