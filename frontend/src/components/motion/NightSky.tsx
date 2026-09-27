// Night sky: parallax layers of real star shapes (twinkling, mixed sizes, a few glowing), frequent meteors,
// and a moon that crosses the sky on an arc while changing phase: rises on the right as a thin crescent,
// becomes full at the zenith, wanes to a crescent on the left and fades out — then repeats.
import { useEffect, useRef } from 'react'
import gsap from 'gsap'
import { reduced } from './index'

function rng(seed: number) {
  let s = seed
  return () => { s = (s * 9301 + 49297) % 233280; return s / 233280 }
}
/** 5-point star path centred on (0,0) with outer radius r. */
function starPath(r: number) {
  const pts: string[] = []
  for (let i = 0; i < 10; i++) {
    const rad = i % 2 === 0 ? r : r * 0.42
    const a = -Math.PI / 2 + (i * Math.PI) / 5
    pts.push(`${(Math.cos(a) * rad).toFixed(2)},${(Math.sin(a) * rad).toFixed(2)}`)
  }
  return `M${pts.join('L')}Z`
}
interface Star { i: number; x: number; y: number; r: number; rot: number; glow: boolean }
function makeStars(n: number, seed: number, min: number, max: number, glowEvery = 0): Star[] {
  const rnd = rng(seed)
  return Array.from({ length: n }, (_, i) => ({
    i, x: rnd() * 1280, y: rnd() * 700, r: min + rnd() * (max - min), rot: rnd() * 72,
    glow: glowEvery > 0 && i % glowEvery === 0,
  }))
}
// far (tiny, many) → near (bigger, fewer, some glowing)
const FAR = makeStars(110, 7, 1.2, 2.2)
const MID = makeStars(60, 13, 2.2, 3.6)
const NEAR = makeStars(26, 29, 3.8, 6.5, 4)
const DOTS = makeStars(90, 41, 0.5, 1.1) // dust: plain dots so the sky is not all pointy shapes

const MOON_R = 34
const ARC = { x0: 1360, x1: -80, yBase: 430, yTop: 95 }

