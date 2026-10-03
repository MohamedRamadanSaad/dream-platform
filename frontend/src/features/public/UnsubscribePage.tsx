import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { publicApi } from '@/api/endpoints'
import { PublicHeader, Footer, YoutubeLogo } from '@/components/layout'
import { Button } from '@/components/ui'

/**
 * Link from the new-video e-mail. Stopping needs one press (e-mail scanners open links on their own, so nothing
 * changes by just opening the page).
 */
export function UnsubscribePage() {
  const { t } = useTranslation()
  const [params] = useSearchParams()
  const u = params.get('u') ?? ''
  const tk = params.get('t') ?? ''
  const [done, setDone] = useState(false)
  const stop = useMutation({ mutationFn: () => publicApi.unsubscribe(u, tk), meta: { toast: false }, onSuccess: () => setDone(true) })
  const valid = !!u && !!tk
  return (
    <div className="flex min-h-[100dvh] flex-col">
      <div className="bg-night"><PublicHeader /></div>
      <main className="mx-auto flex w-full max-w-lg flex-1 flex-col items-center justify-center px-5 py-16 text-center">
        <span className="mb-5 flex h-16 w-16 items-center justify-center rounded-full bg-[#FF0000]/10"><YoutubeLogo size={22} /></span>
        <h1 className="font-display text-3xl">{t(done ? 'unsubscribe.doneTitle' : 'unsubscribe.title')}</h1>
        <p className="mt-3 font-light leading-loose text-fg-muted">
          {!valid ? t('unsubscribe.invalid') : done ? t('unsubscribe.doneBody') : stop.isError ? t('unsubscribe.invalid') : t('unsubscribe.body')}
        </p>
        {valid && !done && !stop.isError && (
          <Button className="mt-7" variant="night" loading={stop.isPending} onClick={() => stop.mutate()}>{t('unsubscribe.confirm')}</Button>
        )}
        <Link to="/" className="mt-6 text-sm text-gold-ink">{t('unsubscribe.home')}</Link>
      </main>
      <Footer />
    </div>
  )
}
