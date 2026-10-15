import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Input, Label, Modal, Skeleton, Switch, Textarea } from '@/components/ui'
import { toast } from '@/components/ui/Toaster'
import { fmtMoney } from '@/lib/utils'
import { InstapayQr } from '@/features/user/InstapayPage'
import type { Currency, InstapaySettings } from '@/api/types'

export const INSTAPAY_COUNTS_KEY = ['admin', 'instapay', 'counts'] as const

/** Every list that changes when a transfer is approved or rejected. */
export function invalidateAfterReview(qc: QueryClient, orderId?: string) {
  qc.invalidateQueries({ queryKey: INSTAPAY_COUNTS_KEY })
  qc.invalidateQueries({ queryKey: ['admin', 'orders'] })
  qc.invalidateQueries({ queryKey: ['admin', 'summary'] })
  qc.invalidateQueries({ queryKey: ['admin', 'dreams'] })
  if (orderId) qc.invalidateQueries({ queryKey: ['admin', 'order', orderId] })
}

/** The server's reason, worded for the interpreter when it has a known code. */
export function useInstapayErrorText() {
  const { t, i18n } = useTranslation()
  return (e: unknown) => {
    const code = (e as ApiError).problem?.code
    if (code && i18n.exists(`admin.instapay.errors.${code}`)) return t(`admin.instapay.errors.${code}`)
    return (e as ApiError).problem?.detail ?? (e as Error).message
  }
}

/**
 * Approve (the reference she checked goes with the approval: if the buyer changed it meanwhile, it is refused)
 * and reject (with a note the buyer sees) for one InstaPay transfer.
 */
export function TransferReviewModals({ orderId, orderNumber, amount, currency, reference, mode, onClose, onDone }: {
  orderId: string; orderNumber: string; amount: number; currency: Currency; reference: string; mode: 'approve' | 'reject' | null; onClose: () => void; onDone?: () => void
}) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const qc = useQueryClient()
  const errorText = useInstapayErrorText()
  const [note, setNote] = useState('')
  useEffect(() => { if (mode === 'reject') setNote('') }, [mode])
  const accept = useMutation({
    mutationFn: () => adminApi.approveTransfer(orderId, reference),
    meta: { toast: false },
    onSuccess: () => { toast.success(t('admin.instapay.accepted')); onClose(); invalidateAfterReview(qc, orderId); onDone?.() },
    onError: (e) => { toast.error(errorText(e)); invalidateAfterReview(qc, orderId) },
  })
  const reject = useMutation({
    mutationFn: () => adminApi.rejectTransfer(orderId, note.trim()),
    meta: { toast: false },
    onSuccess: () => { toast.success(t('admin.instapay.rejected')); onClose(); invalidateAfterReview(qc, orderId); onDone?.() },
    onError: (e) => { toast.error(errorText(e)); invalidateAfterReview(qc, orderId) },
  })
  return (
    <>
      <Modal open={mode === 'approve'} onClose={onClose} title={t('admin.instapay.acceptTitle')} size="sm" focusField={false}
        footer={<><Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button><Button loading={accept.isPending} onClick={() => accept.mutate()}>{t('admin.instapay.acceptConfirm')}</Button></>}>
        <p className="mb-2 text-xs text-fg-dim">{t('admin.instapay.orderNo')}: <span dir="ltr" className="select-all font-mono font-medium text-fg-muted">{orderNumber}</span></p>
        <p className="text-sm leading-relaxed">{t('admin.instapay.acceptBody', { amount: `\u2068${fmtMoney(amount, currency, locale)}\u2069`, reference: `\u2068${reference}\u2069` })}</p>
        <div className="mt-4 rounded-xl border border-gold/40 bg-gold/5 p-4">
          <div className="text-xs text-fg-dim">{t('admin.order.approveRef')}</div>
          <div className="mt-1 flex items-center justify-between gap-2">
            <span dir="ltr" className="select-all font-mono text-xl tracking-wider">{reference}</span>
            <CopyButton text={reference} label={t('admin.instapay.copyRef')} />
          </div>
        </div>
      </Modal>
      <Modal open={mode === 'reject'} onClose={onClose} title={t('admin.instapay.rejectTitle')} size="sm"
        footer={<><Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button><Button variant="ghost" className="border-danger text-danger" loading={reject.isPending} onClick={() => reject.mutate()}>{t('admin.instapay.rejectConfirm')}</Button></>}>
        <p className="mb-4 text-sm leading-relaxed text-fg-muted">{t('admin.instapay.rejectBody')}</p>
        <label htmlFor="ip-note"><Label>{t('admin.instapay.rejectNote')}</Label></label>
        <Textarea id="ip-note" rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder={t('admin.instapay.rejectNotePlaceholder')} />
      </Modal>
    </>
  )
}

