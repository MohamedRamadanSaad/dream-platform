// Create account and forgot password: the same three steps on one page (e-mail → 6-digit code → password).
// The ticket from the code step lives only in this component's state, never in storage.
import { useEffect, useLayoutEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Trans, useTranslation } from 'react-i18next'
import { useMutation } from '@tanstack/react-query'
import gsap from 'gsap'
import { authApi } from '@/api/endpoints'
import { readLastEmail, readRememberMe, saveRememberMe } from '@/app/session'
import { useAuthStore } from '@/app/auth-store'
import { Button, Input, Label } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { reduced } from '@/components/motion'
import { toast } from '@/components/ui/Toaster'
import { cn } from '@/lib/utils'
import type { CodePurpose } from '@/api/types'
import {
  AuthErrorBox, AuthShell, MOCKS, PasswordInput, PasswordRules, RememberMe, SwitchLink, onlyDigits, passwordChecks,
  useAuthErrorText, useFinishLogin, type AuthError, type AuthNavState,
} from './authShared'

type Step = 1 | 2 | 3
const RESEND_SECONDS = 60
const STEP_KEYS = ['email', 'code', 'password'] as const

/** 1 e-mail · 2 code · 3 password: done steps are ticked, the current one is gold. */
function StepIndicator({ step }: { step: Step }) {
  const { t } = useTranslation()
  return (
    <ol aria-label={t('auth.stepOf', { n: step })} className="mb-6 flex items-start">
      {STEP_KEYS.map((k, i) => {
        const n = (i + 1) as Step
        const done = n < step, current = n === step
        return (
          <li key={k} aria-current={current ? 'step' : undefined} className="relative flex flex-1 flex-col items-center gap-1.5">
            {/* the line from the previous step's circle to this one (logical inset: it follows the reading direction) */}
            {i > 0 && <span aria-hidden="true" className={cn('absolute -start-1/2 top-3.5 h-px w-full transition-colors duration-500', n <= step ? 'bg-gold' : 'bg-line')} />}
            <span className={cn('relative z-10 flex h-7 w-7 items-center justify-center rounded-full border text-xs font-semibold transition-colors duration-500',
              done ? 'border-gold bg-gold text-night' : current ? 'border-gold bg-surface text-gold-ink ring-4 ring-gold/15' : 'border-line bg-surface text-fg-dim')}>
              {done ? <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12l4 4L19 6" /></svg> : n}
            </span>
            <span className={cn('text-[11px]', current ? 'font-medium text-fg' : 'text-fg-dim')}>{t(`auth.steps.${k}`)}</span>
          </li>
        )
      })}
    </ol>
  )
}

/** Each step slides in from the reading end (GSAP, reverted on unmount; nothing moves for reduced motion). */
function StepPanel({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  const locale = useAuthStore((s) => s.locale)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el || reduced()) return
    const ctx = gsap.context(() => {
      gsap.from(el, { opacity: 0, x: locale === 'ar' ? -18 : 18, duration: 0.45, ease: 'power2.out', clearProps: 'transform,opacity' })
    }, el)
    return () => ctx.revert()
  }, [locale])
  return <div ref={ref}>{children}</div>
}

/** Seconds left before "Send the code again" works, counting down once a second. */
function useCountdown() {
  const [until, setUntil] = useState(0)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (until <= Date.now()) return
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [until])
  return { left: Math.max(0, Math.ceil((until - now) / 1000)), start: () => { setNow(Date.now()); setUntil(Date.now() + RESEND_SECONDS * 1000) } }
}

