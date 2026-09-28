import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import gsap from 'gsap'
import { SplitText } from 'gsap/SplitText'
import { publicApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { NightSky, type MoonPos } from '@/components/motion/NightSky'
import { Reveal, StaggerGroup, CountUp, reduced } from '@/components/motion'
import { PublicHeader, Footer } from '@/components/layout'
import { Icon } from '@/components/icons/Icon'
import { Kicker, Skeleton, Stars } from '@/components/ui'
import { cn, fmtMoney, countdown } from '@/lib/utils'
import type { PackageDto } from '@/api/types'

const YT = (import.meta.env.VITE_YOUTUBE_URL as string) || 'https://youtube.com/@almoaberafatema'

/** Trust badges: one compact row under the CTA, each drifting gently up/down. */
function FloatingBadges() {
  const { t } = useTranslation()
  const root = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (reduced() || !root.current) return
    const ctx = gsap.context(() => {
      gsap.utils.toArray<HTMLElement>('.float-badge').forEach((b, i) => {
        gsap.fromTo(b, { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.9, delay: 1.3 + i * 0.15, ease: 'power3.out' })
        gsap.to(b, { y: i % 2 ? 6 : -6, duration: 3 + i * 0.5, repeat: -1, yoyo: true, ease: 'sine.inOut', delay: 2.2 + i * 0.15 })
      })
    }, root.current)
    return () => ctx.revert()
  }, [])
  // three-up on every screen: stacked (icon over text) on phones, icon beside text from md up
  const badge = 'float-badge flex flex-col items-center gap-1.5 rounded-2xl border border-navy/80 bg-night/60 px-2 py-2.5 text-center backdrop-blur-md md:flex-row md:gap-2.5 md:px-3.5 md:text-start'
  const ico = 'flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold'
  return (
    <div ref={root} className="mt-2 grid w-full max-w-2xl grid-cols-3 gap-2 md:w-auto md:gap-2.5">
      <div className={badge}><span className={ico}><Icon name="sparkle" size={16} /></span><div><div className="font-quran text-sm leading-tight text-gold-soft md:text-base">{t('hero.badges.verse')}</div><div className="text-[10px] text-pearl/45">{t('hero.badges.verseSrc')}</div></div></div>
      <div className={badge}><span className={ico}><Icon name="shield" size={16} /></span><div><div className="text-[11px] font-medium leading-tight text-pearl md:text-xs">{t('hero.badges.privacy')}</div><div className="hidden text-[10px] text-pearl/45 md:block">{t('hero.badges.privacySub')}</div></div></div>
      <div className={badge}><span className={ico}><Icon name="book" size={16} /></span><div><div className="text-[11px] font-medium leading-tight text-pearl md:text-xs">{t('hero.badges.depth')}</div><div className="hidden text-[10px] text-pearl/45 md:block">{t('hero.badges.depthSub')}</div></div></div>
    </div>
  )
}