/** Small copy button (copied text confirmed with a toast; the value stays selectable when the clipboard is blocked). */
export function CopyButton({ text, label, done }: { text: string; label: string; done?: string }) {
  const { t } = useTranslation()
  return (
    <button type="button" onClick={async () => { try { await navigator.clipboard.writeText(text); toast.success(done ?? t('common.copied')) } catch { /* stays selectable */ } }}
      aria-label={label} title={label} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-fg-dim transition-colors hover:bg-surface-2 hover:text-fg">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V6a2 2 0 0 1 2-2h9" /></svg>
    </button>
  )
}

/** The receiving account (address, link, receiver name, hours to pay). Buyers see a change at once. */
export function InstapaySettingsCard() {
  const { t, i18n } = useTranslation()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['admin', 'instapay', 'settings'], queryFn: adminApi.instapaySettings })
  const [form, setForm] = useState<InstapaySettings | null>(null)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => { if (q.data) setForm(q.data) }, [q.data])
  const save = useMutation({
    mutationFn: (body: InstapaySettings) => adminApi.saveInstapaySettings(body),
    onSuccess: (s) => { setForm(s); qc.setQueryData(['admin', 'instapay', 'settings'], s); toast.success(t('admin.instapay.saved')) },
    onError: (e) => {
      const code = (e as ApiError).problem?.code
      setErr(code && i18n.exists(`admin.instapay.errors.${code}`) ? t(`admin.instapay.errors.${code}`) : (e as Error).message)
    },
  })
  if (q.isLoading || !form) return <Skeleton className="h-48" />
  if (q.isError) return <ErrorBox onRetry={() => q.refetch()} />
  const set = <K extends keyof InstapaySettings>(k: K, v: InstapaySettings[K]) => setForm({ ...form, [k]: v })
  const linkLooksRight = /^https:\/\/ipn\.eg\/S\/[A-Za-z0-9._-]+\/instapay\/[A-Za-z0-9]+$/.test(form.link.trim())
  return (
    <section aria-labelledby="ip-settings" className="card space-y-4 p-5">
      <div>
        <h2 id="ip-settings" className="font-display text-2xl">{t('admin.instapay.settingsTitle')}</h2>
        <p className="text-sm text-fg-muted">{t('admin.instapay.settingsLead')}</p>
      </div>
      <Switch checked={form.enabled} onChange={(v) => set('enabled', v)} label={t('admin.instapay.enabled')} />
      <div className="flex flex-col gap-5 md:flex-row">
        <div className="grid flex-1 gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor="ip-address"><Label>{t('admin.instapay.address')}</Label></label>
            <Input id="ip-address" dir="ltr" value={form.address} onChange={(e) => set('address', e.target.value)} placeholder="name@instapay" />
            <p className="mt-1 text-xs text-fg-dim">{t('admin.instapay.addressHint')}</p>
          </div>
          <div>
            <label htmlFor="ip-recipient"><Label>{t('admin.instapay.recipient')}</Label></label>
            <Input id="ip-recipient" dir="ltr" value={form.recipientName} onChange={(e) => set('recipientName', e.target.value)} />
            <p className="mt-1 text-xs text-fg-dim">{t('admin.instapay.recipientHint')}</p>
          </div>
          <div className="sm:col-span-2">
            <label htmlFor="ip-link"><Label>{t('admin.instapay.link')}</Label></label>
            <Input id="ip-link" dir="ltr" value={form.link} onChange={(e) => set('link', e.target.value)} placeholder="https://ipn.eg/S/name/instapay/XXXXXX" />
            <p className="mt-1 text-xs text-fg-dim">{t('admin.instapay.linkHint')}</p>
          </div>
          <div>
            <label htmlFor="ip-hours"><Label>{t('admin.instapay.expireHours')}</Label></label>
            <Input id="ip-hours" type="number" min={1} max={168} value={form.expireHours} onChange={(e) => set('expireHours', Number(e.target.value) || 1)} />
          </div>
        </div>
        <div className="flex flex-col items-center gap-2">
          <span className="text-xs text-fg-dim">{t('admin.instapay.qrPreview')}</span>
          {linkLooksRight ? <InstapayQr link={form.link.trim()} alt={t('me.checkout.instapay.qrAlt')} size={128} /> : <div className="h-32 w-32 rounded-xl bg-surface-2" />}
        </div>
      </div>
      {err && <p role="alert" className="text-sm text-danger">{err}</p>}
      <div className="flex justify-end">
        <Button loading={save.isPending} onClick={() => { setErr(null); save.mutate({ ...form, address: form.address.trim(), link: form.link.trim(), recipientName: form.recipientName.trim() }) }}>{t('admin.instapay.save')}</Button>
      </div>
    </section>
  )
}
