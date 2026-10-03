import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, matchPath, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuthStore, isInterpreter } from '@/app/auth-store'
import { useSignOut } from '@/app/session'
import { applyLocale } from '@/i18n'
import { Icon, type IconName } from '@/components/icons/Icon'
import { cn } from '@/lib/utils'
import { Avatar } from '@/components/ui/Avatar'
import { useQuery } from '@tanstack/react-query'
import { meApi, notificationsApi, youtubeApi } from '@/api/endpoints'
import { useMutation, useQueryClient } from '@tanstack/react-query'

import flagSa from '@/assets/flags/sa.svg'
import flagGb from '@/assets/flags/gb.svg'

const YT = (import.meta.env.VITE_YOUTUBE_URL as string) || 'https://youtube.com/@almoaberafatema'

/** The real YouTube mark: red rounded play box with a white triangle (not tinted by the theme). */
export function YoutubeLogo({ size = 20, className }: { size?: number; className?: string }) {
  return (
    <svg width={size * 1.4} height={size} viewBox="0 0 28 20" aria-hidden="true" className={cn('shrink-0', className)}>
      <path fill="#FF0000" d="M27.4 3.1A3.5 3.5 0 0 0 25 .6C22.8 0 14 0 14 0S5.2 0 3 .6A3.5 3.5 0 0 0 .6 3.1C0 5.3 0 10 0 10s0 4.7.6 6.9A3.5 3.5 0 0 0 3 19.4c2.2.6 11 .6 11 .6s8.8 0 11-.6a3.5 3.5 0 0 0 2.4-2.5c.6-2.2.6-6.9.6-6.9s0-4.7-.6-6.9z" />
      <path fill="#FFFFFF" d="M11.2 14.3 18.5 10l-7.3-4.3z" />
    </svg>
  )
}

export function YoutubeButton({ dark }: { dark?: boolean }) {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['youtube', 'unseen'], queryFn: youtubeApi.unseen, enabled: !!user, refetchInterval: 120_000, staleTime: 60_000 })
  const seen = useMutation({ mutationFn: youtubeApi.seen, meta: { toast: false }, onSuccess: () => qc.setQueryData(['youtube', 'unseen'], (old: { count: number; latest: unknown[] } | undefined) => (old ? { ...old, count: 0 } : old)) })
  const count = data?.count ?? 0
  const href = data?.latest?.[0]?.url && count > 0 ? data.latest[0].url : YT
  return (
    <a href={href} target="_blank" rel="noreferrer" onClick={() => { if (count > 0) seen.mutate() }} className={cn('btn btn-sm relative gap-2 border', dark ? 'border-navy text-gold-soft hover:border-gold' : 'border-line text-fg hover:border-gold')} aria-label={t('nav.youtube')} title={count > 0 ? t('nav.youtubeNew', { count }) : undefined}>
      <YoutubeLogo size={15} />
      <span className="hidden sm:inline">{t('nav.youtube')}</span>
      {count > 0 && <span className="pulse-ring absolute -top-1 -end-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-[#FF0000] px-1 text-[10px] font-bold text-white">{count > 9 ? '9+' : count}</span>}
    </a>
  )
}

const LANGS = [
  { code: 'ar', flag: flagSa, labelKey: 'common.langAr' },
  { code: 'en', flag: flagGb, labelKey: 'common.langEn' },
] as const

const Flag = ({ src, size = 22 }: { src: string; size?: number }) => (
  <img src={src} alt="" width={size} height={Math.round(size * 0.75)} className="shrink-0 rounded-[3px] object-cover shadow-[0_0_0_1px_rgba(0,0,0,.12)]" />
)

