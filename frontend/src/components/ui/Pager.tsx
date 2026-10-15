import { useTranslation } from 'react-i18next'
import { useAuthStore } from '@/app/auth-store'
import { cn, fmtNum } from '@/lib/utils'

/** Chevron drawn for LTR and mirrored in RTL: "next" always points toward the end of the line. */
export function Chevron({ next, size = 14 }: { next: boolean; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="rtl:-scale-x-100">
      <path d={next ? 'M9 6l6 6-6 6' : 'M15 6l-6 6 6 6'} />
    </svg>
  )
}

/**
 * Previous / next pager with "Page x of y". `page` is zero-based. Renders nothing for a single page.
 * `label` names the list it pages (for screen readers).
 */
export function Pager({ page, pages, onChange, label, className, disabled }: {
  page: number; pages: number; onChange: (page: number) => void; label: string; className?: string; disabled?: boolean
}) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  if (pages <= 1) return null
  const cur = Math.min(Math.max(0, page), pages - 1)
  const btn = 'flex h-9 w-9 items-center justify-center rounded-full border border-line text-fg-muted transition-colors hover:border-gold hover:text-fg focus-visible:border-gold disabled:pointer-events-none disabled:opacity-30'
  return (
    <nav aria-label={label} className={cn('flex items-center justify-between gap-3', className)}>
      <span className="text-xs text-fg-dim" aria-live="polite">
        {t('common.pager.pageOf', { page: fmtNum(cur + 1, locale), pages: fmtNum(pages, locale) })}
      </span>
      <div className="flex items-center gap-1.5">
        <button type="button" className={btn} disabled={disabled || cur === 0} onClick={() => onChange(cur - 1)} aria-label={t('common.pager.prev')} title={t('common.pager.prev')}><Chevron next={false} /></button>
        <button type="button" className={btn} disabled={disabled || cur >= pages - 1} onClick={() => onChange(cur + 1)} aria-label={t('common.pager.next')} title={t('common.pager.next')}><Chevron next /></button>
      </div>
    </nav>
  )
}
