import { useTranslation } from 'react-i18next'
import { PageEnter } from '@/components/motion'
import { InstapaySettingsCard } from './InstapayAdmin'

/** The receiving InstaPay account, set once (its own menu item, not on the payments page). */
export function InstapaySettingsPage() {
  const { t } = useTranslation()
  return (
    <PageEnter className="mx-auto max-w-4xl">
      <h1 className="font-display text-4xl">{t('admin.instapaySettings.title')}</h1>
      <p className="mb-6 mt-1 text-sm font-light text-fg-muted">{t('admin.instapaySettings.lead')}</p>
      <InstapaySettingsCard />
    </PageEnter>
  )
}