/** Language menu: the current flag opens a small list (Saudi flag = العربية, UK flag = English). */
export function LocaleToggle({ dark }: { dark?: boolean }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const setLocale = useAuthStore((s) => s.setLocale)
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const current = LANGS.find((l) => l.code === locale) ?? LANGS[0]
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDown); window.removeEventListener('keydown', onKey) }
  }, [open])
  const pick = (code: 'ar' | 'en') => { setOpen(false); if (code !== locale) { setLocale(code); applyLocale(code) } }
  return (
    <div ref={box} className="relative">
      <button type="button" onClick={() => setOpen((v) => !v)} aria-haspopup="listbox" aria-expanded={open} aria-label={t('common.language')} title={t('common.language')}
        className={cn('flex h-10 items-center gap-1.5 rounded-full border px-2.5 transition-colors', dark ? 'border-navy hover:border-gold' : 'border-line hover:border-gold', open && 'border-gold')}>
        <Flag src={current.flag} />
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={cn('transition-transform duration-300', dark ? 'text-gold-soft/70' : 'text-fg-muted', open && 'rotate-180')}><path d="M6 9l6 6 6-6" /></svg>
      </button>
      {open && (
        <ul role="listbox" aria-label={t('common.language')} className="modal-panel absolute end-0 top-full z-50 mt-2 w-44 overflow-hidden rounded-2xl border border-line bg-surface p-1.5 shadow-calm">
          {LANGS.map((l) => (
            <li key={l.code}>
              <button type="button" role="option" aria-selected={l.code === locale} onClick={() => pick(l.code)} lang={l.code} dir={l.code === 'ar' ? 'rtl' : 'ltr'}
                className={cn('flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm text-fg transition-colors', l.code === locale ? 'bg-gold/10' : 'hover:bg-surface-2')}>
                <Flag src={l.flag} size={24} />
                <span className="flex-1 text-start">{t(l.labelKey)}</span>
                {l.code === locale && <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" className="text-gold-ink" aria-hidden="true"><path d="M5 12l4 4L19 6" /></svg>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Desktop header sign-out: a round icon button that warms to red on hover. */
export function SignOutButton() {
  const { t } = useTranslation()
  const { signOut, pending } = useSignOut()
  const [hover, setHover] = useState(false)
  return (
    <button type="button" disabled={pending} onClick={() => void signOut()} onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      aria-label={t('auth.logout')} title={t('auth.logout')}
      className="flex h-10 w-10 items-center justify-center rounded-full border border-line text-fg-muted transition-colors hover:border-danger/50 hover:bg-danger/10 hover:text-danger disabled:opacity-50">
      {pending ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" /> : <Icon name="logout" size={18} flipRtl active={hover} />}
    </button>
  )
}

export function ThemeToggle({ dark }: { dark?: boolean }) {
  const theme = useAuthStore((s) => s.theme)
  const setTheme = useAuthStore((s) => s.setTheme)
  return (
    <button onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')} aria-label="theme" className={cn('flex h-10 w-10 items-center justify-center rounded-full border transition-colors', dark ? 'border-navy text-gold-soft hover:border-gold' : 'border-line text-fg hover:border-gold')}>
      <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={18} />
    </button>
  )
}

export function NotificationBell({ dark }: { dark?: boolean }) {
  const user = useAuthStore((s) => s.user)
  const { data } = useQuery({ queryKey: ['notifications', 0], queryFn: () => notificationsApi.list(0, 20), enabled: !!user, refetchInterval: 30_000 })
  const unread = data?.items.filter((n) => !n.readAt).length ?? 0
  const to = isInterpreter(user) ? '/admin/notifications' : '/me/notifications'
  return (
    <Link to={to} aria-label="notifications" className={cn('relative flex h-10 w-10 items-center justify-center rounded-full border transition-colors', dark ? 'border-navy text-gold-soft hover:border-gold' : 'border-line text-fg hover:border-gold')}>
      <Icon name="bell" size={18} />
      {unread > 0 && <span className="pulse-ring absolute -top-0.5 -end-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-gold px-1 text-[10px] font-bold text-night">{unread}</span>}
    </Link>
  )
}

export function CreditsPill() {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)
  const { data } = useQuery({ queryKey: ['me', 'dashboard'], queryFn: meApi.dashboard, enabled: !!user && !isInterpreter(user) })
  if (!user || isInterpreter(user)) return null
  const n = data?.credits ?? 0
  return (
    <Link to="/me/packages" aria-label={t('me.credits', { count: n, n })} title={t('me.credits', { count: n, n })} className={cn('chip whitespace-nowrap border py-1.5 font-medium transition-colors', n === 0 ? 'border-danger/40 text-danger' : n === 1 ? 'border-warn/50 text-warn' : 'border-gold/60 bg-gold/10 text-gold-ink')}>
      <Icon name="star" size={14} active={n > 0} />
      {/* phones: just the number, the header has no room for the sentence */}
      <span className="sm:hidden">{n}</span>
      <span className="hidden sm:inline">{t('me.credits', { count: n, n })}</span>
    </Link>
  )
}

export function PublicHeader() {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)
  const nav = [{ to: '/', label: t('nav.home') }, { to: '/#about', label: t('nav.about') }, { to: '/#packages', label: t('nav.packages') }, { to: '/courses', label: t('nav.courses') }]
  return (
    <header className="relative z-20 mx-auto flex h-20 max-w-7xl items-center justify-between px-5 md:px-8">
      <Link to="/" className="flex flex-col leading-tight">
        <span className="whitespace-nowrap font-display text-xl text-gold-soft md:text-2xl">{t('interpreter')}</span>
        <span className="text-[11px] text-fg-dim ltr:tracking-wide">{t('brand')}</span>
      </Link>
      <nav className="hidden md:flex gap-8 text-sm text-pearl/70">
        {nav.map((n) => <a key={n.to} href={n.to} className="hover:text-pearl transition-colors">{n.label}</a>)}
      </nav>
      <div className="flex items-center gap-3">
        <YoutubeButton dark />
        <LocaleToggle dark />
        {user ? (
          <Link to={isInterpreter(user) ? '/admin' : '/me'} aria-label={user.name || t('me.nav.dreams')} title={user.name} className="transition-transform hover:-translate-y-0.5"><Avatar name={user.name || user.email} size={40} /></Link>
        ) : (
          <Link to="/login" aria-label={t('nav.login')} title={t('nav.login')} className="flex h-10 w-10 items-center justify-center rounded-full border border-gold text-gold-soft transition-colors hover:bg-gold hover:text-night"><Icon name="user" size={18} /></Link>
        )}
      </div>
    </header>
  )
}

export function Footer() {
  const { t } = useTranslation()
  return (
    <footer className="bg-night text-fg-dim">
      <div className="mx-auto flex max-w-7xl flex-col gap-6 px-5 py-10 md:flex-row md:items-center md:justify-between md:px-8">
        <div>
          <div className="font-display text-2xl text-gold-soft">{t('interpreter')}</div>
          <div className="text-sm text-pearl/60">{t('tagline')}</div>
        </div>
        <div className="flex flex-col gap-2 text-sm md:items-end">
          <div className="flex gap-6"><a href={YT} target="_blank" rel="noreferrer" className="text-gold-soft">{t('footer.youtube')}</a><Link to="/terms" className="hover:text-pearl">{t('footer.terms')}</Link><Link to="/privacy" className="hover:text-pearl">{t('footer.privacy')}</Link></div>
          <div className="text-pearl/50">{t('footer.rights', { year: new Date().getFullYear() })}</div>
        </div>
      </div>
    </footer>
  )
}

/** Small count on a nav entry (e.g. new support messages); hidden when 0. */
function NavBadge({ n, className }: { n?: number; className?: string }) {
  if (!n) return null
  return <span className={cn('flex h-4 min-w-4 items-center justify-center rounded-full bg-gold px-1 text-[10px] font-bold leading-none text-night', className)}>{n > 99 ? '99+' : n}</span>
}

export function AppShell({ items, children, admin }: { items: { to: string; label: string; icon: IconName; end?: boolean; badge?: number }[]; children: React.ReactNode; admin?: boolean }) {
  const { t } = useTranslation()
  const { pathname } = useLocation()
  const { signOut, pending: signingOut } = useSignOut()
  const user = useAuthStore((s) => s.user)
  const profile = admin ? '/admin/profile' : '/me/profile'
  // "My account" is always the last entry: at the end of the sidebar and the last tab on phones
  const account = items.find((it) => it.to === profile)
  const others = items.filter((it) => it !== account)
  const ordered = account ? [...others, account] : others
  // phones: 5 tabs. When the pages don't fit, the middle ones open from a "More" sheet placed just before the account tab
  const slots = account ? 4 : 5
  const overflow = others.length > slots
  const lead = overflow ? others.slice(0, slots - 1) : others.slice(0, slots)
  const rest = overflow ? others.slice(slots - 1) : []
  const restActive = rest.some((it) => matchPath({ path: it.to, end: !!it.end }, pathname))
  const [moreOpen, setMoreOpen] = useState(false)
  useEffect(() => setMoreOpen(false), [pathname])
  useEffect(() => {
    if (!moreOpen) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setMoreOpen(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [moreOpen])
  return (
    <div className="min-h-screen md:flex">
      <aside className="hidden md:flex md:w-64 md:flex-col bg-night text-pearl sticky top-0 h-screen p-5">
        <Link to="/" className="mb-8 px-2 leading-tight"><div className="font-display text-2xl text-gold-soft">{t('interpreter')}</div><div className="text-[11px] text-pearl/50">{t('brand')}</div></Link>
        <nav className="flex flex-col gap-1">
          {ordered.map((it) => (
            <NavLink key={it.to} to={it.to} end={it.end} className={({ isActive }) => cn('group flex items-center gap-3 rounded-xl px-3 py-3 text-sm transition-colors', isActive ? 'bg-navy text-pearl' : 'text-pearl/60 hover:bg-navy/60 hover:text-pearl')}>
              {({ isActive }) => <><span className={cn(isActive ? 'text-gold' : 'text-current')}><Icon name={it.icon} size={18} active={isActive} /></span>{it.label}<NavBadge n={it.badge} className="ms-auto" /></>}
            </NavLink>
          ))}
        </nav>
        <div className="mt-auto border-t border-navy pt-4 text-sm">
          <Link to={profile} title={t(admin ? 'admin.nav.profile' : 'me.nav.profile')} className="mb-1 flex items-center gap-3 rounded-xl px-3 py-2 transition-colors hover:bg-navy/60">
            <Avatar name={user?.name || user?.email} size={34} />
            <div className="min-w-0"><div className="truncate text-pearl">{user?.name}</div><div className="truncate text-xs text-pearl/50" dir="ltr">{user?.email}</div></div>
          </Link>
          <button type="button" disabled={signingOut} onClick={() => void signOut()} className="flex items-center gap-3 rounded-xl px-3 py-2 text-pearl/60 hover:text-danger disabled:opacity-50"><Icon name="logout" size={18} flipRtl />{t('auth.logout')}</button>
        </div>
      </aside>
      <div className="flex-1 min-w-0">
        <header className="sticky top-0 z-30 flex h-16 items-center justify-between border-b border-line bg-bg/90 px-4 backdrop-blur md:px-8">
          <Link to="/" className="whitespace-nowrap font-display text-lg text-fg md:hidden">{t('interpreter')}</Link>
          <div className="hidden md:block" />
          <div className="flex items-center gap-2 md:gap-3">
            {!admin && <CreditsPill />}
            <YoutubeButton />
            <NotificationBell />
            {/* theme lives in the profile on phones; the header keeps only what fits */}
            <div className="hidden md:block"><ThemeToggle /></div>
            <LocaleToggle />
            {/* desktop: sign out right after the language menu, at the far end of the header */}
            <div className="hidden md:block"><SignOutButton /></div>
          </div>
        </header>
        <main className="mx-auto max-w-6xl px-4 py-6 pb-[calc(7rem_+_env(safe-area-inset-bottom))] md:px-8 md:pb-10">{children}</main>
        <nav className="pb-safe fixed bottom-0 inset-x-0 z-30 flex border-t border-line bg-bg/95 backdrop-blur md:hidden">
          {lead.map((it) => (
            <NavLink key={it.to} to={it.to} end={it.end} className={({ isActive }) => cn('flex flex-1 flex-col items-center gap-1 py-2 text-[11px]', isActive ? 'text-gold-ink' : 'text-fg-muted')}>
              {({ isActive }) => <><span className="relative"><Icon name={it.icon} size={20} active={isActive} /><NavBadge n={it.badge} className="absolute -top-1.5 -end-2.5" /></span>{it.label}</>}
            </NavLink>
          ))}
          {overflow && (
            <button type="button" onClick={() => setMoreOpen((v) => !v)} aria-expanded={moreOpen} aria-controls="more-sheet"
              className={cn('flex flex-1 flex-col items-center gap-1 py-2 text-[11px]', restActive || moreOpen ? 'text-gold-ink' : 'text-fg-muted')}>
              <span className="relative"><Icon name="menu" size={20} active={moreOpen || restActive} />{rest.some((it) => it.badge) && <span aria-hidden="true" className="absolute -top-0.5 -end-1 h-2 w-2 rounded-full bg-gold" />}</span>{t('admin.nav.more')}
            </button>
          )}
          {account && [account].map((it) => (
            <NavLink key={it.to} to={it.to} end={it.end} className={({ isActive }) => cn('flex flex-1 flex-col items-center gap-1 py-2 text-[11px]', isActive ? 'text-gold-ink' : 'text-fg-muted')}>
              {({ isActive }) => <><span className="relative"><Icon name={it.icon} size={20} active={isActive} /><NavBadge n={it.badge} className="absolute -top-1.5 -end-2.5" /></span>{it.label}</>}
            </NavLink>
          ))}
        </nav>
        {overflow && moreOpen && (
          <>
            <div aria-hidden="true" className="fixed inset-0 z-20 bg-night/40 md:hidden" onClick={() => setMoreOpen(false)} />
            <div id="more-sheet" role="dialog" aria-label={t('admin.nav.more')} className="fixed inset-x-3 bottom-[4.75rem] z-40 rounded-xl2 border border-line bg-surface p-2 shadow-calm md:hidden">
              <div className="grid grid-cols-3 gap-1">
                {rest.map((it) => (
                  <NavLink key={it.to} to={it.to} end={it.end} className={({ isActive }) => cn('flex flex-col items-center gap-1.5 rounded-xl px-2 py-3 text-center text-[11px] leading-tight transition-colors', isActive ? 'bg-gold/10 text-gold-ink' : 'text-fg-muted hover:bg-surface-2')}>
                    {({ isActive }) => <><span className="relative"><Icon name={it.icon} size={20} active={isActive} /><NavBadge n={it.badge} className="absolute -top-1.5 -end-2.5" /></span>{it.label}</>}
                  </NavLink>
                ))}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
