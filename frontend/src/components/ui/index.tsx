import { forwardRef, useEffect, useId, useRef, type ButtonHTMLAttributes, type HTMLAttributes, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from 'react'
import { createPortal } from 'react-dom'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'
import type { DreamStatus, OrderStatus } from '@/api/types'
import { useTranslation } from 'react-i18next'

const button = cva('btn', {
  variants: { variant: { gold: 'btn-gold', night: 'btn-night', ghost: 'btn-ghost', link: 'text-fg hover:text-gold-ink underline-offset-4 hover:underline' }, size: { sm: 'btn-sm', md: 'btn-md', lg: 'btn-lg' } },
  defaultVariants: { variant: 'gold', size: 'md' },
})
export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof button> { loading?: boolean }
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(({ className, variant, size, loading, children, disabled, ...p }, ref) => (
  <button ref={ref} className={cn(button({ variant, size }), className)} disabled={disabled || loading} {...p}>
    {loading && <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent" />}
    {children}
  </button>
))
Button.displayName = 'Button'

export const Card = ({ className, hover, ...p }: HTMLAttributes<HTMLDivElement> & { hover?: boolean }) => (
  <div className={cn('card p-6', hover && 'card-hover', className)} {...p} />
)

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(({ className, ...p }, ref) => <input ref={ref} className={cn('input', className)} {...p} />)
Input.displayName = 'Input'
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(({ className, ...p }, ref) => <textarea ref={ref} className={cn('input leading-relaxed', className)} {...p} />)
Textarea.displayName = 'Textarea'

export const Label = ({ children, className }: { children: ReactNode; className?: string }) => <span className={cn('label', className)}>{children}</span>
export const H1 = ({ children, className }: { children: ReactNode; className?: string }) => <h1 className={cn('font-display text-4xl md:text-5xl text-fg', className)}>{children}</h1>
export const H2 = ({ children, className }: { children: ReactNode; className?: string }) => <h2 className={cn('font-display text-3xl md:text-4xl text-fg', className)}>{children}</h2>
export const Kicker = ({ children }: { children: ReactNode }) => <div className="text-[13px] font-medium text-gold-ink ltr:text-xs ltr:tracking-[.15em]">{children}</div>

const statusColor: Record<DreamStatus | OrderStatus, string> = {
  DRAFT: 'bg-surface-2 text-fg-muted', IN_REVIEW: 'bg-info/10 text-info', AWAITING_USER_REPLY: 'bg-danger/10 text-danger', INTERPRETED: 'bg-success/10 text-success', CANCELLED: 'bg-surface-2 text-fg-dim',
  INITIATED: 'bg-warn/10 text-warn', SUCCESS: 'bg-success/10 text-success', FAILED: 'bg-danger/10 text-danger', EXPIRED: 'bg-surface-2 text-fg-dim', REFUNDED: 'bg-info/10 text-info', SUSPICIOUS: 'bg-warn/10 text-warn',
}
/** `interpreter` words a dream's status from the interpreter's side ("waiting for the user's reply" instead of "waiting for your reply"). */
export function StatusBadge({ status, kind = 'dream', audience = 'user' }: { status: DreamStatus | OrderStatus; kind?: 'dream' | 'order'; audience?: 'user' | 'interpreter' }) {
  const { t } = useTranslation()
  return <span className={cn('chip font-medium', statusColor[status])}><span className="h-1.5 w-1.5 rounded-full bg-current" />{t(kind === 'order' ? `me.orderStatus.${status}` : audience === 'interpreter' ? `admin.status.${status}` : `me.status.${status}`)}</span>
}

export function Skeleton({ className }: { className?: string }) { return <div className={cn('animate-pulse rounded-xl bg-surface-2', className)} /> }
export function Empty({ text, action }: { text: string; action?: ReactNode }) {
  return <div className="card p-10 text-center text-fg-muted"><p className="mb-4">{text}</p>{action}</div>
}
export function ErrorBox({ message, onRetry }: { message?: string; onRetry?: () => void }) {
  const { t } = useTranslation()
  return <div className="card border-danger/30 p-6 text-center"><p className="text-danger mb-3">{message || t('common.error')}</p>{onRetry && <Button variant="ghost" size="sm" onClick={onRetry}>{t('common.retry')}</Button>}</div>
}

export function Tabs<T extends string>({ value, onChange, items }: { value: T; onChange: (v: T) => void; items: { value: T; label: string; count?: number | string; tone?: 'danger' }[] }) {
  // on a phone the row scrolls sideways: bring the active tab into view (also when it is chosen from outside)
  const rowRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const row = rowRef.current
    const tab = row?.querySelector<HTMLElement>('[aria-selected="true"]')
    if (!row || !tab) return
    const r = row.getBoundingClientRect(), b = tab.getBoundingClientRect()
    if (b.left < r.left) row.scrollBy({ left: b.left - r.left - 16, behavior: 'smooth' })
    else if (b.right > r.right) row.scrollBy({ left: b.right - r.right + 16, behavior: 'smooth' })
  }, [value])
  return (
    // the line sits on the outer box; the inner row scrolls sideways only (a 1px vertical overflow used to
    // show a stray vertical scrollbar on Windows) and overlaps the line so the active tab's gold bar covers it
    <div className="border-b border-line">
    <div ref={rowRef} role="tablist" className="no-scrollbar -mb-px flex gap-1 overflow-x-auto overflow-y-hidden">
      {items.map((it) => (
        <button key={it.value} role="tab" aria-selected={value === it.value} onClick={() => onChange(it.value)}
          className={cn('whitespace-nowrap px-4 py-3 text-sm transition-colors border-b-2', value === it.value ? 'border-gold text-fg font-medium' : 'border-transparent text-fg-muted hover:text-fg', it.tone === 'danger' && value !== it.value && 'text-danger')}>
          {it.label}{it.count !== undefined && <span className="ms-1.5 text-xs text-fg-dim">· {it.count}</span>}
        </button>
      ))}
    </div>
    </div>
  )
}

