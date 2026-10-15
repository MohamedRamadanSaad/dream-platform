/// <reference lib="webworker" />
// The site's service worker (vite-plugin-pwa injectManifest): offline app shell + Web Push.
// Type-checked by tsconfig.sw.json (WebWorker lib), not by the app's DOM tsconfig.
import { clientsClaim } from 'workbox-core'
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from 'workbox-precaching'
import { NavigationRoute, registerRoute } from 'workbox-routing'

declare const self: ServiceWorkerGlobalScope & { __WB_MANIFEST: Array<string | { url: string; revision: string | null }> }

const APP_NAME = 'إلى سعادة الدارين'
const ICON = '/icons/icon-192.png'

// registerType 'autoUpdate': a new build takes over at once, every open page included
void self.skipWaiting()
clientsClaim()

cleanupOutdatedCaches()
precacheAndRoute(self.__WB_MANIFEST)

// SPA: every page address opens index.html, except the API and the VPS setup scripts
registerRoute(new NavigationRoute(createHandlerBoundToURL('/index.html'), { denylist: [/^\/api/, /^\/ops\//] }))

interface PushPayload { title?: string; body?: string; url?: string | null }

/** The server sends JSON {title, body, url}; anything else is shown as plain text. */
function readPayload(data: PushMessageData | null): PushPayload {
  if (!data) return {}
  try {
    const json: unknown = data.json()
    if (json && typeof json === 'object') return json as PushPayload
    return { body: String(json) }
  } catch {
    try { return { body: data.text() } } catch { return {} }
  }
}

/** Only pages of this site are opened from a notification. */
function sameOriginUrl(url: string | null | undefined): string {
  try {
    const u = new URL(url || '/', self.location.origin)
    return u.origin === self.location.origin ? u.href : new URL('/', self.location.origin).href
  } catch {
    return new URL('/', self.location.origin).href
  }
}

// every push MUST show a notification: a silent push makes iOS (and Chrome after a few) drop the subscription
self.addEventListener('push', (event) => {
  const { title, body, url } = readPayload(event.data)
  const options: NotificationOptions & { renotify?: boolean } = {
    body: body || '',
    icon: ICON,
    badge: ICON,
    data: { url: url || '/' },
    tag: url || undefined,
    // a new message about the same page replaces the old one and still rings (renotify needs a tag)
    renotify: !!url,
    lang: 'ar',
    dir: 'auto',
  }
  event.waitUntil(self.registration.showNotification(title || APP_NAME, options))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const target = sameOriginUrl((event.notification.data as { url?: string } | null)?.url)
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    const open = windows.find((c) => new URL(c.url).origin === self.location.origin)
    if (open) {
      const focused = await open.focus().catch(() => open)
      if (focused.url !== target) {
        // navigate() works only on pages this worker controls; otherwise the page routes itself
        const moved = await focused.navigate(target).catch(() => null)
        if (!moved) focused.postMessage({ type: 'NAVIGATE', url: target })
      }
      return
    }
    await self.clients.openWindow(target)
  })())
})

// The browser renewed or dropped the subscription. The worker has no access token to tell the server, so the page
// re-syncs it on its next start (syncPushSubscription in src/lib/push.ts posts the current subscription).
self.addEventListener('pushsubscriptionchange', () => undefined)
