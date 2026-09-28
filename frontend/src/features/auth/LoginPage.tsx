import { useState, type FormEvent } from 'react'
import { useNavigate, useSearchParams, Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation } from '@tanstack/react-query'
import { GoogleLogin, GoogleOAuthProvider } from '@react-oauth/google'
import { INTERPRETER_DOMAIN } from '@/lib/utils'
import { authApi } from '@/api/endpoints'
import { useAuthStore, isInterpreter } from '@/app/auth-store'
import { NightSky } from '@/components/motion/NightSky'
import { PageEnter } from '@/components/motion'
import { Button, Input, Label } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { LocaleToggle } from '@/components/layout'
import { ApiError } from '@/api/client'
import type { AuthResponse, Gender } from '@/api/types'

const GOOGLE_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined
const MOCKS = import.meta.env.VITE_USE_MOCKS === 'true'

function useFinishLogin() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const setSession = useAuthStore((s) => s.setSession)
  return (r: AuthResponse) => {
    setSession(r.accessToken, r.user)
    const next = params.get('next')
    if (!r.user.onboarded) navigate(`/onboarding${next ? `?next=${encodeURIComponent(next)}` : ''}`)
    else navigate(next || (isInterpreter(r.user) ? '/admin' : '/me'))
  }
}

export default function LoginPage() {
  const { t } = useTranslation()
  const finish = useFinishLogin()
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [sent, setSent] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const google = useMutation({ mutationFn: authApi.google, onSuccess: finish, onError: (e) => setErr((e as ApiError).message) })
  const magic = useMutation({ mutationFn: authApi.magicRequest, onSuccess: () => setSent(true), onError: (e) => setErr((e as ApiError).message) })
  const verify = useMutation({ mutationFn: authApi.magicVerify, onSuccess: finish, onError: (e) => setErr((e as ApiError).message) })

  const isInterpreterEmail = email.trim().toLowerCase().endsWith(INTERPRETER_DOMAIN)
  const submitEmail = (e: FormEvent) => { e.preventDefault(); setErr(null); magic.mutate({ email: email.trim().toLowerCase() }) }
  const submitCode = (e: FormEvent) => { e.preventDefault(); setErr(null); verify.mutate({ email: email.trim().toLowerCase(), code }) }

  return (
    <div className="relative min-h-screen bg-night text-pearl">
      <NightSky className="absolute inset-0" withMoon={false} />
      <div className="absolute top-5 inset-x-5 flex justify-between"><Link to="/" className="font-display text-xl text-gold-soft">{t('interpreter')}</Link><LocaleToggle dark /></div>
      <PageEnter className="relative mx-auto flex min-h-screen max-w-md flex-col items-center justify-center px-5 py-20">
        <div className="mb-6 text-gold-soft"><Icon name="moon" size={56} strokeWidth={0.9} /></div>
        <h1 className="font-display text-4xl">{t('auth.title')}</h1>
        <p className="mt-2 mb-8 text-center text-sm font-light text-pearl/60">{t('auth.lead')}</p>
        <div className="card w-full bg-surface p-6 text-fg">
          {isInterpreterEmail ? (
            <p className="rounded-xl border border-gold/40 bg-gold/10 p-3 text-center text-xs text-gold-deep">{t('auth.interpreterDomainHint')}</p>
          ) : GOOGLE_ID && !MOCKS ? (
            <GoogleOAuthProvider clientId={GOOGLE_ID}>
              <div className="flex justify-center"><GoogleLogin onSuccess={(c) => c.credential && google.mutate({ idToken: c.credential })} onError={() => setErr(t('common.error'))} shape="pill" width="320" /></div>
            </GoogleOAuthProvider>
          ) : (
            <Button variant="ghost" className="w-full bg-white text-[#222] border-[#e5e5e5]" loading={google.isPending} onClick={() => google.mutate({ idToken: 'mock' })}>
              <GoogleG /> {t('auth.google')}
            </Button>
          )}
          {!isInterpreterEmail && <div className="my-5 flex items-center gap-3 text-xs text-fg-dim"><span className="h-px flex-1 bg-line" />{t('auth.or')}<span className="h-px flex-1 bg-line" /></div>}
          {!sent ? (
            <form onSubmit={submitEmail} className="flex flex-col gap-3">
              <Label>{t('auth.email')}</Label>
              <Input dir="ltr" type="email" required placeholder="you@email.com" value={email} onChange={(e) => setEmail(e.target.value)} />
              <Button type="submit" variant="ghost" loading={magic.isPending}>{t('auth.sendLink')} ✦</Button>
              <p className="text-center text-xs text-fg-dim">{t('auth.noPassword')}</p>
            </form>
          ) : (
            <form onSubmit={submitCode} className="flex flex-col gap-3 text-center">
              <p className="text-success">✓ {t('auth.sent')}</p>
              <p className="text-xs text-fg-dim">{t('auth.sentHint')}</p>
              <Input dir="ltr" inputMode="numeric" maxLength={6} placeholder="123456" className="text-center tracking-[.4em]" value={code} onChange={(e) => setCode(e.target.value)} />
              <Button type="submit" loading={verify.isPending}>{t('auth.verify')}</Button>
              {MOCKS && <p className="text-[11px] text-fg-dim">وضع التجربة: أي رمز يدخلك. استخدم fatema@saadatu-aldarein.com لدخول المعبّرة.</p>}
            </form>
          )}
          {err && <p className="mt-4 text-center text-sm text-danger">{err}</p>}
        </div>
      </PageEnter>
    </div>
  )
}

function GoogleG() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
      <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
      <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
      <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" />
      <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" />
    </svg>
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
  const [terms, setTerms] = useState(false)
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
              {(['FEMALE', 'MALE'] as Gender[]).map((g) => <button type="button" key={g} onClick={() => setGender(g)} className={`rounded-xl border px-4 py-3 text-sm transition-colors ${gender === g ? 'border-gold bg-gold/10 text-gold-deep' : 'border-line text-fg-muted'}`}>{t(g === 'FEMALE' ? 'auth.female' : 'auth.male')}</button>)}
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={terms} onChange={(e) => setTerms(e.target.checked)} className="accent-[var(--gold)]" />{t('auth.terms')}</label>
          <div><Label>{t('auth.birthDate')}</Label><Input type="date" dir="ltr" value={birthDate} max={new Date().toISOString().slice(0, 10)} onChange={(e) => setBirthDate(e.target.value)} required /></div>
          <Button type="submit" disabled={!gender || !terms || !name || !birthDate} loading={m.isPending}>{t('auth.continue')}</Button>
        </form>
      </PageEnter>
    </div>
  )
}

export function MagicCallbackPage() {
  // /auth/callback?token=… — the backend redirects here after verifying a link.
  const [params] = useSearchParams()
  const finish = useFinishLogin()
  const m = useMutation({ mutationFn: authApi.magicVerify, onSuccess: finish })
  const token = params.get('token')
  if (token && m.isIdle) m.mutate({ token })
  return <div className="flex min-h-screen items-center justify-center bg-night text-pearl">{m.isError ? 'الرابط غير صالح أو انتهت صلاحيته' : '…'}</div>
}
