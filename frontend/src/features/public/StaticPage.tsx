import { useTranslation } from 'react-i18next'
import { PublicHeader, Footer } from '@/components/layout'

export function StaticPage({ kind }: { kind: 'terms' | 'privacy' }) {
  const { t } = useTranslation()
  return (
    <div>
      <div className="bg-night"><PublicHeader /></div>
      <main className="mx-auto max-w-3xl px-5 py-16">
        <h1 className="font-display text-4xl mb-6">{t(kind === 'terms' ? 'footer.terms' : 'footer.privacy')}</h1>
        <p className="text-fg-muted leading-loose">{t('static.placeholder', { title: t(kind === 'terms' ? 'static.terms' : 'static.privacy') })}</p>
      </main>
      <Footer />
    </div>
  )
}