export function NightSky({ className, withMoon = true }: { className?: string; withMoon?: boolean }) {
  const root = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = root.current
    if (!el || reduced()) return
    const ctx = gsap.context(() => {
      // parallax drift
      gsap.to('.sky-far', { x: -30, duration: 140, repeat: -1, yoyo: true, ease: 'sine.inOut' })
      gsap.to('.sky-mid', { x: -70, y: 8, duration: 100, repeat: -1, yoyo: true, ease: 'sine.inOut' })
      gsap.to('.sky-near', { x: -130, y: 16, duration: 80, repeat: -1, yoyo: true, ease: 'sine.inOut' })
      // twinkle: opacity + a little breathing scale; big ones rotate imperceptibly
      gsap.utils.toArray<SVGElement>('.sky-star').forEach((s) => {
        const big = s.classList.contains('sky-star-big')
        gsap.to(s, {
          opacity: gsap.utils.random(big ? 0.55 : 0.15, 1), scale: gsap.utils.random(0.75, 1.25),
          duration: gsap.utils.random(1.6, 4.5), repeat: -1, yoyo: true, ease: 'sine.inOut', delay: gsap.utils.random(0, 4),
          transformOrigin: '50% 50%',
        })
        if (big) gsap.to(s, { rotation: '+=20', duration: gsap.utils.random(30, 60), repeat: -1, yoyo: true, ease: 'sine.inOut', transformOrigin: '50% 50%' })
      })
      gsap.utils.toArray<SVGElement>('.sky-dot').forEach((s) => {
        gsap.to(s, { opacity: gsap.utils.random(0.1, 0.7), duration: gsap.utils.random(2, 6), repeat: -1, yoyo: true, ease: 'sine.inOut', delay: gsap.utils.random(0, 5) })
      })
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

      // meteors: three independent streaks, each re-firing every few seconds
      gsap.utils.toArray<SVGLineElement>('.sky-shoot').forEach((shoot, idx) => {
        const fire = () => {
          const x = gsap.utils.random(150, 1250), y = gsap.utils.random(20, 320)
          const len = gsap.utils.random(120, 260), drop = len * gsap.utils.random(0.35, 0.6)
          gsap.set(shoot, { attr: { x1: x, y1: y, x2: x, y2: y }, opacity: 0 })
          gsap.timeline({ onComplete: () => gsap.delayedCall(gsap.utils.random(2.5, 7), fire) })
            .to(shoot, { opacity: 0.95, duration: 0.12 })
            .to(shoot, { attr: { x2: x - len, y2: y + drop }, duration: gsap.utils.random(0.55, 0.9), ease: 'power2.out' }, 0)
            .to(shoot, { attr: { x1: x - len, y1: y + drop }, duration: 0.45, ease: 'power2.in' }, 0.35)
            .to(shoot, { opacity: 0, duration: 0.3 }, 0.55)
        }
        gsap.delayedCall(1.5 + idx * 2.2, fire)
      })

      // moon: arc + phases. Lit disc under a dark disc clipped to the moon; the dark disc slides across so the
      // lit part grows from a right-hand crescent to full, then shrinks to a left-hand crescent.
      const moon = el.querySelector<SVGGElement>('.sky-moon')
      const shadow = el.querySelector<SVGCircleElement>('.sky-moon-shadow')
      const glow = el.querySelector<SVGCircleElement>('.sky-moon-glow')
      if (moon && shadow && glow) {
        const state = { p: 0 }
        const render = () => {
          const p = state.p
          const x = ARC.x0 + (ARC.x1 - ARC.x0) * p
          const y = ARC.yBase - (ARC.yBase - ARC.yTop) * Math.sin(Math.PI * p)
          // phase: 0 → thin waxing crescent (dark disc shifted left), .5 → full, 1 → thin waning crescent
          const thin = 0.28 * MOON_R, gone = 2.15 * MOON_R
          const dx = p < 0.5 ? -(thin + (gone - thin) * (p / 0.5)) : thin + (gone - thin) * ((1 - p) / 0.5)
          // fade in/out near the horizons
          const edge = Math.min(1, Math.min(p, 1 - p) / 0.1)
          gsap.set(moon, { attr: { transform: `translate(${x.toFixed(1)} ${y.toFixed(1)})` }, opacity: edge })
          gsap.set(shadow, { attr: { cx: dx.toFixed(2) } })
          gsap.set(glow, { opacity: 0.05 + 0.2 * Math.sin(Math.PI * p) })
        }
        render()
        gsap.to(state, { p: 1, duration: 48, ease: 'none', repeat: -1, repeatDelay: 3, onUpdate: render })
      }
    }, el)
    return () => ctx.revert()
  }, [])

  return (
    <div ref={root} className={className} aria-hidden="true">
      <svg className="absolute inset-0 h-full w-full" viewBox="0 0 1280 700" preserveAspectRatio="xMidYMid slice">
        <defs>
          <radialGradient id="halo" cx="50%" cy="50%" r="50%"><stop offset="0%" stopColor="#D4AF37" stopOpacity=".32" /><stop offset="100%" stopColor="#D4AF37" stopOpacity="0" /></radialGradient>
          <radialGradient id="moonglow" cx="50%" cy="50%" r="50%"><stop offset="0%" stopColor="#EADBAA" stopOpacity=".9" /><stop offset="55%" stopColor="#EADBAA" stopOpacity=".25" /><stop offset="100%" stopColor="#EADBAA" stopOpacity="0" /></radialGradient>
          <linearGradient id="shoot" x1="0" y1="0" x2="1" y2="0"><stop offset="0%" stopColor="#EADBAA" stopOpacity="0" /><stop offset="100%" stopColor="#FFFFFF" /></linearGradient>
          <filter id="starglow" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="1.6" result="b" /><feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge></filter>
          <clipPath id="moonclip"><circle cx="0" cy="0" r={MOON_R} /></clipPath>
        </defs>
        <circle className="sky-halo" cx="640" cy="380" r="340" fill="url(#halo)" />
        <path className="sky-blob" fill="#16244A" opacity=".85" d="M640 180 C790 180 880 270 880 380 C880 500 770 570 640 570 C500 570 400 490 400 380 C400 260 500 180 640 180 Z" />

        <g className="sky-far" fill="#EADBAA">
          {DOTS.map((s) => <circle key={`d${s.i}`} className="sky-dot" cx={s.x} cy={s.y} r={s.r} opacity=".35" />)}
          {FAR.map((s) => <path key={s.i} className="sky-star" d={starPath(s.r)} transform={`translate(${s.x} ${s.y}) rotate(${s.rot})`} opacity=".45" />)}
        </g>
        <g className="sky-mid" fill="#F4EFE6">
          {MID.map((s) => <path key={s.i} className="sky-star" d={starPath(s.r)} transform={`translate(${s.x} ${s.y}) rotate(${s.rot})`} opacity=".6" />)}
        </g>
        <g className="sky-near" fill="#FFFFFF">
          {NEAR.map((s) => <path key={s.i} className={`sky-star sky-star-big${s.glow ? ' sky-star-glow' : ''}`} d={starPath(s.r)} transform={`translate(${s.x} ${s.y}) rotate(${s.rot})`} opacity=".85" filter={s.glow ? 'url(#starglow)' : undefined} />)}
        </g>

        <line className="sky-shoot" x1="0" y1="0" x2="0" y2="0" stroke="url(#shoot)" strokeWidth="1.6" strokeLinecap="round" opacity="0" />
        <line className="sky-shoot" x1="0" y1="0" x2="0" y2="0" stroke="url(#shoot)" strokeWidth="1.2" strokeLinecap="round" opacity="0" />
        <line className="sky-shoot" x1="0" y1="0" x2="0" y2="0" stroke="url(#shoot)" strokeWidth="2" strokeLinecap="round" opacity="0" />

        {withMoon && (
          <g className="sky-moon" transform={`translate(${ARC.x0} ${ARC.yBase})`} opacity="0">
            <circle className="sky-moon-glow" cx="0" cy="0" r={MOON_R * 2.6} fill="url(#moonglow)" opacity=".1" />
            <g clipPath="url(#moonclip)">
              <circle cx="0" cy="0" r={MOON_R} fill="#EADBAA" />
              <circle cx="-12" cy="-9" r="5" fill="#D9C98F" opacity=".55" /><circle cx="9" cy="10" r="3.5" fill="#D9C98F" opacity=".5" /><circle cx="6" cy="-14" r="2.5" fill="#D9C98F" opacity=".4" />
              <circle className="sky-moon-shadow" cx={-0.28 * MOON_R} cy="0" r={MOON_R} fill="#0a1128" opacity=".97" />
            </g>
          </g>
        )}
      </svg>
    </div>
  )
}
