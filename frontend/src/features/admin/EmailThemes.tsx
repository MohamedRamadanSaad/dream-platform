import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import type { Locale, MailTemplateRow, MailThemeDto } from '@/api/types'
import { useAuthStore } from '@/app/auth-store'
import { Button, ErrorBox, Segmented, Skeleton } from '@/components/ui'
import { Icon } from '@/components/icons/Icon'
import { cn } from '@/lib/utils'

/** Theme name in the current UI language. */
export function useThemeName() {
  const locale = useAuthStore((s) => s.locale)
  return (theme: MailThemeDto | undefined) => (theme ? (locale === 'en' ? theme.nameEn : theme.nameAr) : '')
}

/**
 * Small picture of a theme: its header image (1200×360, shown at 10:3) over the theme colours. When the image
 * is missing the colours alone stay visible, so the picker still works before the images are uploaded.
 */
export function ThemeThumb({ theme, className }: { theme: MailThemeDto; className?: string }) {
  const [broken, setBroken] = useState(false)
  useEffect(() => setBroken(false), [theme.headerImageUrl])
  return (
    <span
      aria-hidden="true"
      className={cn('relative block aspect-[10/3] overflow-hidden rounded-md ring-1 ring-inset ring-black/10', className)}
      style={{ background: `linear-gradient(135deg, ${theme.pageBg} 55%, ${theme.accent})` }}
    >
      {!broken && <img src={theme.headerImageUrl} alt="" loading="lazy" onError={() => setBroken(true)} className="absolute inset-0 h-full w-full object-cover" />}
      <span className="absolute bottom-1 end-1 h-2 w-2 rounded-full" style={{ background: theme.accent }} />
    </span>
  )
}

/**
 * A row of theme buttons (radio group). `value` is the selected key; `defaultKey` is marked with a small tag;
 * `inherited` = the selection only follows the default (shown in a softer style).
 */
export function ThemePicker({ themes, value, defaultKey, inherited, onPick, label, size = 'sm', disabled }: {
  themes: MailThemeDto[]
  value: string
  defaultKey?: string
  inherited?: boolean
  onPick: (key: string) => void
  label: string
  size?: 'sm' | 'lg'
  disabled?: boolean
}) {
  const { t } = useTranslation()
  const name = useThemeName()
  return (
    <div role="radiogroup" aria-label={label} className={cn('flex gap-2 overflow-x-auto pb-1', size === 'lg' && 'grid grid-cols-2 gap-3 overflow-visible sm:grid-cols-4')}>
      {themes.map((th) => {
        const selected = th.key === value
        return (
          <button
            key={th.key}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={disabled}
            onClick={() => { if (!selected || inherited) onPick(th.key) }}
            className={cn(
              'group shrink-0 rounded-lg p-1 text-start transition-colors disabled:opacity-50',
              size === 'sm' ? 'w-24' : 'w-full',
              selected ? (inherited ? 'bg-gold/5 ring-1 ring-gold/50' : 'bg-gold/10 ring-2 ring-gold') : 'ring-1 ring-line hover:ring-gold/60',
            )}
          >
            <ThemeThumb theme={th} />
            <span className={cn('mt-1 block truncate px-0.5', size === 'sm' ? 'text-[11px]' : 'text-sm', selected ? 'font-medium text-fg' : 'text-fg-muted')}>{name(th)}</span>
            {defaultKey === th.key && (
              <span className="block px-0.5 text-[10px] text-gold-ink">{t('admin.emails.themes.defaultTag')}</span>
            )}
          </button>
        )
      })}
    </div>
  )
}

/** Full-screen sheet with the rendered e-mail (iframe srcDoc), an Arabic/English switch and a theme switch. */
export function EmailPreview({ row, themes, onClose }: { row: MailTemplateRow; themes: MailThemeDto[]; onClose: () => void }) {
  const { t } = useTranslation()
  const uiLocale = useAuthStore((s) => s.locale)
  const [locale, setLocale] = useState<Locale>(uiLocale)
  const [theme, setTheme] = useState(row.theme)
  const closeRef = useRef<HTMLButtonElement>(null)
  const q = useQuery({
    queryKey: ['admin', 'mail', 'preview', row.template, theme, locale],
    queryFn: () => adminApi.mailPreview({ template: row.template, theme, locale }),
    staleTime: 30_000,
  })

  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { window.removeEventListener('keydown', onKey); document.body.style.overflow = overflow }
  }, [onClose])

  const title = t(`admin.emails.events.${row.template}.label`)
  // portal: the page wrapper is animated (transform), which would trap a fixed sheet inside it
  return createPortal(
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-night/60 sm:items-center sm:p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="email-preview-title"
        onClick={(e) => e.stopPropagation()}
        className="card flex h-[92vh] w-full max-w-3xl flex-col overflow-hidden rounded-b-none p-0 shadow-calm sm:h-[88vh] sm:rounded-b-xl2"
      >
        <header className="flex items-center gap-3 border-b border-line px-4 py-3 sm:px-5">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink"><Icon name="eye" size={18} /></span>
          <div className="min-w-0 flex-1">
            <h3 id="email-preview-title" className="truncate font-medium">{t('admin.emails.preview.title', { name: title })}</h3>
            <p className="truncate text-xs text-fg-muted">{t('admin.emails.preview.lead')}</p>
          </div>
          <Button ref={closeRef} size="sm" variant="ghost" onClick={onClose}>{t('admin.emails.preview.close')}</Button>
        </header>
        <div className="flex flex-col gap-3 border-b border-line px-4 py-3 sm:px-5">
          <div className="flex items-center gap-3">
            <span className="text-xs text-fg-muted">{t('admin.emails.preview.language')}</span>
            <Segmented<Locale> value={locale} onChange={setLocale} items={[{ value: 'ar', label: t('admin.emails.preview.ar') }, { value: 'en', label: t('admin.emails.preview.en') }]} />
          </div>
          <ThemePicker themes={themes} value={theme} onPick={setTheme} label={t('admin.emails.preview.theme')} />
        </div>
        <div className="relative min-h-0 flex-1 bg-surface-2">
          {q.isLoading ? (
            <div className="p-4"><Skeleton className="h-[60vh]" /></div>
          ) : q.isError ? (
            <div className="p-4"><ErrorBox onRetry={() => q.refetch()} /></div>
          ) : (
            <iframe title={t('admin.emails.preview.frame', { name: title })} srcDoc={q.data ?? ''} sandbox="" className="h-full w-full border-0 bg-white" />
          )}
        </div>
      </div>
    </div>,
    document.body,
  )
}
