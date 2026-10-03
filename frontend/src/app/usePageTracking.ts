import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { publicApi } from '@/api/endpoints'

// Page-view tracking (docs/ANALYTICS_REPORTS_CONTRACT.md §1): one fire-and-forget POST /public/track per
// pathname change, carrying two random ids kept in first-party cookies:
//  - visitorId: one per browser, kept for a year, so a person coming back is still one visitor;
//  - sessionId: one per visit, ends after 30 minutes without a page view, so coming back later is a new visit.
// The visitor id is mirrored in localStorage, so clearing only the cookies does not create a new visitor.

const VISITOR_COOKIE = 'saadat_vid'
const SESSION_COOKIE = 'saadat_sid'
const VISITOR_STORAGE = 'saadat-vid'
const VISITOR_MAX_AGE = 60 * 60 * 24 * 365 // one year, in seconds
const SESSION_MAX_AGE = 30 * 60 // a visit ends after 30 minutes without a page view
const ID_PATTERN = /^[A-Za-z0-9-]{8,100}$/

const memory: { visitor: string | null; session: string | null; sessionAt: number } = { visitor: null, session: null, sessionAt: 0 }

function randomId() {
  try {
    if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  } catch { /* insecure context */ }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

function readCookie(name: string): string | null {
  try {
    const hit = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`))
    const value = hit ? decodeURIComponent(hit.slice(name.length + 1)) : null
    return value && ID_PATTERN.test(value) ? value : null
  } catch {
    return null
  }
}

function writeCookie(name: string, value: string, maxAge: number) {
  try {
    const secure = location.protocol === 'https:' ? '; Secure' : ''
    document.cookie = `${name}=${encodeURIComponent(value)}; Max-Age=${maxAge}; Path=/; SameSite=Lax${secure}`
  } catch { /* cookies blocked */ }
}

/** The browser's id: cookie, then localStorage, then a new one. Every hit renews it for another year. */
function visitorId(): string {
  let id = readCookie(VISITOR_COOKIE)
  if (!id) {
    try {
      const stored = localStorage.getItem(VISITOR_STORAGE)
      if (stored && ID_PATTERN.test(stored)) id = stored
    } catch { /* storage blocked */ }
  }
  id = id ?? memory.visitor ?? randomId()
  memory.visitor = id
  writeCookie(VISITOR_COOKIE, id, VISITOR_MAX_AGE)
  try { localStorage.setItem(VISITOR_STORAGE, id) } catch { /* storage blocked */ }
  return id
}

/** The visit's id, and whether this hit starts the visit. Every hit pushes the 30-minute end further. */
function session(): { id: string; first: boolean } {
  const now = Date.now()
  let id = readCookie(SESSION_COOKIE)
  if (!id && memory.session && now - memory.sessionAt < SESSION_MAX_AGE * 1000) id = memory.session // cookies blocked
  const first = !id
  id = id ?? randomId()
  memory.session = id
  memory.sessionAt = now
  writeCookie(SESSION_COOKIE, id, SESSION_MAX_AGE)
  return { id, first }
}

export function trackPageView(pathname: string) {
  try {
    if (pathname.startsWith('/admin')) return // the interpreter's own pages are never counted
    const { id, first } = session()
    publicApi.track({
      path: pathname.slice(0, 255),
      referrer: first ? document.referrer || null : null,
      sessionId: id,
      visitorId: visitorId(),
    })
  } catch {
    /* never let analytics break navigation */
  }
}

/** Mounted once in the root route: sends a hit on every pathname change (not on query-string changes). */
export function usePageTracking() {
  const { pathname } = useLocation()
  const last = useRef<string | null>(null)
  useEffect(() => {
    if (last.current === pathname) return // StrictMode re-runs effects; one hit per change
    last.current = pathname
    trackPageView(pathname)
  }, [pathname])
}