function Hero() {
  const { t } = useTranslation()
  const { data: wait } = useQuery({ queryKey: ['public', 'wait-time'], queryFn: publicApi.waitTime })
  const titleRef = useRef<HTMLHeadingElement>(null)
  const leadRef = useRef<HTMLParagraphElement>(null)
  const ctaRef = useRef<HTMLDivElement>(null)
  const sectionRef = useRef<HTMLElement>(null)
  const hadithOverlayRef = useRef<HTMLDivElement>(null)
  // The bright copy of the sky hadith is clipped to the moon disc so the letters stay readable on top of it.
  const onMoon = useCallback((m: MoonPos) => {
    const o = hadithOverlayRef.current, s = sectionRef.current
    if (!o || !s) return
    if (!m.visible) { o.style.clipPath = 'circle(0 at 0 0)'; return }
    const sr = s.getBoundingClientRect(), or = o.getBoundingClientRect()
    o.style.clipPath = `circle(${m.r}px at ${m.cx - (or.left - sr.left)}px ${m.cy - (or.top - sr.top)}px)`
  }, [])

  useEffect(() => {
    if (reduced() || !titleRef.current) return
    // Arabic must never be split into characters (it breaks letter joining) — words only.
    let st: SplitText | null = null
    const ctx = gsap.context(() => {
      st = new SplitText(titleRef.current!, { type: 'words', wordsClass: 'inline-block' })
      gsap.timeline()
        .from(st.words, { opacity: 0, y: 30, filter: 'blur(6px)', duration: 1, ease: 'power3.out', stagger: 0.08 })
        .fromTo(leadRef.current, { opacity: 0, y: 20 }, { opacity: 1, y: 0, duration: 0.9, ease: 'power3.out' }, '-=0.5')
        .fromTo(Array.from(ctaRef.current!.querySelectorAll('a')), { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.7, stagger: 0.12, ease: 'power3.out', clearProps: 'transform' }, '-=0.5')
    })
    return () => { ctx.revert(); st?.revert() }
  }, [])

  return (
    <section ref={sectionRef} className="relative overflow-hidden bg-night text-pearl">
      <NightSky className="absolute inset-0" onMoon={onMoon} />
      <PublicHeader />
      {/* written in the sky: sits under the moon overlay (NightSky raises the moon above it) */}
      <div className="sky-hadith pointer-events-none relative mx-auto max-w-3xl px-6 pt-6 text-center md:pt-8">
        {/* faint copy: sits under the moon */}
        <p className="font-quran text-lg leading-relaxed text-gold-soft/45 md:text-2xl [text-shadow:0_0_18px_rgba(234,219,170,.25)]">{t('hero.skyHadith')}</p>
        <p className="mt-1 text-[10px] tracking-[.3em] text-pearl/30">{t('hero.skyHadithSrc')}</p>
        {/* bright copy: above the moon, clipped to its disc (see onMoon) */}
        <div ref={hadithOverlayRef} aria-hidden="true" className="absolute inset-0 z-20 px-6 pt-6 md:pt-8" style={{ clipPath: 'circle(0 at 0 0)' }}>
          <p className="font-quran text-lg leading-relaxed text-gold md:text-2xl [-webkit-text-stroke:0.6px_#16244a] [text-shadow:0_0_6px_rgba(22,36,74,.9),0_0_14px_rgba(212,175,55,.8)]">{t('hero.skyHadith')}</p>
          <p className="mt-1 text-[10px] tracking-[.3em] text-navy">{t('hero.skyHadithSrc')}</p>
        </div>
      </div>
      <div className="relative mx-auto flex max-w-4xl flex-col items-center gap-7 px-5 pb-24 pt-8 text-center md:pt-12 lg:px-0">
        <h1 ref={titleRef} className="font-display text-5xl leading-[1.35] md:text-7xl">
          {t('hero.title1')} <span className="text-gold">{t('hero.title2')}</span><br />{t('hero.title3')}
        </h1>
        <p ref={leadRef} className="max-w-2xl text-lg font-light leading-loose text-pearl/70 md:text-xl">{t('hero.lead')}</p>
        <div ref={ctaRef} className="flex flex-wrap items-start justify-center gap-3">
          <div className="flex flex-col items-center gap-2">
            <Link to="/me/new" className="btn btn-lg btn-gold">{t('hero.cta')}</Link>
            {wait && (
              <div className="flex items-center gap-2 text-sm text-pearl/60">
                <span className="h-2 w-2 rounded-full bg-gold animate-[twinkle_2.4s_ease-in-out_infinite] shadow-[0_0_10px_rgba(212,175,55,.9)]" />
                {t('hero.badges.reply')}: {wait.busy ? t('waitTime.range', { min: wait.minDays, max: wait.maxDays }) : t('waitTime.hours', { h: wait.hours })}
              </div>
            )}
          </div>
          <a href={YT} target="_blank" rel="noreferrer" className="btn btn-lg border border-navy text-gold-soft hover:border-gold"><span className="text-[#FF0000]"><Icon name="youtube" size={20} /></span>{t('hero.youtube')}</a>
        </div>
        <FloatingBadges />
        <div className="mt-6 border-t border-navy pt-6">
          <p className="font-quran text-2xl text-gold-soft md:text-3xl">{t('hero.hadith')}</p>
          <p className="mt-1 text-xs text-pearl/50">{t('hero.hadithSrc')}</p>
        </div>
      </div>
    </section>
  )
}

function MethodCard({ icon, title, text }: { icon: Parameters<typeof Icon>[0]['name']; title: string; text: string }) {
  const [h, setH] = useState(false)
  return (
    <div className="card card-hover p-7" onMouseEnter={() => setH(true)} onMouseLeave={() => setH(false)}>
      <div className="mb-4 text-fg"><Icon name={icon} size={36} active={h} strokeWidth={1.2} /></div>
      <div className="mb-2 text-xl font-medium">{title}</div>
      <p className="text-sm font-light leading-relaxed text-fg-muted">{text}</p>
    </div>
  )
}

