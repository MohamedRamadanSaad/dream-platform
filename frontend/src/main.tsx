import { StrictMode, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { MutationCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { router } from './app/router'
import { useAuthStore } from './app/auth-store'
import { applyLocale, setLocaleChangeListener } from './i18n'
import './i18n'
import './theme/globals.css'
import { startMocks } from './mocks/browser'
import i18n from './i18n'
import { Toaster, toast } from './components/ui/Toaster'
import { ApiError } from './api/client'

/**
 * Save feedback for every change: on the interpreter dashboard each successful mutation shows "Saved"
 * and each failure shows the server's reason. Elsewhere a mutation opts in with meta: { toast: 'key' }.
 * meta: { toast: false } silences a mutation (it shows its own feedback).
 */
const mutationCache = new MutationCache({
  onSuccess: (_d, _v, _c, m) => {
    const opt = m.meta?.toast
    if (opt === false) return
    if (typeof opt === 'string') return void toast.success(i18n.t(opt))
    if (window.location.pathname.startsWith('/admin')) toast.success(i18n.t('common.saved'))
  },
  onError: (e, _v, _c, m) => {
    if (m.meta?.toast === false) return
    if (m.meta?.toast === undefined && !window.location.pathname.startsWith('/admin')) return
    toast.error(i18n.t(e instanceof ApiError && e.status === 403 ? 'common.forbidden' : 'common.saveFailed'))
  },
})
const qc = new QueryClient({ mutationCache, defaultOptions: { queries: { retry: 1, staleTime: 15_000, refetchOnWindowFocus: false } } })
setLocaleChangeListener(() => { qc.invalidateQueries() })

function Root() {
  const locale = useAuthStore((s) => s.locale)
  const theme = useAuthStore((s) => s.theme)
  useEffect(() => { applyLocale(locale) }, [locale])
  useEffect(() => { document.documentElement.classList.toggle('dark', theme === 'dark') }, [theme])
  return <RouterProvider router={router} />
}

startMocks().then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={qc}><Root /><Toaster /></QueryClientProvider>
    </StrictMode>,
  )
})
