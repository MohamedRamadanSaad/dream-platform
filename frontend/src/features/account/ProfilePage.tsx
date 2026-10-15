// One account page for every role: /me/profile (users) and /admin/profile (the interpreter).
import { useEffect, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Icon } from '@/components/icons/Icon'
import { Avatar } from '@/components/ui/Avatar'
import { toast } from '@/components/ui/Toaster'
import { adminApi, meApi, notificationsApi } from '@/api/endpoints'
import { useAuthStore, isInterpreter } from '@/app/auth-store'
import { useSignOut, forgetLastEmail } from '@/app/session'
import { applyLocale } from '@/i18n'
import { Button, Input, Label, Modal, Segmented, Switch } from '@/components/ui'
import { PageEnter } from '@/components/motion'
import { GoogleG } from '@/features/auth/LoginPage'
import { fetchPushKey, isIos, isStandalone, pushPermission, subscribePush } from '@/lib/push'
import { cn, flagEmoji, fmtDate, regionName } from '@/lib/utils'
import { DevicesSection } from './DevicesSection'
import { PasskeysSection } from './PasskeysSection'
import { usePasskeys } from './usePasskeys'
import type { Gender, Locale } from '@/api/types'

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

/**
 * How this account signs in: e-mail + password or Google (the interpreter: e-mail + password only), plus fingerprint /
 * face once a passkey is added (#passkeys below). Accounts opened before passwords existed may still show the e-mail code.
 */
function SignInMethods({ interpreter }: { interpreter: boolean }) {
  const { t } = useTranslation()
  const providers = useAuthStore((s) => s.user)!.providers
  const passkeys = usePasskeys()
  const withPasskey = (passkeys.data?.length ?? 0) > 0
  const user = useAuthStore((s) => s.user)!
  const base: string[] = providers.length ? providers : interpreter ? ['PASSWORD'] : ['GOOGLE', 'PASSWORD']
  const methods = [...new Set([...base, ...(user.hasPassword ? ['PASSWORD'] : []), ...(withPasskey ? ['PASSKEY'] : [])])]
  const icon = (p: string) => (p === 'GOOGLE' ? <GoogleG size={16} /> : <span className="text-gold-ink"><Icon name={p === 'PASSKEY' ? 'passkey' : p === 'PASSWORD' ? 'lock' : 'mail'} size={16} /></span>)
  const line = interpreter
    ? withPasskey ? 'me.profile.signInLineInterpreterPasskey' : 'me.profile.signInLineInterpreter'
    : withPasskey ? 'me.profile.signInLinePasskey' : 'me.profile.signInLine'
  return (
    <section aria-labelledby="signin-title" className="card p-5 sm:p-6">
      <h2 id="signin-title" className="font-display text-xl">{t('me.profile.signInTitle')}</h2>
      <div className="mt-4 flex flex-wrap gap-2">
        {methods.map((p) => (
          <span key={p} className="chip border border-line bg-surface-2/50 px-3.5 py-1.5 text-sm text-fg">
            {icon(p)}
            {t(`auth.providers.${p}`, { defaultValue: p })}
          </span>
        ))}
      </div>
      <p className="mt-3 flex items-start gap-2 text-sm text-fg-muted">
        <span className="mt-0.5 shrink-0 text-gold-ink"><Icon name="shield" size={16} /></span>
        {t(line)}
      </p>
      {/* a new password goes through the e-mailed code (the same steps as "Forgot password?") */}
      <Link to="/forgot-password" state={{ email: user.email }} className="btn btn-sm btn-ghost mt-4">
        <Icon name="lock" size={16} />{t(user.hasPassword ? 'me.profile.changePassword' : 'me.profile.createPassword')}
      </Link>
    </section>
  )
}

type PushNote = { key: string; tone: 'ok' | 'bad' } | null

/**
 * Phone notifications for this device (users and the interpreter). States: not possible in this browser / iPhone
 * not installed yet (how to install) / not asked yet (turn on) / blocked (how to allow it in the settings) / on (send
 * a test, re-connect). Turning on asks the permission only on the tap.
 */
