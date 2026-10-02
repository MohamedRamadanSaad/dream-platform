import { Link, NavLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuthStore, isInterpreter } from '@/app/auth-store'
import { applyLocale } from '@/i18n'
import { Icon } from '@/components/icons/Icon'
import { cn } from '@/lib/utils'
import { Avatar } from '@/components/ui/Avatar'
import { useQuery } from '@tanstack/react-query'
import { meApi, notificationsApi, youtubeApi } from '@/api/endpoints'
import { useMutation, useQueryClient } from '@tanstack/react-query'

const YT = (import.meta.env.VITE_YOUTUBE_URL as string) || 'https://youtube.com/@almoaberafatema'

export function YoutubeButton({ dark }: { dark?: boolean }) {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['youtube', 'unseen'], queryFn: youtubeApi.unseen, enabled: !!user, refetchInterval: 120_000, staleTime: 60_000 })
  const seen = useMutation({ mutationFn: youtubeApi.seen, onSuccess: () => qc.setQueryData(['youtube', 'unseen'], (old: { count: number; latest: unknown[] } | undefined) => (old ? { ...old, count: 0 } : old)) })
  const count = data?.count ?? 0
  const href = data?.latest?.[0]?.url && count > 0 ? data.latest[0].url : YT
  return (
    <a href={href} target="_blank" rel="noreferrer" onClick={() => { if (count > 0) seen.mutate() }} className={cn('btn btn-sm relative gap-2 border', dark ? 'border-navy text-gold-soft hover:border-gold' : 'border-line text-fg hover:border-gold')} aria-label={t('nav.youtube')} title={count > 0 ? t('nav.youtubeNew', { count }) : undefined}>
      <span className="text-[#FF0000]"><Icon name="youtube" size={18} /></span>
      <span className="hidden sm:inline">{t('nav.youtube')}</span>
      {count > 0 && <span className="pulse-ring absolute -top-1 -end-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-[#FF0000] px-1 text-[10px] font-bold text-white">{count > 9 ? '9+' : count}</span>}
    </a>
  )
}

export function LocaleToggle({ dark }: { dark?: boolean }) {
  const locale = useAuthStore((s) => s.locale)
  const setLocale = useAuthStore((s) => s.setLocale)
  const next = locale === 'ar' ? 'en' : 'ar'
  return (
    <button onClick={() => { setLocale(next); applyLocale(next) }} className={cn('text-sm', dark ? 'text-gold-soft/80 hover:text-gold-soft' : 'text-fg-muted hover:text-fg')} aria-label="language">
      {next === 'ar' ? 'العربية' : 'English'}
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

export function AppShell({ items, children, admin }: { items: { to: string; label: string; icon: Parameters<typeof Icon>[0]['name']; end?: boolean }[]; children: React.ReactNode; admin?: boolean }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const clear = useAuthStore((s) => s.clear)
  const user = useAuthStore((s) => s.user)
  return (
    <div className="min-h-screen md:flex">
      <aside className="hidden md:flex md:w-64 md:flex-col bg-night text-pearl sticky top-0 h-screen p-5">
        <Link to="/" className="mb-8 px-2 leading-tight"><div className="font-display text-2xl text-gold-soft">{t('interpreter')}</div><div className="text-[11px] text-pearl/50">{t('brand')}</div></Link>
        <nav className="flex flex-col gap-1">
          {items.map((it) => (
            <NavLink key={it.to} to={it.to} end={it.end} className={({ isActive }) => cn('group flex items-center gap-3 rounded-xl px-3 py-3 text-sm transition-colors', isActive ? 'bg-navy text-pearl' : 'text-pearl/60 hover:bg-navy/60 hover:text-pearl')}>
              {({ isActive }) => <><span className={cn(isActive ? 'text-gold' : 'text-current')}><Icon name={it.icon} size={18} active={isActive} /></span>{it.label}</>}
            </NavLink>
          ))}
        </nav>
        <div className="mt-auto border-t border-navy pt-4 text-sm">
          <div className="flex items-center gap-3 px-3 pb-3"><Avatar name={user?.name || user?.email} size={34} /><div className="min-w-0"><div className="truncate text-pearl">{user?.name}</div><div className="truncate text-xs text-pearl/50" dir="ltr">{user?.email}</div></div></div>
          <button onClick={() => { clear(); navigate('/') }} className="flex items-center gap-3 rounded-xl px-3 py-2 text-pearl/60 hover:text-danger"><Icon name="logout" size={18} />{t('auth.logout')}</button>
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
          </div>
        </header>
        <main className="mx-auto max-w-6xl px-4 py-6 pb-[calc(7rem_+_env(safe-area-inset-bottom))] md:px-8 md:pb-10">{children}</main>
        <nav className="pb-safe fixed bottom-0 inset-x-0 z-30 flex border-t border-line bg-bg/95 backdrop-blur md:hidden">
          {items.slice(0, 5).map((it) => (
            <NavLink key={it.to} to={it.to} end={it.end} className={({ isActive }) => cn('flex flex-1 flex-col items-center gap-1 py-2 text-[11px]', isActive ? 'text-gold-ink' : 'text-fg-muted')}>
              {({ isActive }) => <><Icon name={it.icon} size={20} active={isActive} />{it.label}</>}
            </NavLink>
          ))}
        </nav>
      </div>
    </div>
  )
}
