import { StrictMode, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { router } from './app/router'
import { useAuthStore } from './app/auth-store'
import { applyLocale } from './i18n'
import './i18n'
import './theme/globals.css'
import { startMocks } from './mocks/browser'

const qc = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 15_000, refetchOnWindowFocus: false } } })

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
      <QueryClientProvider client={qc}><Root /></QueryClientProvider>
    </StrictMode>,
  )
})
