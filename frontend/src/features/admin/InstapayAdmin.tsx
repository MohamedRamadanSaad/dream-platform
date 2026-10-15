import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Input, Label, Modal, Skeleton, StatusBadge, Switch, Textarea } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { toast } from '@/components/ui/Toaster'
import { fmtDate, fmtMoney, timeAgo } from '@/lib/utils'
import { InstapayQr } from '@/features/user/InstapayPage'
import type { InstapaySettings, InstapayTransferRow } from '@/api/types'

export const INSTAPAY_COUNTS_KEY = ['admin', 'instapay', 'counts'] as const
const TRANSFERS_KEY = ['admin', 'instapay', 'transfers'] as const

async function copy(text: string, done: string) {
  try { await navigator.clipboard.writeText(text); toast.success(done) } catch { /* the number stays selectable */ }
}

/** InstaPay transfers waiting for the interpreter: accept when the money is in her InstaPay history, else reject. */
export function InstapayTransfersPanel() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const qc = useQueryClient()
  const q = useQuery({ queryKey: TRANSFERS_KEY, queryFn: adminApi.instapayTransfers, refetchInterval: 60_000 })
  const [accepting, setAccepting] = useState<InstapayTransferRow | null>(null)
  const [rejecting, setRejecting] = useState<InstapayTransferRow | null>(null)
  const [note, setNote] = useState('')

  const refresh = () => {
    qc.invalidateQueries({ queryKey: TRANSFERS_KEY })
    qc.invalidateQueries({ queryKey: INSTAPAY_COUNTS_KEY })
    qc.invalidateQueries({ queryKey: ['admin', 'orders'] })
  }
  const errorText = (e: unknown) => {
    const code = (e as ApiError).problem?.code
    if (code && t(`admin.instapay.errors.${code}`, { defaultValue: '' })) return t(`admin.instapay.errors.${code}`)
    return (e as ApiError).problem?.detail ?? (e as Error).message
  }
  const accept = useMutation({
    // the reference the interpreter checked goes with the approval: if the buyer changed it meanwhile, it is refused
    mutationFn: (r: InstapayTransferRow) => adminApi.approveTransfer(r.orderId, r.reference ?? ''),
    onSuccess: () => { toast.success(t('admin.instapay.accepted')); setAccepting(null); refresh() },
    onError: (e) => { toast.error(errorText(e)); refresh() },
  })
  const reject = useMutation({
    mutationFn: ({ id, note }: { id: string; note: string }) => adminApi.rejectTransfer(id, note),
    onSuccess: () => { toast.success(t('admin.instapay.rejected')); setRejecting(null); setNote(''); refresh() },
    onError: (e) => { toast.error(errorText(e)); refresh() },
  })

  const pending = q.data?.pending ?? []
  return (
    <section aria-labelledby="ip-pending" className="space-y-3">
      <div>
        <h2 id="ip-pending" className="flex items-center gap-2 font-display text-2xl">
          {t('admin.instapay.title')}
          {pending.length > 0 && <span className="chip bg-gold/15 font-medium text-gold-ink">{pending.length}</span>}
        </h2>
        <p className="text-sm text-fg-muted">{t('admin.instapay.lead')}</p>
      </div>
      {q.isLoading ? <Skeleton className="h-32" /> : q.isError ? <ErrorBox onRetry={() => q.refetch()} /> : pending.length === 0 ? (
        <div className="card p-6 text-center text-sm text-fg-muted">{t('admin.instapay.empty')}</div>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {pending.map((r) => (
            <article key={r.orderId} className="card space-y-4 p-5">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="truncate font-medium">{r.userName}</div>
                  <div dir="ltr" className="truncate text-start text-xs text-fg-dim">{r.userEmail}</div>
                  <div className="mt-1 text-xs text-fg-dim">{t('admin.instapay.orderNo')}: <span dir="ltr" className="select-all font-mono font-medium text-fg-muted">{r.orderNumber}</span></div>
                </div>
                <div className="text-end">
                  <div dir="ltr" className="font-mono text-xl font-semibold">{fmtMoney(r.amount, r.currency, locale)}</div>
                  <div className="text-xs text-fg-dim">{r.submittedAt ? t('admin.instapay.submitted', { time: timeAgo(r.submittedAt, locale) }) : ''}</div>
                </div>
              </div>
              <dl className="grid gap-3 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-xs text-fg-dim">{t('admin.instapay.reference')}</dt>
                  <dd className="flex items-center gap-1">
                    <span dir="ltr" className="select-all font-mono tracking-wider">{r.reference}</span>
                    <button type="button" onClick={() => void copy(r.reference ?? '', t('me.payments.copied'))} aria-label={t('admin.instapay.copyRef')} title={t('admin.instapay.copyRef')} className="flex h-9 w-9 items-center justify-center rounded-lg text-fg-dim hover:bg-surface-2 hover:text-fg">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V6a2 2 0 0 1 2-2h9" /></svg>
                    </button>
                  </dd>
                </div>
                <div><dt className="text-xs text-fg-dim">{t('admin.instapay.sender')}</dt><dd dir="ltr" className="text-start">{r.senderName}</dd></div>
                <div><dt className="text-xs text-fg-dim">{t('admin.instapay.package')}</dt><dd>{r.packageName}</dd></div>
                {r.attachedDreams > 0 && <div className="flex items-end"><span className="chip bg-gold/10 text-gold-ink"><Icon name="moon" size={13} />{t('admin.instapay.dreams', { n: r.attachedDreams })}</span></div>}
              </dl>
              <div className="flex gap-2">
                <Button className="min-h-11 flex-1" onClick={() => setAccepting(r)}>{t('admin.instapay.accept')}</Button>
                <Button variant="ghost" className="min-h-11" onClick={() => { setNote(''); setRejecting(r) }}>{t('admin.instapay.reject')}</Button>
              </div>
            </article>
          ))}
        </div>
      )}

      {q.data && (
        <details className="card p-0">
          <summary className="cursor-pointer px-5 py-3 text-sm text-fg-muted">{t('admin.instapay.reviewed')} ({q.data.reviewed.length})</summary>
          {q.data.reviewed.length === 0 ? <div className="px-5 pb-4 text-sm text-fg-dim">{t('admin.instapay.reviewedEmpty')}</div> : (
            <ul className="divide-y divide-line border-t border-line">
              {q.data.reviewed.map((r) => (
                <li key={r.orderId} className="flex flex-wrap items-center justify-between gap-3 px-5 py-3 text-sm">
                  <div className="min-w-0">
                    <div className="truncate">{r.userName} · <span dir="ltr">{fmtMoney(r.amount, r.currency, locale)}</span></div>
                    <div className="text-xs text-fg-dim"><span dir="ltr" className="select-all font-mono text-fg-muted">{r.orderNumber}</span> · <span dir="ltr" className="font-mono">{r.reference}</span>{r.reviewedAt ? ` · ${fmtDate(r.reviewedAt, locale, true)}` : ''}{r.reviewNote ? ` · ${r.reviewNote}` : ''}</div>
                  </div>
                  <StatusBadge status={r.status} kind="order" />
                </li>
              ))}
            </ul>
          )}
        </details>
      )}

      <Modal open={!!accepting} onClose={() => setAccepting(null)} title={t('admin.instapay.acceptTitle')} size="sm" focusField={false}
        footer={<><Button variant="ghost" onClick={() => setAccepting(null)}>{t('common.cancel')}</Button><Button loading={accept.isPending} onClick={() => accepting && accept.mutate(accepting)}>{t('admin.instapay.acceptConfirm')}</Button></>}>
        {accepting && <p className="text-sm leading-relaxed">{t('admin.instapay.acceptBody', { amount: `\u2068${fmtMoney(accepting.amount, accepting.currency, locale)}\u2069`, reference: `\u2068${accepting.reference ?? ''}\u2069` })}</p>}
      </Modal>
      <Modal open={!!rejecting} onClose={() => setRejecting(null)} title={t('admin.instapay.rejectTitle')} size="sm"
        footer={<><Button variant="ghost" onClick={() => setRejecting(null)}>{t('common.cancel')}</Button><Button variant="ghost" className="border-danger text-danger" loading={reject.isPending} onClick={() => rejecting && reject.mutate({ id: rejecting.orderId, note })}>{t('admin.instapay.rejectConfirm')}</Button></>}>
        <p className="mb-4 text-sm leading-relaxed text-fg-muted">{t('admin.instapay.rejectBody')}</p>
        <label htmlFor="ip-note"><Label>{t('admin.instapay.rejectNote')}</Label></label>
        <Textarea id="ip-note" rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder={t('admin.instapay.rejectNotePlaceholder')} />
      </Modal>
    </section>
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
