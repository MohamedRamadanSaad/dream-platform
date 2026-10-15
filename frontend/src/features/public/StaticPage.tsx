import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { publicApi } from '@/api/endpoints'
import { useTranslation } from 'react-i18next'
import { PublicHeader, Footer } from '@/components/layout'
import { useAuthStore } from '@/app/auth-store'
import { fmtDay, fmtNum } from '@/lib/utils'
import { Icon } from '@/components/icons/Icon'

const FALLBACK_EMAIL = 'support@saadatu-aldarein.com'
type Kind = 'terms' | 'privacy' | 'refund'
/** Change a page's date when its text changes. */
const UPDATED: Record<Kind, string> = { terms: '2026-10-09', privacy: '2026-10-03', refund: '2026-10-09' }

type Section = { h: string; p: string[] }

const legalQuery = { queryKey: ['public', 'legal'], queryFn: publicApi.legal, staleTime: 10 * 60_000 }

/** Sets the tab title while a page is open and starts it at the top. */
function usePageTitle(title: string, key: string) {
  const { t } = useTranslation()
  useEffect(() => {
    const before = document.title
    document.title = `${title} | ${t('brand')}`
    return () => { document.title = before }
  }, [title, t])
  useEffect(() => { window.scrollTo(0, 0) }, [key])
}

export function StaticPage({ kind }: { kind: Kind }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const title = t(`static.${kind}Title`)
  const sections = t(`static.${kind}.sections`, { returnObjects: true }) as Section[]
  const legal = useQuery(legalQuery).data
  const email = legal?.supportEmail || FALLBACK_EMAIL
  const seller = legal ? ([['name', legal.name, false], ['taxNo', legal.taxRegistrationNo, true]] as const).filter(([, v]) => !!v) : []
  usePageTitle(title, kind)
  return (
    <div className="flex min-h-[100dvh] flex-col">
      <div className="bg-night"><PublicHeader /></div>
      <main className="mx-auto w-full max-w-3xl flex-1 px-5 py-12 md:py-16">
        <h1 className="font-display text-4xl md:text-5xl">{title}</h1>
        <p className="mt-3 text-sm text-fg-dim">{t('static.updated', { date: fmtDay(UPDATED[kind], locale, { day: 'numeric', month: 'long', year: 'numeric' }) })}</p>
        <p className="mt-8 text-lg font-light leading-loose text-fg-muted">{t(`static.${kind}.intro`)}</p>

        <div className="mt-10 space-y-5">
          {Array.isArray(sections) && sections.map((s, i) => (
            <section key={s.h} className="card p-6 md:p-7">
              <h2 className="mb-4 flex items-center gap-3 font-display text-xl md:text-2xl">
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gold/15 text-sm font-medium text-gold-ink">{fmtNum(i + 1, locale)}</span>
                {s.h}
              </h2>
              <ul className="space-y-3 ps-1">
                {s.p.map((line) => (
                  <li key={line} className="flex gap-3 leading-loose text-fg-muted">
                    <span aria-hidden="true" className="mt-[0.8em] h-1.5 w-1.5 shrink-0 rounded-full bg-gold/70" />
                    <span>{line}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>

        {/* seller details required for selling online (shown on the terms page) */}
        {kind === 'terms' && seller.length > 0 && (
          <section className="card mt-5 p-6 md:p-7">
            <h2 className="mb-4 font-display text-xl md:text-2xl">{t('static.seller.title')}</h2>
            <dl className="divide-y divide-line">
              {seller.map(([k, v, ltr]) => (
                <div key={k} className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 py-3">
                  <dt className="text-sm text-fg-muted">{t(`static.seller.${k}`)}</dt>
                  <dd dir={ltr ? 'ltr' : undefined} className="font-medium tabular-nums">{v}</dd>
                </div>
              ))}
            </dl>
          </section>
        )}

        <div className="mt-10 flex flex-wrap items-center justify-between gap-3 rounded-xl2 bg-night px-6 py-5 text-pearl">
          <span className="text-sm text-pearl/70">{t('static.contactLabel')}</span>
          <a href={`mailto:${email}`} dir="ltr" className="font-medium text-gold-soft hover:text-gold">{email}</a>
          {kind === 'refund' && legal?.supportPhone && (
            <Link to="/contact" className="w-full text-sm text-pearl/60 hover:text-pearl">{t('footer.contact')}</Link>
          )}
        </div>

        {/* attribution required by the free DB-IP country database (CC BY 4.0) */}
        {kind === 'privacy' && (
          <p className="mt-6 text-xs text-fg-dim"><a href="https://db-ip.com" target="_blank" rel="noreferrer" className="hover:text-fg-muted">{t('static.geoCredit')}</a></p>
        )}
      </main>
      <Footer />
    </div>
  )
}

/** Contact us: the support e-mail with the support hours (both from the dashboard settings). No phone on this page. */
export function ContactPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const title = t('static.contactTitle')
  const legal = useQuery(legalQuery).data
  const email = legal?.supportEmail || FALLBACK_EMAIL
  const hours = (locale === 'ar' ? legal?.supportHoursAr : legal?.supportHoursEn) || ''
  usePageTitle(title, 'contact')
  return (
    <div className="flex min-h-[100dvh] flex-col">
      <div className="bg-night"><PublicHeader /></div>
      <main className="mx-auto w-full max-w-3xl flex-1 px-5 py-12 md:py-16">
        <h1 className="font-display text-4xl md:text-5xl">{title}</h1>
        <p className="mt-6 text-lg font-light leading-loose text-fg-muted">{t('static.contact.lead')}</p>

        <div className="mt-8 grid gap-5">
          <section className="card flex flex-col gap-4 p-6 md:p-7">
            <div className="flex items-center gap-3">
              <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink"><Icon name="mail" size={20} /></span>
              <h2 className="font-display text-xl md:text-2xl">{t('static.contact.email')}</h2>
            </div>
            <a href={`mailto:${email}`} dir="ltr" className="break-all text-start text-lg font-medium text-fg hover:text-gold-ink">{email}</a>
            <p className="text-sm text-fg-muted">{t('static.contact.emailNote')}</p>
            {hours && <p className="flex items-center gap-2 text-sm text-fg-muted"><Icon name="clock" size={16} />{t('static.contact.hours', { hours })}</p>}
            <a href={`mailto:${email}`} className="btn btn-md btn-gold mt-auto w-full sm:w-auto sm:self-start">{t('static.contact.write')}</a>
          </section>
        </div>

        <p className="mt-8 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-fg-muted">
          <Icon name="shield" size={16} />{t('static.contact.refundNote')}
          <Link to="/refund-policy" className="font-medium text-gold-ink underline-offset-4 hover:underline">{t('static.contact.refundLink')}</Link>
        </p>
      </main>
      <Footer />
    </div>
  )
}
