// One account page for every role: /me/profile (users) and /admin/profile (the interpreter).
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation } from '@tanstack/react-query'
import { Icon } from '@/components/icons/Icon'
import { Avatar } from '@/components/ui/Avatar'
import { toast } from '@/components/ui/Toaster'
import { meApi, notificationsApi } from '@/api/endpoints'
import { useAuthStore, isInterpreter } from '@/app/auth-store'
import { useSignOut } from '@/app/session'
import { applyLocale } from '@/i18n'
import { Button, Input, Label, Modal, Segmented } from '@/components/ui'
import { PageEnter } from '@/components/motion'
import { GoogleG } from '@/features/auth/LoginPage'
import { cn, flagEmoji, fmtDate, regionName } from '@/lib/utils'
import { DevicesSection } from './DevicesSection'
import type { AuthProvider, Gender, Locale } from '@/api/types'

async function enablePush(): Promise<'granted' | 'denied' | 'unsupported'> {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return 'unsupported'
  const perm = await Notification.requestPermission()
  if (perm !== 'granted') return 'denied'
  const reg = await navigator.serviceWorker.ready
  const key = import.meta.env.VITE_VAPID_PUBLIC_KEY as string | undefined
  if (!key) return 'granted' // mocks: nothing to subscribe against
  const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key })
  const j = sub.toJSON()
  await notificationsApi.subscribePush({ endpoint: sub.endpoint, keys: { p256dh: j.keys!.p256dh, auth: j.keys!.auth }, userAgent: navigator.userAgent })
  return 'granted'
}

function Field({ label, children, className }: { label: ReactNode; children: ReactNode; className?: string }) {
  return <div className={cn('min-w-0', className)}><Label>{label}</Label>{children}</div>
}

/** A value the user cannot change here (e-mail, country, member since). */
function ReadOnly({ children, ltr }: { children: ReactNode; ltr?: boolean }) {
  return <div dir={ltr ? 'ltr' : undefined} className="input truncate bg-surface-2/50 text-fg-muted">{children}</div>
}

function PersonalCard() {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)!
  const setUser = useAuthStore((s) => s.setUser)
  const locale = useAuthStore((s) => s.locale)
  const setLocale = useAuthStore((s) => s.setLocale)
  const theme = useAuthStore((s) => s.theme)
  const setTheme = useAuthStore((s) => s.setTheme)
  const [name, setName] = useState(user.name)
  const [gender, setGender] = useState<Gender>(user.gender ?? 'FEMALE')
  const [birthDate, setBirthDate] = useState(user.birthDate ?? '')
  const save = useMutation({ meta: { toast: 'common.saved' }, mutationFn: () => meApi.preferences({ name, gender, locale, birthDate: birthDate || undefined }), onSuccess: setUser })
  return (
    <section aria-labelledby="personal-title" className="card p-5 sm:p-6">
      <h2 id="personal-title" className="font-display text-xl">{t('me.profile.personal')}</h2>
      <div className="mt-5 grid gap-5 sm:grid-cols-2">
        <Field label={t('me.profile.name')}><Input dir="auto" value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" /></Field>
        <Field label={t('me.profile.email')}><ReadOnly ltr>{user.email}</ReadOnly></Field>
        <Field label={t('me.profile.gender')}><Segmented value={gender} onChange={setGender} items={[{ value: 'FEMALE', label: t('auth.female') }, { value: 'MALE', label: t('auth.male') }]} /></Field>
        <Field label={<>{t('me.profile.birthDate')}{user.age != null && <span className="ms-2 text-fg-muted">({t('me.profile.years', { n: user.age })})</span>}</>}>
          <Input type="date" dir="ltr" value={birthDate} max={new Date().toISOString().slice(0, 10)} onChange={(e) => setBirthDate(e.target.value)} />
        </Field>
        <Field label={t('me.profile.language')}><Segmented value={locale} onChange={(l: Locale) => { setLocale(l); applyLocale(l) }} items={[{ value: 'ar', label: t('common.langAr') }, { value: 'en', label: t('common.langEn') }]} /></Field>
        <Field label={t('me.profile.theme')}><Segmented value={theme} onChange={setTheme} items={[{ value: 'light', label: t('me.profile.light') }, { value: 'dark', label: t('me.profile.dark') }]} /></Field>
        <Field label={t('me.profile.country')}>
          <ReadOnly>{user.countryCode && <span aria-hidden="true" className="me-2">{flagEmoji(user.countryCode)}</span>}{regionName(user.countryCode, locale, user.countryName)}</ReadOnly>
        </Field>
        <Field label={t('me.profile.since')}><ReadOnly>{fmtDate(user.createdAt, locale)}</ReadOnly></Field>
      </div>
      <div className="mt-6 flex items-center justify-end gap-3">
        <span role="status" className="text-xs text-success">{save.isSuccess && `✓ ${t('me.profile.saved')}`}</span>
        <Button loading={save.isPending} onClick={() => save.mutate()}>{t('me.profile.save')}</Button>
      </div>
    </section>
  )
}

