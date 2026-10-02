import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, type ButtonProps } from '.'
import { Icon } from '@/components/icons/Icon'
import { ApiError } from '@/api/client'
import { cn } from '@/lib/utils'

/** Button for a file download (PDF / Excel): spinner while the file is prepared, a short message if it fails. */
export function DownloadButton({ run, label, className, variant = 'ghost', size = 'sm', align = 'end', disabled }: {
  run: () => Promise<void>
  label: string
  className?: string
  variant?: ButtonProps['variant']
  size?: ButtonProps['size']
  align?: 'start' | 'end'
  disabled?: boolean
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const go = async () => {
    setBusy(true)
    setError(null)
    try {
      await run()
    } catch (e) {
      setError(t(e instanceof ApiError && e.status === 404 ? 'reports.notFound' : 'reports.failed'))
    } finally {
      setBusy(false)
    }
  }
  return (
    <span className={cn('inline-flex flex-col gap-1', align === 'end' ? 'items-end' : 'items-start', className)}>
      <Button type="button" variant={variant} size={size} loading={busy} disabled={disabled} onClick={go} aria-busy={busy}>
        {!busy && <Icon name="download" size={15} />}
        {busy ? t('reports.preparing') : label}
      </Button>
      {error && <span role="alert" className="text-xs text-danger">{error}</span>}
    </span>
  )
}
