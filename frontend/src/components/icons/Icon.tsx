// Inline SVG icons with a "before" and "after" path. On hover GSAP morphs between them.
// MorphSVGPlugin is used when available (gsap >= 3.13 ships it free); we also keep stroke-dash/rotate fallbacks.
import { useEffect, useRef, type SVGProps } from 'react'
import gsap from 'gsap'
import { MorphSVGPlugin } from 'gsap/MorphSVGPlugin'

gsap.registerPlugin(MorphSVGPlugin)

export type IconName = 'moon' | 'star' | 'scroll' | 'wallet' | 'user' | 'bell' | 'plus' | 'logout' | 'play' | 'chat' | 'chart' | 'globe' | 'gift' | 'clock' | 'check' | 'search' | 'settings' | 'sun' | 'youtube' | 'sparkle' | 'book' | 'quote' | 'shield' | 'heart'
  | 'download' | 'mail' | 'alert' | 'info' | 'bulb' | 'trophy' | 'eye' | 'flame' | 'filter' | 'menu' | 'link' | 'phone' | 'calendar'

interface Shape { before: string; after: string; fill?: boolean; extra?: JSX.Element }

const shapes: Record<IconName, Shape> = {
  moon: { before: 'M20 13.5A8.5 8.5 0 1 1 10.5 4a6.5 6.5 0 0 0 9.5 9.5z', after: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z' },
  // a sun with rays (a bare circle read as an empty button); hover lengthens the rays
  sun: { before: 'M12 16.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zM12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4', after: 'M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1.5v2.5M12 20v2.5M1.5 12H4M20 12h2.5M4.6 4.6l1.8 1.8M17.6 17.6l1.8 1.8M4.6 19.4l1.8-1.8M17.6 6.4l1.8-1.8' },
  star: { before: 'M12 3l2.4 5.2 5.6.6-4.2 3.8 1.2 5.6L12 15.4 7 18.2l1.2-5.6L4 8.8l5.6-.6z', after: 'M12 2l1.8 6.2 6.2 1.8-6.2 1.8L12 18l-1.8-6.2L4 10l6.2-1.8z', fill: true },
  sparkle: { before: 'M12 2l1.8 6.2 6.2 1.8-6.2 1.8L12 18l-1.8-6.2L4 10l6.2-1.8z', after: 'M12 4l1 4 4 1-4 1-1 4-1-4-4-1 4-1z', fill: true },
  scroll: { before: 'M6 3h11a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z', after: 'M5 4h14a1 1 0 0 1 1 1v3H4V5a1 1 0 0 1 1-1zM4 8h16v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z' },
  book: { before: 'M4 5a2 2 0 0 1 2-2h12v16H6a2 2 0 0 0-2 2z', after: 'M4 5a2 2 0 0 1 2-2h12v16H6a2 2 0 0 0-2 2zM4 19h14M8 7h6M8 10h4' },
  wallet: { before: 'M3 6h18v13H3zM3 10h18', after: 'M4 5h16v14H4zM4 9h16M15 13.5h2' },
  user: { before: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c0-4 3.6-7 8-7s8 3 8 7', after: 'M12 13a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zM3 22c0-4.5 4-8 9-8s9 3.5 9 8' },
  bell: { before: 'M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0', after: 'M7 16V11a5 5 0 0 1 10 0v5l1.5 2H5.5zM10 20a2 2 0 0 0 4 0' },
  plus: { before: 'M12 5v14M5 12h14', after: 'M12 4v16M4 12h16' },
  logout: { before: 'M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M14 8l4 4-4 4M18 12H9', after: 'M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M16 8l4 4-4 4M20 12H9' },
  play: { before: 'M2.5 5h19v14h-19zM10 9.5v5l4.5-2.5z', after: 'M2.5 5h19v14h-19zM9.5 9v6l6-3z' },
  youtube: { before: 'M22 8.2c-.2-1.1-1-1.9-2.1-2.1C18 5.7 12 5.7 12 5.7s-6 0-7.9.4C3 6.3 2.2 7.1 2 8.2 1.6 10 1.6 12 1.6 12s0 2 .4 3.8c.2 1.1 1 1.9 2.1 2.1 1.9.4 7.9.4 7.9.4s6 0 7.9-.4c1.1-.2 1.9-1 2.1-2.1.4-1.8.4-3.8.4-3.8s0-2-.4-3.8zM10 15V9l5.2 3z', after: 'M22 8.2c-.2-1.1-1-1.9-2.1-2.1C18 5.7 12 5.7 12 5.7s-6 0-7.9.4C3 6.3 2.2 7.1 2 8.2 1.6 10 1.6 12 1.6 12s0 2 .4 3.8c.2 1.1 1 1.9 2.1 2.1 1.9.4 7.9.4 7.9.4s6 0 7.9-.4c1.1-.2 1.9-1 2.1-2.1.4-1.8.4-3.8.4-3.8s0-2-.4-3.8zM9.5 15.5v-7l6.2 3.5z', fill: true },
  chat: { before: 'M4 5h16v11H8l-4 4z', after: 'M4 6h16v10H9l-5 4zM8 9h8M8 12h5' },
  chart: { before: 'M3 21h18M6 18v-6M11 18V8M16 18v-4', after: 'M3 21h18M6 18v-9M11 18V5M16 18v-7' },
  globe: { before: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18', after: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c4 3 4 15 0 18M12 3c-4 3-4 15 0 18' },
  gift: { before: 'M3 9h18v4H3zM5 13h14v8H5zM12 9v12M12 9c-2 0-4-1-4-3s3-2 4 3M12 9c2 0 4-1 4-3s-3-2-4 3', after: 'M3 9h18v4H3zM5 13h14v8H5zM12 9v12M12 9c-2.5 0-4.5-1.5-4.5-3.5S11 3 12 9M12 9c2.5 0 4.5-1.5 4.5-3.5S13 3 12 9' },
  clock: { before: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2', after: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l-3 3' },
  check: { before: 'M5 12l4 4L19 6', after: 'M4 12l5 5L20 6' },
  search: { before: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4', after: 'M11 17a6 6 0 1 0 0-12 6 6 0 0 0 0 12zM21 21l-5.5-5.5' },
  settings: { before: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19 12l2-1-1-3-2 .5-1.5-1.5.5-2-3-1-1 2h-2l-1-2-3 1 .5 2L6 8.5 4 8l-1 3 2 1v2l-2 1 1 3 2-.5 1.5 1.5-.5 2 3 1 1-2h2l1 2 3-1-.5-2 1.5-1.5 2 .5 1-3-2-1z', after: 'M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM19 12l2-1-1-3-2 .5-1.5-1.5.5-2-3-1-1 2h-2l-1-2-3 1 .5 2L6 8.5 4 8l-1 3 2 1v2l-2 1 1 3 2-.5 1.5 1.5-.5 2 3 1 1-2h2l1 2 3-1-.5-2 1.5-1.5 2 .5 1-3-2-1z' },
  quote: { before: 'M4 6h7v7H6l-2 3zM13 6h7v7h-5l-2 3z', after: 'M4 5h7v8H6l-2 4zM13 5h7v8h-5l-2 4z' },
  shield: { before: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z', after: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM9 12l2 2 4-4' },
  heart: { before: 'M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.5-7 10-7 10z', after: 'M12 21s-8-5-8-11a4.5 4.5 0 0 1 8-3 4.5 4.5 0 0 1 8 3c0 6-8 11-8 11z', fill: true },
  download: { before: 'M12 4v11M7.5 10.5L12 15l4.5-4.5M5 20h14', after: 'M12 3v13M7 11.5l5 5 5-5M4 20.5h16' },
  mail: { before: 'M3.5 6h17v12h-17zM3.5 7l8.5 6.5L20.5 7', after: 'M3.5 6h17v12h-17zM3.5 6l8.5 7.5L20.5 6' },
  alert: { before: 'M12 3.5l9.5 16.5h-19zM12 10v4.5M12 17.5v.5', after: 'M12 3l10 17.5H2zM12 9.5v5M12 17.5v.5' },
  info: { before: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5.5M12 7.5v.5', after: 'M12 21.5a9.5 9.5 0 1 0 0-19 9.5 9.5 0 0 0 0 19zM12 10.5v6M12 7v.5' },
  bulb: { before: 'M9.5 18h5M10.5 21h3M12 3a6 6 0 0 0-3.6 10.8c.7.6 1.1 1.3 1.1 2.2h5c0-.9.4-1.6 1.1-2.2A6 6 0 0 0 12 3z', after: 'M9.5 18h5M10.5 21h3M12 2.5a6.5 6.5 0 0 0-3.9 11.7c.7.6 1.1 1.2 1.1 1.8h5.6c0-.6.4-1.2 1.1-1.8A6.5 6.5 0 0 0 12 2.5z' },
  trophy: { before: 'M8 4h8v5a4 4 0 0 1-8 0zM8 6H5.5a2.5 2.5 0 0 0 2.6 3.5M16 6h2.5a2.5 2.5 0 0 1-2.6 3.5M12 13v4M8.5 20.5h7M9.5 17h5v3.5h-5z', after: 'M7.5 3.5h9V9a4.5 4.5 0 0 1-9 0zM7.5 5.5h-3a3 3 0 0 0 3.2 4.3M16.5 5.5h3a3 3 0 0 1-3.2 4.3M12 13.5V17M8 20.5h8M9.5 17h5v3.5h-5z' },
  eye: { before: 'M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', after: 'M2.5 12S6 6.5 12 6.5 21.5 12 21.5 12 18 17.5 12 17.5 2.5 12 2.5 12zM12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z' },
  flame: { before: 'M12 3c.8 3.6 5 5.4 5 10.2a5 5 0 0 1-10 0c0-2.4 1.3-3.9 2.4-4.9.2 1.8 1 2.9 2.2 3.4C11.2 9.2 10.8 6 12 3z', after: 'M12 2.5c1.2 3.8 5.5 5.2 5.5 10.7a5.5 5.5 0 0 1-11 0c0-2.7 1.4-4.4 2.6-5.4.3 2 1.2 3.2 2.4 3.6C11 9 10.5 5.5 12 2.5z' },
  filter: { before: 'M4 7h16M7 12h10M10 17h4', after: 'M4 6.5h16M6.5 12h11M9.5 17.5h5' },
  menu: { before: 'M4 7h16M4 12h16M4 17h16', after: 'M4 7h16M8 12h12M12 17h8' },
  link: { before: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1', after: 'M9.5 14.5a4.5 4.5 0 0 0 6.4 0l2.6-2.6a4.5 4.5 0 0 0-6.4-6.4l-.9.9M14.5 9.5a4.5 4.5 0 0 0-6.4 0l-2.6 2.6a4.5 4.5 0 0 0 6.4 6.4l.9-.9' },
  phone: { before: 'M7.5 3h9a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2', after: 'M8 2.5h8A1.5 1.5 0 0 1 17.5 4v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 20V4A1.5 1.5 0 0 1 8 2.5zM10.5 18.5h3' },
  calendar: { before: 'M4 6h16v14H4zM4 10h16M8 3.5v4M16 3.5v4', after: 'M4 6h16v14H4zM4 10h16M8 3v4.5M16 3v4.5M8 14h3' },
}

interface Props extends Omit<SVGProps<SVGSVGElement>, 'name'> {
  name: IconName
  size?: number
  /** external hover control (parent card); otherwise self hover */
  active?: boolean
  strokeWidth?: number
}

export function Icon({ name, size = 22, active, strokeWidth = 1.5, className, ...rest }: Props) {
  const ref = useRef<SVGPathElement>(null)
  const svgRef = useRef<SVGSVGElement>(null)
  const shape = shapes[name]
  const controlled = active !== undefined

  useEffect(() => {
    const path = ref.current
    if (!path) return
    const to = active ? shape.after : shape.before
    gsap.to(path, { morphSVG: { shape: to, shapeIndex: 'auto' }, duration: 0.7, ease: 'power3.out', overwrite: 'auto' })
    if (shape.fill) gsap.to(path, { fill: active ? 'currentColor' : 'transparent', duration: 0.5, overwrite: 'auto' })
    gsap.to(svgRef.current, { scale: active ? 1.12 : 1, rotate: active && (name === 'star' || name === 'sparkle') ? 144 : active && name === 'moon' ? -20 : 0, duration: 0.8, ease: 'power3.out', transformOrigin: '50% 50%', overwrite: 'auto' })
  }, [active, name, shape])

  const selfHover = controlled ? {} : {
    onMouseEnter: () => { const p = ref.current; if (!p) return; gsap.to(p, { morphSVG: shape.after, duration: 0.7, ease: 'power3.out' }); if (shape.fill) gsap.to(p, { fill: 'currentColor', duration: .5 }); gsap.to(svgRef.current, { scale: 1.12, duration: .6 }) },
    onMouseLeave: () => { const p = ref.current; if (!p) return; gsap.to(p, { morphSVG: shape.before, duration: 0.7, ease: 'power3.out' }); if (shape.fill) gsap.to(p, { fill: 'transparent', duration: .5 }); gsap.to(svgRef.current, { scale: 1, duration: .6 }) },
  }

  return (
    <svg ref={svgRef} width={size} height={size} viewBox="0 0 24 24" fill={shape.fill && name === 'youtube' ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" className={className} style={{ overflow: 'visible' }} aria-hidden="true" {...selfHover} {...rest}>
      <path ref={ref} d={shape.before} fill={name === 'youtube' ? 'currentColor' : shape.fill ? 'transparent' : 'none'} />
    </svg>
  )
}
