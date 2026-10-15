// Pieces shared by the sign-in, create-account and forgot-password screens.
import { forwardRef, useId, useState, type InputHTMLAttributes, type ReactNode } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuthStore, isInterpreter } from '@/app/auth-store'
import { rememberLastEmail } from '@/app/session'
import { NightSky } from '@/components/motion/NightSky'
import { PageEnter } from '@/components/motion'
import { Input } from '@/components/ui'
import { Icon, type IconName } from '@/components/icons/Icon'
import { LocaleToggle } from '@/components/layout'
import { ApiError } from '@/api/client'
import { armPasskeyOffer } from '@/features/account/PasskeyOffer'
import { cn } from '@/lib/utils'
import type { AuthResponse } from '@/api/types'

export const GOOGLE_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined
export const MOCKS = import.meta.env.VITE_USE_MOCKS === 'true'

export type SignInMethod = 'google' | 'email' | 'password' | 'passkey'

/** Router state the auth screens pass to each other: the e-mail already typed. */
export type AuthNavState = { email?: string } | null

/** After any sign-in: keep the session, remember the e-mail, then onboarding or the account's home (or ?next=). */
export function useFinishLogin() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const setSession = useAuthStore((s) => s.setSession)
  return (r: AuthResponse, remember: boolean, method: SignInMethod) => {
    setSession(r.accessToken, r.user, remember)
    rememberLastEmail(r.user.email, remember)
    // after a password, Google or an e-mail code, the app may offer fingerprint / face sign-in once (see PasskeyOffer)
    if (method !== 'passkey') armPasskeyOffer(r.user.id)
    const next = params.get('next')
    if (!r.user.onboarded) navigate(`/onboarding${next ? `?next=${encodeURIComponent(next)}` : ''}`)
    else navigate(next || (isInterpreter(r.user) ? '/admin' : '/me'))
  }
}

/** A server error on an auth screen: the code (for its action button) and the words to show. */
export type AuthError = { code?: string; text: string }

/** Server codes with their own wording (auth.errors.*); TOO_MANY_ATTEMPTS adds "try again in N minutes". */
export function useAuthErrorText() {
  const { t, i18n } = useTranslation()
  return (e: unknown): AuthError => {
    const problem = e instanceof ApiError ? e.problem : undefined
    const code = problem?.code
    if (!code || !i18n.exists(`auth.errors.${code}`)) return { code, text: problem ? (e as ApiError).message || t('common.error') : t('common.error') }
    let text = t(`auth.errors.${code}`)
    if (code === 'TOO_MANY_ATTEMPTS' && typeof problem?.retryAfterSeconds === 'number') {
      text += ` ${t('auth.tryAgainIn', { count: Math.max(1, Math.ceil(problem.retryAfterSeconds / 60)) })}`
    }
    return { code, text }
  }
}

/** The auth screens' frame: the night sky, the language switch, an icon, the title and lead, then the card. */
export function AuthShell({ icon, title, lead, above, children }: { icon: IconName; title: string; lead: string; above?: ReactNode; children: ReactNode }) {
  const { t } = useTranslation()
  return (
    <div className="relative min-h-screen bg-night text-pearl">
      <NightSky className="absolute inset-0" withMoon={false} />
      <div className="absolute inset-x-5 top-5 flex justify-between"><Link to="/" className="font-display text-xl text-gold-soft">{t('interpreter')}</Link><LocaleToggle dark /></div>
      <PageEnter className="relative mx-auto flex min-h-screen max-w-md flex-col items-center justify-center px-4 py-20 sm:px-5">
        <div className="mb-5 text-gold-soft"><Icon name={icon} size={52} strokeWidth={0.9} /></div>
        <h1 className="text-center font-display text-4xl">{title}</h1>
        <p className="mt-2 mb-6 text-center text-sm font-light leading-relaxed text-pearl/65">{lead}</p>
        {above}
        <div className="card w-full bg-surface p-5 text-fg sm:p-6">{children}</div>
      </PageEnter>
    </div>
  )
}

/** "Already have an account? Sign in" above the card of the create-account and forgot-password screens. */
export function SwitchLink({ question, action, to, email }: { question: string; action: string; to: string; email?: string }) {
  const { search } = useLocation()
  return (
    <p className="mb-4 text-center text-sm text-pearl/70">
      {question}{' '}
      <Link to={{ pathname: to, search }} state={email ? { email } : null} className="font-medium text-gold-soft underline-offset-4 hover:text-gold hover:underline">{action}</Link>
    </p>
  )
}

