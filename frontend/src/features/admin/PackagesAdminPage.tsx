import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Input, Label, Modal, Skeleton, Tabs, Textarea } from '@/components/ui'
import { PageEnter } from '@/components/motion'
import { Icon } from '@/components/icons/Icon'
import { arrowNext, cn, fmtDate } from '@/lib/utils'
import { PromotionTarget, targetLabel, useGeo } from './PromotionTarget'
import type { AdminPackage, CouponDto, PromotionDto } from '@/api/types'

type Tab = 'packages' | 'promotions' | 'coupons'
const iso = (d: string) => (d ? new Date(d).toISOString() : '')
const local = (iso: string) => (iso ? new Date(iso).toISOString().slice(0, 16) : '')

type OfferState = 'running' | 'upcoming' | 'stopped' | 'expired' | 'usedUp'
const isCurrent = (s: OfferState) => s === 'running' || s === 'upcoming'
const usedUp = (used: number, max: number | null) => max != null && max > 0 && used >= max

// Nothing here is deleted: a stopped, past-end or used-up offer moves to the "ended" list and can be turned on
// again (stopped) or given new dates / a new limit (ended).
function promotionState(p: PromotionDto, now: number): OfferState {
  if (!p.active) return 'stopped'
  if (new Date(p.endsAt).getTime() <= now) return 'expired'
  if (usedUp(p.usedCount, p.maxUses)) return 'usedUp'
  return new Date(p.startsAt).getTime() > now ? 'upcoming' : 'running'
}
function couponState(c: CouponDto, now: number): OfferState {
  if (!c.active) return 'stopped'
  if (c.expiresAt && new Date(c.expiresAt).getTime() <= now) return 'expired'
  if (usedUp(c.usedCount, c.maxUses)) return 'usedUp'
  return 'running'
}

function StateChip({ state }: { state: OfferState }) {
  const { t } = useTranslation()
  const tone = state === 'running' ? 'bg-success/10 text-success' : state === 'upcoming' ? 'bg-gold/10 text-gold-ink' : 'bg-surface-2 text-fg-muted'
  return <span className={cn('chip text-[11px]', tone)}><span className="h-1.5 w-1.5 rounded-full bg-current" />{t(`admin.packages.state.${state}`)}</span>
}

function StopButton({ active, onClick }: { active: boolean; onClick: () => void }) {
  const { t } = useTranslation()
  return <Button size="sm" variant="ghost" className={active ? 'text-danger' : 'text-success'} onClick={onClick}>{active ? t('admin.packages.stop') : t('admin.packages.resume')}</Button>
}

function Row({ title, meta, actions, ended, state }: { title: ReactNode; meta: ReactNode; actions: ReactNode; ended?: boolean; state?: OfferState }) {
  return (
    <div className={cn('card flex flex-col gap-3 p-5 sm:flex-row sm:items-center sm:justify-between sm:gap-4', ended && 'bg-surface-2/40')}>
      <div className="min-w-0">
        <div className={cn('flex flex-wrap items-center gap-2 font-medium', ended && 'text-fg-muted')}>{title}{state && <StateChip state={state} />}</div>
        <div className="mt-1 text-xs text-fg-dim">{meta}</div>
      </div>
      <div className="flex shrink-0 flex-wrap gap-2">{actions}</div>
    </div>
  )
}

/** The current list, then the stopped / ended ones in a folded section (kept, never deleted). */
function Split<T>({ current, ended, endedTitle, render }: { current: T[]; ended: T[]; endedTitle: string; render: (x: T) => ReactNode }) {
  const { t } = useTranslation()
  return (
    <>
      {current.length ? current.map(render) : <div className="card p-6 text-center text-sm text-fg-muted">{t('admin.packages.emptyCurrent')}</div>}
      <details className="group mt-8" open={ended.length > 0 && current.length === 0}>
        <summary className="flex cursor-pointer select-none list-none items-center gap-2 text-sm font-medium text-fg-muted hover:text-fg">
          <span className="transition-transform duration-300 group-open:rotate-90 rtl:rotate-180 rtl:group-open:rotate-90">{'›'}</span>
          {endedTitle}<span className="chip bg-surface-2 text-xs text-fg-dim">{ended.length}</span>
        </summary>
        <p className="mb-3 mt-2 text-xs text-fg-dim">{t('admin.packages.endedHint')}</p>
        <div className="space-y-3">{ended.length ? ended.map(render) : <div className="card p-6 text-center text-sm text-fg-muted">{t('admin.packages.emptyEnded')}</div>}</div>
      </details>
    </>
  )
}

