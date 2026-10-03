import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { publicApi } from '@/api/endpoints'
import { session, visitorId } from '@/lib/visit'

// Page-view tracking: one fire-and-forget POST /public/track per pathname change, with the visit and visitor ids
// from '@/lib/visit'.

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
