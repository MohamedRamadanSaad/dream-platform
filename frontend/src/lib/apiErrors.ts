// Shared helpers for API failures and mutations: translated error text, a double-submit lock and cache refreshes.
import { useCallback, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import type { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/client'

/** The server's problem code, if any. */
export const errorCode = (e: unknown) => (e instanceof ApiError ? e.problem?.code : undefined)
export const isNotFound = (e: unknown) => e instanceof ApiError && e.status === 404

/**
 * Text for a failed call: the first `<prefix>.<CODE>` key that exists, else `common.error`.
 * Never the server's own (English) sentence, so an Arabic page stays Arabic.
 */
export function useErrorText(...prefixes: string[]) {
  const { t, i18n } = useTranslation()
  return useCallback((e: unknown) => {
    const code = errorCode(e)
    if (code) for (const p of [...prefixes, 'common.errors']) if (i18n.exists(`${p}.${code}`)) return t(`${p}.${code}`)
    return t('common.error')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t, i18n, prefixes.join('|')])
}

/**
 * Double-submit guard: `run(fn)` starts `fn` only when nothing is running (a ref, so two quick taps in the same
 * frame cannot both get through before React re-renders the disabled button).
 */
export function useOnce() {
  const busy = useRef(false)
  return useCallback((fn: () => Promise<unknown> | unknown) => {
    if (busy.current) return
    busy.current = true
    Promise.resolve().then(fn).catch(() => undefined).finally(() => { busy.current = false })
  }, [])
}

/** Everything that shows a dream's state: the dream itself, the user's lists and the dashboard counts. */
export function invalidateDreams(qc: QueryClient, _dreamIds: (string | undefined)[] = []) {
  // every dream page: a payment step can change dreams the caller does not list (attached from ?dreams=)
  qc.invalidateQueries({ queryKey: ['dream'] })
  qc.invalidateQueries({ queryKey: ['dreams'] })
  qc.invalidateQueries({ queryKey: ['me'] })
}
