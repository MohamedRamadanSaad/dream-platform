import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { checkoutApi, checkoutMockApi, publicApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Input, Label, Skeleton } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { PackageCard } from '@/features/public/LandingPage'
import { arrowBack, fmtMoney } from '@/lib/utils'
import { ApiError } from '@/api/client'
import { CreditExpiryNotice } from './CreditExpiryNotice'
import type { PackageDto } from '@/api/types'

export function PackagesPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const dreams = params.get('dreams')
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['public', 'catalog'], queryFn: publicApi.catalog })
  const choose = (p: PackageDto) => navigate(`/me/checkout?package=${p.id}${dreams ? `&dreams=${dreams}` : ''}`)
  return (
    <PageEnter>
      <h1 className="font-display text-4xl">{t('packages.title')}</h1>
      <p className="mb-2 text-sm font-light text-fg-muted">{t('packages.lead')}</p>
      {data && <div className="chip mb-8 border border-line bg-surface text-xs text-fg-muted"><Icon name="globe" size={14} />{t('packages.shownFor', { country: data.countryName })}</div>}
      <CreditExpiryNotice className="mb-6" />
      {dreams && <div className="card mb-6 border-gold/40 bg-gold/5 p-4 text-sm">{t('me.needMore', { n: dreams.split(',').length })}</div>}
      {isLoading ? <div className="grid gap-6 md:grid-cols-3">{[1, 2, 3].map((i) => <Skeleton key={i} className="h-80" />)}</div>
        : isError ? <ErrorBox onRetry={() => refetch()} />
        : <StaggerGroup className="grid gap-6 md:grid-cols-3">{data!.packages.map((p, i) => <PackageCard key={p.id} p={p} featured={i === 1} onChoose={choose} />)}</StaggerGroup>}
    </PageEnter>
  )
}

export function CheckoutPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const locale = useAuthStore((s) => s.locale)
  const packageId = params.get('package') ?? ''
  const dreamIds = params.get('dreams')?.split(',').filter(Boolean)
  const [coupon, setCoupon] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const { data: catalog } = useQuery({ queryKey: ['public', 'catalog'], queryFn: publicApi.catalog })
  const pkg = catalog?.packages.find((p) => p.id === packageId)
  const create = useMutation({
    mutationFn: () => checkoutApi.create({ packageId, couponCode: coupon || undefined, dreamIds }),
    onSuccess: (r) => { window.location.href = r.checkoutUrl.startsWith('http') ? r.checkoutUrl : r.checkoutUrl + `?return=${encodeURIComponent('/me/payments')}` },
    onError: (e) => setErr((e as ApiError).message),
  })
  if (!packageId) { navigate('/me/packages'); return null }
  return (
    <PageEnter className="mx-auto max-w-lg">
      <Link to="/me/packages" className="text-sm text-fg-muted">{arrowBack(locale)} {t('common.back')}</Link>
      <h1 className="mt-2 font-display text-4xl">{t('me.checkout.title')}</h1>
      <p className="mb-6 text-sm font-light text-fg-muted"><Icon name="shield" size={14} className="inline" /> {t('me.checkout.secure')}</p>
      {!pkg ? <Skeleton className="h-40" /> : (
        <div className="card p-6">
          <div className="flex items-center justify-between"><div><div className="font-display text-2xl">{pkg.name}</div><div className="text-xs text-fg-dim">{t('packages.dreams', { count: pkg.credits })}</div></div><div className="text-3xl font-medium">{fmtMoney(pkg.price, pkg.currency, locale)}</div></div>
          {dreamIds?.length ? <div className="mt-3 text-xs text-fg-muted">{t('me.checkout.autoSubmit', { count: dreamIds.length, n: dreamIds.length })}</div> : null}
          <div className="mt-6"><Label>{t('me.checkout.coupon')}</Label><div className="flex gap-2"><Input dir="ltr" value={coupon} onChange={(e) => setCoupon(e.target.value.toUpperCase())} placeholder="CODE" /></div></div>
          {err && <p className="mt-3 text-sm text-danger">{err}</p>}
          <Button className="mt-6 w-full" size="lg" loading={create.isPending} onClick={() => { setErr(null); create.mutate() }}>{t('me.checkout.pay', { amount: fmtMoney(pkg.price, pkg.currency, locale), currency: '' })}</Button>
          <p className="mt-3 text-center text-xs text-fg-dim">{pkg.currency === 'EGP' ? t('me.checkout.methodsEg') : t('me.checkout.methods')}</p>
        </div>
      )}
    </PageEnter>
  )
}

/** Stands in for the provider's hosted checkout while mocks are on. */
export function MockCheckoutPage() {
  const { t } = useTranslation()
  const { orderId } = useParams()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [tries, setTries] = useState(0)
  const order = useQuery({ queryKey: ['order', orderId], queryFn: () => checkoutApi.order(orderId!), refetchInterval: (q) => (q.state.data?.status === 'SUCCESS' ? false : 1500) })
  useEffect(() => { if (order.data?.status !== 'SUCCESS') setTries((x) => x + 1) }, [order.dataUpdatedAt, order.data?.status])
  useEffect(() => { if (order.data?.status === 'SUCCESS') { qc.invalidateQueries(); const id = setTimeout(() => navigate('/me'), 1800); return () => clearTimeout(id) } }, [order.data?.status, navigate, qc])
  const ok = order.data?.status === 'SUCCESS'
  const trigger = useMutation({ mutationFn: (success: boolean) => checkoutMockApi.trigger(orderId!, success), onSuccess: () => qc.invalidateQueries({ queryKey: ['order', orderId] }) })
  return (
    <div className="flex min-h-screen items-center justify-center bg-night p-5 text-pearl">
      <div className="card w-full max-w-md bg-surface p-8 text-center text-fg">
        <div className="mb-3 text-fg-dim text-xs">{t('me.checkout.mockProvider')}</div>
        {ok ? (
          <><div className="mx-auto mb-3 text-success"><Icon name="check" size={40} active /></div><div className="text-lg text-success">{t('me.checkout.success')}</div></>
        ) : (
          <><div className="mx-auto mb-3 h-8 w-8 animate-spin rounded-full border-2 border-gold border-t-transparent" /><div className="text-sm text-fg-muted">{t('me.checkout.waiting')} ({tries})</div><p className="mt-4 text-xs text-fg-dim">{t('me.checkout.mockNote')}</p>
            <div className="mt-6 flex flex-col gap-2">
              <Button onClick={() => trigger.mutate(true)} loading={trigger.isPending}>{t('me.checkout.mockPay')}</Button>
              <Button variant="ghost" onClick={() => trigger.mutate(false)} disabled={trigger.isPending}>{t('me.checkout.mockFail')}</Button>
              {trigger.isError && <ErrorBox message={(trigger.error as Error).message} />}
            </div></>
        )}
      </div>
    </div>
  )
}