function CodeFlow({ purpose }: { purpose: CodePurpose }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { state, search } = useLocation()
  const finish = useFinishLogin()
  const errorOf = useAuthErrorText()
  const signUp = purpose === 'SIGN_UP'
  const handed = ((state as AuthNavState)?.email ?? '').trim().toLowerCase()

  const [step, setStep] = useState<Step>(1)
  const [email, setEmail] = useState(() => handed || (signUp ? '' : readLastEmail()))
  const [code, setCode] = useState('')
  const [ticket, setTicket] = useState<string | null>(null)
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [terms, setTerms] = useState(false)
  const [remember, setRemember] = useState(readRememberMe)
  const [err, setErr] = useState<AuthError | null>(null)
  const timer = useCountdown()
  const codeRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const emailRef = useRef<HTMLInputElement>(null)
  const cleanEmail = email.trim().toLowerCase()

  useEffect(() => {
    const field = step === 1 ? emailRef.current : step === 2 ? codeRef.current : passwordRef.current
    field?.focus({ preventScroll: true })
  }, [step])

  const requestCode = (body: { email: string }) => (signUp ? authApi.signupRequest(body) : authApi.passwordForgot(body))
  const send = useMutation({
    mutationFn: requestCode,
    onSuccess: () => { setCode(''); setStep(2); timer.start() },
    onError: (e) => setErr(errorOf(e)),
  })
  const resend = useMutation({
    mutationFn: requestCode,
    onSuccess: () => { toast.success(t('auth.resent')); timer.start(); setCode(''); codeRef.current?.focus() },
    onError: (e) => setErr(errorOf(e)),
  })
  const verify = useMutation({
    mutationFn: authApi.codeVerify,
    onSuccess: (r) => { setTicket(r.ticket); setStep(3) },
    onError: (e) => { setErr(errorOf(e)); setCode(''); codeRef.current?.focus() },
  })
  const onDone = {
    onSuccess: (r: Parameters<ReturnType<typeof useFinishLogin>>[0], v: { rememberMe?: boolean }) => finish(r, v.rememberMe ?? true, 'password'),
    onError: (e: unknown) => {
      const x = errorOf(e)
      // the 15 minutes ran out (or the ticket was used): start again from the e-mail
      if (x.code === 'TICKET_INVALID') { setTicket(null); setCode(''); setPassword(''); setConfirm(''); setStep(1) }
      setErr(x)
    },
  }
  const complete = useMutation({ mutationFn: authApi.signupComplete, ...onDone })
  const reset = useMutation({ mutationFn: authApi.passwordReset, ...onDone })

  const checks = passwordChecks(password, confirm)
  const ready = checks.strong && checks.match && (!signUp || terms) && !!ticket

  const submitEmail = (e: FormEvent) => { e.preventDefault(); setErr(null); if (cleanEmail) send.mutate({ email: cleanEmail }) }
  const submitCode = (value = code) => { setErr(null); if (value.length === 6 && !verify.isPending) verify.mutate({ email: cleanEmail, code: value, purpose }) }
  const submitPassword = (e: FormEvent) => {
    e.preventDefault()
    setErr(null)
    if (!ready || !ticket) return
    saveRememberMe(remember)
    if (signUp) complete.mutate({ ticket, password, acceptedTerms: true, rememberMe: remember })
    else reset.mutate({ ticket, password, rememberMe: remember })
  }
  const toLogin = () => navigate({ pathname: '/login', search }, { state: { email: cleanEmail } satisfies AuthNavState })
  const changeEmail = () => { setErr(null); setCode(''); setStep(1) }

  const signInAction = err?.code === 'ACCOUNT_EXISTS'
    ? <Button type="button" size="sm" variant="ghost" className="border-danger/50 text-danger" onClick={toLogin}>{t('auth.signInInstead')}</Button>
    : undefined

  return (
    <AuthShell icon={signUp ? 'star' : 'lock'} title={t(signUp ? 'auth.registerTitle' : 'auth.forgotTitle')} lead={t(signUp ? 'auth.registerLead' : 'auth.forgotLead')}
      above={<SwitchLink question={t('auth.haveAccount')} action={t('auth.signInLink')} to="/login" email={cleanEmail} />}>
      <StepIndicator step={step} />

      {step === 1 && (
        <StepPanel key="s1">
          <form onSubmit={submitEmail} className="flex flex-col gap-4" noValidate>
            <div>
              <label htmlFor="flow-email"><Label>{t('auth.email')}</Label></label>
              <Input ref={emailRef} id="flow-email" name="email" dir="ltr" type="email" required autoComplete="email" inputMode="email" placeholder="you@email.com" value={email} onChange={(e) => setEmail(e.target.value)} />
            </div>
            <AuthErrorBox error={err} action={signInAction} />
            <Button type="submit" size="lg" loading={send.isPending} disabled={!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(cleanEmail)}>{t('auth.sendCode')}</Button>
          </form>
        </StepPanel>
      )}

      {step === 2 && (
        <StepPanel key="s2">
          <form onSubmit={(e) => { e.preventDefault(); submitCode() }} className="flex flex-col gap-4" noValidate>
            <div className="rounded-xl bg-surface-2/70 p-3 text-center text-sm leading-relaxed">
              <p className="text-fg-muted">{t('auth.codeSentTo')}</p>
              <p dir="ltr" className="mt-0.5 break-all font-medium text-fg">{cleanEmail}</p>
              <p className="mt-1.5 text-xs text-fg-dim">{t(signUp ? 'auth.codeHint' : 'auth.forgotCodeNote')}</p>
            </div>
            <div>
              <label htmlFor="flow-code" className="block text-center"><Label>{t('auth.code')}</Label></label>
              <Input ref={codeRef} id="flow-code" name="code" dir="ltr" inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="123456"
                className="h-14 text-center font-mono text-2xl tracking-[.45em]" value={code}
                onChange={(e) => { const v = onlyDigits(e.target.value).slice(0, 6); setCode(v); if (v.length === 6) submitCode(v) }} />
            </div>
            <AuthErrorBox error={err} />
            <Button type="submit" size="lg" loading={verify.isPending} disabled={code.length !== 6}>{t('auth.verifyCode')}</Button>
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
              <button type="button" onClick={changeEmail} className="rounded-lg py-1 text-fg-muted underline-offset-4 hover:text-fg hover:underline">{t('auth.changeEmail')}</button>
              <button type="button" disabled={timer.left > 0 || resend.isPending} onClick={() => { setErr(null); resend.mutate({ email: cleanEmail }) }}
                className="rounded-lg py-1 font-medium text-gold-ink underline-offset-4 hover:underline disabled:cursor-not-allowed disabled:font-normal disabled:text-fg-dim disabled:no-underline">
                {timer.left > 0 ? t('auth.resendIn', { s: timer.left }) : t('auth.resend')}
              </button>
            </div>
            {MOCKS && <p className="text-center text-[11px] text-fg-dim">{t('auth.mockCodeHint')}</p>}
          </form>
        </StepPanel>
      )}

      {step === 3 && (
        <StepPanel key="s3">
          <form onSubmit={submitPassword} className="flex flex-col gap-4" noValidate>
            {/* lets password managers save the new password with the right e-mail */}
            <input type="email" name="email" autoComplete="username" value={cleanEmail} readOnly hidden />
            <div className="flex items-center gap-2 rounded-xl bg-surface-2/70 px-3 py-2 text-sm">
              <span className="shrink-0 text-gold-ink"><Icon name="mail" size={16} /></span>
              <span dir="ltr" className="min-w-0 truncate text-start text-fg-muted">{cleanEmail}</span>
            </div>
            <div>
              <label htmlFor="flow-password"><Label>{t(signUp ? 'auth.password' : 'auth.newPassword')}</Label></label>
              <PasswordInput ref={passwordRef} id="flow-password" name="new-password" dir="ltr" required autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} aria-describedby="flow-rules" />
            </div>
            <div>
              <label htmlFor="flow-confirm"><Label>{t('auth.confirmPassword')}</Label></label>
              <PasswordInput id="flow-confirm" name="confirm-password" dir="ltr" required autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
            </div>
            <div id="flow-rules" aria-live="polite"><PasswordRules password={password} confirm={confirm} /></div>
            {signUp && (
              <label className="flex cursor-pointer items-start gap-3 text-sm leading-relaxed">
                <input type="checkbox" checked={terms} onChange={(e) => setTerms(e.target.checked)} className="mt-1 h-[18px] w-[18px] shrink-0 cursor-pointer accent-gold" />
                <span>
                  <Trans i18nKey="auth.acceptTerms" components={{ terms: <a href="/terms" target="_blank" rel="noopener noreferrer" className="font-medium text-gold-ink underline underline-offset-4" /> }} />
                </span>
              </label>
            )}
            <AuthErrorBox error={err} action={signInAction} />
            <Button type="submit" size="lg" disabled={!ready} loading={complete.isPending || reset.isPending}>{t(signUp ? 'auth.createAccount' : 'auth.savePassword')}</Button>
            <RememberMe checked={remember} onChange={(v) => { setRemember(v); saveRememberMe(v) }} className="mt-1" />
          </form>
        </StepPanel>
      )}
    </AuthShell>
  )
}

export function RegisterPage() {
  return <CodeFlow purpose="SIGN_UP" />
}

export function ForgotPasswordPage() {
  return <CodeFlow purpose="RESET_PASSWORD" />
}
