import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { dreamsApi, instapayApi } from '@/api/endpoints'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Input, Label, Skeleton, Textarea } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'
import { toast } from '@/components/ui/Toaster'
import { arrowBack, arrowNext, cn, fmtDate, fmtMoney } from '@/lib/utils'
import type { Gender, InstapayOrderView } from '@/api/types'

/** Same minimum as the dream editor (the server checks dreams.min_chars). */
const DREAM_MIN = 20
const REFERENCE_RE = /^\d{6,24}$/

/** Arabic-Indic and Persian digits → 0-9, spaces and dashes removed (the server does the same). */
export function normalizeReference(raw: string) {
  return raw
    .replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x660))
    .replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x6f0))
    .replace(/[\s\-‎‏]/g, '')
}

/** The form survives a trip to the InstaPay app (phones often reload the tab on return). */
type Saved = { reference: string; sender: string; dream: string; gender: Gender; draftId?: string }
const storageKey = (orderId: string) => `instapay:${orderId}`
function readSaved(orderId: string): Partial<Saved> {
  try { return JSON.parse(localStorage.getItem(storageKey(orderId)) ?? '{}') as Partial<Saved> } catch { return {} }
}
function writeSaved(orderId: string, value: Saved) {
  try { localStorage.setItem(storageKey(orderId), JSON.stringify(value)) } catch { /* private mode: the form still works */ }
}
function clearSaved(orderId: string) {
  try { localStorage.removeItem(storageKey(orderId)) } catch { /* ignore */ }
}

async function copyText(text: string, done: string) {
  try { await navigator.clipboard.writeText(text); toast.success(done) } catch { /* clipboard blocked: the text stays selectable */ }
}

function CopyIcon() {
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V6a2 2 0 0 1 2-2h9" /></svg>
}

