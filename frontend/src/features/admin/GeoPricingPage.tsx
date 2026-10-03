import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Input, Label, Modal, Skeleton } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { cn, fmtMoney } from '@/lib/utils'
import type { Continent, CountryDto, CountryGroup, Currency, PriceRule, PriceScope, AdminPackage, PricingGap } from '@/api/types'

const CONTINENTS: Continent[] = ['AF', 'AS', 'EU', 'NA', 'SA', 'OC']
const FLAG = (cc: string) => cc.toUpperCase().replace(/./g, (c) => String.fromCodePoint(127397 + c.charCodeAt(0)))
type Sel = { kind: 'GLOBAL' } | { kind: 'CONTINENT'; id: Continent } | { kind: 'GROUP'; id: string } | { kind: 'COUNTRY'; id: string }

const SHOWN = 12

/**
 * Red warning on top of the page: countries whose visitors cannot buy some active package (no price in their
 * currency), grouped by what they miss. A country opens its prices in the editor below.
 */
function PricingGaps({ gaps, onPick }: { gaps: PricingGap[]; onPick: (code: string) => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [open, setOpen] = useState<Record<string, boolean>>({})
  if (!gaps.length) return null
  const pkgName = (p: { nameAr: string; nameEn: string }) => (locale === 'ar' ? p.nameAr : p.nameEn)
  const quote = (s: string) => (locale === 'ar' ? `«${s}»` : `“${s}”`)
  const groups = new Map<string, PricingGap[]>()
  for (const g of gaps) {
    const key = `${g.currency}|${g.missing.map((m) => m.id).sort().join(',')}`
    groups.set(key, [...(groups.get(key) ?? []), g])
  }
  return (
    <section role="alert" className="mb-6 rounded-2xl border border-danger/40 bg-danger/5 p-4 md:p-5">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 shrink-0 text-danger"><Icon name="alert" size={20} /></span>
        <div className="min-w-0 flex-1">
          <h2 className="font-medium text-danger">{t('admin.pricing.gaps.title', { count: gaps.length, n: gaps.length })}</h2>
          <p className="mt-1 text-sm text-fg-muted">{t('admin.pricing.gaps.lead')}</p>
          <div className="mt-4 space-y-4">
            {[...groups.entries()].map(([key, list]) => {
              const first = list[0]
              const expanded = !!open[key]
              const shown = expanded ? list : list.slice(0, SHOWN)
              return (
                <div key={key} className="rounded-xl border border-danger/20 bg-surface/60 p-3">
                  <div className="text-sm font-medium text-fg">
                    {first.allMissing ? t('admin.pricing.gaps.all', { currency: t(`admin.pricing.gaps.currency.${first.currency}`) })
                      : t('admin.pricing.gaps.missing', { currency: t(`admin.pricing.gaps.currency.${first.currency}`), packages: first.missing.map((m) => quote(pkgName(m))).join(locale === 'ar' ? '، ' : ', ') })}
                  </div>
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {shown.map((g) => (
                      <button key={g.countryCode} type="button" onClick={() => onPick(g.countryCode)} title={g.groupName ?? undefined}
                        className="chip border border-danger/30 bg-danger/10 text-xs text-danger transition-colors hover:bg-danger/20">
                        {FLAG(g.countryCode)} {locale === 'ar' ? g.nameAr : g.nameEn}
                      </button>
                    ))}
                    {list.length > SHOWN && (
                      <button type="button" onClick={() => setOpen({ ...open, [key]: !expanded })} className="chip text-xs text-fg-muted underline-offset-2 hover:underline">
                        {expanded ? t('admin.pricing.gaps.less') : t('admin.pricing.gaps.more', { n: list.length - SHOWN })}
                      </button>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
          <p className="mt-3 text-xs text-fg-dim">{t('admin.pricing.gaps.hint')}</p>
        </div>
      </div>
    </section>
  )
}

function resolve(rules: PriceRule[], c: CountryDto | null, pkgId: string, scopeSel: Sel): { rule: PriceRule | null; source: PriceScope | null } {
  const by = (s: PriceScope, id: string | null) => rules.find((r) => r.scope === s && r.scopeId === id && r.packageId === pkgId) ?? null
  if (scopeSel.kind === 'GLOBAL') return { rule: by('GLOBAL', null), source: 'GLOBAL' }
  if (scopeSel.kind === 'CONTINENT') return by('CONTINENT', scopeSel.id) ? { rule: by('CONTINENT', scopeSel.id), source: 'CONTINENT' } : { rule: by('GLOBAL', null), source: 'GLOBAL' }
  if (scopeSel.kind === 'GROUP') return by('GROUP', scopeSel.id) ? { rule: by('GROUP', scopeSel.id), source: 'GROUP' } : { rule: by('GLOBAL', null), source: 'GLOBAL' }
  if (!c) return { rule: null, source: null }
  // like the backend PriceResolver: a country only ever sees prices in its own currency
  const mine = (r: PriceRule | null) => (r && r.currency === c.defaultCurrency ? r : null)
  const own = mine(by('COUNTRY', c.code)); if (own) return { rule: own, source: 'COUNTRY' }
  const g = c.groupId ? mine(by('GROUP', c.groupId)) : null; if (g) return { rule: g, source: 'GROUP' }
  const ct = mine(by('CONTINENT', c.continent)); if (ct) return { rule: ct, source: 'CONTINENT' }
  const gl = mine(by('GLOBAL', null))
  return gl ? { rule: gl, source: 'GLOBAL' } : { rule: null, source: null }
}

export function GeoPricingPage() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const locale = useAuthStore((s) => s.locale)
  const countries = useQuery({ queryKey: ['admin', 'countries-list'], queryFn: adminApi.countries_list })
  const groups = useQuery({ queryKey: ['admin', 'groups'], queryFn: adminApi.groups })
  const rules = useQuery({ queryKey: ['admin', 'rules'], queryFn: adminApi.priceRules })
  const packages = useQuery({ queryKey: ['admin', 'packages'], queryFn: adminApi.packages })
  const gaps = useQuery({ queryKey: ['admin', 'pricing-gaps'], queryFn: adminApi.pricingGaps })
  const [openCont, setOpenCont] = useState<Continent | null>('AS')
  const [sel, setSel] = useState<Sel>({ kind: 'COUNTRY', id: 'SA' })
  const [search, setSearch] = useState('')
  const [customOnly, setCustomOnly] = useState(false)
  const [groupModal, setGroupModal] = useState<Partial<CountryGroup> | null>(null)
  const inv = () => qc.invalidateQueries({ queryKey: ['admin'] })
  const saveRule = useMutation({ mutationFn: adminApi.savePriceRule, onSuccess: inv })
  const delRule = useMutation({ mutationFn: adminApi.deletePriceRule, meta: { toast: 'common.deleted' }, onSuccess: inv })
  const saveGroup = useMutation({ mutationFn: adminApi.saveGroup, onSuccess: () => { setGroupModal(null); inv() } })
  const delGroup = useMutation({ mutationFn: adminApi.deleteGroup, meta: { toast: 'common.deleted' }, onSuccess: inv })

  const cs = countries.data ?? [], gs = groups.data ?? [], rs = rules.data ?? [], ps = (packages.data ?? []).filter((p) => p.active)
  const name = (c: CountryDto) => (locale === 'ar' ? c.nameAr : c.nameEn)
  const selCountry = sel.kind === 'COUNTRY' ? cs.find((c) => c.code === sel.id) ?? null : null
  const hasCustom = (c: CountryDto) => rs.some((r) => r.scope === 'COUNTRY' && r.scopeId === c.code)
  const filtered = useMemo(() => cs.filter((c) => (!search || name(c).includes(search) || c.code.includes(search.toUpperCase())) && (!customOnly || hasCustom(c))), [cs, search, customOnly, rs, locale]) // eslint-disable-line react-hooks/exhaustive-deps
  const title = sel.kind === 'GLOBAL' ? t('admin.pricing.global') : sel.kind === 'CONTINENT' ? t(`admin.pricing.${sel.id}`) : sel.kind === 'GROUP' ? gs.find((g) => g.id === sel.id)?.name : selCountry ? `${FLAG(selCountry.code)} ${name(selCountry)}` : ''
  const gapCodes = useMemo(() => new Set((gaps.data ?? []).map((g) => g.countryCode)), [gaps.data])
  const currencyDefault: Currency = selCountry?.defaultCurrency ?? 'USD'

  if (countries.isLoading || rules.isLoading || packages.isLoading) return <div className="grid gap-4 lg:grid-cols-3"><Skeleton className="h-96" /><Skeleton className="h-96 lg:col-span-2" /></div>

  return (
    <PageEnter>
      <h1 className="font-display text-4xl">{t('admin.pricing.title')}</h1>
      <p className="mb-6 text-sm font-light text-fg-muted">{t('admin.pricing.lead')}</p>
      <PricingGaps gaps={gaps.data ?? []} onPick={(code) => {
        const c = cs.find((x) => x.code === code)
        if (c) setOpenCont(c.continent)
        setSearch('')
        setCustomOnly(false)
        setSel({ kind: 'COUNTRY', id: code })
        requestAnimationFrame(() => document.getElementById('price-editor')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
      }} />
      <div className="grid gap-5 lg:grid-cols-3">
        <aside className="card p-3 lg:sticky lg:top-20 lg:self-start">
          <button onClick={() => setSel({ kind: 'GLOBAL' })} className={cn('flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-sm', sel.kind === 'GLOBAL' ? 'bg-night text-pearl' : 'hover:bg-surface-2')}><Icon name="globe" size={16} />{t('admin.pricing.global')}</button>
          <div className="mt-3 px-3 text-xs ltr:tracking-wider text-gold-ink">{t('admin.pricing.continents')}</div>
          {CONTINENTS.map((ct) => {
            const list = filtered.filter((c) => c.continent === ct)
            const open = openCont === ct
            return (
              <div key={ct}>
                <div className="flex items-center">
                  <button onClick={() => { setOpenCont(open ? null : ct); setSel({ kind: 'CONTINENT', id: ct }) }} className={cn('flex flex-1 items-center justify-between rounded-xl px-3 py-2.5 text-sm', sel.kind === 'CONTINENT' && sel.id === ct ? 'bg-night text-pearl' : 'hover:bg-surface-2')}>
                    <span>{t(`admin.pricing.${ct}`)} <span className="text-xs opacity-60">· {cs.filter((c) => c.continent === ct).length}</span></span><span className={cn('transition-transform', open && 'rotate-90')}>{locale === 'ar' ? '‹' : '›'}</span>
                  </button>
                </div>
                {open && (
                  <div className="ms-3 border-s border-line ps-2 py-1">
                    {list.length === 0 && <div className="px-3 py-1 text-xs text-fg-dim">{t('common.none')}</div>}
                    {list.map((c) => (
                      <button key={c.code} onClick={() => setSel({ kind: 'COUNTRY', id: c.code })} className={cn('flex w-full items-center justify-between rounded-lg px-3 py-2 text-sm', sel.kind === 'COUNTRY' && sel.id === c.code ? 'bg-gold/15 text-gold-ink' : 'hover:bg-surface-2')}>
                        <span className="flex items-center gap-1.5">{FLAG(c.code)} {name(c)}{gapCodes.has(c.code) && <span className="h-1.5 w-1.5 rounded-full bg-danger" aria-label={t('admin.pricing.gaps.dot')} />}</span>
                        <span className="text-[10px] text-fg-dim">{hasCustom(c) ? t('admin.pricing.custom') : c.groupId ? gs.find((g) => g.id === c.groupId)?.name : t('admin.pricing.inherited')}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
          <div className="mt-4 flex items-center justify-between px-3 text-xs ltr:tracking-wider text-gold-ink"><span>{t('admin.pricing.groups')}</span><button onClick={() => setGroupModal({ name: '', countryCodes: [] })} className="text-fg-muted hover:text-fg"><Icon name="plus" size={14} /></button></div>
          {gs.map((g) => (
            <button key={g.id} onClick={() => setSel({ kind: 'GROUP', id: g.id })} className={cn('flex w-full items-center justify-between rounded-xl px-3 py-2.5 text-sm', sel.kind === 'GROUP' && sel.id === g.id ? 'bg-night text-pearl' : 'hover:bg-surface-2')}>
              <span><Icon name="heart" size={14} className="inline me-1" />{g.name}</span><span className="text-xs opacity-60">{g.countryCodes.length}</span>
            </button>
          ))}
          <div className="mt-4 space-y-2 px-1">
            <Input placeholder={t('common.search')} value={search} onChange={(e) => setSearch(e.target.value)} className="py-2 text-xs" />
            <label className="flex items-center gap-2 text-xs text-fg-muted"><input type="checkbox" checked={customOnly} onChange={(e) => setCustomOnly(e.target.checked)} className="accent-gold" />{t('admin.pricing.customOnly')}</label>
          </div>
        </aside>

        <section id="price-editor" className="card scroll-mt-24 p-6 lg:col-span-2">
          <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
            <h2 className="font-display text-2xl">{title}</h2>
            {sel.kind === 'GROUP' && <div className="flex gap-2"><Button size="sm" variant="ghost" onClick={() => setGroupModal(gs.find((g) => g.id === sel.id)!)}>{t('common.edit')}</Button><Button size="sm" variant="ghost" className="text-danger" onClick={() => { if (confirm('?')) { delGroup.mutate(sel.id); setSel({ kind: 'GLOBAL' }) } }}>{t('common.delete')}</Button></div>}
          </div>
          {sel.kind === 'GROUP' && <div className="mb-5 flex flex-wrap gap-1.5">{gs.find((g) => g.id === sel.id)?.countryCodes.map((cc) => <span key={cc} className="chip bg-surface-2">{FLAG(cc)} {name(cs.find((c) => c.code === cc)!)}</span>)}</div>}
          {selCountry && <div className="mb-5 text-sm text-fg-muted">{t(`admin.pricing.${selCountry.continent}`)}{selCountry.groupId && <> · {gs.find((g) => g.id === selCountry.groupId)?.name}</>} · {selCountry.defaultCurrency}</div>}
          <div className="space-y-3">
            {ps.map((p) => <PriceRow key={p.id} pkg={p} sel={sel} country={selCountry} rules={rs} groups={gs} currencyDefault={currencyDefault} onSave={(r) => saveRule.mutate(r)} onReset={(id) => delRule.mutate(id)} />)}
          </div>
        </section>
      </div>

      <Modal open={!!groupModal} onClose={() => setGroupModal(null)} title={groupModal?.id ? t('common.edit') : t('admin.pricing.newGroup')}
        footer={<><Button variant="ghost" onClick={() => setGroupModal(null)}>{t('common.cancel')}</Button><Button disabled={!groupModal?.name} loading={saveGroup.isPending} onClick={() => groupModal && saveGroup.mutate(groupModal)}>{t('admin.pricing.save')}</Button></>}>
        {groupModal && (
          <div className="space-y-4">
            <div><Label>{t('admin.pricing.groupName')}</Label><Input value={groupModal.name ?? ''} onChange={(e) => setGroupModal({ ...groupModal, name: e.target.value })} /></div>
            <div><Label>{t('common.country')}</Label>
              <div className="grid max-h-64 grid-cols-2 gap-1 overflow-auto rounded-xl border border-line p-2 text-sm">
                {cs.map((c) => { const on = groupModal.countryCodes?.includes(c.code); const other = c.groupId && c.groupId !== groupModal.id ? gs.find((g) => g.id === c.groupId)?.name : null
                  return <label key={c.code} className={cn('flex items-center gap-2 rounded-lg px-2 py-1.5', on && 'bg-gold/10')}><input type="checkbox" checked={!!on} onChange={(e) => setGroupModal({ ...groupModal, countryCodes: e.target.checked ? [...(groupModal.countryCodes ?? []), c.code] : (groupModal.countryCodes ?? []).filter((x) => x !== c.code) })} className="accent-gold" />{FLAG(c.code)} {name(c)}{other && <span className="text-[10px] text-fg-dim">({other})</span>}</label> })}
              </div>
            </div>
          </div>
        )}
      </Modal>
    </PageEnter>
  )
}

function PriceRow({ pkg, sel, country, rules, groups, currencyDefault, onSave, onReset }: { pkg: AdminPackage; sel: Sel; country: CountryDto | null; rules: PriceRule[]; groups: CountryGroup[]; currencyDefault: Currency; onSave: (r: Omit<PriceRule, 'id'> & { id?: string }) => void; onReset: (id: string) => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const { rule, source } = resolve(rules, country, pkg.id, sel)
  const scope: PriceScope = sel.kind
  const scopeId = sel.kind === 'GLOBAL' ? null : sel.id
  const own = source === scope && rule?.scopeId === scopeId
  const [edit, setEdit] = useState(false)
  const [price, setPrice] = useState<string>(rule ? String(rule.price) : '')
  const [cur, setCur] = useState<Currency>(rule?.currency ?? currencyDefault)
  const srcLabel = own ? t('admin.pricing.custom') : source === 'GROUP' ? `${t('admin.pricing.fromGroup')} ${groups.find((g) => g.id === rule?.scopeId)?.name ?? ''}` : source === 'CONTINENT' ? t('admin.pricing.fromContinent') : source === 'GLOBAL' && scope !== 'GLOBAL' ? t('admin.pricing.inherited') : ''
  return (
    <div className={cn('flex flex-wrap items-center gap-3 rounded-xl border p-4', own ? 'border-gold/50 bg-gold/5' : 'border-line')}>
      <div className="min-w-0 flex-1"><div className="font-medium">{locale === 'ar' ? pkg.nameAr : pkg.nameEn}</div><div className="text-xs text-fg-dim">{t('packages.dreams', { count: pkg.credits })}{srcLabel && <> · <span className={own ? 'text-gold-ink' : ''}>{srcLabel}</span></>}</div></div>
      {edit ? (
        <div className="flex items-center gap-2">
          <Input type="number" min={0} value={price} onChange={(e) => setPrice(e.target.value)} className="w-28 py-2" dir="ltr" />
          <select value={cur} onChange={(e) => setCur(e.target.value as Currency)} className="input w-24 py-2">{(['EGP', 'USD'] as Currency[]).map((c) => <option key={c}>{c}</option>)}</select>
          <Button size="sm" onClick={() => { onSave({ id: own ? rule?.id : undefined, scope, scopeId, packageId: pkg.id, price: Number(price), currency: cur }); setEdit(false) }}>{t('admin.pricing.save')}</Button>
          <Button size="sm" variant="ghost" onClick={() => setEdit(false)}>{t('common.cancel')}</Button>
        </div>
      ) : (
        <div className="flex items-center gap-3">
          {rule ? <div className="text-xl font-medium">{fmtMoney(rule.price, rule.currency, locale)}</div>
            : country && sel.kind === 'COUNTRY' ? <div className="text-sm font-medium text-danger">{t('admin.pricing.gaps.noPrice', { currency: t(`admin.pricing.gaps.currency.${country.defaultCurrency}`) })}</div>
            : <div className="text-xl font-medium">-</div>}
          <Button size="sm" variant="ghost" onClick={() => { setPrice(rule ? String(rule.price) : ''); setCur(rule?.currency ?? currencyDefault); setEdit(true) }}>{t('common.edit')}</Button>
          {own && scope !== 'GLOBAL' && rule && <Button size="sm" variant="ghost" className="text-fg-dim" onClick={() => onReset(rule.id)}>{t('admin.pricing.reset')}</Button>}
        </div>
      )}
    </div>
  )
}