export function PackagesAdminPage() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const locale = useAuthStore((s) => s.locale)
  const [tab, setTab] = useState<Tab>('packages')
  const inv = () => qc.invalidateQueries({ queryKey: ['admin'] })
  const pk = useQuery({ queryKey: ['admin', 'packages'], queryFn: adminApi.packages })
  const pr = useQuery({ queryKey: ['admin', 'promotions'], queryFn: adminApi.promotions })
  const cp = useQuery({ queryKey: ['admin', 'coupons'], queryFn: adminApi.coupons })
  const [editP, setEditP] = useState<Partial<AdminPackage> | null>(null)
  const [editPr, setEditPr] = useState<Partial<PromotionDto> | null>(null)
  const [editC, setEditC] = useState<Partial<CouponDto> | null>(null)
  const saveP = useMutation({ mutationFn: adminApi.savePackage, onSuccess: () => { setEditP(null); inv() } })
  const savePr = useMutation({ mutationFn: adminApi.savePromotion, onSuccess: () => { setEditPr(null); inv() } })
  const saveC = useMutation({ mutationFn: adminApi.saveCoupon, onSuccess: () => { setEditC(null); inv() } })
  const now = Date.now()
  const packages = [...(pk.data ?? [])].sort((a, b) => a.sortOrder - b.sortOrder)
  const promotions = [...(pr.data ?? [])].sort((a, b) => b.endsAt.localeCompare(a.endsAt))
  const coupons = cp.data ?? []
  const geo = useGeo(tab === 'promotions')
  const prNeedsTarget = !!editPr && !!editPr.scope && editPr.scope !== 'GLOBAL' && !editPr.scopeId

  return (
    <PageEnter>
      <div className="mb-6 flex items-center justify-between"><h1 className="font-display text-4xl">{t('admin.packages.title')}</h1>
        <Button size="sm" onClick={() => tab === 'packages' ? setEditP({ nameAr: '', nameEn: '', descriptionAr: '', descriptionEn: '', credits: 1, badge: null, sortOrder: (pk.data?.length ?? 0) + 1, active: true, validityMonths: 12 }) : tab === 'promotions' ? setEditPr({ name: '', packageIds: [], type: 'PERCENT', value: 10, startsAt: new Date().toISOString(), endsAt: new Date(Date.now() + 7 * 864e5).toISOString(), maxUses: null, scope: 'GLOBAL', scopeId: null, active: true }) : setEditC({ code: '', type: 'PERCENT', value: 10, maxUses: null, perUserLimit: 1, expiresAt: null, active: true })}>+ {t('admin.packages.new')}</Button></div>
      <Tabs value={tab} onChange={setTab} items={[{ value: 'packages', label: t('admin.packages.packages') }, { value: 'promotions', label: t('admin.packages.promotions') }, { value: 'coupons', label: t('admin.packages.coupons') }]} />
      <div className="mt-6 space-y-3">
        {tab === 'packages' && (pk.isLoading ? <Skeleton className="h-40" /> : (
          <Split current={packages.filter((p) => p.active)} ended={packages.filter((p) => !p.active)} endedTitle={t('admin.packages.stoppedPackages')}
            render={(p) => (
              <Row key={p.id} ended={!p.active} state={p.active ? undefined : 'stopped'}
                title={<>{locale === 'ar' ? p.nameAr : p.nameEn} {p.badge && <span className="chip bg-gold/10 text-gold-ink">{p.badge}</span>}</>}
                meta={t('packages.dreams', { count: p.credits })}
                actions={<><Button size="sm" variant="ghost" onClick={() => setEditP(p)}>{t('common.edit')}</Button><StopButton active={p.active} onClick={() => saveP.mutate({ id: p.id, active: !p.active })} /></>} />
            )} />
        ))}
        {tab === 'promotions' && (pr.isLoading ? <Skeleton className="h-40" /> : (
          <Split current={promotions.filter((p) => isCurrent(promotionState(p, now)))} ended={promotions.filter((p) => !isCurrent(promotionState(p, now)))} endedTitle={t('admin.packages.endedPromotions')}
            render={(p) => {
              const state = promotionState(p, now)
              return (
                <Row key={p.id} ended={!isCurrent(state)} state={state}
                  title={<>{p.name} <span className="text-xs text-fg-dim">· {p.type === 'PERCENT' ? `${p.value}%` : p.type === 'FIXED' ? `-${p.value}` : `+${p.value}`}</span><span className="chip bg-gold/10 text-gold-ink"><Icon name="globe" size={12} />{targetLabel(t, locale, p.scope, p.scopeId, geo.countries.data, geo.groups.data)}</span></>}
                  meta={<>{fmtDate(p.startsAt, locale)} {arrowNext(locale)} {fmtDate(p.endsAt, locale)} · {t('admin.packages.used')} {p.usedCount}{p.maxUses ? `/${p.maxUses}` : ''} · {p.packageIds.map((id) => { const x = pk.data?.find((y) => y.id === id); return locale === 'ar' ? x?.nameAr : x?.nameEn }).join(locale === 'ar' ? '، ' : ', ')}</>}
                  actions={<>
                    <Button size="sm" variant="ghost" onClick={() => setEditPr(p)}>{state === 'expired' || state === 'usedUp' ? t('admin.packages.renew') : t('common.edit')}</Button>
                    {(state !== 'expired' && state !== 'usedUp') && <StopButton active={p.active} onClick={() => savePr.mutate({ id: p.id, active: !p.active })} />}
                  </>} />
              )
            }} />
        ))}
        {tab === 'coupons' && (cp.isLoading ? <Skeleton className="h-40" /> : (
          <Split current={coupons.filter((c) => isCurrent(couponState(c, now)))} ended={coupons.filter((c) => !isCurrent(couponState(c, now)))} endedTitle={t('admin.packages.endedCoupons')}
            render={(c) => {
              const state = couponState(c, now)
              return (
                <Row key={c.id} ended={!isCurrent(state)} state={state}
                  title={<span dir="ltr">{c.code} <span className="text-xs text-fg-dim">· {c.type === 'PERCENT' ? `${c.value}%` : `-${c.value}`}</span></span>}
                  meta={<>{t('admin.packages.used')} {c.usedCount}{c.maxUses ? `/${c.maxUses}` : ''} · {t('admin.packages.perUser')} {c.perUserLimit}{c.expiresAt && ` · ${t('admin.packages.expires')} ${fmtDate(c.expiresAt, locale)}`}</>}
                  actions={<>
                    <Button size="sm" variant="ghost" onClick={() => setEditC(c)}>{state === 'expired' || state === 'usedUp' ? t('admin.packages.renew') : t('common.edit')}</Button>
                    {(state !== 'expired' && state !== 'usedUp') && <StopButton active={c.active} onClick={() => saveC.mutate({ id: c.id, active: !c.active })} />}
                  </>} />
              )
            }} />
        ))}
      </div>

      <Modal open={!!editP} onClose={() => setEditP(null)} title={t('admin.packages.packages')} size="lg"
        footer={<><Button variant="ghost" onClick={() => setEditP(null)}>{t('common.cancel')}</Button><Button loading={saveP.isPending} onClick={() => editP && saveP.mutate(editP)}>{t('admin.pricing.save')}</Button></>}>
        {editP && <div className="grid gap-3 sm:grid-cols-2">
          <div><Label>{t('admin.packages.nameAr')}</Label><Input value={editP.nameAr ?? ''} onChange={(e) => setEditP({ ...editP, nameAr: e.target.value })} /></div>
          <div><Label>{t('admin.packages.nameEn')}</Label><Input dir="ltr" value={editP.nameEn ?? ''} onChange={(e) => setEditP({ ...editP, nameEn: e.target.value })} /></div>
          <div className="sm:col-span-2"><Label>{t('admin.packages.descAr')}</Label><Textarea rows={2} value={editP.descriptionAr ?? ''} onChange={(e) => setEditP({ ...editP, descriptionAr: e.target.value })} /></div>
          <div className="sm:col-span-2"><Label>{t('admin.packages.descEn')}</Label><Textarea dir="ltr" rows={2} value={editP.descriptionEn ?? ''} onChange={(e) => setEditP({ ...editP, descriptionEn: e.target.value })} /></div>
          <div><Label>{t('admin.packages.credits')}</Label><Input type="number" min={1} value={editP.credits ?? 1} onChange={(e) => setEditP({ ...editP, credits: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.badge')}</Label><Input value={editP.badge ?? ''} onChange={(e) => setEditP({ ...editP, badge: e.target.value || null })} /></div>
          <div><Label>{t('admin.packages.validity')}</Label><Input type="number" value={editP.validityMonths ?? ''} onChange={(e) => setEditP({ ...editP, validityMonths: e.target.value ? Number(e.target.value) : null })} /></div>
          <div className="flex items-end sm:pb-3"><label className="flex min-h-[44px] items-center gap-2 text-sm"><input type="checkbox" checked={!!editP.active} onChange={(e) => setEditP({ ...editP, active: e.target.checked })} className="accent-gold" />{t('admin.packages.active')}</label></div>
        </div>}
      </Modal>

      <Modal open={!!editPr} onClose={() => setEditPr(null)} title={t('admin.packages.promotions')} size="lg"
        footer={<><Button variant="ghost" onClick={() => setEditPr(null)}>{t('common.cancel')}</Button><Button disabled={prNeedsTarget} loading={savePr.isPending} onClick={() => editPr && savePr.mutate(editPr)}>{t('admin.pricing.save')}</Button></>}>
        {editPr && <div className="grid gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2"><Label>{t('admin.packages.name')}</Label><Input value={editPr.name ?? ''} onChange={(e) => setEditPr({ ...editPr, name: e.target.value })} /></div>
          <div><Label>{t('admin.packages.type')}</Label><select className="input" value={editPr.type} onChange={(e) => setEditPr({ ...editPr, type: e.target.value as PromotionDto['type'] })}><option value="PERCENT">{t('admin.packages.types.PERCENT')}</option><option value="FIXED">{t('admin.packages.types.FIXED')}</option><option value="BONUS">{t('admin.packages.types.BONUS')}</option></select></div>
          <div><Label>{t('admin.packages.value')}</Label><Input type="number" value={editPr.value ?? 0} onChange={(e) => setEditPr({ ...editPr, value: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.starts')}</Label><Input type="datetime-local" value={local(editPr.startsAt ?? '')} onChange={(e) => setEditPr({ ...editPr, startsAt: iso(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.ends')}</Label><Input type="datetime-local" value={local(editPr.endsAt ?? '')} onChange={(e) => setEditPr({ ...editPr, endsAt: iso(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.maxUses')}</Label><Input type="number" value={editPr.maxUses ?? ''} onChange={(e) => setEditPr({ ...editPr, maxUses: e.target.value ? Number(e.target.value) : null })} /></div>
          <div className="sm:col-span-2"><PromotionTarget scope={editPr.scope ?? 'GLOBAL'} scopeId={editPr.scopeId ?? null} onChange={(scope, scopeId) => setEditPr({ ...editPr, scope, scopeId })} /></div>
          <div className="sm:col-span-2"><Label>{t('admin.packages.packages')}</Label><div className="flex flex-wrap gap-2">{pk.data?.map((p) => <label key={p.id} className={cn('chip cursor-pointer border', editPr.packageIds?.includes(p.id) ? 'border-gold bg-gold/10' : 'border-line')}><input type="checkbox" className="hidden" checked={!!editPr.packageIds?.includes(p.id)} onChange={(e) => setEditPr({ ...editPr, packageIds: e.target.checked ? [...(editPr.packageIds ?? []), p.id] : (editPr.packageIds ?? []).filter((x) => x !== p.id) })} />{locale === 'ar' ? p.nameAr : p.nameEn}</label>)}</div></div>
        </div>}
      </Modal>

      <Modal open={!!editC} onClose={() => setEditC(null)} title={t('admin.packages.coupons')} size="lg"
        footer={<><Button variant="ghost" onClick={() => setEditC(null)}>{t('common.cancel')}</Button><Button loading={saveC.isPending} onClick={() => editC && saveC.mutate(editC)}>{t('admin.pricing.save')}</Button></>}>
        {editC && <div className="grid gap-3 sm:grid-cols-2">
          <div><Label>{t('admin.packages.code')}</Label><Input dir="ltr" value={editC.code ?? ''} onChange={(e) => setEditC({ ...editC, code: e.target.value.toUpperCase() })} /></div>
          <div><Label>{t('admin.packages.type')}</Label><select className="input" value={editC.type} onChange={(e) => setEditC({ ...editC, type: e.target.value as CouponDto['type'] })}><option value="PERCENT">{t('admin.packages.types.PERCENT')}</option><option value="FIXED">{t('admin.packages.types.FIXED')}</option></select></div>
          <div><Label>{t('admin.packages.value')}</Label><Input type="number" value={editC.value ?? 0} onChange={(e) => setEditC({ ...editC, value: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.maxUses')}</Label><Input type="number" value={editC.maxUses ?? ''} onChange={(e) => setEditC({ ...editC, maxUses: e.target.value ? Number(e.target.value) : null })} /></div>
          <div><Label>{t('admin.packages.perUser')}</Label><Input type="number" value={editC.perUserLimit ?? 1} onChange={(e) => setEditC({ ...editC, perUserLimit: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.expires')}</Label><Input type="datetime-local" value={local(editC.expiresAt ?? '')} onChange={(e) => setEditC({ ...editC, expiresAt: e.target.value ? iso(e.target.value) : null })} /></div>
        </div>}
      </Modal>
    </PageEnter>
  )
}