/** QR code of the payment link, drawn in the browser (the library loads only on this page). */
export function InstapayQr({ link, alt, size = 120 }: { link: string; alt: string; size?: number }) {
  const [src, setSrc] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    import('qrcode')
      .then((QR) => QR.toString(link, { type: 'svg', margin: 1, errorCorrectionLevel: 'M', color: { dark: '#0a1128', light: '#ffffff' } }))
      .then((svg) => { if (alive) setSrc(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`) })
      .catch(() => { if (alive) setSrc(null) })
    return () => { alive = false }
  }, [link])
  if (!src) return <div className="shrink-0 animate-pulse rounded-xl bg-surface-2" style={{ width: size, height: size }} />
  return <img src={src} alt={alt} width={size} height={size} className="shrink-0 rounded-xl bg-white p-1" />
}

function StepTitle({ n, children, aside }: { n: number; children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="flex items-center gap-3">
      <span aria-hidden="true" className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-gold text-sm font-semibold text-night">{n}</span>
      <h2 className="flex-1 text-lg font-medium">{children}</h2>
      {aside}
    </div>
  )
}

export function InstapayPage() {
  const { t } = useTranslation()
  const { orderId = '' } = useParams()
  const locale = useAuthStore((s) => s.locale)
  const q = useQuery({
    queryKey: ['instapay', orderId],
    queryFn: () => instapayApi.view(orderId),
    // while the interpreter checks the transfer, the page follows by itself
    refetchInterval: (query) => (query.state.data?.status === 'PENDING_REVIEW' ? 30_000 : false),
  })
  const v = q.data
  const [editing, setEditing] = useState(false)

  if (q.isLoading) return <div className="mx-auto max-w-lg space-y-4"><Skeleton className="h-10 w-1/2" /><Skeleton className="h-40" /><Skeleton className="h-64" /></div>
  if (q.isError || !v) return <PageEnter className="mx-auto max-w-lg"><ErrorBox onRetry={() => q.refetch()} /></PageEnter>

  const rejected = v.status === 'FAILED' && !!v.reference
  const showForm = v.status === 'INITIATED' || rejected || (v.status === 'PENDING_REVIEW' && editing)

  return (
    <PageEnter className="mx-auto max-w-lg space-y-5">
      <div>
        <Link to="/me/packages" className="text-sm text-fg-muted">{arrowBack(locale)} {t('common.back')}</Link>
        <h1 className="mt-2 font-display text-4xl">{t('me.checkout.instapay.title')}</h1>
        <p className="text-sm font-light text-fg-muted">{t('me.checkout.instapay.sub')}</p>
      </div>

      {v.status === 'SUCCESS' && <SuccessCard v={v} />}
      {v.status === 'PENDING_REVIEW' && !editing && <PendingCard v={v} onEdit={() => setEditing(true)} />}
      {(v.status === 'EXPIRED' || (v.status === 'FAILED' && !v.reference)) && <ExpiredCard />}
      {rejected && (
        <div role="alert" className="card border-danger/40 bg-danger/5 p-5">
          <div className="flex items-center gap-2 text-danger"><Icon name="alert" size={20} /><h2 className="text-lg font-medium">{t('me.checkout.instapay.rejectedTitle')}</h2></div>
          <p className="mt-2 text-sm leading-relaxed text-fg-muted">{t('me.checkout.instapay.rejectedLead')}</p>
          {v.reviewNote && <p className="mt-2 text-sm">{t('me.checkout.instapay.rejectedNote', { note: v.reviewNote })}</p>}
        </div>
      )}

      {showForm && (
        <>
          <PackageRow v={v} locale={locale} />
          <AmountCard v={v} locale={locale} />
          <TransferStep v={v} />
          <DetailsForm v={v} editing={v.status !== 'INITIATED'} onDone={() => setEditing(false)} onCancel={v.status === 'PENDING_REVIEW' ? () => setEditing(false) : undefined} />
          <div className="flex items-start gap-3 rounded-2xl border border-line p-4 text-sm leading-relaxed text-fg-muted">
            <Icon name="shield" size={18} className="mt-0.5 shrink-0 text-gold-ink" />
            <span>{t('me.checkout.instapay.safety')}</span>
          </div>
        </>
      )}

      <div className="flex justify-between gap-3 text-xs text-fg-dim">
        <span>{t('me.checkout.instapay.orderNo')}</span>
        <span dir="ltr" className="select-all font-mono">{v.orderId}</span>
      </div>
    </PageEnter>
  )
}

function PackageRow({ v, locale }: { v: InstapayOrderView; locale: string }) {
  const { t } = useTranslation()
  return (
    <div className="card flex items-center gap-4 p-4">
      <div aria-hidden="true" className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-night text-gold"><Icon name="moon" size={22} /></div>
      <div className="min-w-0 flex-1">
        <div className="truncate font-medium">{v.packageName}</div>
        <div className="text-xs text-fg-dim">{t('packages.dreams', { count: v.credits })}</div>
      </div>
      {v.status === 'INITIATED' && <Link to="/me/packages" className="flex min-h-11 items-center text-sm text-gold-ink">{t('me.checkout.instapay.change')}</Link>}
      {v.status !== 'INITIATED' && <span className="text-sm text-fg-muted">{fmtMoney(v.amount, v.currency, locale)}</span>}
    </div>
  )
}

function AmountCard({ v, locale }: { v: InstapayOrderView; locale: string }) {
  const { t } = useTranslation()
  // plain digits, as InstaPay wants them typed
  const plain = Number.isInteger(v.amount) ? String(v.amount) : v.amount.toFixed(2)
  return (
    <section aria-label={t('me.checkout.instapay.amountLabel')} className="card space-y-4 border-gold/60 p-5">
      <div className="text-sm text-fg-muted">{t('me.checkout.instapay.amountLabel')}</div>
      <div className="flex items-center justify-between gap-3">
        <div dir="ltr" className="flex items-baseline gap-2">
          <span className="font-mono text-4xl font-semibold tracking-tight">{plain}</span>
          <span className="text-base font-medium text-gold-ink">{v.currency}</span>
        </div>
        <Button type="button" variant="ghost" size="sm" className="min-h-11 border border-gold/60 text-gold-ink" onClick={() => void copyText(plain, t('me.checkout.instapay.copied'))} aria-label={`${t('me.checkout.instapay.copy')} ${fmtMoney(v.amount, v.currency, locale)}`}>
          <CopyIcon /> {t('me.checkout.instapay.copy')}
        </Button>
      </div>
      {/* the interpreter compares the money received with the package price: a different amount is rejected */}
      <div role="note" className="space-y-1.5 rounded-xl border border-gold/60 bg-gold/10 p-3 text-sm leading-relaxed">
        <div className="flex items-center gap-2 font-medium text-gold-ink">
          <Icon name="alert" size={18} className="shrink-0" />
          <span>{t('me.checkout.instapay.reviewTitle')}</span>
        </div>
        <p>{t('me.checkout.instapay.reviewBody')}</p>
        <p className="text-fg-muted">{t('me.checkout.instapay.reviewAfter')}</p>
      </div>
      <div className="flex items-start gap-2 rounded-xl bg-surface-2 p-3 text-xs leading-relaxed text-fg-muted">
        <Icon name="info" size={16} className="mt-0.5 shrink-0 text-gold-ink" />
        <span>{t('me.checkout.instapay.amountNote')}</span>
      </div>
      {v.status === 'INITIATED' && <div className="flex items-center gap-2 text-xs text-fg-dim"><Icon name="clock" size={14} />{t('me.checkout.instapay.deadline', { time: fmtDate(v.expiresAt, locale, true) })}</div>}
    </section>
  )
}

function TransferStep({ v }: { v: InstapayOrderView }) {
  const { t } = useTranslation()
  return (
    <section className="card space-y-4 p-5">
      <StepTitle n={1}>{t('me.checkout.instapay.step1')}</StepTitle>
      <a href={v.link} target="_blank" rel="noopener noreferrer" className="btn btn-gold flex min-h-14 w-full items-center justify-center gap-2 text-base">
        {t('me.checkout.instapay.open')} <Icon name="link" size={18} />
      </a>
      <p className="text-center text-xs text-fg-dim">{t('me.checkout.instapay.openHint')}</p>
      <div className="space-y-1.5 rounded-2xl border border-dashed border-line p-3">
        <div className="text-xs text-fg-dim">{t('me.checkout.instapay.manual')}</div>
        <div className="flex items-center justify-between gap-2">
          <span dir="ltr" className="select-all break-all font-mono text-sm">{v.address}</span>
          <button type="button" onClick={() => void copyText(v.address, t('me.checkout.instapay.copied'))} aria-label={t('me.checkout.instapay.copyAddress')} title={t('me.checkout.instapay.copyAddress')} className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border border-line text-gold-ink hover:bg-surface-2">
            <CopyIcon />
          </button>
        </div>
        {v.recipientName && <div className="text-xs text-fg-muted">{t('me.checkout.instapay.recipient', { name: v.recipientName })}</div>}
      </div>
      {/* scanning your own screen is impossible on a phone: the code is for computers */}
      <div className="hidden items-center gap-4 rounded-2xl bg-surface-2 p-3 md:flex">
        <InstapayQr link={v.link} alt={t('me.checkout.instapay.qrAlt')} size={112} />
        <div>
          <div className="text-sm font-medium">{t('me.checkout.instapay.qrTitle')}</div>
          <div className="text-xs leading-relaxed text-fg-muted">{t('me.checkout.instapay.qrHint')}</div>
        </div>
      </div>
    </section>
  )
}

function DetailsForm({ v, editing, onDone, onCancel }: { v: InstapayOrderView; editing: boolean; onDone: () => void; onCancel?: () => void }) {
  const { t, i18n } = useTranslation()
  const qc = useQueryClient()
  const user = useAuthStore((s) => s.user)
  const saved = useMemo(() => readSaved(v.orderId), [v.orderId])
  const [reference, setReference] = useState(saved.reference ?? v.reference ?? '')
  const [sender, setSender] = useState(saved.sender ?? v.senderName ?? '')
  const [dream, setDream] = useState(saved.dream ?? '')
  const [gender, setGender] = useState<Gender>(saved.gender ?? user?.gender ?? 'FEMALE')
  const [draftId, setDraftId] = useState<string | undefined>(saved.draftId)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => { writeSaved(v.orderId, { reference, sender, dream, gender, draftId }) }, [v.orderId, reference, sender, dream, gender, draftId])

  const ref = normalizeReference(reference)
  const refBad = ref.length > 0 && !REFERENCE_RE.test(ref) && (ref.length >= 12 || /\D/.test(ref))
  const dreamText = dream.trim()
  const dreamShort = dreamText.length > 0 && dreamText.length < DREAM_MIN
  const canAttach = v.attachedDreams === 0 && !editing
  const ready = REFERENCE_RE.test(ref) && sender.trim().length >= 3 && !dreamShort

  const send = useMutation({
    mutationFn: async () => {
      let ids: string[] | undefined
      if (canAttach && dreamText) {
        // one draft per order: a retry reuses it
        const d = draftId ? await dreamsApi.updateDraft(draftId, { text: dreamText, gender }) : await dreamsApi.createDraft({ text: dreamText, gender })
        setDraftId(d.id)
        ids = [d.id]
      }
      return instapayApi.submit(v.orderId, { reference: ref, senderName: sender.trim(), dreamIds: ids })
    },
    onSuccess: (view) => {
      clearSaved(v.orderId)
      qc.setQueryData(['instapay', v.orderId], view)
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['dreams'] })
      onDone()
      window.scrollTo({ top: 0, behavior: 'smooth' })
    },
    onError: (e) => {
      const code = (e as ApiError).problem?.code
      setErr(code && i18n.exists(`me.checkout.instapay.errors.${code}`) ? t(`me.checkout.instapay.errors.${code}`) : (e as Error).message)
    },
  })

  const submitLabel = editing
    ? (v.status === 'FAILED' ? t('me.checkout.instapay.resend') : t('me.checkout.instapay.saveEdit'))
    : canAttach && dreamText ? t('me.checkout.instapay.submitWithDream') : t('me.checkout.instapay.submit')

  return (
    <form className="space-y-5" onSubmit={(e) => { e.preventDefault(); setErr(null); if (ready) send.mutate() }} noValidate>
      <section className="card space-y-4 p-5">
        <StepTitle n={2}>{t('me.checkout.instapay.step2')}</StepTitle>
        <div>
          <label htmlFor="ip-ref"><Label>{t('me.checkout.instapay.reference')}</Label></label>
          <Input id="ip-ref" dir="ltr" inputMode="numeric" autoComplete="off" maxLength={40} placeholder="202347403793" value={reference}
            onChange={(e) => setReference(normalizeReference(e.target.value))} aria-invalid={refBad} aria-describedby="ip-ref-hint"
            className={cn('h-12 font-mono text-lg tracking-widest', refBad && 'border-danger', REFERENCE_RE.test(ref) && 'border-success/60')} />
          <p id="ip-ref-hint" className={cn('mt-1.5 text-xs leading-relaxed', refBad ? 'text-danger' : 'text-fg-dim')} role={refBad ? 'alert' : undefined}>
            {refBad ? t('me.checkout.instapay.referenceInvalid') : t('me.checkout.instapay.referenceHint')}
          </p>
        </div>
        <div>
          <label htmlFor="ip-sender"><Label>{t('me.checkout.instapay.sender')}</Label></label>
          <Input id="ip-sender" dir="ltr" autoComplete="name" maxLength={120} placeholder="AHMED MOHAMED ALI" value={sender}
            onChange={(e) => setSender(e.target.value)} aria-describedby="ip-sender-hint" className="h-12 uppercase" />
          <p id="ip-sender-hint" className="mt-1.5 text-xs leading-relaxed text-fg-dim">{t('me.checkout.instapay.senderHint')}</p>
        </div>
      </section>

      {!editing && (
        <section className="card space-y-4 p-5">
          <StepTitle n={3} aside={canAttach ? <span className="chip bg-surface-2 text-xs text-fg-muted">{t('me.checkout.instapay.optional')}</span> : undefined}>{t('me.checkout.instapay.step3')}</StepTitle>
          {canAttach ? (
            <>
              <p className="text-sm leading-relaxed text-fg-muted">{t('me.checkout.instapay.dreamLead')}</p>
              <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label={t('auth.gender')}>
                {(['FEMALE', 'MALE'] as Gender[]).map((g) => (
                  <button key={g} type="button" role="radio" aria-checked={gender === g} onClick={() => setGender(g)}
                    className={cn('min-h-11 rounded-xl border px-4 py-2.5 text-sm transition-colors', gender === g ? 'border-gold bg-gold/10 text-gold-ink' : 'border-line text-fg-muted')}>
                    {t(g === 'FEMALE' ? 'auth.female' : 'auth.male')}
                  </button>
                ))}
              </div>
              <label htmlFor="ip-dream" className="sr-only">{t('me.detail.dream')}</label>
              <Textarea id="ip-dream" rows={6} value={dream} onChange={(e) => setDream(e.target.value)} placeholder={t('me.checkout.instapay.dreamPlaceholder')} className="text-base" />
              <div className="flex items-center justify-between gap-3 text-xs">
                <span className={dreamShort ? 'text-danger' : 'text-fg-dim'}>{dreamShort ? t('me.checkout.instapay.dreamShort', { n: DREAM_MIN }) : t('me.checkout.instapay.savedLocally')}</span>
                <span className="text-fg-dim">{t('me.newDream.chars', { n: dream.length })}</span>
              </div>
            </>
          ) : (
            <div className="flex items-start gap-3 rounded-xl bg-gold/5 p-3 text-sm leading-relaxed">
              <Icon name="moon" size={18} className="mt-0.5 shrink-0 text-gold-ink" />
              <span>{t('me.checkout.instapay.dreamAttached', { n: v.attachedDreams })}</span>
            </div>
          )}
        </section>
      )}

      <div className="space-y-2">
        {err && <p role="alert" className="text-sm text-danger">{err}</p>}
        <Button type="submit" size="lg" className="min-h-14 w-full" disabled={!ready} loading={send.isPending}>{submitLabel}</Button>
        {onCancel && <Button type="button" variant="ghost" className="w-full" onClick={onCancel}>{t('me.checkout.instapay.cancelEdit')}</Button>}
        {!editing && <p className="text-center text-xs leading-relaxed text-fg-dim">{t('me.checkout.instapay.submitNote')}</p>}
      </div>
    </form>
  )
}

function PendingCard({ v, onEdit }: { v: InstapayOrderView; onEdit: () => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const rows: [string, ReactNode][] = [
    [t('me.checkout.instapay.summaryPackage'), v.packageName],
    [t('me.checkout.instapay.summaryAmount'), <span key="a" dir="ltr" className="font-mono">{fmtMoney(v.amount, v.currency, locale)}</span>],
    [t('me.checkout.instapay.summaryRef'), <span key="r" dir="ltr" className="font-mono">{v.reference}</span>],
    [t('me.checkout.instapay.summarySender'), <span key="s" dir="ltr">{v.senderName}</span>],
  ]
  return (
    <div className="space-y-5">
      <div className="card flex flex-col items-center gap-2 p-6 text-center">
        <div aria-hidden="true" className="flex h-16 w-16 items-center justify-center rounded-full border border-gold/60 bg-night text-gold"><Icon name="clock" size={28} /></div>
        <h2 className="mt-1 text-xl font-medium">{t('me.checkout.instapay.pendingTitle')}</h2>
        <p className="text-sm leading-relaxed text-fg-muted">{t('me.checkout.instapay.pendingLead')}</p>
      </div>
      <ol className="card space-y-3 p-5" aria-label={t('me.checkout.instapay.pendingTitle')}>
        <li className="flex items-center gap-3"><span aria-hidden="true" className="flex h-7 w-7 items-center justify-center rounded-full bg-success text-night"><Icon name="check" size={16} /></span><span>{t('me.checkout.instapay.stepSent')}</span></li>
        <li className="flex items-center gap-3"><span aria-hidden="true" className="flex h-7 w-7 items-center justify-center rounded-full border-2 border-gold"><span className="h-2.5 w-2.5 rounded-full bg-gold" /></span><span className="font-medium text-gold-ink">{t('me.checkout.instapay.stepCheck')}</span><span className="ms-auto text-xs text-gold-ink">{t('me.checkout.instapay.now')}</span></li>
        <li className="flex items-center gap-3 text-fg-dim"><span aria-hidden="true" className="h-7 w-7 rounded-full border-2 border-line" /><span>{v.attachedDreams > 0 ? t('me.checkout.instapay.stepCredit') : t('me.checkout.instapay.stepCreditNoDream')}</span></li>
      </ol>
      <dl className="card divide-y divide-line p-0">
        {rows.map(([k, val]) => <div key={k} className="flex items-center justify-between gap-4 px-5 py-3 text-sm"><dt className="text-fg-muted">{k}</dt><dd className="min-w-0 truncate">{val}</dd></div>)}
      </dl>
      {v.attachedDreams > 0 && (
        <div className="flex items-start gap-3 rounded-2xl border border-gold/40 bg-gold/5 p-4 text-sm leading-relaxed">
          <Icon name="moon" size={18} className="mt-0.5 shrink-0 text-gold-ink" />
          <span>{t('me.checkout.instapay.dreamAttached', { n: v.attachedDreams })}</span>
        </div>
      )}
      <div className="flex flex-wrap justify-center gap-3 text-sm">
        <Button type="button" variant="ghost" onClick={onEdit}>{t('me.checkout.instapay.edit')}</Button>
        <Link to="/me/payments" className="btn btn-md btn-ghost">{t('me.checkout.instapay.toPayments')}</Link>
      </div>
    </div>
  )
}

function SuccessCard({ v }: { v: InstapayOrderView }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  return (
    <div className="card flex flex-col items-center gap-3 p-6 text-center">
      <div aria-hidden="true" className="flex h-16 w-16 items-center justify-center rounded-full bg-success/15 text-success"><Icon name="check" size={32} active /></div>
      <h2 className="text-xl font-medium">{t('me.checkout.instapay.successTitle')}</h2>
      <p className="text-sm leading-relaxed text-fg-muted">{v.attachedDreams > 0 ? t('me.checkout.instapay.successLead') : t('me.checkout.instapay.successLeadNoDream')}</p>
      <div className="mt-2 flex flex-wrap justify-center gap-3">
        <Link to="/me" className="btn btn-md btn-gold">{t('me.checkout.instapay.toDreams')}</Link>
        {v.attachedDreams === 0 && <Link to="/me/new" className="btn btn-md btn-ghost">{t('me.checkout.instapay.writeDream')} {arrowNext(locale)}</Link>}
      </div>
    </div>
  )
}

function ExpiredCard() {
  const { t } = useTranslation()
  return (
    <div className="card space-y-3 p-6 text-center">
      <div aria-hidden="true" className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-surface-2 text-fg-dim"><Icon name="clock" size={26} /></div>
      <h2 className="text-xl font-medium">{t('me.checkout.instapay.expiredTitle')}</h2>
      <p className="text-sm leading-relaxed text-fg-muted">{t('me.checkout.instapay.expiredLead')}</p>
      <div className="flex flex-wrap justify-center gap-3">
        <Link to="/me/packages" className="btn btn-md btn-gold">{t('me.checkout.instapay.choosePackage')}</Link>
        <Link to="/contact" className="btn btn-md btn-ghost">{t('me.checkout.instapay.contact')}</Link>
      </div>
    </div>
  )
}
