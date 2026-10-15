import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { dreamsApi, instapayApi, meApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Modal } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { toast } from '@/components/ui/Toaster'
import { arrowNext, cn } from '@/lib/utils'
import { errorCode, invalidateDreams, useErrorText, useOnce } from '@/lib/apiErrors'
import type { DreamPaymentLink } from '@/api/types'

/**
 * What a dream attached to a payment can do. Under review: open the payment, or remove the dream from it (back to
 * Drafts; the payment stays as it is). Rejected: also send it with the balance, attach it to a new payment, or delete it.
 */
export function DreamPaymentActions({ dreamId, payment, compact, onGone }: { dreamId: string; payment: DreamPaymentLink; compact?: boolean; onGone?: () => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const qc = useQueryClient()
  const navigate = useNavigate()
  const once = useOnce()
  const errorText = useErrorText('me.dreamErrors')
  const rejected = payment.status === 'REJECTED'
  const dash = useQuery({ queryKey: ['me', 'dashboard'], queryFn: meApi.dashboard, enabled: rejected })
  const credits = dash.data?.credits ?? 0
  const [confirm, setConfirm] = useState<'release' | 'delete' | null>(null)

  const refresh = () => { invalidateDreams(qc, [dreamId]); qc.invalidateQueries({ queryKey: ['instapay', payment.orderId] }) }
  const fail = (e: unknown) => { toast.error(errorText(e)); refresh() }
  const release = useMutation({
    mutationFn: () => instapayApi.releaseDreams(payment.orderId, [dreamId]),
    meta: { toast: false },
    onSuccess: () => { setConfirm(null); toast.success(t('me.dreamPayment.released')); refresh() },
    onError: fail,
  })
  const submit = useMutation({
    mutationFn: () => dreamsApi.submit({ dreamIds: [dreamId] }),
    meta: { toast: false },
    onSuccess: () => { toast.success(t('me.dreamPayment.sent')); refresh(); qc.invalidateQueries({ queryKey: ['notifications'] }) },
    onError: (e) => { if (errorCode(e) === 'INSUFFICIENT_CREDITS') navigate(`/me/packages?dreams=${dreamId}`); else fail(e) },
  })
  const remove = useMutation({
    mutationFn: () => dreamsApi.deleteDraft(dreamId),
    meta: { toast: false },
    onSuccess: () => { setConfirm(null); toast.success(t('me.dreamPayment.deleted')); qc.removeQueries({ queryKey: ['dream', dreamId] }); invalidateDreams(qc); onGone?.() },
    onError: fail,
  })

  const link = 'inline-flex min-h-9 items-center gap-1.5 font-medium text-gold-ink hover:underline dark:text-gold'
  const quiet = 'inline-flex min-h-9 items-center gap-1.5 text-fg-muted hover:text-fg'
  return (
    <div role="group" aria-label={t('me.dreamPayment.actions')} className={cn('flex flex-wrap items-center gap-x-4 gap-y-1', compact ? 'text-xs' : 'text-sm')}>
      <Link to={`/me/checkout/instapay/${payment.orderId}`} className={link}>{t('me.dreamPayment.open')} <span aria-hidden="true">{arrowNext(locale)}</span></Link>
      {rejected && credits > 0 && (
        <button type="button" className={link} disabled={submit.isPending} onClick={() => once(() => submit.mutateAsync())}>
          <Icon name="moon" size={14} />{t('me.dreamPayment.sendWithBalance')}
        </button>
      )}
      {rejected && <Link to={`/me/packages?dreams=${dreamId}`} className={link}>{t('me.dreamPayment.payAgain')}</Link>}
      <button type="button" className={quiet} aria-haspopup="dialog" onClick={() => setConfirm('release')}>{t('me.dreamPayment.release')}</button>
      {rejected && <button type="button" className={cn(quiet, 'hover:text-danger')} aria-haspopup="dialog" onClick={() => setConfirm('delete')}><Icon name="trash" size={14} />{t('common.delete')}</button>}

      <Modal open={confirm === 'release'} onClose={() => setConfirm(null)} title={t('me.dreamPayment.releaseTitle')} size="sm" focusField={false}
        footer={<><Button variant="ghost" onClick={() => setConfirm(null)}>{t('common.cancel')}</Button><Button loading={release.isPending} onClick={() => once(() => release.mutateAsync())}>{t('me.dreamPayment.releaseConfirm')}</Button></>}>
        <p className="text-sm leading-relaxed">{t('me.dreamPayment.releaseBody')}</p>
        <p className="mt-3 flex items-start gap-2 rounded-xl bg-surface-2 p-3 text-sm leading-relaxed text-fg-muted"><Icon name="info" size={16} className="mt-0.5 shrink-0 text-gold-ink" />{t('me.dreamPayment.releaseKeep')}</p>
      </Modal>
      <Modal open={confirm === 'delete'} onClose={() => setConfirm(null)} title={t('me.dreamPayment.deleteTitle')} size="sm" focusField={false}
        footer={<><Button variant="ghost" onClick={() => setConfirm(null)}>{t('common.cancel')}</Button><Button variant="ghost" className="border-danger text-danger" loading={remove.isPending} onClick={() => once(() => remove.mutateAsync())}>{t('common.delete')}</Button></>}>
        <p className="text-sm leading-relaxed text-fg-muted">{t('me.dreamPayment.deleteBody')}</p>
      </Modal>
    </div>
  )
}