function Countdown({ iso }: { iso: string }) {
  const { t } = useTranslation()
  const [c, setC] = useState(countdown(iso))
  useEffect(() => { const id = setInterval(() => setC(countdown(iso)), 60_000); return () => clearInterval(id) }, [iso])
  return <span className="text-xs">{t('packages.endsIn')} {c.d}{t('common.days').slice(0, 1)} {c.h}{t('common.hours').slice(0, 1)}</span>
}

export function PackageCard({ p, featured, onChoose }: { p: PackageDto; featured?: boolean; onChoose: (p: PackageDto) => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [h, setH] = useState(false)
  return (
    <div onMouseEnter={() => setH(true)} onMouseLeave={() => setH(false)} className={cn('card card-hover relative flex flex-col items-center gap-3 p-8 text-center', featured && 'bg-night text-pearl border-gold')}>
      {p.badge && <span className="absolute -top-3 rounded-full bg-gold px-3 py-1 text-xs font-bold text-night">{p.badge}</span>}
      <div className={featured ? 'text-gold' : 'text-fg'}><Icon name={p.credits === 1 ? 'star' : p.credits === 2 ? 'sparkle' : 'moon'} size={34} active={h} strokeWidth={1.2} /></div>
      <div className="font-display text-2xl">{p.name}</div>
      <p className={cn('text-sm font-light leading-relaxed', featured ? 'text-pearl/70' : 'text-fg-muted')}>{p.description}</p>
      <div className="mt-2 flex items-baseline gap-2">
        <span className="text-4xl font-medium">{fmtMoney(p.price, p.currency, locale)}</span>
        {p.originalPrice && <span className={cn('text-sm line-through', featured ? 'text-pearl/40' : 'text-fg-dim')}>{fmtMoney(p.originalPrice, p.currency, locale)}</span>}
      </div>
      {p.promotion && <div className={cn('chip', featured ? 'bg-gold/20 text-gold-soft' : 'bg-gold/10 text-gold-deep')}>{p.promotion.label} · <Countdown iso={p.promotion.endsAt} /></div>}
      <div className={cn('text-xs', featured ? 'text-pearl/50' : 'text-fg-dim')}>{t('packages.dreams', { count: p.credits })}{p.validityMonths ? ` · ${t('packages.validity', { m: p.validityMonths })}` : ''}</div>
      <button onClick={() => onChoose(p)} className={cn('btn btn-md mt-3 w-full', featured ? 'btn-gold' : 'btn-ghost border-fg text-fg')}>{t('packages.choose')}</button>
    </div>
  )
}

export function PackagesSection({ onChoose }: { onChoose: (p: PackageDto) => void }) {
  const { t } = useTranslation()
  const { data, isLoading } = useQuery({ queryKey: ['public', 'catalog'], queryFn: publicApi.catalog })
  return (
    <section id="packages" className="mx-auto max-w-6xl px-5 py-20 md:px-8">
      <div className="mb-10 flex flex-col items-center gap-3 text-center">
        <Reveal as="h2" className="font-display text-4xl md:text-5xl" split>{t('packages.title')}</Reveal>
        <Reveal as="p" className="max-w-2xl font-light leading-relaxed text-fg-muted" delay={0.2}>{t('packages.lead')}</Reveal>
        {data && <Reveal delay={0.3} className="chip border border-line bg-surface text-xs text-fg-muted"><Icon name="globe" size={14} />{t('packages.shownFor', { country: data.countryName })}</Reveal>}
      </div>
      {isLoading ? (
        <div className="grid gap-6 md:grid-cols-3">{[1, 2, 3].map((i) => <Skeleton key={i} className="h-80" />)}</div>
      ) : (
        <StaggerGroup className="grid gap-6 md:grid-cols-3">
          {data?.packages.map((p, i) => <PackageCard key={p.id} p={p} featured={i === 1} onChoose={onChoose} />)}
        </StaggerGroup>
      )}
    </section>
  )
}

export default function LandingPage() {
  const { t } = useTranslation()
  const { data: stats } = useQuery({ queryKey: ['public', 'stats'], queryFn: publicApi.stats })
  const { data: tst } = useQuery({ queryKey: ['public', 'testimonials'], queryFn: publicApi.testimonials })
  const locale = useAuthStore((s) => s.locale)
  const user = useAuthStore((s) => s.user)
  const choose = (p: PackageDto) => { window.location.href = user ? `/me/checkout?package=${p.id}` : `/login?next=${encodeURIComponent(`/me/checkout?package=${p.id}`)}` }
  const num = (s?: string) => Number((s ?? '0').replace(/\D/g, '')) || 0
  const suf = (s?: string) => (s ?? '').replace(/[\d,]/g, '')

  return (
    <div>
      <Hero />

      <section id="about" className="mx-auto grid max-w-6xl gap-12 px-5 py-20 md:grid-cols-12 md:px-8">
        <div className="md:col-span-5 flex flex-col gap-4">
          <Reveal><Kicker>{t('about.kicker')}</Kicker></Reveal>
          <Reveal as="h2" className="font-display text-5xl text-night dark:text-pearl" split>{t('about.title')}</Reveal>
          <Reveal className="h-px w-16 bg-gold" />
          <Reveal as="p" className="font-medium leading-loose" delay={0.15}>{t('about.experience')}</Reveal>
        </div>
        <StaggerGroup className="md:col-span-7 flex flex-col gap-5 font-light leading-loose text-fg-muted">
          <p>{t('about.p1')}</p><p>{t('about.p2')}</p><p>{t('about.p3')}</p>
        </StaggerGroup>
      </section>

      <Reveal className="mx-5 md:mx-auto md:max-w-6xl rounded-xl2 bg-night px-8 py-10 text-center">
        <p className="font-quran text-2xl leading-loose text-gold-soft md:text-3xl">{t('about.verse')}</p>
        <div className="mt-2 text-sm text-pearl/50">{t('about.verseSrc')}</div>
      </Reveal>

      <section className="mx-auto max-w-6xl px-5 py-20 md:px-8">
        <Reveal as="h2" className="mb-10 text-center font-display text-4xl md:text-5xl" split>{t('method.title')}</Reveal>
        <StaggerGroup className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          <MethodCard icon="book" title={t('method.depth')} text={t('method.depthText')} />
          <MethodCard icon="shield" title={t('method.honesty')} text={t('method.honestyText')} />
          <MethodCard icon="moon" title={t('method.calm')} text={t('method.calmText')} />
          <MethodCard icon="chat" title={t('method.context')} text={t('method.contextText')} />
        </StaggerGroup>
      </section>

      <section className="mx-auto max-w-6xl px-5 pb-20 md:px-8">
        <div className="mb-8 flex flex-col items-center gap-2 text-center">
          <Reveal><Kicker>{t('stats.kicker')}</Kicker></Reveal>
          <Reveal as="h2" className="max-w-3xl font-display text-3xl md:text-4xl" split>{t('stats.title')}</Reveal>
        </div>
        <StaggerGroup className="grid gap-6 md:grid-cols-3">
          {[{ k: 'subscribers', v: stats?.subscribers, i: 'user' }, { k: 'views', v: stats?.views, i: 'play' }, { k: 'videos', v: stats?.videos, i: 'youtube' }].map((s) => (
            <div key={s.k} className="card card-hover flex flex-col items-center gap-2 p-8">
              <div className="text-fg"><Icon name={s.i as never} size={32} strokeWidth={1.2} /></div>
              <div className="text-5xl font-medium text-night dark:text-pearl" dir="ltr">{s.v ? <CountUp to={num(s.v)} suffix={suf(s.v)} /> : '…'}</div>
              <div className="text-sm font-light text-fg-muted">{t(`stats.${s.k}`)}</div>
            </div>
          ))}
        </StaggerGroup>
      </section>

      <PackagesSection onChoose={choose} />

      <section className="mx-auto max-w-6xl px-5 pb-20 md:px-8">
        <Reveal as="h2" className="mb-8 text-center font-display text-4xl" split>{t('testimonials.title')}</Reveal>
        <StaggerGroup className="grid gap-5 md:grid-cols-3">
          {tst?.items.map((x) => (
            <div key={x.id} className="card card-hover p-6">
              <Stars value={x.rating} size={16} />
              <p className="my-4 font-light leading-relaxed">{x.comment}</p>
              <div className="flex items-center justify-between text-xs text-fg-dim"><span>{x.name}</span><span className="chip bg-success/10 text-success"><Icon name="check" size={12} />{t('testimonials.verified')}</span></div>
            </div>
          ))}
        </StaggerGroup>
        <Reveal as="p" className="mx-auto mt-12 max-w-3xl text-center font-display text-2xl leading-loose text-night dark:text-pearl" delay={0.2}>{t('testimonials.quote')}</Reveal>
      </section>

      <Footer />
      <span className="hidden">{locale}</span>
    </div>
  )
}
