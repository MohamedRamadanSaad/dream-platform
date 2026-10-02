// "Sign in faster next time": right after a Google or e-mail-code sign-in, on a device with a fingerprint, face or
// screen lock, an account without any passkey is offered fingerprint / face sign-in — once per device.
import { useEffect, useId, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { create } from 'zustand'
import gsap from 'gsap'
import { useAuthStore } from '@/app/auth-store'
import { Icon } from '@/components/icons/Icon'
import { Button } from '@/components/ui'
import { toast } from '@/components/ui/Toaster'
import { reduced } from '@/components/motion'
import { markPasskeyOfferDone, passkeyLabel, passkeyOfferDone, usePasskeySupport } from '@/lib/passkeys'
import { useAddPasskey, usePasskeys } from './usePasskeys'
import { FingerprintOrFace } from './PasskeyGlyph'

/** Armed by the sign-in page for that account; in memory only, so it is about this sign-in and nothing older. */
const useOffer = create<{ userId: string | null }>(() => ({ userId: null }))
/** After a Google or e-mail-code sign-in (not after a passkey sign-in). */
export const armPasskeyOffer = (userId: string) => useOffer.setState({ userId })
const disarm = () => useOffer.setState({ userId: null })

export function PasskeyOffer() {
  const armedFor = useOffer((s) => s.userId)
  const userId = useAuthStore((s) => s.user?.id)
  const { pathname } = useLocation()
  const support = usePasskeySupport()
  const eligible = !!userId && armedFor === userId && support?.platform === true && !passkeyOfferDone()
  const q = usePasskeys(eligible)
  // the account page has the whole section: no card on top of it
  const onAccountPage = /\/profile\/?$/.test(pathname)
  if (!eligible || onAccountPage || !q.data || q.data.length > 0) return null
  return <OfferCard />
}

function OfferCard() {
  const { t } = useTranslation()
  const add = useAddPasskey(true)
  const titleId = useId()
  const ref = useRef<HTMLElement>(null)

  useEffect(() => {
    const el = ref.current
    if (!el || reduced()) return
    // revert (not kill) on cleanup: a killed "from" tween would leave the card invisible
    const ctx = gsap.context(() => { gsap.from(el, { opacity: 0, y: -10, duration: 0.6, ease: 'power3.out', delay: 0.35 }) }, el)
    return () => ctx.revert()
  }, [])

  /** Answered on this device (turned on, or "not now"): folds away and does not come back. */
  const close = () => {
    markPasskeyOfferDone()
    const el = ref.current
    if (!el || reduced()) return disarm()
    gsap.to(el, { opacity: 0, height: 0, marginBottom: 0, paddingTop: 0, paddingBottom: 0, borderWidth: 0, duration: 0.4, ease: 'power2.inOut', onComplete: disarm })
  }
  const turnOn = () => {
    if (add.busy) return
    void add.start(passkeyLabel(t)).then((added) => {
      if (!added) return
      toast.success(t('me.passkeys.addedToast'))
      close()
    })
  }

  return (
    <section ref={ref} aria-labelledby={titleId} className="card relative mb-5 overflow-hidden border-gold/50 p-4 sm:p-5">
      {/* a faint moonlit wash from the reading start */}
      <span aria-hidden="true" className="pointer-events-none absolute inset-0 bg-gradient-to-l from-gold/10 via-gold/[.03] to-transparent ltr:bg-gradient-to-r" />
      <div className="relative flex items-start gap-3 sm:gap-4">
        <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink">
          <FingerprintOrFace size={26} />
        </span>
        {/* phones: text, then the buttons; wide screens: one row */}
        <div className="min-w-0 flex-1 lg:flex lg:items-center lg:gap-8">
          <div className="min-w-0 flex-1">
            <h2 id={titleId} className="font-display text-lg leading-snug">{t('me.passkeys.offerTitle')}</h2>
            <p className="mt-1 text-sm leading-relaxed text-fg-muted">{t('me.passkeys.offerBody')}</p>
            {add.failure && <p role="alert" className="mt-2 text-sm text-danger">{t(`me.passkeys.errors.${add.failure}`)}</p>}
          </div>
          <div className="mt-4 flex flex-wrap items-center gap-2 lg:mt-0 lg:shrink-0">
            <Button size="sm" loading={add.busy} onClick={turnOn}>{!add.busy && <Icon name="passkey" size={16} />}{t('me.passkeys.offerYes')}</Button>
            <Button size="sm" variant="ghost" onClick={close}>{t('me.passkeys.offerLater')}</Button>
          </div>
        </div>
        <button type="button" onClick={close} aria-label={t('common.close')} title={t('common.close')}
          className="-me-1.5 -mt-1.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-fg-muted transition-colors hover:bg-surface-2 hover:text-fg">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
        </button>
      </div>
    </section>
  )
}
