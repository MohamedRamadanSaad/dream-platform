import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { publicApi } from '@/api/endpoints'

// Page-view tracking (docs/ANALYTICS_REPORTS_CONTRACT.md §1): one fire-and-forget POST /public/track per
// pathname change. "Visitors" are counted by sessionId, a random id kept in sessionStorage.

const SESSION_KEY = 'saadat-sid'
let memoryId: string | null = null

function randomId() {
  try {
    if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  } catch { /* insecure context */ }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

/** The session id, and whether this is the session's first hit (storage may be blocked: fall back to memory). */
function session(): { id: string; first: boolean } {
  try {
    const stored = sessionStorage.getItem(SESSION_KEY)
    if (stored) return { id: stored, first: false }
  } catch { /* storage blocked */ }
  if (memoryId) return { id: memoryId, first: false }
  memoryId = randomId()
  try { sessionStorage.setItem(SESSION_KEY, memoryId) } catch { /* storage blocked */ }
  return { id: memoryId, first: true }
}

export function trackPageView(pathname: string) {
  try {
    if (pathname.startsWith('/admin')) return // the interpreter's own pages are never counted
    const { id, first } = session()
    publicApi.track({ path: pathname.slice(0, 255), referrer: first ? document.referrer || null : null, sessionId: id })
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
