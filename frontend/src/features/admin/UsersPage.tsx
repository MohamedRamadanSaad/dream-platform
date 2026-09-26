import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Empty, ErrorBox, Input, Label, Modal, Segmented, Skeleton, Stars, StatusBadge, Tabs, Textarea } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { fmtDate, fmtMoney, fmtNum, timeAgo } from '@/lib/utils'
import type { UserListKind } from '@/api/types'

const LISTS: UserListKind[] = ['all', 'top-visits', 'top-paying', 'has-drafts', 'high-rating', 'low-rating']

export function UsersPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [params, setParams] = useSearchParams()
  const list = (params.get('list') as UserListKind) || 'all'
  const [q, setQ] = useState('')
  const users = useQuery({ queryKey: ['admin', 'users', list, q], queryFn: () => adminApi.users(list, 0, 50, q) })
  return (
    <PageEnter>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3"><h1 className="font-display text-4xl">{t('admin.users.title')}</h1><Input placeholder={t('admin.users.search')} value={q} onChange={(e) => setQ(e.target.value)} className="w-64 py-2 text-sm" /></div>
      <Segmented className="mb-6 flex-wrap" value={list} onChange={(v) => setParams({ list: v })} items={LISTS.map((l) => ({ value: l, label: t(`admin.users.lists.${l}`) }))} />
      {users.isLoading ? <Skeleton className="h-64" /> : users.isError ? <ErrorBox onRetry={() => users.refetch()} /> : !users.data?.items.length ? <Empty text={t('common.none')} /> : (
        <div className="card overflow-x-auto p-0">
          <table className="w-full text-sm">
            <thead className="text-xs text-fg-dim"><tr className="border-b border-line">{['', t('common.country'), t('admin.users.visits'), t('admin.users.dreams'), t('admin.users.drafts'), t('admin.users.paid'), t('admin.users.rating'), t('admin.users.lastSeen')].map((h, i) => <th key={i} className="px-4 py-3 text-start font-normal">{h}</th>)}</tr></thead>
            <tbody>
              {users.data.items.map((u) => (
                <tr key={u.id} className="border-b border-line last:border-0 hover:bg-surface-2/60">
                  <td className="px-4 py-3"><Link to={`/admin/users/${u.id}`} className="font-medium hover:text-gold-deep">{u.name || u.email}</Link><div className="text-xs text-fg-dim" dir="ltr">{u.email}</div></td>
                  <td className="px-4 py-3">{u.countryCode}</td><td className="px-4 py-3">{fmtNum(u.visits, locale)}</td><td className="px-4 py-3">{fmtNum(u.dreams, locale)}</td>
                  <td className="px-4 py-3">{u.drafts > 0 ? <span className="chip bg-warn/10 text-warn">{u.drafts}</span> : '—'}</td>
                  <td className="px-4 py-3">{fmtMoney(Math.round(u.totalPaidBase), 'USD', locale)}</td>
                  <td className="px-4 py-3">{u.avgRating !== null ? <Stars value={Math.round(u.avgRating)} size={12} /> : '—'}</td>
                  <td className="px-4 py-3 text-xs text-fg-dim">{timeAgo(u.lastSeenAt, locale)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </PageEnter>
  )
}

export function User360Page() {
  const { t } = useTranslation()
  const { id } = useParams()
  const qc = useQueryClient()
  const locale = useAuthStore((s) => s.locale)
  const q = useQuery({ queryKey: ['admin', 'user', id], queryFn: () => adminApi.user(id!) })
  const [tab, setTab] = useState<'dreams' | 'orders' | 'credits' | 'testimonials'>('dreams')
  const [notes, setNotes] = useState<string | null>(null)
  const [tags, setTags] = useState<string | null>(null)
  const [creditModal, setCreditModal] = useState(false)
  const [delta, setDelta] = useState(1)
  const [reason, setReason] = useState('')
  const saveNotes = useMutation({ mutationFn: () => adminApi.saveUserNotes(id!, notes ?? q.data?.notes ?? '', (tags ?? q.data?.tags.join(',') ?? '').split(',').map((x) => x.trim()).filter(Boolean)), onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'user', id] }) })
  const addCredits = useMutation({ mutationFn: () => adminApi.adjustCredits(id!, delta, reason), onSuccess: () => { setCreditModal(false); qc.invalidateQueries({ queryKey: ['admin', 'user', id] }) } })
  if (q.isLoading) return <Skeleton className="h-96" />
  if (q.isError || !q.data) return <ErrorBox onRetry={() => q.refetch()} />
  const u = q.data
  const reasonL: Record<string, string> = { PURCHASE: 'شراء', SUBMIT: 'تقديم', REFUND: 'استرجاع', MANUAL: 'يدوي', BONUS: 'هدية' }
  return (
    <PageEnter className="space-y-5">
      <Link to="/admin/users" className="text-sm text-fg-muted">→ {t('common.back')}</Link>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div><h1 className="font-display text-4xl">{u.name || u.email}</h1><div className="text-sm text-fg-muted" dir="ltr">{u.email}</div>
          <div className="mt-2 flex flex-wrap gap-2 text-xs text-fg-dim"><span>{u.countryCode}</span><span>· {u.gender ? t(u.gender === 'FEMALE' ? 'auth.female' : 'auth.male') : '—'}</span><span>· {u.locale}</span><span>· {u.providers.join(', ')}</span><span>· {t('admin.users.since')} {fmtDate(u.createdAt, locale)}</span></div>
          {u.tags.length > 0 && <div className="mt-2 flex gap-1.5">{u.tags.map((x) => <span key={x} className="chip bg-gold/10 text-gold-deep">{x}</span>)}</div>}</div>
        <Button size="sm" onClick={() => setCreditModal(true)}><Icon name="plus" size={14} />{t('admin.users.addCredits')}</Button>
      </div>
      <StaggerGroup className="grid grid-cols-2 gap-3 md:grid-cols-5" stagger={0.05}>
        {[[t('admin.users.credits'), fmtNum(u.credits.balance, locale)], [t('admin.users.visits'), fmtNum(u.visits, locale)], [t('admin.users.dreams'), fmtNum(u.dreams, locale)], [t('admin.users.drafts'), fmtNum(u.drafts, locale)], [t('admin.users.paid'), fmtMoney(Math.round(u.totalPaidBase), 'USD', locale)]].map(([l, v]) => <div key={l} className="card p-4"><div className="text-xs text-fg-muted">{l}</div><div className="font-display text-2xl">{v}</div></div>)}
      </StaggerGroup>
      <div className="grid gap-5 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <Tabs value={tab} onChange={setTab} items={[{ value: 'dreams', label: t('admin.users.userDreams'), count: u.dreamList.length }, { value: 'orders', label: t('admin.users.orders'), count: u.orders.length }, { value: 'credits', label: t('admin.users.credits') }, { value: 'testimonials', label: t('admin.users.testimonials'), count: u.testimonials.length }]} />
          <div className="mt-4 space-y-2">
            {tab === 'dreams' && (u.dreamList.length ? u.dreamList.map((d) => <Link key={d.id} to={d.status === 'DRAFT' ? '#' : `/admin/dreams/${d.id}`} className="card card-hover block p-4"><div className="mb-1 flex justify-between"><StatusBadge status={d.status} /><span className="text-xs text-fg-dim">{fmtDate(d.createdAt, locale)}</span></div><p className="line-clamp-2 text-sm font-light">{d.status === 'DRAFT' ? '[مسودة — لا تُعرض قبل التقديم]' : d.excerpt}</p></Link>) : <Empty text={t('common.none')} />)}
            {tab === 'orders' && (u.orders.length ? u.orders.map((o) => <div key={o.id} className="card flex items-center justify-between p-4 text-sm"><div>{o.packageName} · {fmtMoney(o.amount, o.currency, locale)}<div className="text-xs text-fg-dim" dir="ltr">{o.provider} {o.providerRef ?? ''} · {o.countryCode} · {fmtDate(o.createdAt, locale, true)}</div></div><StatusBadge status={o.status} kind="order" /></div>) : <Empty text={t('common.none')} />)}
            {tab === 'credits' && u.credits.entries.map((e) => <div key={e.id} className="card flex items-center justify-between p-4 text-sm"><div>{reasonL[e.reason]}<div className="text-xs text-fg-dim">{fmtDate(e.createdAt, locale, true)}</div></div><div className={e.delta > 0 ? 'text-success' : 'text-fg-muted'} dir="ltr">{e.delta > 0 ? '+' : ''}{e.delta}</div></div>)}
            {tab === 'testimonials' && (u.testimonials.length ? u.testimonials.map((x) => <div key={x.dreamId} className="card p-4 text-sm"><Stars value={x.rating} size={14} /><p className="mt-2">{x.comment}</p><div className="mt-1 text-xs text-fg-dim">{x.approved ? t('admin.testimonials.approved') : t('admin.testimonials.pending')}</div></div>) : <Empty text={t('common.none')} />)}
          </div>
        </div>
        <aside className="card space-y-4 p-5">
          <div><Label>{t('admin.users.notes')}</Label><Textarea rows={5} value={notes ?? u.notes} onChange={(e) => setNotes(e.target.value)} /></div>
          <div><Label>{t('admin.users.tags')}</Label><Input value={tags ?? u.tags.join(', ')} onChange={(e) => setTags(e.target.value)} placeholder="متابع دائم، …" /></div>
          <div className="flex justify-end"><Button size="sm" variant="ghost" loading={saveNotes.isPending} onClick={() => saveNotes.mutate()}>{t('admin.pricing.save')}</Button></div>
        </aside>
      </div>
      <Modal open={creditModal} onClose={() => setCreditModal(false)} title={t('admin.users.addCredits')}>
        <div className="space-y-3"><div><Label>{t('admin.packages.credits')}</Label><Input type="number" value={delta} onChange={(e) => setDelta(Number(e.target.value))} /></div><div><Label>السبب</Label><Input value={reason} onChange={(e) => setReason(e.target.value)} /></div>
          <div className="flex justify-end gap-2"><Button variant="ghost" onClick={() => setCreditModal(false)}>{t('common.cancel')}</Button><Button disabled={!reason || !delta} loading={addCredits.isPending} onClick={() => addCredits.mutate()}>{t('common.confirm')}</Button></div></div>
      </Modal>
    </PageEnter>
  )
}
