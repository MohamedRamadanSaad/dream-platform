import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { create } from 'zustand'
import { cn } from '@/lib/utils'

type Tone = 'success' | 'error'
interface ToastItem { id: number; tone: Tone; text: string }
interface ToastState { items: ToastItem[]; push: (tone: Tone, text: string) => void; drop: (id: number) => void }

let seq = 0
export const useToasts = create<ToastState>((set) => ({
  items: [],
  push: (tone, text) => set((s) => ({ items: [...s.items.filter((x) => x.text !== text), { id: ++seq, tone, text }].slice(-3) })),
  drop: (id) => set((s) => ({ items: s.items.filter((x) => x.id !== id) })),
}))

/** Imperative helpers (usable outside React, e.g. from the QueryClient's MutationCache). */
export const toast = {
  success: (text: string) => useToasts.getState().push('success', text),
  error: (text: string) => useToasts.getState().push('error', text),
}

function Item({ it }: { it: ToastItem }) {
  const { t } = useTranslation()
  const drop = useToasts((s) => s.drop)
  useEffect(() => { const h = window.setTimeout(() => drop(it.id), it.tone === 'error' ? 6000 : 3200); return () => window.clearTimeout(h) }, [it, drop])
  return (
    <div role={it.tone === 'error' ? 'alert' : 'status'} className={cn('toast-in pointer-events-auto flex items-center gap-3 rounded-xl2 px-4 py-3 text-sm shadow-calm', it.tone === 'success' ? 'bg-night text-pearl' : 'bg-danger text-white')}>
      <span className={cn('flex h-6 w-6 shrink-0 items-center justify-center rounded-full', it.tone === 'success' ? 'bg-gold text-night' : 'bg-white/20')}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d={it.tone === 'success' ? 'M5 12l4 4L19 6' : 'M12 7v6M12 17v.5'} />
        </svg>
      </span>
      <span className="min-w-0 flex-1">{it.text}</span>
      <button type="button" onClick={() => drop(it.id)} aria-label={t('common.close')} className="-me-1 rounded-full p-1 opacity-70 hover:opacity-100">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
      </button>
    </div>
  )
}

/** Stack of short confirmations above everything (dialogs included); bottom-centre, clear of the phone tab bar. */
export function Toaster() {
  const items = useToasts((s) => s.items)
  return createPortal(
    <div aria-live="polite" className="pointer-events-none fixed inset-x-0 bottom-24 z-[90] flex flex-col items-center gap-2 px-4 md:bottom-8">
      {items.map((it) => <div key={it.id} className="w-full max-w-sm"><Item it={it} /></div>)}
    </div>,
    document.body,
  )
}