function PushCard({ interpreter }: { interpreter: boolean }) {
  const { t } = useTranslation()
  const key = useQuery({ queryKey: ['public', 'push-key'], queryFn: fetchPushKey, staleTime: Infinity, retry: 1 })
  const [permission, setPermission] = useState(pushPermission)
  const [note, setNote] = useState<PushNote>(null)
  // back from the phone settings (notifications allowed or blocked there): show the new state
  useEffect(() => {
    const onVisible = () => { if (document.visibilityState === 'visible') setPermission(pushPermission()) }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])
  const connect = useMutation({
    meta: { toast: false },
    mutationFn: (_again: boolean) => subscribePush(),
    onSuccess: (r, again) => {
      setPermission(pushPermission())
      if (r === 'granted') setNote(again ? { key: 'me.profile.pushReconnected', tone: 'ok' } : null)
      else if (r === 'no-key') setNote({ key: 'me.profile.pushNotConfigured', tone: 'bad' })
      else if (r === 'failed') setNote({ key: 'me.profile.pushFailed', tone: 'bad' })
      else setNote(null)
    },
  })
  const test = useMutation({
    meta: { toast: false },
    mutationFn: notificationsApi.testPush,
    onSuccess: (r) => setNote(
      !r.configured ? { key: 'me.profile.pushNotConfigured', tone: 'bad' }
        : r.subscriptions === 0 ? { key: 'me.profile.pushTestNoDevice', tone: 'bad' }
        : r.sent === 0 ? { key: 'me.profile.pushTestFailed', tone: 'bad' }
        : { key: 'me.profile.pushTestSent', tone: 'ok' }),
    onError: () => setNote({ key: 'me.profile.pushTestFailed', tone: 'bad' }),
  })
  if (key.isPending) return null
  // no key on the server: users see nothing, the interpreter sees why
  if (!key.data && !interpreter) return null
  const ios = isIos()
  const body = (() => {
    if (!key.data) return <p className="text-sm text-fg-muted">{t('me.profile.pushNotConfigured')}</p>
    if (ios && !isStandalone()) {
      return <div className="text-sm text-fg-muted"><div className="font-medium text-fg">{t('me.profile.pushIosTitle')}</div><p className="mt-1">{t('me.profile.pushIosBody')}</p></div>
    }
    if (permission === 'unsupported') return <p className="text-sm text-fg-muted">{t('me.profile.pushUnsupported')}</p>
    if (permission === 'denied') {
      return <p className="flex items-start gap-2 text-sm text-fg-muted"><span className="mt-0.5 shrink-0 text-danger"><Icon name="alert" size={16} /></span>{t(ios ? 'me.profile.pushDeniedIos' : 'me.profile.pushDeniedOther')}</p>
    }
    if (permission === 'default') {
      return <Button size="sm" loading={connect.isPending} onClick={() => connect.mutate(false)}><Icon name="bell" size={16} />{t('me.profile.pushEnable')}</Button>
    }
    return (
      <>
        <div className="flex items-center gap-2 text-sm text-success"><Icon name="check" size={16} />{t('me.profile.pushOn')}</div>
        <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
          <Button variant="ghost" size="sm" loading={test.isPending} disabled={connect.isPending} onClick={() => { setNote(null); test.mutate() }}>
            <Icon name="bell" size={16} />{t('me.profile.pushTest')}
          </Button>
          <button type="button" disabled={connect.isPending || test.isPending} onClick={() => { setNote(null); connect.mutate(true) }}
            className="text-sm text-fg-muted underline-offset-4 hover:text-fg hover:underline disabled:opacity-50">
            {connect.isPending ? t('me.profile.pushConnecting') : t('me.profile.pushReconnect')}
          </button>
        </div>
      </>
    )
  })()
  return (
    <section aria-labelledby="push-title" className="card p-5 sm:p-6">
      <h2 id="push-title" className="font-display text-xl">{t('me.profile.push')}</h2>
      <p className="mt-1 text-sm font-light text-fg-muted">{t(interpreter ? 'me.profile.pushLeadInterpreter' : 'me.profile.pushLead')}</p>
      <div className="mt-4">{body}</div>
      <p role="status" className={cn('mt-3 text-sm empty:hidden', note?.tone === 'bad' ? 'text-danger' : 'text-success')}>{note ? t(note.key) : ''}</p>
    </section>
  )
}

