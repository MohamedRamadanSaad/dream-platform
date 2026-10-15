import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation } from '@tanstack/react-query'
import { GoogleLogin, GoogleOAuthProvider } from '@react-oauth/google'
import { cn, INTERPRETER_DOMAIN } from '@/lib/utils'
import { getPasskey, passkeyFailure, useFreshOptions, usePasskeySupport, withOptions } from '@/lib/passkeys'
import { authApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { readRememberMe, saveRememberMe, readLastEmail, forgetLastEmail } from '@/app/session'
import { NightSky } from '@/components/motion/NightSky'
import { PageEnter } from '@/components/motion'
import { Button, Input, Label } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { Avatar } from '@/components/ui/Avatar'
import { ApiError } from '@/api/client'
import type { AuthResponse, Gender } from '@/api/types'
import {
  AuthErrorBox, AuthShell, GOOGLE_ID, GoogleG, MOCKS, PasswordInput, RememberMe, useAuthErrorText, useFinishLogin,
  type AuthError, type AuthNavState,
} from './authShared'

export { GoogleG, useFinishLogin } from './authShared'
export { RegisterPage, ForgotPasswordPage } from './CodeFlows'

type PasskeyNote = 'cancelled' | 'invalid' | 'failed'

/**
 * "Sign in with fingerprint or face", shown only where it can work (this device, or a phone nearby). The options are
 * fetched before the tap so the device's prompt opens inside it — Safari opens it only from the tap itself.
 */
function PasskeySignIn({ remember, onStart, onSignedIn }: { remember: boolean; onStart: () => void; onSignedIn: (r: AuthResponse, remember: boolean) => void }) {
  const { t } = useTranslation()
  const support = usePasskeySupport()
  const usable = support?.signIn === true
  const prepared = useFreshOptions(authApi.passkeyOptions, usable)
  const [prompting, setPrompting] = useState(false)
  const [note, setNote] = useState<PasskeyNote | null>(null)
  const verify = useMutation({
    mutationFn: authApi.passkeyVerify,
    onSuccess: (r, v) => onSignedIn(r, v.rememberMe ?? true),
    onError: (e) => {
      // unknown or removed passkey, wrong site, expired challenge…: the server answers PASSKEY_INVALID
      setNote(e instanceof ApiError && (e.problem.code === 'PASSKEY_INVALID' || e.status === 401) ? 'invalid' : 'failed')
      prepared.warm()
    },
  })
  if (!usable) return null
  const busy = prompting || verify.isPending
  const start = () => {
    if (busy) return
    onStart()
    setNote(null)
    setPrompting(true)
    withOptions(prepared, getPasskey)
      .then(({ requestId, credential }) => verify.mutate({ requestId, credential, rememberMe: remember }))
      .catch((e: unknown) => {
        // closing the prompt is not an error: a quiet hint for those who have not turned it on yet
        setNote(passkeyFailure(e) === 'cancelled' ? 'cancelled' : 'failed')
        prepared.warm()
      })
      .finally(() => setPrompting(false))
  }
  return (
    <div className="mb-3">
      <Button size="lg" className="w-full px-5 leading-snug" loading={busy} onClick={start} onPointerEnter={prepared.warm} onFocus={prepared.warm}>
        {!busy && <span className="shrink-0"><Icon name="passkey" size={22} /></span>}{t('auth.passkey.button')}
      </Button>
      {note && (
        <p role={note === 'cancelled' ? 'status' : 'alert'} className={cn('mt-2.5 text-center text-xs leading-relaxed', note === 'cancelled' ? 'text-fg-muted' : 'text-danger')}>
          {t(`auth.passkey.${note}`)}
        </p>
      )}
    </div>
  )
}

/**
 * Google under the form, as before: the real button when a client id is set (live site), else the preview button.
 * Interpreter-domain e-mails never see it (the server refuses Google for them).
 */
export function GoogleSection({ remember, onError, onSignedIn }: { remember: boolean; onError: (e: unknown) => void; onSignedIn: (r: AuthResponse, remember: boolean) => void }) {
  const { t } = useTranslation()
  const google = useMutation({ mutationFn: authApi.google, onSuccess: (r, v) => onSignedIn(r, v.rememberMe ?? true), onError })
  return (
    <>
      <div className="my-5 flex items-center gap-3 text-xs text-fg-dim"><span className="h-px flex-1 bg-line" />{t('auth.or')}<span className="h-px flex-1 bg-line" /></div>
      {GOOGLE_ID && !MOCKS ? (
        <GoogleOAuthProvider clientId={GOOGLE_ID}>
          <div className="flex justify-center"><GoogleLogin onSuccess={(c) => c.credential && google.mutate({ idToken: c.credential, rememberMe: remember })} onError={() => onError(null)} shape="pill" width="320" /></div>
        </GoogleOAuthProvider>
      ) : (
        <Button variant="ghost" className="w-full border-[#e5e5e5] bg-white text-[#222]" loading={google.isPending} onClick={() => google.mutate({ idToken: 'mock', rememberMe: remember })}>
          <GoogleG /> {t('auth.google')}
        </Button>
      )}
    </>
  )
}

/**
 * Sign in: e-mail + password. A returning visitor sees the remembered e-mail as a fixed line ("Not you?" brings the
 * field back) and only types the password. Create account and forgot password are their own screens.
 */
export default function LoginPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { search, state } = useLocation()
  const [params] = useSearchParams()
  const finish = useFinishLogin()
  const errorOf = useAuthErrorText()
  // an e-mail handed over by another screen ("Sign in instead") wins over the remembered one
  const handed = ((state as AuthNavState)?.email ?? params.get('email') ?? '').trim().toLowerCase()
  const [rememberedEmail, setRememberedEmail] = useState(() => (handed ? '' : readLastEmail()))
  const [email, setEmail] = useState(handed || rememberedEmail)
  const [password, setPassword] = useState('')
  const [err, setErr] = useState<AuthError | null>(null)
  const [remember, setRemember] = useState(readRememberMe)
  const choose = (v: boolean) => { setRemember(v); saveRememberMe(v) }
  const passwordRef = useRef<HTMLInputElement>(null)
  const emailRef = useRef<HTMLInputElement>(null)
  const fixedEmail = !!rememberedEmail && email === rememberedEmail

  // a known e-mail: the cursor waits in the password field
  useEffect(() => { if (fixedEmail || handed) passwordRef.current?.focus({ preventScroll: true }) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const login = useMutation({
    mutationFn: authApi.passwordLogin,
    onSuccess: (r, v) => finish(r, v.rememberMe ?? true, 'password'),
    onError: (e) => { setErr(errorOf(e)); setPassword(''); passwordRef.current?.focus() },
  })
  const showError = (e: unknown) => setErr(e ? errorOf(e) : { text: t('common.error') })

  const cleanEmail = email.trim().toLowerCase()
  const isInterpreterEmail = cleanEmail.endsWith(INTERPRETER_DOMAIN)
  const submit = (e: FormEvent) => {
    e.preventDefault()
    setErr(null)
    if (!cleanEmail || !password) return
    login.mutate({ email: cleanEmail, password, rememberMe: remember })
  }
  const notYou = () => {
    forgetLastEmail(); setRememberedEmail(''); setEmail(''); setPassword(''); setErr(null)
    window.setTimeout(() => emailRef.current?.focus(), 0)
  }
  const goForgot = () => navigate({ pathname: '/forgot-password', search }, { state: { email: cleanEmail } satisfies AuthNavState })

  return (
    <AuthShell icon="moon" title={t('auth.title')} lead={t('auth.lead')}>
      <PasskeySignIn remember={remember} onStart={() => setErr(null)} onSignedIn={(r, rem) => finish(r, rem, 'passkey')} />
      {isInterpreterEmail && <p className="mb-5 rounded-xl border border-gold/40 bg-gold/10 p-3 text-center text-xs leading-relaxed text-gold-ink">{t('auth.interpreterDomainHint')}</p>}
      <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
        {fixedEmail ? (
          <div>
            <Label>{t('auth.signingInAs')}</Label>
            <div className="flex items-center gap-3 rounded-xl border border-line bg-surface-2/60 px-3 py-2.5">
              <Avatar name={rememberedEmail} size={30} />
              <span dir="ltr" className="min-w-0 flex-1 break-all text-start text-sm font-medium leading-snug">{rememberedEmail}</span>
              <button type="button" onClick={notYou} className="shrink-0 rounded-lg px-2 py-1 text-xs text-gold-ink underline-offset-4 hover:underline">{t('auth.notYou')}</button>
            </div>
            {/* lets password managers pair the saved password with this e-mail */}
            <input type="email" name="email" autoComplete="username" value={rememberedEmail} readOnly hidden />
          </div>
        ) : (
          <div>
            <label htmlFor="login-email"><Label>{t('auth.email')}</Label></label>
            <Input ref={emailRef} id="login-email" name="email" dir="ltr" type="email" required autoComplete="username" inputMode="email" placeholder="you@email.com" value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
        )}
        <div>
          <div className="flex items-baseline justify-between gap-3">
            <label htmlFor="login-password"><Label>{t('auth.password')}</Label></label>
            <button type="button" onClick={goForgot} className="mb-2 text-xs text-fg-muted underline-offset-4 hover:text-gold-ink hover:underline">{t('auth.forgotLink')}</button>
          </div>
          <PasswordInput ref={passwordRef} id="login-password" name="password" dir="ltr" required autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <AuthErrorBox error={err} action={err?.code === 'PASSWORD_NOT_SET' ? (
          <Button type="button" size="sm" variant="ghost" className="border-danger/50 text-danger" onClick={goForgot}><Icon name="lock" size={16} />{t('auth.createPassword')}</Button>
        ) : undefined} />
        <Button type="submit" size="lg" loading={login.isPending} disabled={!cleanEmail || !password}>{t('auth.signIn')}</Button>
      </form>
      <p className="mt-4 text-center text-sm text-fg-muted">
        {t('auth.newHere')}{' '}
        <Link to={{ pathname: '/register', search }} state={{ email: fixedEmail ? '' : cleanEmail } satisfies AuthNavState} className="font-medium text-gold-ink underline-offset-4 hover:underline">{t('auth.createAccountLink')}</Link>
      </p>
      {!isInterpreterEmail && <GoogleSection remember={remember} onError={showError} onSignedIn={(r, rem) => finish(r, rem, 'google')} />}
      <RememberMe checked={remember} onChange={choose} />
      {MOCKS && <p className="mt-4 text-center text-[11px] leading-relaxed text-fg-dim">{t('auth.mockHint', { email: 'fatema@saadatu-aldarein.com', password: 'saadat2026' })}</p>}
    </AuthShell>
  )
}

export function OnboardingPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const user = useAuthStore((s) => s.user)
  const setUser = useAuthStore((s) => s.setUser)
  const [name, setName] = useState(user?.name ?? '')
  const [gender, setGender] = useState<Gender | ''>(user?.gender ?? '')
  const [birthDate, setBirthDate] = useState(user?.birthDate ?? '')
  // accepted already at sign-up: the box is not shown again (acceptedTerms is still sent)
  const termsDone = user?.termsAccepted === true
  const [terms, setTerms] = useState(termsDone)
  const m = useMutation({ mutationFn: authApi.onboarding, onSuccess: (u) => { setUser(u); navigate(params.get('next') || '/me') } })
  return (
    <div className="relative min-h-screen bg-night text-pearl">
      <NightSky className="absolute inset-0" withMoon={false} />
      <PageEnter className="relative mx-auto flex min-h-screen max-w-md flex-col items-center justify-center px-5">
        <h1 className="font-display text-4xl">{t('auth.onboardingTitle')}</h1>
        <p className="mt-2 mb-8 text-center text-sm font-light text-pearl/60">{t('auth.onboardingLead')}</p>
        <form className="card w-full p-6 text-fg flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); if (gender && terms && birthDate) m.mutate({ name, gender, birthDate, acceptedTerms: true }) }}>
          <div><Label>{t('auth.name')}</Label><Input required value={name} onChange={(e) => setName(e.target.value)} /></div>
          <div><Label>{t('auth.gender')}</Label>
            <div className="grid grid-cols-2 gap-2">
              {(['FEMALE', 'MALE'] as Gender[]).map((g) => <button type="button" key={g} onClick={() => setGender(g)} className={`rounded-xl border px-4 py-3 text-sm transition-colors ${gender === g ? 'border-gold bg-gold/10 text-gold-ink' : 'border-line text-fg-muted'}`}>{t(g === 'FEMALE' ? 'auth.female' : 'auth.male')}</button>)}
            </div>
          </div>
          {!termsDone && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={terms} onChange={(e) => setTerms(e.target.checked)} className="accent-gold" />{t('auth.terms')}</label>}
          <div><Label>{t('auth.birthDate')}</Label><Input type="date" dir="ltr" value={birthDate} max={new Date().toISOString().slice(0, 10)} onChange={(e) => setBirthDate(e.target.value)} required /></div>
          <Button type="submit" disabled={!gender || !terms || !name || !birthDate} loading={m.isPending}>{t('auth.continue')}</Button>
        </form>
      </PageEnter>
    </div>
  )
}

export function MagicCallbackPage() {
  // /auth/callback?token=… — the backend redirects here after verifying a link.
  const { t } = useTranslation()
  const [params] = useSearchParams()
  const finish = useFinishLogin()
  const m = useMutation({ mutationFn: authApi.magicVerify, onSuccess: (r, v) => finish(r, v.rememberMe ?? true, 'email') })
  const token = params.get('token')
  const started = useRef(false)
  useEffect(() => {
    // once per page: the link is single-use (React's dev double effects must not spend it twice)
    if (!token || started.current) return
    started.current = true
    // the "Keep me signed in" choice saved on the login page before the link was requested (default: yes)
    m.mutate({ token, rememberMe: readRememberMe() })
  }, [token, m])
  return <div className="flex min-h-screen items-center justify-center bg-night text-pearl">{m.isError ? t('auth.linkInvalid') : '…'}</div>
}
