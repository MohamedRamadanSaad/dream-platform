// Web Push on this browser: support checks, subscribe (after the user taps), start-up re-sync and sign-out cleanup.
import { notificationsApi, publicApi } from '@/api/endpoints'
import { fromBase64url, toBase64url } from './base64url'

/** Build-time key, used only when the server gives none (GET /public/push-key is the source). */
const ENV_PUSH_KEY = ((import.meta.env.VITE_VAPID_PUBLIC_KEY as string | undefined) ?? '').trim()

/** The browser's push service can stall (no network to it): give up instead of spinning forever. */
const PUSH_WAIT_MS = 20_000
const inTime = <T,>(p: Promise<T>) =>
  Promise.race([p, new Promise<never>((_, reject) => window.setTimeout(() => reject(new Error('push: no answer')), PUSH_WAIT_MS))])

export function pushSupported(): boolean {
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

/** iPhone, iPod and iPad (iPadOS presents itself as a Mac, but a Mac has no touch screen). */
export function isIos(): boolean {
  if (typeof navigator === 'undefined') return false
  return /iphone|ipad|ipod/i.test(navigator.userAgent) || (/macintosh/i.test(navigator.userAgent) && navigator.maxTouchPoints > 1)
}

/** Opened from the home-screen icon (installed app), where iOS allows push. */
export function isStandalone(): boolean {
  if (typeof window === 'undefined') return false
  return window.matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true
}

export function pushPermission(): NotificationPermission | 'unsupported' {
  return pushSupported() ? Notification.permission : 'unsupported'
}

/** The server's Web Push key, '' when push is not set up on the server. */
export async function fetchPushKey(): Promise<string> {
  const k = await publicApi.pushKey().catch(() => null)
  return k?.publicKey?.trim() || ENV_PUSH_KEY
}

/**
 * This browser's subscription for `key`, created when missing. A subscription made with another key (an old
 * build-time key, or keys regenerated on the server) cannot be used: it is replaced.
 */
async function currentSubscription(key: string): Promise<PushSubscription> {
  const reg = await inTime(navigator.serviceWorker.ready)
  const serverKey = fromBase64url(key)
  let sub = await reg.pushManager.getSubscription()
  const own = sub?.options.applicationServerKey
  if (sub && (!own || toBase64url(own) !== toBase64url(serverKey))) {
    await sub.unsubscribe().catch(() => undefined)
    sub = null
  }
  sub ??= await inTime(reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: serverKey }))
  return sub
}

/** Saves the subscription on the account signed in now (the server moves an endpoint to its latest account). */
async function register(sub: PushSubscription) {
  const j = sub.toJSON()
  if (!j.keys?.p256dh || !j.keys.auth) throw new Error('push: subscription without keys')
  await notificationsApi.subscribePush({ endpoint: sub.endpoint, keys: { p256dh: j.keys.p256dh, auth: j.keys.auth }, userAgent: navigator.userAgent })
}

export type SubscribeResult = 'granted' | 'denied' | 'unsupported' | 'no-key' | 'failed'

/** After a tap on "Turn on notifications" (or "Re-connect"): asks permission if needed, subscribes, tells the server. */
export async function subscribePush(): Promise<SubscribeResult> {
  if (!pushSupported()) return 'unsupported'
  try {
    const perm = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission()
    if (perm !== 'granted') return perm === 'denied' ? 'denied' : 'failed'
    const key = await fetchPushKey()
    if (!key) return 'no-key'
    await register(await currentSubscription(key))
    return 'granted'
  } catch {
    return 'failed'
  }
}

/**
 * Start-up / sign-in re-sync: when notifications are already allowed, makes sure this browser has a live
 * subscription for the server's key and that the server has it on the signed-in account. Never asks for permission
 * and never throws. A subscription the browser or the push service dropped (iOS does that) is created again here.
 */
export async function syncPushSubscription(): Promise<void> {
  if (!pushSupported() || Notification.permission !== 'granted') return
  try {
    const key = await fetchPushKey()
    if (!key) return
    await register(await currentSubscription(key))
  } catch {
    /* best effort: the account page offers "Re-connect" */
  }
}

/** Sign-out: the server stops sending this account's notifications to this browser. The browser keeps its subscription. */
export async function unsubscribePush(): Promise<void> {
  if (!pushSupported()) return
  try {
    const reg = await navigator.serviceWorker.getRegistration()
    const sub = await reg?.pushManager.getSubscription()
    if (sub) await notificationsApi.unsubscribePush(sub.endpoint)
  } catch {
    /* best effort */
  }
}