export function Segmented<T extends string>({ value, onChange, items, className }: { value: T; onChange: (v: T) => void; items: { value: T; label: string }[]; className?: string }) {
  return (
    <div className={cn('inline-flex gap-1 rounded-full bg-surface-2 p-1', className)}>
      {items.map((it) => (
        <button key={it.value} onClick={() => onChange(it.value)} className={cn('rounded-full px-4 py-1.5 text-xs transition-colors', value === it.value ? 'bg-night text-pearl' : 'text-fg-muted hover:text-fg')}>{it.label}</button>
      ))}
    </div>
  )
}

/**
 * Dialog rendered in a portal on <body> (page wrappers are GSAP-animated with transform,
 * which would trap a fixed element and push it under the header). Header and footer stay
 * pinned; only the body scrolls, so long forms never get cut on short screens.
 * Phone: bottom sheet. Tablet/desktop: centred card.
 * `focusField={false}`: focus the dialog itself, not its first field (no phone keyboard over a one-tap dialog).
 */
export function Modal({ open, onClose, title, children, footer, size = 'md', focusField = true }: { open: boolean; onClose: () => void; title: string; children: ReactNode; footer?: ReactNode; size?: 'sm' | 'md' | 'lg'; focusField?: boolean }) {
  const { t } = useTranslation()
  const panelRef = useRef<HTMLDivElement>(null)
  const titleId = useId()
  const closeRef = useRef(onClose)
  closeRef.current = onClose
  const focusFieldRef = useRef(focusField)
  focusFieldRef.current = focusField

  useEffect(() => {
    if (!open) return
    const prevFocus = document.activeElement as HTMLElement | null
    const { overflow, paddingInlineEnd } = document.body.style
    const gap = window.innerWidth - document.documentElement.clientWidth
    document.body.style.overflow = 'hidden'
    if (gap > 0) document.body.style.paddingInlineEnd = `${gap}px`
    const first = focusFieldRef.current ? panelRef.current?.querySelector<HTMLElement>('input:not([type=hidden]):not([disabled]), select, textarea') : null
    ;(first ?? panelRef.current)?.focus({ preventScroll: true })
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); closeRef.current() }
      if (e.key === 'Tab' && panelRef.current) {
        const f = panelRef.current.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]):not([type=hidden]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')
        if (!f.length) return
        const a = f[0], z = f[f.length - 1]
        if (e.shiftKey && document.activeElement === a) { e.preventDefault(); z.focus() }
        else if (!e.shiftKey && document.activeElement === z) { e.preventDefault(); a.focus() }
      }
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = overflow
      document.body.style.paddingInlineEnd = paddingInlineEnd
      prevFocus?.focus?.({ preventScroll: true })
    }
  }, [open])

  if (!open) return null
  return createPortal(
    <div className="modal-backdrop fixed inset-0 z-[70] flex items-end justify-center bg-night/60 backdrop-blur-[2px] sm:items-center sm:p-6" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div ref={panelRef} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby={titleId}
        className={cn('modal-panel card flex w-full flex-col overflow-hidden p-0 shadow-calm outline-none',
          'max-h-[calc(100dvh-0.75rem)] rounded-b-none pb-[env(safe-area-inset-bottom)] sm:max-h-[min(88dvh,52rem)] sm:rounded-b-xl2 sm:pb-0',
          size === 'sm' ? 'sm:max-w-md' : size === 'lg' ? 'sm:max-w-2xl' : 'sm:max-w-lg')}>
        <span aria-hidden="true" className="mx-auto mt-2.5 h-1 w-10 shrink-0 rounded-full bg-line sm:hidden" />
        <header className="flex shrink-0 items-center justify-between gap-3 border-b border-line px-5 py-4 sm:px-6">
          <h3 id={titleId} className="min-w-0 truncate font-display text-xl sm:text-2xl">{title}</h3>
          <button type="button" onClick={onClose} aria-label={t('common.close')} className="-me-2 flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-fg-muted transition-colors hover:bg-surface-2 hover:text-fg">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-5 sm:px-6">{children}</div>
        {footer && <footer className="flex shrink-0 flex-wrap items-center justify-end gap-2 border-t border-line bg-surface px-5 py-4 sm:px-6">{footer}</footer>}
      </div>
    </div>,
    document.body,
  )
}

