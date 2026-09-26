import { forwardRef, type ButtonHTMLAttributes, type HTMLAttributes, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'
import type { DreamStatus, OrderStatus } from '@/api/types'
import { useTranslation } from 'react-i18next'

const button = cva('btn', {
  variants: { variant: { gold: 'btn-gold', night: 'btn-night', ghost: 'btn-ghost', link: 'text-fg hover:text-gold-deep underline-offset-4 hover:underline' }, size: { sm: 'btn-sm', md: 'btn-md', lg: 'btn-lg' } },
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
export const Kicker = ({ children }: { children: ReactNode }) => <div className="text-xs tracking-[.15em] text-gold-deep">{children}</div>

const statusColor: Record<DreamStatus | OrderStatus, string> = {
  DRAFT: 'bg-surface-2 text-fg-muted', IN_REVIEW: 'bg-info/10 text-info', AWAITING_USER_REPLY: 'bg-danger/10 text-danger', INTERPRETED: 'bg-success/10 text-success', CANCELLED: 'bg-surface-2 text-fg-dim',
  INITIATED: 'bg-warn/10 text-warn', SUCCESS: 'bg-success/10 text-success', FAILED: 'bg-danger/10 text-danger', EXPIRED: 'bg-surface-2 text-fg-dim', REFUNDED: 'bg-info/10 text-info', SUSPICIOUS: 'bg-warn/10 text-warn',
}
export function StatusBadge({ status, kind = 'dream' }: { status: DreamStatus | OrderStatus; kind?: 'dream' | 'order' }) {
  const { t } = useTranslation()
  return <span className={cn('chip font-medium', statusColor[status])}><span className="h-1.5 w-1.5 rounded-full bg-current" />{t(kind === 'dream' ? `me.status.${status}` : `me.orderStatus.${status}`)}</span>
}

export function Skeleton({ className }: { className?: string }) { return <div className={cn('animate-pulse rounded-xl bg-surface-2', className)} /> }
export function Empty({ text, action }: { text: string; action?: ReactNode }) {
  return <div className="card p-10 text-center text-fg-muted"><p className="mb-4">{text}</p>{action}</div>
}
export function ErrorBox({ message, onRetry }: { message?: string; onRetry?: () => void }) {
  const { t } = useTranslation()
  return <div className="card border-danger/30 p-6 text-center"><p className="text-danger mb-3">{message || t('common.error')}</p>{onRetry && <Button variant="ghost" size="sm" onClick={onRetry}>{t('common.retry')}</Button>}</div>
}

export function Tabs<T extends string>({ value, onChange, items }: { value: T; onChange: (v: T) => void; items: { value: T; label: string; count?: number; tone?: 'danger' }[] }) {
  return (
    <div role="tablist" className="flex gap-1 overflow-x-auto border-b border-line">
      {items.map((it) => (
        <button key={it.value} role="tab" aria-selected={value === it.value} onClick={() => onChange(it.value)}
          className={cn('whitespace-nowrap px-4 py-3 text-sm transition-colors border-b-2 -mb-px', value === it.value ? 'border-gold text-fg font-medium' : 'border-transparent text-fg-muted hover:text-fg', it.tone === 'danger' && value !== it.value && 'text-danger')}>
          {it.label}{it.count !== undefined && <span className="ms-1.5 text-xs text-fg-dim">· {it.count}</span>}
        </button>
      ))}
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

export function Modal({ open, onClose, title, children }: { open: boolean; onClose: () => void; title: string; children: ReactNode }) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-night/60 p-4" onClick={onClose}>
      <div className="card w-full max-w-lg p-6 shadow-calm" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 className="font-display text-2xl mb-4">{title}</h3>
        {children}
      </div>
    </div>
  )
}

export function Stars({ value, onChange, size = 22 }: { value: number; onChange?: (v: number) => void; size?: number }) {
  return (
    <div className="inline-flex gap-1" role={onChange ? 'radiogroup' : undefined}>
      {[1, 2, 3, 4, 5].map((i) => (
        <button key={i} type="button" disabled={!onChange} onClick={() => onChange?.(i)} aria-label={`${i}`} className="disabled:cursor-default">
          <svg width={size} height={size} viewBox="0 0 24 24" fill={i <= value ? 'var(--gold)' : 'transparent'} stroke="var(--gold)" strokeWidth="1.3"><path d="M12 3l2.4 5.2 5.6.6-4.2 3.8 1.2 5.6L12 15.4 7 18.2l1.2-5.6L4 8.8l5.6-.6z" /></svg>
        </button>
      ))}
    </div>
  )
}

export function Stat({ label, value, tone, icon }: { label: string; value: ReactNode; tone?: 'gold' | 'danger' | 'night'; icon?: ReactNode }) {
  return (
    <div className={cn('card p-5 flex items-center justify-between gap-3', tone === 'gold' && 'border-gold/50', tone === 'danger' && 'border-danger/30 bg-danger/5', tone === 'night' && 'bg-night text-pearl border-navy')}>
      <div><div className={cn('text-xs', tone === 'night' ? 'text-gold-soft/70' : 'text-fg-muted')}>{label}</div><div className="mt-1 text-3xl font-display">{value}</div></div>
      {icon && <div className={cn(tone === 'night' ? 'text-gold' : 'text-gold-deep')}>{icon}</div>}
    </div>
  )
}
