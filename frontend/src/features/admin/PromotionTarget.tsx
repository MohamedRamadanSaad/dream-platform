import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Input, Label, Skeleton } from '@/components/ui'
import { cn, flagEmoji } from '@/lib/utils'
import type { Continent, CountryDto, CountryGroup, PriceScope } from '@/api/types'

const CONTINENTS: Continent[] = ['AS', 'AF', 'EU', 'NA', 'SA', 'OC']
const SCOPES: PriceScope[] = ['GLOBAL', 'CONTINENT', 'GROUP', 'COUNTRY']

/** Shared loaders so the list row and the picker read the same cached data. */
export function useGeo(enabled = true) {
  const countries = useQuery({ queryKey: ['admin', 'countries-list'], queryFn: adminApi.countries_list, staleTime: 10 * 60_000, enabled })
  const groups = useQuery({ queryKey: ['admin', 'groups'], queryFn: adminApi.groups, staleTime: 60_000, enabled })
  return { countries, groups }
}

/** Short human label for where an offer is shown ("كل الدول", "🇸🇦 السعودية", "آسيا", group name). */
export function targetLabel(t: (k: string, o?: Record<string, unknown>) => string, locale: string, scope: PriceScope | undefined, scopeId: string | null | undefined, countries?: CountryDto[], groups?: CountryGroup[]) {
  if (!scope || scope === 'GLOBAL' || !scopeId) return t('admin.packages.target.scopes.GLOBAL')
  if (scope === 'CONTINENT') return t(`admin.pricing.${scopeId}`)
  if (scope === 'GROUP') return groups?.find((g) => g.id === scopeId)?.name ?? t('admin.packages.target.scopes.GROUP')
  const c = countries?.find((x) => x.code === scopeId)
  return `${flagEmoji(scopeId)} ${c ? (locale === 'ar' ? c.nameAr : c.nameEn) : scopeId}`
}

/**
 * Where an offer applies: all countries, one continent, one country group, or one country.
 * Matches the backend PromotionService.scopeMatches (scopeId = continent code / group id / country code).
 */
export function PromotionTarget({ scope, scopeId, onChange }: { scope: PriceScope; scopeId: string | null; onChange: (scope: PriceScope, scopeId: string | null) => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const { countries, groups } = useGeo()
  const [q, setQ] = useState('')
  const cs = countries.data ?? []
  const gs = groups.data ?? []
  const name = (c: CountryDto) => (locale === 'ar' ? c.nameAr : c.nameEn)

  const filtered = useMemo(() => {
    const s = q.trim().toLowerCase()
    const list = s ? cs.filter((c) => c.nameAr.includes(q.trim()) || c.nameEn.toLowerCase().includes(s) || c.code.toLowerCase() === s) : cs
    return [...list].sort((a, b) => name(a).localeCompare(name(b), locale))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cs, q, locale])

  const row = (key: string, on: boolean, onPick: () => void, main: React.ReactNode, side?: React.ReactNode) => (
    <button key={key} type="button" role="option" aria-selected={on} onClick={onPick}
      className={cn('flex w-full items-center justify-between gap-3 rounded-lg px-3 py-2.5 text-start text-sm transition-colors', on ? 'bg-gold/15 text-fg ring-1 ring-inset ring-gold/60' : 'hover:bg-surface-2')}>
      <span className="flex min-w-0 items-center gap-2"><span className={cn('flex h-4 w-4 shrink-0 items-center justify-center rounded-full border', on ? 'border-gold bg-gold' : 'border-line')}>{on && <span className="h-1.5 w-1.5 rounded-full bg-night" />}</span><span className="truncate">{main}</span></span>
      {side && <span className="shrink-0 text-xs text-fg-dim">{side}</span>}
    </button>
  )

  const loading = countries.isLoading || groups.isLoading
  return (
    <div className="space-y-3">
      <Label>{t('admin.packages.target.label')}</Label>
      <div role="radiogroup" aria-label={t('admin.packages.target.label')} className="grid grid-cols-2 gap-1 rounded-2xl bg-surface-2 p-1 sm:grid-cols-4">
        {SCOPES.map((s) => (
          <button key={s} type="button" role="radio" aria-checked={scope === s} onClick={() => { setQ(''); onChange(s, null) }}
            className={cn('rounded-xl px-3 py-2 text-xs transition-colors', scope === s ? 'bg-night text-pearl shadow-sm' : 'text-fg-muted hover:text-fg')}>
            {t(`admin.packages.target.scopes.${s}`)}
          </button>
        ))}
      </div>

      {scope !== 'GLOBAL' && (
        <div className="rounded-xl border border-line bg-surface p-2">
          {loading ? <Skeleton className="h-32" /> : (
            <>
              {scope === 'COUNTRY' && <div className="p-1 pb-2"><Input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('admin.packages.target.search')} aria-label={t('admin.packages.target.search')} /></div>}
              <div role="listbox" aria-label={t(`admin.packages.target.scopes.${scope}`)} className="max-h-60 space-y-0.5 overflow-y-auto overscroll-contain">
                {scope === 'CONTINENT' && CONTINENTS.map((ct) => row(ct, scopeId === ct, () => onChange(scope, ct), t(`admin.pricing.${ct}`), t('admin.packages.target.countries', { count: cs.filter((c) => c.continent === ct).length })))}
                {scope === 'GROUP' && (gs.length === 0
                  ? <p className="px-3 py-4 text-center text-sm text-fg-muted">{t('admin.packages.target.noGroups')}</p>
                  : gs.map((g) => row(g.id, scopeId === g.id, () => onChange(scope, g.id),
                    <>{g.name} <span className="ms-1 text-xs opacity-80">{g.countryCodes.slice(0, 6).map(flagEmoji).join(' ')}{g.countryCodes.length > 6 ? ' …' : ''}</span></>,
                    t('admin.packages.target.countries', { count: g.countryCodes.length }))))}
                {scope === 'COUNTRY' && (filtered.length === 0
                  ? <p className="px-3 py-4 text-center text-sm text-fg-muted">{t('admin.packages.target.noResults')}</p>
                  : filtered.map((c) => row(c.code, scopeId === c.code, () => onChange(scope, c.code), <>{flagEmoji(c.code)} {name(c)}</>, t(`admin.pricing.${c.continent}`))))}
              </div>
            </>
          )}
        </div>
      )}
      {scope !== 'GLOBAL' && !scopeId && !loading && <p className="text-xs text-warn">{t('admin.packages.target.pick')}</p>}
    </div>
  )
}