/** "Keep me signed in on this device" (ticked by default); the hint warns against it on a shared device. */
export function RememberMe({ checked, onChange, className }: { checked: boolean; onChange: (v: boolean) => void; className?: string }) {
  const { t } = useTranslation()
  const hintId = useId()
  return (
    <label className={cn('mt-5 flex cursor-pointer items-start gap-3 border-t border-line pt-4', className)}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} aria-describedby={hintId}
        className="mt-0.5 h-[18px] w-[18px] shrink-0 cursor-pointer accent-gold" />
      <span className="min-w-0 leading-snug">
        <span className="block text-sm text-fg">{t('auth.rememberMe')}</span>
        <span id={hintId} className="mt-1 block text-xs text-fg-muted">{t('auth.rememberMeHint')}</span>
      </span>
    </label>
  )
}

function EyeIcon({ open }: { open: boolean }) {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" />
      <circle cx="12" cy="12" r="3" />
      {!open && <path d="M4 4l16 16" />}
    </svg>
  )
}

/** Password input with a show / hide eye button (aria-pressed = the password is visible). */
export const PasswordInput = forwardRef<HTMLInputElement, Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>>(({ className, dir, ...p }, ref) => {
  const { t } = useTranslation()
  const [visible, setVisible] = useState(false)
  return (
    // the box takes the field's direction, so the eye sits at the field's end and never covers the typed text
    <div className="relative" dir={dir}>
      <Input ref={ref} type={visible ? 'text' : 'password'} autoCapitalize="off" autoCorrect="off" spellCheck={false} maxLength={128} className={cn('pe-12', className)} {...p} />
      <button type="button" onClick={() => setVisible((v) => !v)} aria-label={t('auth.showPassword')} aria-pressed={visible} title={t('auth.showPassword')}
        className="absolute inset-y-0 end-0 flex w-12 items-center justify-center rounded-e-xl text-fg-dim transition-colors hover:text-fg focus-visible:text-gold-ink">
        <EyeIcon open={visible} />
      </button>
    </div>
  )
})
PasswordInput.displayName = 'PasswordInput'

/** The error box under a form, with an optional action (e.g. "Create a password"). */
export function AuthErrorBox({ error, action }: { error: AuthError | null; action?: ReactNode }) {
  if (!error) return null
  return (
    <div role="alert" className="rounded-xl border border-danger/40 bg-danger/5 p-3 text-sm leading-relaxed text-danger">
      <p>{error.text}</p>
      {action && <div className="mt-2.5">{action}</div>}
    </div>
  )
}

/** Google's four-colour "G" (the sign-in button and the profile's sign-in methods). */
export function GoogleG({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
      <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
      <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" />
      <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" />
    </svg>
  )
}

/** Same rule as the server: 8 to 128 characters, at least one letter and one digit. */
export function passwordChecks(password: string, confirm?: string) {
  const checks = {
    length: password.length >= 8 && password.length <= 128,
    letter: /\p{L}/u.test(password),
    digit: /\p{Nd}/u.test(password),
  }
  return { ...checks, strong: checks.length && checks.letter && checks.digit, match: confirm !== undefined && password.length > 0 && password === confirm }
}

/** Live checklist of the password rule (and "passwords match"). */
export function PasswordRules({ password, confirm }: { password: string; confirm: string }) {
  const { t } = useTranslation()
  const c = passwordChecks(password, confirm)
  const items: [string, boolean][] = [['length', c.length], ['letter', c.letter], ['digit', c.digit], ['match', c.match]]
  return (
    <div className="rounded-xl bg-surface-2/70 p-3">
      <p className="mb-2 text-xs text-fg-muted">{t('auth.rules.title')}</p>
      <ul className="grid gap-1.5 text-xs sm:grid-cols-2">
        {items.map(([k, ok]) => (
          <li key={k} className={cn('flex items-center gap-2 transition-colors', ok ? 'text-success' : 'text-fg-muted')}>
            <span aria-hidden="true" className={cn('flex h-4 w-4 shrink-0 items-center justify-center rounded-full border transition-colors', ok ? 'border-success bg-success text-night' : 'border-line')}>
              {ok && <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12l4 4L19 6" /></svg>}
            </span>
            <span>{t(`auth.rules.${k}`)}<span className="sr-only">: {t(ok ? 'auth.rules.met' : 'auth.rules.unmet')}</span></span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Arabic-Indic and Persian digits → 0-9, everything else dropped (a pasted code may carry spaces). */
export const onlyDigits = (raw: string) => raw
  .replace(/[\u0660-\u0669]/g, (d) => String(d.charCodeAt(0) - 0x660))
  .replace(/[\u06f0-\u06f9]/g, (d) => String(d.charCodeAt(0) - 0x6f0))
  .replace(/\D/g, '')
