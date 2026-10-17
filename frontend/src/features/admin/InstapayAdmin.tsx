import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { errorCode, invalidateDreams, useErrorText, useOnce } from '@/lib/apiErrors'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Input, Label, Modal, Skeleton, Switch, Textarea } from '@/components/ui'
import { toast } from '@/components/ui/Toaster'
import { fmtMoney } from '@/lib/utils'
import { InstapayQr } from '@/features/user/InstapayPage'
import type { Currency, InstapaySettings, WithdrawReason } from '@/api/types'

export const INSTAPAY_COUNTS_KEY = ['admin', 'instapay', 'counts'] as const

/** Every list that changes when a transfer is approved or rejected (attached dreams move too). */
export function invalidateAfterReview(qc: QueryClient, orderId?: string) {
  invalidateDreams(qc)
  qc.invalidateQueries({ queryKey: ['admin', 'dream'] })
  qc.invalidateQueries({ queryKey: INSTAPAY_COUNTS_KEY })
  qc.invalidateQueries({ queryKey: ['admin', 'orders'] })
  qc.invalidateQueries({ queryKey: ['admin', 'summary'] })
  qc.invalidateQueries({ queryKey: ['admin', 'dreams'] })
  if (orderId) qc.invalidateQueries({ queryKey: ['admin', 'order', orderId] })
}

/** The server's reason, worded for the interpreter when it has a known code (else a translated generic text). */
export function useInstapayErrorText() {
  return useErrorText('admin.instapay.errors')
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
  const once = useOnce()
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
        footer={<><Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button><Button loading={accept.isPending} onClick={() => once(() => accept.mutateAsync())}>{t('admin.instapay.acceptConfirm')}</Button></>}>
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
        footer={<><Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button><Button variant="ghost" className="border-danger text-danger" loading={reject.isPending} onClick={() => once(() => reject.mutateAsync())}>{t('admin.instapay.rejectConfirm')}</Button></>}>
        <p className="mb-4 text-sm leading-relaxed text-fg-muted">{t('admin.instapay.rejectBody')}</p>
        <label htmlFor="ip-note"><Label>{t('admin.instapay.rejectNote')}</Label></label>
        <Textarea id="ip-note" rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder={t('admin.instapay.rejectNotePlaceholder')} />
      </Modal>
    </>
  )
}

/**
 * "Undo approval" of a SUCCESS InstaPay order approved by mistake: the credits are taken back, the dreams paid by it
 * are kept and wait (AWAITING_PAYMENT), and the buyer is asked to review and resend the transfer details.
 */
