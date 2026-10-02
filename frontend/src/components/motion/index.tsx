// Motion helpers: text reveal, fade-up on scroll, morphing blob. GSAP-driven, honoring reduced motion.
import { useEffect, useRef, type ReactNode } from 'react'
import gsap from 'gsap'
import { cn } from '@/lib/utils'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { SplitText } from 'gsap/SplitText'

gsap.registerPlugin(ScrollTrigger, SplitText)

export const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

/** Words rise in one after another the first time the element scrolls into view. */
export function Reveal({ children, as: Tag = 'div', className, delay = 0, split = false }: { children?: ReactNode; as?: keyof JSX.IntrinsicElements; className?: string; delay?: number; split?: boolean }) {
  const ref = useRef<HTMLElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el || reduced()) return
    let st: SplitText | null = null
    const ctx = gsap.context(() => {
      let targets: Element[] = [el]
      if (split) { st = new SplitText(el, { type: 'words', wordsClass: 'inline-block' }); targets = st.words }
      gsap.from(targets, { y: 24, opacity: 0, duration: 0.9, ease: 'power3.out', stagger: split ? 0.05 : 0, delay, scrollTrigger: { trigger: el, start: 'top 88%', once: true } })
    }, el)
    return () => { ctx.revert(); st?.revert() }
  }, [delay, split])
  const C = Tag as unknown as 'div'
  return <C ref={ref as never} className={className}>{children}</C>
}

/** Children fade-up with stagger when the container enters the viewport. */
export function StaggerGroup({ children, className, stagger = 0.12 }: { children: ReactNode; className?: string; stagger?: number }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el || reduced()) return
    const ctx = gsap.context(() => { gsap.from(el.children, { y: 30, opacity: 0, duration: 0.9, ease: 'power3.out', stagger, scrollTrigger: { trigger: el, start: 'top 85%', once: true } }) }, el)
    return () => ctx.revert()
  }, [stagger])
  return <div ref={ref} className={className}>{children}</div>
}

/** Page-level enter: fades the whole route in. */
export function PageEnter({ children, className }: { children: ReactNode; className?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!ref.current || reduced()) return
    const ctx = gsap.context(() => { gsap.from(ref.current, { opacity: 0, y: 12, duration: 0.5, ease: 'power2.out' }) }, ref)
    return () => ctx.revert()
  }, [])
  return <div ref={ref} className={className}>{children}</div>
}

/** Counts a number up when it scrolls into view. */
/** Parses "1M+", "50K+", "230+" → full value, compact label and trailing sign. */
export function parseStat(raw: string): { full: number; compact: string; sign: string } {
  const m = /^\s*([\d.,]+)\s*([KkMmBb]?)\s*(\+?)\s*$/.exec(raw) ?? []
  const n = Number(String(m[1] ?? '0').replace(/,/g, '')) || 0
  const unit = (m[2] ?? '').toUpperCase()
  const mult = unit === 'K' ? 1e3 : unit === 'M' ? 1e6 : unit === 'B' ? 1e9 : 1
  return { full: Math.round(n * mult), compact: `${m[1] ?? n}${unit}`, sign: m[3] ?? '' }
}

/**
 * Counts up through the FULL number with digit grouping (0 → 1,000,000), then collapses to the compact
 * label ("1M+") once it lands — so a million reads like a million, not like a one.
 */
const enDigits = (v: number) => Math.round(v).toLocaleString('en-US')

export function CountUp({ to, suffix = '', compact, className, format = enDigits }: { to: number; suffix?: string; compact?: string; className?: string; /** digit grouping per locale; defaults to en-US */ format?: (n: number) => string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const fmtRef = useRef(format)
  fmtRef.current = format
  const final = `${compact ?? format(to)}${suffix}`
  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (reduced() || typeof IntersectionObserver === 'undefined') { el.textContent = final; return }
    const o = { v: 0 }
    const dur = to >= 1e6 ? 3.2 : to >= 1e3 ? 2.4 : 1.8
    let ctx: gsap.Context | null = null
    const run = () => {
      ctx = gsap.context(() => {
        const tl = gsap.timeline()
          .to(o, { v: to, duration: dur, ease: 'power3.out', onUpdate: () => { el.textContent = `${fmtRef.current(Math.round(o.v))}${suffix}` } })
        // the compact label (1M+) lands with a small pop; a full number simply settles
        if (compact) {
          tl.to(el, { opacity: 0.2, scale: 0.94, duration: 0.18, ease: 'power1.in', onComplete: () => { el.textContent = final } })
            .to(el, { opacity: 1, scale: 1, duration: 0.35, ease: 'back.out(2)' })
        } else {
          tl.call(() => { el.textContent = final })
        }
      }, el)
    }
    // Starts when the number itself becomes visible. An IntersectionObserver follows the element wherever
    // layout moves it (sections above loading or shrinking), unlike a scroll position computed once.
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) { io.disconnect(); run() }
    }, { threshold: 0.2 })
    io.observe(el)
    return () => { io.disconnect(); ctx?.revert(); el.textContent = final }
  }, [to, suffix, final, compact])
  return <span ref={ref} className={cn('inline-block tabular-nums', className)}>{format(0)}{suffix}</span>
}
