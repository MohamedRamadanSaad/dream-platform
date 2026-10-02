// Lightweight inline-SVG charts for the dashboard (no chart library). Time runs in the reading direction:
// left → right in English, right → left in Arabic.
import { useEffect, useId, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from 'react'
import gsap from 'gsap'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '@/app/auth-store'
import { reduced } from '@/components/motion'
import { addDays, cn, fmtCompact, fmtDay, fmtHour, fmtNum } from '@/lib/utils'
import type { TrafficReport } from '@/api/types'
import { useWidth } from './DashboardParts'

/** Axis maximum rounded up so four gridlines land on clean numbers. */
function niceMax(v: number) {
  const target = Math.max(4, v) / 4
  const p = 10 ** Math.floor(Math.log10(target))
  const mults = p >= 10 ? [1, 1.5, 2, 2.5, 3, 4, 5, 6, 7.5, 8, 10] : [1, 2, 3, 4, 5, 6, 8, 10]
  return mults.map((m) => m * p).find((s) => s >= target)! * 4
}

/** Monotone cubic curve (no overshoot below zero) through LTR points; `mx` mirrors x for RTL. */
function smoothPath(pts: [number, number][], mx: (x: number) => number) {
  const n = pts.length
  const P = (x: number, y: number) => `${mx(x).toFixed(1)},${y.toFixed(1)}`
  if (!n) return ''
  if (n === 1) return `M${P(...pts[0])}`
  const secant = (i: number) => (pts[i + 1][1] - pts[i][1]) / (pts[i + 1][0] - pts[i][0])
  const tan: number[] = new Array(n).fill(0)
  for (let i = 1; i < n - 1; i++) {
    const h0 = pts[i][0] - pts[i - 1][0], h1 = pts[i + 1][0] - pts[i][0]
    const s0 = secant(i - 1), s1 = secant(i)
    const p = (s0 * h1 + s1 * h0) / (h0 + h1)
    tan[i] = (Math.sign(s0) + Math.sign(s1)) * Math.min(Math.abs(s0), Math.abs(s1), 0.5 * Math.abs(p)) || 0
  }
  if (n === 2) { tan[0] = secant(0); tan[1] = secant(0) } else {
    tan[0] = (3 * secant(0) - tan[1]) / 2
    tan[n - 1] = (3 * secant(n - 2) - tan[n - 2]) / 2
  }
  let d = `M${P(...pts[0])}`
  for (let i = 0; i < n - 1; i++) {
    const [x0, y0] = pts[i], [x1, y1] = pts[i + 1], h = (x1 - x0) / 3
    d += `C${P(x0 + h, y0 + h * tan[i])} ${P(x1 - h, y1 - h * tan[i + 1])} ${P(x1, y1)}`
  }
  return d
}

const pickTicks = (n: number, k: number) => (n <= k ? Array.from({ length: n }, (_, i) => i) : [...new Set(Array.from({ length: k }, (_, j) => Math.round((j * (n - 1)) / (k - 1))))])

function LineKey({ color, dashed }: { color: string; dashed?: boolean }) {
  return <svg width="16" height="6" aria-hidden="true" className="shrink-0"><line x1="1" y1="3" x2="15" y2="3" stroke={color} strokeWidth="2" strokeLinecap="round" strokeDasharray={dashed ? '3 3' : undefined} /></svg>
}

/** Views per day (gold, solid) against the aligned day of the period before (slate, dashed). */
export function DailyChart({ rows, compareFrom }: { rows: TrafficReport['daily']; compareFrom: string }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const rtl = locale === 'ar'
  const [ref, width] = useWidth<HTMLDivElement>()
  const [active, setActive] = useState<number | null>(null)
  const lineRef = useRef<SVGPathElement>(null)
  const areaRef = useRef<SVGPathElement>(null)
  const gid = `area${useId().replace(/[^a-zA-Z0-9]/g, '')}`

  const n = rows.length
  const compact = width < 520
  const height = compact ? 200 : 250
  const pad = { top: 14, bottom: 30, axis: compact ? 34 : 44, end: compact ? 10 : 16 }
  const plotW = Math.max(1, width - pad.axis - pad.end)
  const plotH = height - pad.top - pad.bottom
  const max = niceMax(Math.max(0, ...rows.map((r) => Math.max(r.views, r.previousViews))))
  const ticks = [0, 1, 2, 3, 4].map((k) => (max / 4) * k)
  const lx = (i: number) => pad.axis + (n <= 1 ? plotW / 2 : (i / (n - 1)) * plotW)
  const mx = (x: number) => (rtl ? width - x : x)
  const y = (v: number) => pad.top + plotH - (v / max) * plotH
  const base = y(0)
  const linePath = smoothPath(rows.map((r, i) => [lx(i), y(r.views)]), mx)
  const prevPath = smoothPath(rows.map((r, i) => [lx(i), y(r.previousViews)]), mx)
  const areaPath = n > 1 ? `${linePath}L${mx(lx(n - 1)).toFixed(1)},${base}L${mx(lx(0)).toFixed(1)},${base}Z` : ''
  const xTicks = useMemo(() => pickTicks(n, compact ? 3 : 6), [n, compact])
  const empty = rows.every((r) => !r.views && !r.previousViews)
  const total = rows.reduce((a, r) => a + r.views, 0)
  const signature = `${rows[0]?.date}:${n}:${total}`
  const ready = width > 0

  // the current line draws itself in (reading direction) the first time it scrolls into view
  useEffect(() => {
    const line = lineRef.current, area = areaRef.current
    if (!line || reduced()) return
    const len = line.getTotalLength()
    if (!len) return
    const ctx = gsap.context(() => {
      const tl = gsap.timeline({ scrollTrigger: { trigger: line, start: 'top 95%', once: true } })
        .fromTo(line, { strokeDasharray: len, strokeDashoffset: len }, { strokeDashoffset: 0, duration: 1.2, ease: 'power2.out', clearProps: 'strokeDasharray,strokeDashoffset' })
      if (area) tl.fromTo(area, { opacity: 0 }, { opacity: 1, duration: 0.8, ease: 'power1.out' }, 0.35)
    })
    return () => ctx.revert()
  }, [signature, ready])

  const indexAt = (clientX: number, el: Element) => {
    if (n <= 1) return 0
    const px = clientX - el.getBoundingClientRect().left
    const x = rtl ? width - px : px
    return Math.min(n - 1, Math.max(0, Math.round(((x - pad.axis) / plotW) * (n - 1))))
  }
  const onPointer = (e: PointerEvent<SVGSVGElement>) => { if (n) setActive(indexAt(e.clientX, e.currentTarget)) }
  const onKey = (e: KeyboardEvent<SVGSVGElement>) => {
    if (!n) return
    const later = rtl ? 'ArrowLeft' : 'ArrowRight', earlier = rtl ? 'ArrowRight' : 'ArrowLeft'
    if (e.key === later || e.key === earlier) {
      e.preventDefault()
      setActive((a) => Math.min(n - 1, Math.max(0, (a ?? n - 1) + (e.key === later ? 1 : -1))))
    } else if (e.key === 'Home') setActive(0)
    else if (e.key === 'End') setActive(n - 1)
    else if (e.key === 'Escape') setActive(null)
  }

  const a = active !== null ? rows[active] : null
  const ax = active !== null ? mx(lx(active)) : 0
  const TIP_W = 184
  const tipLeft = ax + 14 + TIP_W <= width ? ax + 14 : Math.max(0, ax - 14 - TIP_W)

  return (
    <div ref={ref} className="relative w-full select-none" style={{ height }}>
      {ready && (
        <>
          <svg
            width={width} height={height} role="img" tabIndex={0}
            aria-label={t('admin.traffic.chartLabel', { total: fmtNum(total, locale), days: fmtNum(n, locale) })}
            className="block touch-pan-y rounded-lg"
            onPointerMove={onPointer} onPointerDown={onPointer}
            onPointerLeave={(e) => { if (e.pointerType === 'mouse') setActive(null) }}
            onKeyDown={onKey} onFocus={() => setActive((v) => v ?? n - 1)} onBlur={() => setActive(null)}
          >
            <defs>
              <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--viz-line)" stopOpacity="0.15" />
                <stop offset="100%" stopColor="var(--viz-line)" stopOpacity="0" />
              </linearGradient>
            </defs>
            {ticks.map((v) => <line key={v} x1={mx(pad.axis)} x2={mx(width - pad.end)} y1={y(v)} y2={y(v)} stroke="var(--viz-grid)" strokeWidth={1} />)}
            {areaPath && <path ref={areaRef} d={areaPath} fill={`url(#${gid})`} />}
            <path d={prevPath} fill="none" stroke="var(--viz-prev)" strokeWidth={1.75} strokeDasharray="5 5" strokeLinecap="round" />
            <path ref={lineRef} d={linePath} fill="none" stroke="var(--viz-line)" strokeWidth={2.25} strokeLinecap="round" strokeLinejoin="round" />
            {a ? (
              <g>
                <line x1={ax} x2={ax} y1={pad.top} y2={base} stroke="var(--fg-dim)" strokeWidth={1} />
                <circle cx={ax} cy={y(a.previousViews)} r={4} fill="var(--viz-prev)" stroke="var(--surface)" strokeWidth={2} />
                <circle cx={ax} cy={y(a.views)} r={5} fill="var(--viz-line)" stroke="var(--surface)" strokeWidth={2} />
              </g>
            ) : n > 0 && (
              <circle cx={mx(lx(n - 1))} cy={y(rows[n - 1].views)} r={4.5} fill="var(--viz-line)" stroke="var(--surface)" strokeWidth={2} />
            )}
          </svg>

          {ticks.map((v) => (
            <span key={v} aria-hidden="true" className="pointer-events-none absolute text-[10px] leading-none text-fg-dim tabular-nums"
              style={{ top: y(v) - 5, width: pad.axis - 8, ...(rtl ? { right: 0, textAlign: 'left' } : { left: 0, textAlign: 'right' }) }}>
              {fmtCompact(v, locale)}
            </span>
          ))}
          {xTicks.map((i) => {
            const x = mx(lx(i))
            const shift = x < 30 ? 'none' : x > width - 30 ? 'translateX(-100%)' : 'translateX(-50%)'
            return <span key={i} aria-hidden="true" className="pointer-events-none absolute whitespace-nowrap text-[10px] leading-none text-fg-dim" style={{ top: height - pad.bottom + 10, left: x, transform: shift }}>{fmtDay(rows[i].date, locale)}</span>
          })}

          {empty && <div className="pointer-events-none absolute inset-x-0 flex justify-center text-sm text-fg-muted" style={{ top: pad.top + plotH / 2 - 10 }}>{t('admin.traffic.noData')}</div>}

          {a && active !== null && (
            <div className="pointer-events-none absolute z-10 rounded-xl border border-line bg-surface p-3 text-xs shadow-calm" style={{ left: tipLeft, top: pad.top, width: TIP_W }}>
              <div className="mb-2 text-fg-muted">{fmtDay(a.date, locale, { weekday: 'long', day: 'numeric', month: 'short' })}</div>
              <div className="flex items-center gap-2">
                <LineKey color="var(--viz-line)" />
                <span className="text-base font-semibold leading-none text-fg">{fmtNum(a.views, locale)}</span>
                <span className="text-fg-muted">{t('admin.traffic.viewsWord')}</span>
              </div>
              <div className="mt-1.5 flex items-center gap-2">
                <LineKey color="var(--viz-prev)" dashed />
                <span className="font-medium text-fg">{fmtNum(a.previousViews, locale)}</span>
                <span className="text-fg-dim">{fmtDay(addDays(compareFrom, active), locale)}</span>
              </div>
              <div className="mt-1.5 text-fg-dim">{t('admin.traffic.visitorsN', { n: fmtNum(a.visitors, locale) })}</div>
            </div>
          )}
        </>
      )}
    </div>
  )
}