export function RevokeApprovalModal({ orderId, orderNumber, heldDreams, open, onClose }: {
  orderId: string; orderNumber: string; heldDreams: number; open: boolean; onClose: () => void
}) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const errorText = useInstapayErrorText()
  const once = useOnce()
  const [note, setNote] = useState('')
  useEffect(() => { if (open) setNote('') }, [open])
  const revoke = useMutation({
    mutationFn: () => adminApi.revokeTransfer(orderId, note.trim() || undefined),
    meta: { toast: false },
    onSuccess: () => { toast.success(t('admin.order.revoked')); onClose(); invalidateAfterReview(qc, orderId) },
    onError: (e) => { toast.error(errorText(e)); onClose(); invalidateAfterReview(qc, orderId) },
  })
  const effects = [t('admin.order.revokeCredits'), t('admin.order.revokeDreams'), t('admin.order.revokeBuyer')]
  return (
    <Modal open={open} onClose={onClose} title={t('admin.order.revokeTitle')} size="sm" focusField={false}
      footer={<><Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button><Button variant="ghost" className="border-danger text-danger" loading={revoke.isPending} onClick={() => once(() => revoke.mutateAsync())}>{t('admin.order.revokeConfirm')}</Button></>}>
      <p className="mb-3 text-xs text-fg-dim">{t('admin.instapay.orderNo')}: <span dir="ltr" className="select-all font-mono font-medium text-fg-muted">{orderNumber}</span></p>
      <p className="text-sm font-medium">{t('admin.order.revokeWhat')}</p>
      <ul className="mt-2 space-y-2 text-sm leading-relaxed text-fg-muted">
        {effects.map((e) => <li key={e} className="flex items-start gap-2"><span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-danger" />{e}</li>)}
      </ul>
      <p className="mt-3 rounded-xl bg-warn/10 p-3 text-sm text-warn">{t('admin.order.revokeDreamsCount', { count: heldDreams })}</p>
      <label htmlFor="revoke-note" className="mt-4 block"><Label>{t('admin.order.revokeNote')}</Label></label>
      <Textarea id="revoke-note" rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder={t('admin.order.revokeNotePlaceholder')} />
      <div dir="ltr" className="mt-1 text-end text-[11px] tabular-nums text-fg-dim">{note.length}/500</div>
    </Modal>
  )
}

/**
 * Withdraw the unused credits of a SUCCESS InstaPay order: a reason (a note is required for "other"), the order stays
 * SUCCESS, the dreams do not change, the buyer is told. The page shows the server's answer at once.
 */
const REASONS: WithdrawReason[] = ['UNCONFIRMED', 'AMOUNT_SHORT', 'OTHER']
export function WithdrawLeftoverModal({ orderId, orderNumber, credits, open, onClose }: { orderId: string; orderNumber: string; credits: number; open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const errorText = useInstapayErrorText()
  const once = useOnce()
  const [reason, setReason] = useState<WithdrawReason>('UNCONFIRMED')
  const [note, setNote] = useState('')
  const [tried, setTried] = useState(false)
  useEffect(() => { if (open) { setNote(''); setReason('UNCONFIRMED'); setTried(false) } }, [open])
  const needsNote = reason === 'OTHER' && !note.trim()
  const withdraw = useMutation({
    mutationFn: () => adminApi.withdrawLeftover(orderId, reason, note.trim() || undefined),
    meta: { toast: false },
    onSuccess: (detail) => { qc.setQueryData(['admin', 'order', orderId], detail); toast.success(t('admin.order.leftoverDone')); onClose(); invalidateAfterReview(qc, orderId) },
    onError: (e) => { toast.error(errorText(e)); if (errorCode(e) !== 'NOTE_REQUIRED') { onClose(); invalidateAfterReview(qc, orderId) } },
  })
  const amount = t('admin.order.creditsObj', { count: credits })
  const submit = () => { setTried(true); if (!needsNote) once(() => withdraw.mutateAsync()) }
  return (
    <Modal open={open} onClose={onClose} title={t('admin.order.leftoverTitle')} size="sm" focusField={false}
      footer={<><Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button><Button variant="ghost" className="border-danger text-danger" loading={withdraw.isPending} onClick={submit}>{t('admin.order.leftoverConfirm')}</Button></>}>
      <p className="mb-3 text-xs text-fg-dim">{t('admin.instapay.orderNo')}: <span dir="ltr" className="select-all font-mono font-medium text-fg-muted">{orderNumber}</span></p>
      <p className="rounded-xl bg-danger/10 p-3 text-sm font-medium text-bad-ink">{t('admin.order.leftoverBody', { credits: amount })}</p>
      <ul className="mt-3 space-y-2 text-sm leading-relaxed text-fg-muted">
        {[t('admin.order.leftoverKeep'), t('admin.order.leftoverBuyer')].map((e) => <li key={e} className="flex items-start gap-2"><span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-gold" />{e}</li>)}
      </ul>
      <fieldset className="mt-4">
        <legend className="label">{t('admin.order.leftoverReasonLabel')}</legend>
        <div className="space-y-2" role="radiogroup">
          {REASONS.map((r) => (
            <label key={r} className={`flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border px-3 py-2 text-sm transition-colors ${reason === r ? 'border-gold bg-gold/10' : 'border-line'}`}>
              <input type="radio" name="leftover-reason" value={r} checked={reason === r} onChange={() => setReason(r)} className="h-4 w-4 accent-gold" />
              {t(`admin.order.leftoverReasons.${r}`)}
            </label>
          ))}
        </div>
      </fieldset>
      <label htmlFor="leftover-note" className="mt-4 block"><Label>{t(reason === 'OTHER' ? 'admin.order.leftoverNoteForOther' : 'admin.order.leftoverNote')}</Label></label>
      <Textarea id="leftover-note" rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder={t('admin.order.leftoverNotePlaceholder')}
        aria-invalid={tried && needsNote} aria-describedby={tried && needsNote ? 'leftover-note-err' : undefined} className={tried && needsNote ? 'border-danger' : undefined} />
      <div className="mt-1 flex items-start justify-between gap-3 text-[11px]">
        <span id="leftover-note-err" role={tried && needsNote ? 'alert' : undefined} className="text-bad-ink">{tried && needsNote ? t('admin.order.leftoverNoteRequired') : ''}</span>
        <span dir="ltr" className="tabular-nums text-fg-dim">{note.length}/500</span>
      </div>
    </Modal>
  )
}

/** Give back the credits of the last withdrawal (a mistaken one, or the money arrived after all). */
export function RestoreLeftoverModal({ orderId, credits, open, onClose }: { orderId: string; credits: number; open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const errorText = useInstapayErrorText()
  const once = useOnce()
  const restore = useMutation({
    mutationFn: () => adminApi.restoreLeftover(orderId),
    meta: { toast: false },
    onSuccess: (detail) => { qc.setQueryData(['admin', 'order', orderId], detail); toast.success(t('admin.order.restored')); onClose(); invalidateAfterReview(qc, orderId) },
    onError: (e) => { toast.error(errorText(e)); onClose(); invalidateAfterReview(qc, orderId) },
  })
  return (
    <Modal open={open} onClose={onClose} title={t('admin.order.restoreTitle')} size="sm" focusField={false}
      footer={<><Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button><Button loading={restore.isPending} onClick={() => once(() => restore.mutateAsync())}>{t('admin.order.restoreConfirm')}</Button></>}>
      <p className="text-sm leading-relaxed">{t('admin.order.restoreBody', { credits: t('admin.order.creditsObj', { count: credits }) })}</p>
    </Modal>
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
  const { t } = useTranslation()
  const qc = useQueryClient()
  const settingsError = useInstapayErrorText()
  const q = useQuery({ queryKey: ['admin', 'instapay', 'settings'], queryFn: adminApi.instapaySettings })
  const [form, setForm] = useState<InstapaySettings | null>(null)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => { if (q.data) setForm(q.data) }, [q.data])
  const save = useMutation({
    mutationFn: (body: InstapaySettings) => adminApi.saveInstapaySettings(body),
    onSuccess: (s) => { setForm(s); qc.setQueryData(['admin', 'instapay', 'settings'], s); toast.success(t('admin.instapay.saved')) },
    meta: { toast: false },
    onError: (e) => setErr(settingsError(e)),
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