/** New-video e-mails: on by default; the user can stop or restart them here (also stoppable from the e-mail). */
function ChannelMailCard() {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)!
  const setUser = useAuthStore((s) => s.setUser)
  const save = useMutation({ meta: { toast: 'common.saved' }, mutationFn: (on: boolean) => meApi.preferences({ marketingOptIn: on }), onSuccess: setUser })
  const on = save.isPending ? !!save.variables : user.marketingOptIn !== false
  return (
    <section className="card flex items-center justify-between gap-4 p-5 sm:p-6">
      <div className="min-w-0">
        <div className="font-medium">{t('me.profile.channelMail')}</div>
        <p className="mt-1 text-sm font-light text-fg-muted">{t('me.profile.channelMailHint')}</p>
      </div>
      <Switch checked={on} disabled={save.isPending} label={t('me.profile.channelMail')} onChange={(v) => save.mutate(v)} />
    </section>
  )
}

const SELLER_KEYS = { name: 'brand.legal_name', taxNo: 'brand.tax_registration_no', phone: 'brand.support_phone', hoursAr: 'brand.support_hours.ar', hoursEn: 'brand.support_hours.en' } as const

/** Interpreter only: the seller details shown on the terms page, in the footer and on the payment receipt. */
function SellerCard() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['admin', 'settings'], queryFn: adminApi.settings })
  const [draft, setDraft] = useState<Record<string, string>>({})
  const value = (k: keyof typeof SELLER_KEYS) => draft[SELLER_KEYS[k]] ?? q.data?.[SELLER_KEYS[k]] ?? ''
  const changes = Object.fromEntries(Object.entries(draft).filter(([k, v]) => q.data?.[k] !== v).map(([k, v]) => [k, v.trim()]))
  const save = useMutation({
    mutationFn: () => adminApi.saveSettings(changes),
    onSuccess: (all) => { qc.setQueryData(['admin', 'settings'], all); setDraft({}); void qc.invalidateQueries({ queryKey: ['public', 'legal'] }) },
  })
  return (
    <section aria-labelledby="seller-title" className="card p-5 sm:p-6">
      <h2 id="seller-title" className="font-display text-xl">{t('admin.legal.title')}</h2>
      <p className="mt-1 text-sm font-light text-fg-muted">{t('admin.legal.lead')}</p>
      <div className="mt-5 grid gap-5 sm:grid-cols-2">
        <Field label={t('admin.legal.name')}><Input dir="auto" value={value('name')} onChange={(e) => setDraft((d) => ({ ...d, [SELLER_KEYS.name]: e.target.value }))} /></Field>
        <Field label={t('admin.legal.taxNo')}><Input dir="ltr" inputMode="numeric" value={value('taxNo')} onChange={(e) => setDraft((d) => ({ ...d, [SELLER_KEYS.taxNo]: e.target.value }))} /></Field>
        <Field label={t('admin.legal.phone')}><Input dir="ltr" type="tel" inputMode="tel" value={value('phone')} onChange={(e) => setDraft((d) => ({ ...d, [SELLER_KEYS.phone]: e.target.value }))} /></Field>
        <Field label={t('admin.legal.hoursAr')}><Input dir="rtl" value={value('hoursAr')} onChange={(e) => setDraft((d) => ({ ...d, [SELLER_KEYS.hoursAr]: e.target.value }))} /></Field>
        <Field label={t('admin.legal.hoursEn')}><Input dir="ltr" value={value('hoursEn')} onChange={(e) => setDraft((d) => ({ ...d, [SELLER_KEYS.hoursEn]: e.target.value }))} /></Field>
      </div>
      <div className="mt-6 flex justify-end"><Button disabled={!Object.keys(changes).length} loading={save.isPending} onClick={() => save.mutate()}>{t('admin.legal.save')}</Button></div>
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
    onSuccess: () => { forgetLastEmail(); toast.success(t('me.profile.deleted')); void signOut({ server: false }) },
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
      <PushCard interpreter={interpreter} />
      {!interpreter && <ChannelMailCard />}
      {interpreter && <SellerCard />}
      {/* how this account signs in, then where: methods → fingerprint / face (#passkeys) → devices (#devices) */}
      <SignInMethods interpreter={interpreter} />
      <PasskeysSection />
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
            footer={<><Button variant="ghost" onClick={() => setConfirmDelete(false)}>{t('common.cancel')}</Button><Button className="bg-danger text-white hover:shadow-none dark:text-night" loading={del.isPending} onClick={() => del.mutate()}>{t('me.profile.deleteYes')}</Button></>}>
            <p className="text-sm leading-relaxed text-fg-muted">{t('me.profile.deleteBody')}</p>
          </Modal>
        </>
      )}
    </PageEnter>
  )
}