/** 24 bars of views per hour; the three busiest hours are gold. Hover / tap a bar for its numbers. */
export function HourlyBars({ hourly }: { hourly: TrafficReport['hourly'] }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [active, setActive] = useState<number | null>(null)
  const rows = [...hourly].sort((a, b) => a.hour - b.hour)
  const max = Math.max(1, ...rows.map((h) => h.views))
  const peak = new Set([...rows].sort((a, b) => b.views - a.views).slice(0, 3).filter((h) => h.views > 0).map((h) => h.hour))
  const at = (i: number, align: 'center' | 'auto'): CSSProperties => {
    const edge = align === 'auto' ? (i < 4 ? 'start' : i > 19 ? 'end' : 'center') : i === 0 ? 'start' : 'center'
    const shift = edge === 'start' ? 0 : edge === 'end' ? 100 : 50
    // logical offset from the reading start; the transform pulls the box back towards it
    return { insetInlineStart: `${((i + (edge === 'start' ? 0 : edge === 'end' ? 1 : 0.5)) / rows.length) * 100}%`, transform: `translateX(${locale === 'ar' ? shift : -shift}%)` }
  }
  const cur = active !== null ? rows.findIndex((h) => h.hour === active) : -1
  const h = cur >= 0 ? rows[cur] : null
  const tip = (x: { views: number; dreams: number }) => t('admin.traffic.hourTip', { views: fmtNum(x.views, locale), dreams: fmtNum(x.dreams, locale) })

  return (
    <div className="relative pt-11" onPointerLeave={(e) => { if (e.pointerType === 'mouse') setActive(null) }}>
      {h && (
        <div className="pointer-events-none absolute top-0 z-10 whitespace-nowrap rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs shadow-calm" style={at(cur, 'auto')}>
          <span className="font-semibold text-fg">{fmtHour(h.hour, locale)}</span>
          <span className="mx-1.5 text-fg-dim">·</span>
          <span className="text-fg-muted">{tip(h)}</span>
        </div>
      )}
      <div role="list" aria-label={t('admin.traffic.hourly')} className="flex h-28 items-end gap-[2px]">
        {rows.map((x) => (
          <div key={x.hour} role="listitem" aria-label={`${fmtHour(x.hour, locale)}: ${tip(x)}`}
            className="flex h-full flex-1 items-end justify-center"
            onPointerEnter={() => setActive(x.hour)} onPointerDown={() => setActive(x.hour)}>
            <div className={cn('w-full max-w-[22px] rounded-t-[4px] transition-[height,opacity] duration-700 ease-out', peak.has(x.hour) ? 'bg-viz-line' : 'bg-viz-bar', active !== null && active !== x.hour && 'opacity-50')}
              style={{ height: `${Math.max(3, (x.views / max) * 100)}%` }} />
          </div>
        ))}
      </div>
      <div aria-hidden="true" className="relative mt-2 h-4 text-[10px] text-fg-dim">
        {[0, 6, 12, 18].map((hour) => <span key={hour} className="absolute top-0 whitespace-nowrap" style={at(hour, 'center')}>{fmtHour(hour, locale)}</span>)}
      </div>
    </div>
  )
}
