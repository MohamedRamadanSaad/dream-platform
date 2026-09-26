// Night sky: three parallax star layers drifting, twinkling; a moon that floats and slowly rocks; a morphing gold halo.
import { useEffect, useRef } from 'react'
import gsap from 'gsap'
import { reduced } from './index'

function makeStars(n: number, seed: number) {
  let s = seed
  const rnd = () => { s = (s * 9301 + 49297) % 233280; return s / 233280 }
  return Array.from({ length: n }, (_, i) => ({ i, x: rnd() * 100, y: rnd() * 100, r: 0.6 + rnd() * 1.4, d: rnd() * 5 }))
}
const L1 = makeStars(70, 7), L2 = makeStars(45, 13), L3 = makeStars(25, 29)

export function NightSky({ className, withMoon = true }: { className?: string; withMoon?: boolean }) {
  const root = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = root.current
    if (!el || reduced()) return
    const ctx = gsap.context(() => {
      gsap.to('.sky-l1', { x: -40, duration: 120, repeat: -1, yoyo: true, ease: 'sine.inOut' })
      gsap.to('.sky-l2', { x: -90, y: 10, duration: 90, repeat: -1, yoyo: true, ease: 'sine.inOut' })
      gsap.to('.sky-l3', { x: -160, y: 20, duration: 70, repeat: -1, yoyo: true, ease: 'sine.inOut' })
      gsap.utils.toArray<SVGCircleElement>('.sky-star').forEach((s) => {
        gsap.to(s, { opacity: gsap.utils.random(0.2, 1), duration: gsap.utils.random(2, 5), repeat: -1, yoyo: true, ease: 'sine.inOut', delay: gsap.utils.random(0, 4) })
      })
      gsap.to('.sky-moon', { y: -14, rotate: -6, duration: 7, repeat: -1, yoyo: true, ease: 'sine.inOut', transformOrigin: '50% 50%' })
      gsap.to('.sky-halo', { scale: 1.08, opacity: 0.85, duration: 9, repeat: -1, yoyo: true, ease: 'sine.inOut', transformOrigin: '50% 50%' })
      const blob = el.querySelector<SVGPathElement>('.sky-blob')
      if (blob) {
        const shapes = [
          'M640 180 C790 180 880 270 880 380 C880 500 770 570 640 570 C500 570 400 490 400 380 C400 260 500 180 640 180 Z',
          'M640 160 C800 190 900 290 870 400 C840 520 740 580 630 570 C490 560 390 480 410 360 C430 250 520 150 640 160 Z',
          'M660 170 C780 150 900 250 890 370 C880 490 790 590 650 580 C510 570 380 500 400 370 C420 250 540 190 660 170 Z',
        ]
        const tl = gsap.timeline({ repeat: -1, yoyo: true })
        shapes.slice(1).forEach((sh) => tl.to(blob, { attr: { d: sh }, duration: 9, ease: 'sine.inOut' }))
      }
      // shooting star every ~12s
      const shoot = el.querySelector<SVGLineElement>('.sky-shoot')
      if (shoot) {
        const fire = () => {
          const x = gsap.utils.random(200, 1000), y = gsap.utils.random(40, 260)
          gsap.set(shoot, { attr: { x1: x, y1: y, x2: x, y2: y }, opacity: 0 })
          gsap.timeline({ onComplete: () => gsap.delayedCall(gsap.utils.random(8, 16), fire) })
            .to(shoot, { opacity: 0.9, duration: 0.15 })
            .to(shoot, { attr: { x2: x - 160, y2: y + 70 }, duration: 0.7, ease: 'power2.out' }, 0)
            .to(shoot, { opacity: 0, duration: 0.4 }, 0.5)
        }
        gsap.delayedCall(3, fire)
      }
    }, el)
    return () => ctx.revert()
  }, [])

  return (
    <div ref={root} className={className} aria-hidden="true">
      <svg className="absolute inset-0 h-full w-full" viewBox="0 0 1280 700" preserveAspectRatio="xMidYMid slice">
        <defs>
          <radialGradient id="halo" cx="50%" cy="50%" r="50%"><stop offset="0%" stopColor="#D4AF37" stopOpacity=".32" /><stop offset="100%" stopColor="#D4AF37" stopOpacity="0" /></radialGradient>
          <linearGradient id="shoot" x1="0" y1="0" x2="1" y2="0"><stop offset="0%" stopColor="#EADBAA" stopOpacity="0" /><stop offset="100%" stopColor="#EADBAA" /></linearGradient>
        </defs>
        <circle className="sky-halo" cx="640" cy="380" r="340" fill="url(#halo)" />
        <path className="sky-blob" fill="#16244A" opacity=".85" d="M640 180 C790 180 880 270 880 380 C880 500 770 570 640 570 C500 570 400 490 400 380 C400 260 500 180 640 180 Z" />
        <g className="sky-l1" fill="#EADBAA">{L1.map((s) => <circle key={s.i} className="sky-star" cx={s.x * 12.8} cy={s.y * 7} r={s.r * 0.7} opacity=".4" />)}</g>
        <g className="sky-l2" fill="#F4EFE6">{L2.map((s) => <circle key={s.i} className="sky-star" cx={s.x * 12.8} cy={s.y * 7} r={s.r} opacity=".5" />)}</g>
        <g className="sky-l3" fill="#FFFFFF">{L3.map((s) => <circle key={s.i} className="sky-star" cx={s.x * 12.8} cy={s.y * 7} r={s.r * 1.2} opacity=".7" />)}</g>
        <line className="sky-shoot" x1="0" y1="0" x2="0" y2="0" stroke="url(#shoot)" strokeWidth="1.5" strokeLinecap="round" opacity="0" />
        {withMoon && (
          <g className="sky-moon">
            <circle cx="300" cy="160" r="46" fill="#F4EFE6" opacity=".08" />
            <path d="M332 178a36 36 0 1 1-40-40 28 28 0 0 0 40 40z" fill="#EADBAA" />
            <circle cx="315" cy="150" r="3" fill="#D9C98F" opacity=".6" /><circle cx="328" cy="172" r="2" fill="#D9C98F" opacity=".5" />
          </g>
        )}
      </svg>
    </div>
  )
}
