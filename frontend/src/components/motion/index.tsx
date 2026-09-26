// Motion helpers: text reveal, fade-up on scroll, morphing blob. GSAP-driven, honoring reduced motion.
import { useEffect, useRef, type ReactNode } from 'react'
import gsap from 'gsap'
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
export function CountUp({ to, suffix = '', className }: { to: number; suffix?: string; className?: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (reduced()) { el.textContent = `${to}${suffix}`; return }
    const o = { v: 0 }
    const ctx = gsap.context(() => { gsap.to(o, { v: to, duration: 1.8, ease: 'power2.out', scrollTrigger: { trigger: el, start: 'top 90%', once: true }, onUpdate: () => { el.textContent = `${Math.round(o.v)}${suffix}` } }) }, el)
    return () => ctx.revert()
  }, [to, suffix])
  return <span ref={ref} className={className}>0{suffix}</span>
}
