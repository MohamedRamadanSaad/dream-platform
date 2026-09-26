import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

export const worker = setupWorker(...handlers)

export async function startMocks() {
  if (import.meta.env.VITE_USE_MOCKS === 'false') return // mocks are on unless explicitly disabled
  await worker.start({ onUnhandledRequest: 'bypass', serviceWorker: { url: '/mockServiceWorker.js' } })
}