/** How this account signs in. The site has no passwords: Google or a code sent by e-mail (the interpreter: code only). */
function SignInMethods({ interpreter }: { interpreter: boolean }) {
  const { t } = useTranslation()
  const providers = useAuthStore((s) => s.user)!.providers
  const methods: AuthProvider[] = providers.length ? providers : interpreter ? ['MAGIC_LINK'] : ['GOOGLE', 'MAGIC_LINK']
  return (
    <section aria-labelledby="signin-title" className="card p-5 sm:p-6">
      <h2 id="signin-title" className="font-display text-xl">{t('me.profile.signInTitle')}</h2>
      <div className="mt-4 flex flex-wrap gap-2">
        {methods.map((p) => (
          <span key={p} className="chip border border-line bg-surface-2/50 px-3.5 py-1.5 text-sm text-fg">
            {p === 'GOOGLE' ? <GoogleG size={16} /> : <span className="text-gold-ink"><Icon name="mail" size={16} /></span>}
            {t(`auth.providers.${p}`, { defaultValue: p })}
          </span>
        ))}
      </div>
      <p className="mt-3 flex items-start gap-2 text-sm text-fg-muted">
        <span className="mt-0.5 shrink-0 text-gold-ink"><Icon name="shield" size={16} /></span>
        {t(interpreter ? 'me.profile.noPasswordsInterpreter' : 'me.profile.noPasswords')}
      </p>
    </section>
  )
}

function PushCard() {
  const { t } = useTranslation()
  const [push, setPush] = useState<string>(typeof Notification !== 'undefined' && Notification.permission === 'granted' ? 'granted' : '')
  const isIos = /iphone|ipad/i.test(navigator.userAgent)
  const standalone = window.matchMedia('(display-mode: standalone)').matches
  return (
    <section className="card p-5 sm:p-6">
      <Label>{t('me.profile.push')}</Label>
      {push === 'granted' ? <div className="text-sm text-success">✓ {t('me.profile.pushOn')}</div>
        : isIos && !standalone ? <div className="text-sm text-fg-muted"><div className="font-medium text-fg">{t('common.installTitle')}</div>{t('common.installIos')}</div>
        : <Button variant="ghost" size="sm" onClick={async () => setPush(await enablePush())}>{t('me.profile.pushEnable')}</Button>}
      {push === 'denied' && <p className="mt-2 text-xs text-danger">{t('me.profile.pushDenied')}</p>}
    </section>
  )
}

export function ProfilePage() {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)!
  const interpreter = isInterpreter(user)
  const { signOut, pending: signingOut } = useSignOut()
  const [confirmDelete, setConfirmDelete] = useState(false)
  const del = useMutation({
    mutationFn: meApi.deleteAccount,
    meta: { toast: false },
    // the server has already ended every session of the account
    onSuccess: () => { toast.success(t('me.profile.deleted')); void signOut({ server: false }) },
    onError: () => toast.error(t('common.error')),
  })
  return (
    <PageEnter className="mx-auto max-w-2xl space-y-5">
      <div className="flex items-center gap-4">
        <Avatar name={user.name || user.email} size={56} />
        <div className="min-w-0">
          <h1 className="font-display text-4xl">{t('me.profile.title')}</h1>
          {interpreter && <div className="mt-1 text-sm text-gold-ink">{t('me.profile.interpreterRole')}</div>}
        </div>
      </div>
      <PersonalCard />
      <SignInMethods interpreter={interpreter} />
      {!interpreter && <PushCard />}
      <DevicesSection />
      <section className="card flex items-center justify-between gap-4 p-5 sm:p-6">
        <div className="min-w-0"><Label>{t('me.profile.signedInAs')}</Label><div className="truncate text-sm" dir="ltr">{user.email}</div></div>
        <button type="button" aria-label={t('auth.logout')} title={t('auth.logout')} disabled={signingOut} onClick={() => void signOut()}
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-line text-fg-muted transition-colors hover:border-danger hover:text-danger disabled:opacity-50">
          {signingOut ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" /> : <Icon name="logout" size={20} flipRtl />}
        </button>
      </section>
      {!interpreter && (
        <>
          <button type="button" className="text-sm text-danger hover:underline" onClick={() => setConfirmDelete(true)}>{t('me.profile.delete')}</button>
          <Modal open={confirmDelete} onClose={() => setConfirmDelete(false)} title={t('me.profile.deleteConfirm')} size="sm"
            footer={<><Button variant="ghost" onClick={() => setConfirmDelete(false)}>{t('common.cancel')}</Button><Button className="bg-danger text-white hover:shadow-none" loading={del.isPending} onClick={() => del.mutate()}>{t('me.profile.deleteYes')}</Button></>}>
            <p className="text-sm leading-relaxed text-fg-muted">{t('me.profile.deleteBody')}</p>
          </Modal>
        </>
      )}
    </PageEnter>
  )
}
