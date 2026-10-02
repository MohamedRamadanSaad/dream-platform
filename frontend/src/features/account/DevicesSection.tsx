// "Devices" on the account page: every browser where this account is signed in (docs/SESSIONS_PROFILE_CONTRACT.md §2).
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { meApi } from '@/api/endpoints'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/app/auth-store'
import { useSignOut } from '@/app/session'
import { Icon, type IconName } from '@/components/icons/Icon'
import { Button, Modal, Skeleton } from '@/components/ui'
import { toast } from '@/components/ui/Toaster'
import { reduced } from '@/components/motion'
import { cn, flagEmoji, fmtDate, regionName, timeAgo } from '@/lib/utils'
import type { DeviceDto, DeviceType } from '@/api/types'

const KEY = ['me', 'devices'] as const
const TYPE_ICON: Record<DeviceType, IconName> = { MOBILE: 'phone', TABLET: 'tablet', DESKTOP: 'desktop' }
/** How long a signed-out row takes to fade before it leaves the list. */
const LEAVE_MS = 320

function DeviceRow({ d, leaving, busy, onSignOut }: { d: DeviceDto; leaving: boolean; busy: boolean; onSignOut: () => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const browser = t(`me.devices.browsers.${d.browser}`, { defaultValue: d.browser })
  const os = t(`me.devices.os.${d.os}`, { defaultValue: d.os })
  const type = t(`me.devices.types.${d.deviceType}`, { defaultValue: d.deviceType })
  const place = d.countryName || regionName(d.countryCode, locale)
  // this device's button is the full sign-out
  const action = d.current ? t('auth.logout') : t('me.devices.signOut')
  return (
    <li className={cn('flex items-start gap-3 py-4 transition-[opacity,transform] duration-300 sm:gap-4', leaving && 'scale-[.98] opacity-0')}>
      <span title={type} className={cn('flex h-11 w-11 shrink-0 items-center justify-center rounded-full border', d.current ? 'border-gold/60 bg-gold/10 text-gold-ink' : 'border-line bg-surface-2/50 text-fg-muted')}>
        <Icon name={TYPE_ICON[d.deviceType] ?? 'desktop'} size={20} />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span className="font-medium text-fg"><span className="sr-only">{type}: </span>{t('me.devices.browserOn', { browser, os })}</span>
          {d.current && <span className="chip bg-gold/15 py-0.5 font-medium text-gold-ink">{t('me.devices.thisDevice')}</span>}
        </div>
        <div className="mt-1 text-sm text-fg-muted">
          {d.countryCode && <span aria-hidden="true" className="me-1.5">{flagEmoji(d.countryCode)}</span>}
          {place ?? t('me.devices.unknownPlace')}
        </div>
        <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-fg-muted">
          <span title={fmtDate(d.signedInAt, locale, true)}>{t('me.devices.signedIn', { when: timeAgo(d.signedInAt, locale) })}</span>
          {d.current
            ? <span className="inline-flex items-center gap-1.5 text-success"><span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-success" />{t('me.devices.activeNow')}</span>
            : <span title={fmtDate(d.lastActiveAt, locale, true)}>{t('me.devices.lastActive', { when: timeAgo(d.lastActiveAt, locale) })}</span>}
        </div>
        <div className="mt-1.5 flex items-start gap-1.5 text-xs leading-snug text-fg-muted">
          <span className="mt-px shrink-0"><Icon name={d.persistent ? 'check' : 'clock'} size={14} /></span>
          {t(d.persistent ? 'me.devices.remembered' : 'me.devices.notRemembered')}
        </div>
      </div>
      <button type="button" onClick={onSignOut} disabled={busy} aria-label={action} title={action}
        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-line text-fg-muted transition-colors hover:border-danger hover:text-danger disabled:opacity-50">
        {busy ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" /> : <Icon name="logout" size={18} flipRtl />}
      </button>
    </li>
  )
}

export function DevicesSection() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const { hash } = useLocation()
  const { signOut, pending: signingOut } = useSignOut()
  const q = useQuery({ queryKey: KEY, queryFn: meApi.devices })
  const [leaving, setLeaving] = useState<string[]>([])
  const [askOthers, setAskOthers] = useState(false)
  const [flash, setFlash] = useState(false)
  const ref = useRef<HTMLElement>(null)

  // this device first, then the server's order (most recently active first)
  const list = [...(q.data ?? [])].sort((a, b) => Number(b.current) - Number(a.current))
  const others = list.filter((d) => !d.current)

  /** The rows fade out, then leave the list; the list is then fetched again from the server. */
  const leave = (ids: string[], message: string) => {
    setLeaving(ids)
    window.setTimeout(() => {
      qc.setQueryData<DeviceDto[]>(KEY, (old) => old?.filter((d) => !ids.includes(d.id)))
      setLeaving([])
      toast.success(message)
      void qc.invalidateQueries({ queryKey: KEY })
    }, reduced() ? 0 : LEAVE_MS)
  }

  const one = useMutation({
    mutationFn: (id: string) => meApi.signOutDevice(id),
    meta: { toast: false },
    onSuccess: (_r, id) => leave([id], t('me.devices.signedOut')),
    // 404: that device was already signed out
    onError: (e, id) => (e instanceof ApiError && e.status === 404 ? leave([id], t('me.devices.signedOut')) : toast.error(t('common.error'))),
  })
  const rest = useMutation({
    mutationFn: meApi.signOutOtherDevices,
    meta: { toast: false },
    onSuccess: () => { setAskOthers(false); leave(others.map((d) => d.id), t('me.devices.othersDone')) },
    onError: () => toast.error(t('common.error')),
  })

  // the "new sign-in" e-mail links to …/profile#devices: bring the list into view once it is drawn
  const arrived = useRef(false)
  useEffect(() => {
    if (hash !== '#devices' || arrived.current || q.isLoading) return
    const h = window.setTimeout(() => {
      arrived.current = true
      ref.current?.scrollIntoView({ behavior: reduced() ? 'auto' : 'smooth', block: 'start' })
      setFlash(true)
    }, 120)
    return () => window.clearTimeout(h)
  }, [hash, q.isLoading])
  useEffect(() => {
    if (!flash) return
    const h = window.setTimeout(() => setFlash(false), 2400)
    return () => window.clearTimeout(h)
  }, [flash])

  return (
    <section id="devices" ref={ref} aria-labelledby="devices-title"
      className={cn('card scroll-mt-24 p-5 transition-shadow duration-700 sm:p-6', flash && 'ring-2 ring-gold/50')}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <h2 id="devices-title" className="font-display text-xl">{t('me.devices.title')}</h2>
          <p className="mt-1 text-sm text-fg-muted">{t('me.devices.lead')}</p>
        </div>
        {q.data && <span className="chip shrink-0 bg-surface-2 font-medium text-fg-muted" aria-label={t('me.devices.count', { count: list.length })}>{list.length}</span>}
      </div>

      {q.isLoading ? (
        <div className="mt-5 space-y-3">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-20" />)}</div>
      ) : q.isError ? (
        <div className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-danger/30 bg-danger/5 p-4 text-sm">
          <span className="text-danger">{t('common.error')}</span>
          <Button variant="ghost" size="sm" onClick={() => void q.refetch()}>{t('common.retry')}</Button>
        </div>
      ) : (
        <ul className="mt-3 divide-y divide-line">
          {list.map((d) => (
            <DeviceRow key={d.id} d={d} leaving={leaving.includes(d.id)}
              busy={d.current ? signingOut : (one.isPending && one.variables === d.id) || leaving.includes(d.id)}
              onSignOut={() => (d.current ? void signOut() : one.mutate(d.id))} />
          ))}
        </ul>
      )}

      {q.data && list.length > 0 && (others.length > 0 ? (
        <div className="mt-1 border-t border-line pt-4">
          <Button variant="ghost" size="sm" className="hover:border-danger hover:text-danger" onClick={() => setAskOthers(true)}>
            <Icon name="logout" size={16} flipRtl />{t('me.devices.signOutOthers')}
          </Button>
        </div>
      ) : (
        <p className="mt-1 border-t border-line pt-4 text-xs text-fg-muted">{t('me.devices.onlyThis')}</p>
      ))}

      <Modal open={askOthers} onClose={() => setAskOthers(false)} title={t('me.devices.othersTitle')} size="sm"
        footer={<><Button variant="ghost" onClick={() => setAskOthers(false)}>{t('common.cancel')}</Button><Button loading={rest.isPending} onClick={() => rest.mutate()}>{t('me.devices.othersYes')}</Button></>}>
        <p className="text-sm leading-relaxed text-fg-muted">{t('me.devices.othersBody')}</p>
      </Modal>
    </section>
  )
}
