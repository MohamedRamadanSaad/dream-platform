import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Icon } from '@/components/icons/Icon'
import { Avatar } from '@/components/ui/Avatar'
import { useMutation } from '@tanstack/react-query'
import { meApi, notificationsApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { applyLocale } from '@/i18n'
import { Button, Input, Label, Segmented } from '@/components/ui'
import { PageEnter } from '@/components/motion'
import { fmtDate } from '@/lib/utils'
import type { Gender, Locale } from '@/api/types'

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

export function ProfilePage() {
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
  const clear = useAuthStore((s) => s.clear)
  const navigate = useNavigate()
  const [push, setPush] = useState<string>(typeof Notification !== 'undefined' && Notification.permission === 'granted' ? 'granted' : '')
  const save = useMutation({ meta: { toast: 'common.saved' }, mutationFn: () => meApi.preferences({ name, gender, locale, birthDate: birthDate || undefined }), onSuccess: setUser })
  const isIos = /iphone|ipad/i.test(navigator.userAgent)
  const standalone = window.matchMedia('(display-mode: standalone)').matches
  return (
    <PageEnter className="mx-auto max-w-2xl space-y-5">
      <div className="flex items-center gap-4"><Avatar name={name || user.email} size={56} /><h1 className="font-display text-4xl">{t('me.profile.title')}</h1></div>
      <div className="card p-6 space-y-5">
        <div><Label>{t('me.profile.name')}</Label><Input value={name} onChange={(e) => setName(e.target.value)} /></div>
        <div><Label>{t('me.profile.email')}</Label><Input dir="ltr" value={user.email} readOnly className="opacity-70" /></div>
        <div><Label>{t('me.profile.gender')}</Label><Segmented value={gender} onChange={setGender} items={[{ value: 'FEMALE', label: t('auth.female') }, { value: 'MALE', label: t('auth.male') }]} /></div>
        <div><Label>{t('me.profile.birthDate')}{user.age != null && <span className="ms-2 text-fg-dim">({t('me.profile.years', { n: user.age })})</span>}</Label><Input type="date" dir="ltr" value={birthDate} max={new Date().toISOString().slice(0, 10)} onChange={(e) => setBirthDate(e.target.value)} /></div>
        <div><Label>{t('me.profile.language')}</Label><Segmented value={locale} onChange={(l: Locale) => { setLocale(l); applyLocale(l) }} items={[{ value: 'ar', label: 'العربية' }, { value: 'en', label: 'English' }]} /></div>
        <div><Label>{t('me.profile.theme')}</Label><Segmented value={theme} onChange={setTheme} items={[{ value: 'light', label: t('me.profile.light') }, { value: 'dark', label: t('me.profile.dark') }]} /></div>
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div><Label>{t('me.profile.country')}</Label>{user.countryName}</div>
          <div><Label>{t('me.profile.provider')}</Label>{user.providers.map((p) => t(`auth.providers.${p}`, { defaultValue: p })).join('، ')}</div>
          <div><Label>{t('me.profile.since')}</Label>{fmtDate(user.createdAt, locale)}</div>
        </div>
        <div className="flex items-center justify-between"><span className="text-xs text-success">{save.isSuccess && `✓ ${t('me.profile.saved')}`}</span><Button loading={save.isPending} onClick={() => save.mutate()}>{t('me.profile.save')}</Button></div>
      </div>
      <div className="card p-6">
        <Label>{t('me.profile.push')}</Label>
        {push === 'granted' ? <div className="text-sm text-success">✓ {t('me.profile.pushOn')}</div>
          : isIos && !standalone ? <div className="text-sm text-fg-muted"><div className="font-medium text-fg">{t('common.installTitle')}</div>{t('common.installIos')}</div>
          : <Button variant="ghost" size="sm" onClick={async () => setPush(await enablePush())}>{t('me.profile.pushEnable')}</Button>}
        {push === 'denied' && <p className="mt-2 text-xs text-danger">{t('me.profile.pushDenied')}</p>}
      </div>
      <div className="card flex items-center justify-between p-6">
        <div className="min-w-0"><Label>{t('me.profile.signedInAs')}</Label><div className="truncate text-sm" dir="ltr">{user.email}</div></div>
        <button aria-label={t('auth.logout')} title={t('auth.logout')} onClick={() => { clear(); navigate('/') }} className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-line text-fg-muted transition-colors hover:border-danger hover:text-danger"><Icon name="logout" size={20} /></button>
      </div>
      <button className="text-sm text-danger hover:underline" onClick={() => confirm(t('me.profile.deleteConfirm')) && meApi.deleteAccount()}>{t('me.profile.delete')}</button>
    </PageEnter>
  )
}
