// Small building blocks shared by the interpreter dashboard sections.
import { useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '@/app/auth-store'
import { Icon, type IconName } from '@/components/icons/Icon'
import { cn, fmtPct, pctChange } from '@/lib/utils'

export function SectionHeader({ id, icon, title, lead, action }: { id: string; icon: IconName; title: string; lead?: string; action?: ReactNode }) {
  const [hover, setHover] = useState(false)
  return (
    <div className="mb-4 flex flex-wrap items-end justify-between gap-3" onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}>
      <div className="flex min-w-0 items-start gap-3">
        <span className="mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink"><Icon name={icon} size={20} active={hover} /></span>
        <div className="min-w-0">
          <h2 id={id} className="font-display text-2xl leading-tight">{title}</h2>
          {lead && <p className="mt-1 text-sm font-light text-fg-muted">{lead}</p>}
        </div>
      </div>
      {action}
    </div>
  )
}

/** Diagonal trend arrow drawn for LTR (↗ / ↘) and mirrored in RTL, so "later" follows the reading direction. */
export function TrendArrow({ dir }: { dir: 'up' | 'down' | 'flat' }) {
  const d = dir === 'up' ? 'M4 12L12 4M6 4h6v6' : dir === 'down' ? 'M4 4l8 8M12 6v6H6' : 'M3 8h10'
  return (
    <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={dir === 'flat' ? undefined : 'rtl:-scale-x-100'}>
      <path d={d} />
    </svg>
  )
}

/** Change against the previous period: up = success, down = danger (or the reverse with `lowerIsBetter`). */
export function DeltaChip({ current, previous, lowerIsBetter }: { current: number; previous: number; lowerIsBetter?: boolean }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const change = pctChange(current, previous)
  if (change === null) return <span className="chip bg-gold/15 px-2 py-0.5 font-medium text-gold-ink">{t('admin.traffic.new')}</span>
  const rounded = Math.round(change)
  const dir = rounded > 0 ? 'up' : rounded < 0 ? 'down' : 'flat'
  const good = lowerIsBetter ? dir === 'down' : dir === 'up'
  const pct = fmtPct(Math.abs(change), locale, 0)
  const label = dir === 'flat' ? t('admin.traffic.changeFlat') : t(dir === 'up' ? 'admin.traffic.changeUp' : 'admin.traffic.changeDown', { pct })
  return (
    <span role="img" title={label} aria-label={label} className={cn('chip gap-1 px-2 py-0.5 font-medium', dir === 'flat' ? 'bg-surface-2 text-fg-muted' : good ? 'bg-success/10 text-ok-ink' : 'bg-danger/10 text-bad-ink')}>
      <TrendArrow dir={dir} />
      <span>{pct}</span>
    </span>
  )
}

export interface BarRow { key: string; label: ReactNode; value: number; valueText: string; sub?: string }

/** Ranked list with a thin inline bar per row (bar length relative to the first row). */
export function BarList({ rows, empty }: { rows: BarRow[]; empty: string }) {
  if (!rows.length) return <p className="py-8 text-center text-sm text-fg-muted">{empty}</p>
  const max = Math.max(1, ...rows.map((r) => r.value))
  return (
    <ol className="space-y-3">
      {rows.map((r) => (
        <li key={r.key}>
          <div className="mb-1.5 flex items-baseline justify-between gap-3 text-sm">
            <span className="min-w-0 truncate">{r.label}</span>
            {/* flex items keep each number in its own bidi run (adjacent Arabic-Indic numbers would merge) */}
            <span className="flex shrink-0 items-baseline gap-1.5 text-fg-muted">
              <span className="font-medium text-fg tabular-nums">{r.valueText}</span>
              {r.sub && <><span aria-hidden="true" className="text-fg-dim">·</span><span className="text-[11px] text-fg-dim">{r.sub}</span></>}
            </span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-surface-2">
            <div className="h-full rounded-full bg-viz-line transition-[width] duration-700 ease-out" style={{ width: `${Math.max(1.5, (r.value / max) * 100)}%` }} />
          </div>
        </li>
      ))}
    </ol>
  )
}

/** Card with a small title row — used for every block inside a section. */
export function Panel({ title, aside, children, className }: { title?: ReactNode; aside?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <div className={cn('card min-w-0 p-5', className)}>
      {(title || aside) && (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          {title && <h3 className="font-medium">{title}</h3>}
          {aside}
        </div>
      )}
      {children}
    </div>
  )
}

/** Legend key that mirrors the mark: a short line (solid or dashed) or a square swatch. */
export function LegendKey({ color, dashed, swatch, ring, label }: { color: string; dashed?: boolean; swatch?: boolean; ring?: boolean; label: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-xs text-fg-muted">
      {swatch || ring ? (
        <span aria-hidden="true" className="h-3 w-3 rounded-[4px]" style={ring ? { boxShadow: `inset 0 0 0 2px ${color}` } : { background: color }} />
      ) : (
        <svg width="22" height="6" aria-hidden="true"><line x1="1" y1="3" x2="21" y2="3" stroke={color} strokeWidth="2" strokeLinecap="round" strokeDasharray={dashed ? '4 4' : undefined} /></svg>
      )}
      {label}
    </span>
  )
}

/** Width of an element, kept up to date with a ResizeObserver (charts draw in real pixels so text never scales). */
export function useWidth<T extends HTMLElement>(): [RefObject<T>, number] {
  const ref = useRef<T>(null)
  const [width, setWidth] = useState(0)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    setWidth(el.clientWidth)
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver((entries) => setWidth(Math.round(entries[0].contentRect.width)))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return [ref, width]
}
