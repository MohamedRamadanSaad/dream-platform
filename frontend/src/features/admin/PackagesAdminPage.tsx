import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Input, Label, Modal, Skeleton, Tabs, Textarea } from '@/components/ui'
import { PageEnter } from '@/components/motion'
import { arrowNext, cn, fmtDate } from '@/lib/utils'
import type { AdminPackage, CouponDto, PromotionDto } from '@/api/types'

type Tab = 'packages' | 'promotions' | 'coupons'
const iso = (d: string) => (d ? new Date(d).toISOString() : '')
const local = (iso: string) => (iso ? new Date(iso).toISOString().slice(0, 16) : '')

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
  const delPr = useMutation({ mutationFn: adminApi.deletePromotion, onSuccess: inv })
  const delC = useMutation({ mutationFn: adminApi.deleteCoupon, onSuccess: inv })

  return (
    <PageEnter>
      <div className="mb-6 flex items-center justify-between"><h1 className="font-display text-4xl">{t('admin.packages.title')}</h1>
        <Button size="sm" onClick={() => tab === 'packages' ? setEditP({ nameAr: '', nameEn: '', descriptionAr: '', descriptionEn: '', credits: 1, badge: null, sortOrder: (pk.data?.length ?? 0) + 1, active: true, validityMonths: 12 }) : tab === 'promotions' ? setEditPr({ name: '', packageIds: [], type: 'PERCENT', value: 10, startsAt: new Date().toISOString(), endsAt: new Date(Date.now() + 7 * 864e5).toISOString(), maxUses: null, scope: 'GLOBAL', scopeId: null, active: true }) : setEditC({ code: '', type: 'PERCENT', value: 10, maxUses: null, perUserLimit: 1, expiresAt: null, active: true })}>+ {t('admin.packages.new')}</Button></div>
      <Tabs value={tab} onChange={setTab} items={[{ value: 'packages', label: t('admin.packages.packages') }, { value: 'promotions', label: t('admin.packages.promotions') }, { value: 'coupons', label: t('admin.packages.coupons') }]} />
      <div className="mt-6 space-y-3">
        {tab === 'packages' && (pk.isLoading ? <Skeleton className="h-40" /> : pk.data!.sort((a, b) => a.sortOrder - b.sortOrder).map((p) => (
          <div key={p.id} className={cn('card flex items-center justify-between gap-4 p-5', !p.active && 'opacity-50')}>
            <div><div className="font-medium">{locale === 'ar' ? p.nameAr : p.nameEn} {p.badge && <span className="chip bg-gold/10 text-gold-deep">{p.badge}</span>}</div><div className="text-xs text-fg-dim">{t('packages.dreams', { count: p.credits })} · {p.active ? t('admin.packages.active') : t('admin.packages.inactive')}</div></div>
            <div className="flex gap-2"><Button size="sm" variant="ghost" onClick={() => setEditP(p)}>{t('common.edit')}</Button><Button size="sm" variant="ghost" onClick={() => saveP.mutate({ id: p.id, active: !p.active })}>{p.active ? t('admin.packages.inactive') : t('admin.packages.active')}</Button></div>
          </div>
        )))}
        {tab === 'promotions' && (pr.isLoading ? <Skeleton className="h-40" /> : pr.data!.map((p) => (
          <div key={p.id} className={cn('card flex items-center justify-between gap-4 p-5', !p.active && 'opacity-50')}>
            <div><div className="font-medium">{p.name} <span className="text-xs text-fg-dim">· {p.type === 'PERCENT' ? `${p.value}%` : p.type === 'FIXED' ? `-${p.value}` : `+${p.value}`}</span></div><div className="text-xs text-fg-dim">{fmtDate(p.startsAt, locale)} {arrowNext(locale)} {fmtDate(p.endsAt, locale)} · {t('admin.packages.used')} {p.usedCount}{p.maxUses ? `/${p.maxUses}` : ''} · {p.packageIds.map((id) => { const x = pk.data?.find((y) => y.id === id); return locale === 'ar' ? x?.nameAr : x?.nameEn }).join(locale === 'ar' ? '، ' : ', ')}</div></div>
            <div className="flex gap-2"><Button size="sm" variant="ghost" onClick={() => setEditPr(p)}>{t('common.edit')}</Button><Button size="sm" variant="ghost" className="text-danger" onClick={() => confirm('?') && delPr.mutate(p.id)}>{t('common.delete')}</Button></div>
          </div>
        )))}
        {tab === 'coupons' && (cp.isLoading ? <Skeleton className="h-40" /> : cp.data!.map((c) => (
          <div key={c.id} className={cn('card flex items-center justify-between gap-4 p-5', !c.active && 'opacity-50')}>
            <div><div className="font-medium" dir="ltr">{c.code} <span className="text-xs text-fg-dim">· {c.type === 'PERCENT' ? `${c.value}%` : `-${c.value}`}</span></div><div className="text-xs text-fg-dim">{t('admin.packages.used')} {c.usedCount}{c.maxUses ? `/${c.maxUses}` : ''} · {t('admin.packages.perUser')} {c.perUserLimit}{c.expiresAt && ` · ${t('admin.packages.expires')} ${fmtDate(c.expiresAt, locale)}`}</div></div>
            <div className="flex gap-2"><Button size="sm" variant="ghost" onClick={() => setEditC(c)}>{t('common.edit')}</Button><Button size="sm" variant="ghost" className="text-danger" onClick={() => confirm('?') && delC.mutate(c.id)}>{t('common.delete')}</Button></div>
          </div>
        )))}
      </div>

      <Modal open={!!editP} onClose={() => setEditP(null)} title={t('admin.packages.packages')}>
        {editP && <div className="grid gap-3 sm:grid-cols-2">
          <div><Label>{t('admin.packages.nameAr')}</Label><Input value={editP.nameAr ?? ''} onChange={(e) => setEditP({ ...editP, nameAr: e.target.value })} /></div>
          <div><Label>{t('admin.packages.nameEn')}</Label><Input dir="ltr" value={editP.nameEn ?? ''} onChange={(e) => setEditP({ ...editP, nameEn: e.target.value })} /></div>
          <div className="sm:col-span-2"><Label>{t('admin.packages.descAr')}</Label><Textarea rows={2} value={editP.descriptionAr ?? ''} onChange={(e) => setEditP({ ...editP, descriptionAr: e.target.value })} /></div>
          <div className="sm:col-span-2"><Label>{t('admin.packages.descEn')}</Label><Textarea dir="ltr" rows={2} value={editP.descriptionEn ?? ''} onChange={(e) => setEditP({ ...editP, descriptionEn: e.target.value })} /></div>
          <div><Label>{t('admin.packages.credits')}</Label><Input type="number" min={1} value={editP.credits ?? 1} onChange={(e) => setEditP({ ...editP, credits: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.badge')}</Label><Input value={editP.badge ?? ''} onChange={(e) => setEditP({ ...editP, badge: e.target.value || null })} /></div>
          <div><Label>{t('admin.packages.validity')}</Label><Input type="number" value={editP.validityMonths ?? ''} onChange={(e) => setEditP({ ...editP, validityMonths: e.target.value ? Number(e.target.value) : null })} /></div>
          <div className="flex items-end"><label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={!!editP.active} onChange={(e) => setEditP({ ...editP, active: e.target.checked })} className="accent-[var(--gold)]" />{t('admin.packages.active')}</label></div>
          <div className="sm:col-span-2 flex justify-end gap-2"><Button variant="ghost" onClick={() => setEditP(null)}>{t('common.cancel')}</Button><Button loading={saveP.isPending} onClick={() => saveP.mutate(editP)}>{t('admin.pricing.save')}</Button></div>
        </div>}
      </Modal>

      <Modal open={!!editPr} onClose={() => setEditPr(null)} title={t('admin.packages.promotions')}>
        {editPr && <div className="grid gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2"><Label>{t('admin.packages.name')}</Label><Input value={editPr.name ?? ''} onChange={(e) => setEditPr({ ...editPr, name: e.target.value })} /></div>
          <div><Label>{t('admin.packages.type')}</Label><select className="input" value={editPr.type} onChange={(e) => setEditPr({ ...editPr, type: e.target.value as PromotionDto['type'] })}><option value="PERCENT">{t('admin.packages.types.PERCENT')}</option><option value="FIXED">{t('admin.packages.types.FIXED')}</option><option value="BONUS">{t('admin.packages.types.BONUS')}</option></select></div>
          <div><Label>{t('admin.packages.value')}</Label><Input type="number" value={editPr.value ?? 0} onChange={(e) => setEditPr({ ...editPr, value: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.starts')}</Label><Input type="datetime-local" value={local(editPr.startsAt ?? '')} onChange={(e) => setEditPr({ ...editPr, startsAt: iso(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.ends')}</Label><Input type="datetime-local" value={local(editPr.endsAt ?? '')} onChange={(e) => setEditPr({ ...editPr, endsAt: iso(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.maxUses')}</Label><Input type="number" value={editPr.maxUses ?? ''} onChange={(e) => setEditPr({ ...editPr, maxUses: e.target.value ? Number(e.target.value) : null })} /></div>
          <div className="sm:col-span-2"><Label>{t('admin.packages.packages')}</Label><div className="flex flex-wrap gap-2">{pk.data?.map((p) => <label key={p.id} className={cn('chip cursor-pointer border', editPr.packageIds?.includes(p.id) ? 'border-gold bg-gold/10' : 'border-line')}><input type="checkbox" className="hidden" checked={!!editPr.packageIds?.includes(p.id)} onChange={(e) => setEditPr({ ...editPr, packageIds: e.target.checked ? [...(editPr.packageIds ?? []), p.id] : (editPr.packageIds ?? []).filter((x) => x !== p.id) })} />{p.nameAr}</label>)}</div></div>
          <div className="sm:col-span-2 flex justify-end gap-2"><Button variant="ghost" onClick={() => setEditPr(null)}>{t('common.cancel')}</Button><Button loading={savePr.isPending} onClick={() => savePr.mutate(editPr)}>{t('admin.pricing.save')}</Button></div>
        </div>}
      </Modal>

      <Modal open={!!editC} onClose={() => setEditC(null)} title={t('admin.packages.coupons')}>
        {editC && <div className="grid gap-3 sm:grid-cols-2">
          <div><Label>{t('admin.packages.code')}</Label><Input dir="ltr" value={editC.code ?? ''} onChange={(e) => setEditC({ ...editC, code: e.target.value.toUpperCase() })} /></div>
          <div><Label>{t('admin.packages.type')}</Label><select className="input" value={editC.type} onChange={(e) => setEditC({ ...editC, type: e.target.value as CouponDto['type'] })}><option value="PERCENT">{t('admin.packages.types.PERCENT')}</option><option value="FIXED">{t('admin.packages.types.FIXED')}</option></select></div>
          <div><Label>{t('admin.packages.value')}</Label><Input type="number" value={editC.value ?? 0} onChange={(e) => setEditC({ ...editC, value: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.maxUses')}</Label><Input type="number" value={editC.maxUses ?? ''} onChange={(e) => setEditC({ ...editC, maxUses: e.target.value ? Number(e.target.value) : null })} /></div>
          <div><Label>{t('admin.packages.perUser')}</Label><Input type="number" value={editC.perUserLimit ?? 1} onChange={(e) => setEditC({ ...editC, perUserLimit: Number(e.target.value) })} /></div>
          <div><Label>{t('admin.packages.expires')}</Label><Input type="datetime-local" value={local(editC.expiresAt ?? '')} onChange={(e) => setEditC({ ...editC, expiresAt: e.target.value ? iso(e.target.value) : null })} /></div>
          <div className="sm:col-span-2 flex justify-end gap-2"><Button variant="ghost" onClick={() => setEditC(null)}>{t('common.cancel')}</Button><Button loading={saveC.isPending} onClick={() => saveC.mutate(editC)}>{t('admin.pricing.save')}</Button></div>
        </div>}
      </Modal>
    </PageEnter>
  )
}