export function Stars({ value, onChange, size = 22 }: { value: number; onChange?: (v: number) => void; size?: number }) {
  return (
    <div className="inline-flex gap-1" role={onChange ? 'radiogroup' : undefined}>
      {[1, 2, 3, 4, 5].map((i) => (
        <button key={i} type="button" disabled={!onChange} onClick={() => onChange?.(i)} aria-label={`${i}`} className="disabled:cursor-default">
          <svg width={size} height={size} viewBox="0 0 24 24" fill={i <= value ? 'rgb(var(--gold))' : 'transparent'} stroke="rgb(var(--gold))" strokeWidth="1.3"><path d="M12 3l2.4 5.2 5.6.6-4.2 3.8 1.2 5.6L12 15.4 7 18.2l1.2-5.6L4 8.8l5.6-.6z" /></svg>
        </button>
      ))}
    </div>
  )
}

/** Accessible on/off switch (role="switch"); the knob moves to the reading end when on. */
export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} onClick={() => onChange(!checked)}
      className={cn('relative h-7 w-12 shrink-0 rounded-full transition-colors duration-300 disabled:opacity-40', checked ? 'bg-gold' : 'bg-surface-2 ring-1 ring-inset ring-line')}>
      <span className={cn('absolute top-1 h-5 w-5 rounded-full bg-white shadow transition-all duration-300', checked ? 'start-6' : 'start-1')} />
    </button>
  )
}

export function Stat({ label, value, tone, icon }: { label: string; value: ReactNode; tone?: 'gold' | 'danger' | 'night'; icon?: ReactNode }) {
  return (
    <div className={cn('card p-5 flex items-center justify-between gap-3', tone === 'gold' && 'border-gold/50', tone === 'danger' && 'border-danger/30 bg-danger/5', tone === 'night' && 'bg-night text-pearl border-navy')}>
      <div><div className={cn('text-xs', tone === 'night' ? 'text-gold-soft/70' : 'text-fg-muted')}>{label}</div><div className="mt-1 text-3xl font-display">{value}</div></div>
      {icon && <div className={cn('hidden sm:block', tone === 'night' ? 'text-gold' : 'text-gold-ink')}>{icon}</div>}
    </div>
  )
}
