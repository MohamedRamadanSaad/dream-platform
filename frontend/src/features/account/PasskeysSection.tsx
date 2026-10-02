// "Fingerprint or face sign-in" on the account page (#passkeys), for every role: this account's passkeys, adding one
// on this device and removing one (docs/PASSKEYS_CONTRACT.md).
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { meApi } from '@/api/endpoints'
import { ApiError } from '@/api/client'
import { useAuthStore, isInterpreter } from '@/app/auth-store'
import { Icon } from '@/components/icons/Icon'
import { Button, Input, Label, Modal, Skeleton } from '@/components/ui'
import { toast } from '@/components/ui/Toaster'
import { reduced } from '@/components/motion'
import { passkeyLabel, usePasskeySupport, type PasskeySupport } from '@/lib/passkeys'
import { cn, fmtDate, timeAgo } from '@/lib/utils'
import { PASSKEYS_KEY, useAddPasskey, usePasskeys } from './usePasskeys'
import { useAnchorArrival } from './useAnchorArrival'
import { FingerprintOrFace } from './PasskeyGlyph'
import type { PasskeyDto } from '@/api/types'

/** How long a removed row takes to fade before it leaves the list. */
const LEAVE_MS = 320

const Spinner = () => <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />

function PasskeyRow({ p, leaving, onRemove }: { p: PasskeyDto; leaving: boolean; onRemove: () => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const remove = t('me.passkeys.remove', { label: p.label })
  return (
    <li className={cn('flex items-start gap-3 py-4 transition-[opacity,transform] duration-300 sm:gap-4', leaving && 'scale-[.98] opacity-0')}>
      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-gold/50 bg-gold/10 text-gold-ink">
        <Icon name="passkey" size={20} />
      </span>
      <div className="min-w-0 flex-1">
        <div className="break-words font-medium text-fg"><bdi>{p.label}</bdi></div>
        <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-fg-muted">
          <span title={fmtDate(p.createdAt, locale, true)}>{t('me.passkeys.added', { date: fmtDate(p.createdAt, locale) })}</span>
          {p.lastUsedAt
            ? <span title={fmtDate(p.lastUsedAt, locale, true)}>{t('me.passkeys.lastUsed', { when: timeAgo(p.lastUsedAt, locale) })}</span>
            : <span>{t('me.passkeys.neverUsed')}</span>}
        </div>
      </div>
      <button type="button" onClick={onRemove} disabled={leaving} aria-label={remove} title={remove}
        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-line text-fg-muted transition-colors hover:border-danger hover:text-danger disabled:opacity-50">
        {leaving ? <Spinner /> : <Icon name="trash" size={18} />}
      </button>
    </li>
  )
}

/** The add button, or — on a browser or device that cannot hold a passkey — one line saying why. */
function AddArea({ support, primary, onAdd }: { support: PasskeySupport | null; primary: boolean; onAdd: () => void }) {
  const { t } = useTranslation()
  if (!support) return <Skeleton className="h-10 w-full rounded-full sm:w-80" />
  if (!support.platform) {
    return (
      <p className="flex items-start gap-2 text-xs leading-relaxed text-fg-muted">
        <span className="mt-px shrink-0"><Icon name="info" size={14} /></span>
        {t(support.webauthn ? 'me.passkeys.unsupportedDevice' : 'me.passkeys.unsupportedBrowser')}
      </p>
    )
  }
  return (
    <Button variant={primary ? 'gold' : 'ghost'} size="sm" onClick={onAdd} className="w-full whitespace-normal py-2.5 text-center leading-snug sm:w-auto">
      <span className="shrink-0"><Icon name="passkey" size={18} /></span>{t('me.passkeys.add')}
    </Button>
  )
}

export function PasskeysSection() {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const interpreter = isInterpreter(useAuthStore((s) => s.user))
  const support = usePasskeySupport()
  const q = usePasskeys()
  const list = q.data ?? []
  // the "passkey added" e-mail links to …/profile#passkeys
  const { ref, flash } = useAnchorArrival<HTMLElement>('passkeys', !q.isLoading)
  const formId = useId()

  // ---- add on this device: a name (prefilled from the browser and system), then the device's own prompt
  const [adding, setAdding] = useState(false)
  const [label, setLabel] = useState('')
  const add = useAddPasskey(adding)
  const openAdd = () => { add.clearFailure(); setLabel(passkeyLabel(t)); setAdding(true) }
  const confirmAdd = (e?: FormEvent) => {
    e?.preventDefault()
    if (add.busy) return
    void add.start(label).then((added) => {
      if (!added) return
      setAdding(false)
      toast.success(t('me.passkeys.addedToast'))
    })
  }

  // ---- remove, after a confirm step; the row fades, then the list is fetched again
  const [asking, setAsking] = useState<PasskeyDto | null>(null)
  const [leaving, setLeaving] = useState<string | null>(null)
  const leave = (id: string) => {
    setAsking(null)
    setLeaving(id)
    window.setTimeout(() => {
      qc.setQueryData<PasskeyDto[]>(PASSKEYS_KEY, (old) => old?.filter((p) => p.id !== id))
      setLeaving(null)
      toast.success(t('me.passkeys.removed'))
      void qc.invalidateQueries({ queryKey: PASSKEYS_KEY })
    }, reduced() ? 0 : LEAVE_MS)
  }
  const remove = useMutation({
    mutationFn: (id: string) => meApi.removePasskey(id),
    meta: { toast: false },
    onSuccess: (_r, id) => leave(id),
    // 404: it was already removed (another tab or device)
    onError: (e, id) => (e instanceof ApiError && e.status === 404 ? leave(id) : toast.error(t('common.error'))),
  })

  return (
    <section id="passkeys" ref={ref} aria-labelledby="passkeys-title"
      className={cn('card scroll-mt-24 p-5 transition-shadow duration-700 sm:p-6', flash && 'ring-2 ring-gold/50')}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <h2 id="passkeys-title" className="font-display text-xl">{t('me.passkeys.title')}</h2>
          <p className="mt-1 text-sm leading-relaxed text-fg-muted">{t('me.passkeys.lead')}</p>
        </div>
        {q.data && list.length > 0 && (
          <span className="chip shrink-0 bg-surface-2 font-medium text-fg-muted" aria-label={t('me.passkeys.count', { count: list.length })}>{list.length}</span>
        )}
      </div>

      {q.isLoading ? (
        <div className="mt-5 space-y-3">{[0, 1].map((i) => <Skeleton key={i} className="h-16" />)}</div>
      ) : q.isError ? (
        <div className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-danger/30 bg-danger/5 p-4 text-sm">
          <span className="text-danger">{t('common.error')}</span>
          <Button variant="ghost" size="sm" onClick={() => void q.refetch()}>{t('common.retry')}</Button>
        </div>
      ) : list.length > 0 ? (
        <ul className="mt-3 divide-y divide-line">
          {list.map((p) => <PasskeyRow key={p.id} p={p} leaving={leaving === p.id} onRemove={() => setAsking(p)} />)}
        </ul>
      ) : (
        <p className="mt-5 flex items-center gap-3 rounded-xl border border-dashed border-line px-4 py-4 text-sm text-fg-muted">
          <span className="shrink-0 text-fg-dim"><Icon name="passkey" size={20} /></span>{t('me.passkeys.empty')}
        </p>
      )}

      {!q.isLoading && !q.isError && (
        <div className={cn(list.length > 0 ? 'mt-1 border-t border-line pt-4' : 'mt-4')}>
          <AddArea support={support} primary={list.length === 0} onAdd={openAdd} />
        </div>
      )}

      <Modal open={adding} onClose={() => setAdding(false)} title={t('me.passkeys.addTitle')} size="sm" focusField={false}
        footer={<>
          <Button variant="ghost" onClick={() => setAdding(false)}>{t('common.cancel')}</Button>
          <Button type="submit" form={formId} loading={add.busy}>{!add.busy && <Icon name="passkey" size={18} />}{t('me.passkeys.continue')}</Button>
        </>}>
        <form id={formId} onSubmit={confirmAdd}>
          <div className="flex items-start gap-4">
            <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink"><FingerprintOrFace size={28} /></span>
            <p className="text-sm leading-relaxed text-fg-muted">{t('me.passkeys.addBody')}</p>
          </div>
          <label className="mt-5 block">
            <Label>{t('me.passkeys.labelField')}</Label>
            <Input value={label} maxLength={60} autoComplete="off" onChange={(e) => setLabel(e.target.value)} />
            <span className="mt-1.5 block text-xs text-fg-muted">{t('me.passkeys.labelHint')}</span>
          </label>
          {add.failure && <p role="alert" className="mt-4 rounded-xl border border-danger/30 bg-danger/5 p-3 text-sm text-danger">{t(`me.passkeys.errors.${add.failure}`)}</p>}
        </form>
      </Modal>

      <Modal open={!!asking} onClose={() => setAsking(null)} title={t('me.passkeys.removeTitle')} size="sm"
        footer={<>
          <Button variant="ghost" onClick={() => setAsking(null)}>{t('common.cancel')}</Button>
          <Button className="bg-danger text-white hover:shadow-none dark:text-night" loading={remove.isPending} onClick={() => asking && remove.mutate(asking.id)}>{t('me.passkeys.removeYes')}</Button>
        </>}>
        <p className="text-sm leading-relaxed text-fg">{t(interpreter ? 'me.passkeys.removeBodyInterpreter' : 'me.passkeys.removeBody', { label: asking?.label ?? '' })}</p>
        <p className="mt-3 flex items-start gap-2 text-xs leading-relaxed text-fg-muted">
          <span className="mt-px shrink-0"><Icon name="info" size={14} /></span>{t('me.passkeys.removeDevice')}
        </p>
      </Modal>
    </section>
  )
}
