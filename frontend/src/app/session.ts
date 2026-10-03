// Session lifecycle on this browser: the "Keep me signed in" choice, start-up check and sign-out.
import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/api/endpoints'
import { refreshSession } from '@/api/client'
import { forgetSessionTab, isSessionTab, markSessionTab, useAuthStore } from './auth-store'

const REMEMBER_KEY = 'saadat-remember-me'

/**
 * The last "Keep me signed in on this device" choice on this browser. The magic-link page reads it, because the
 * link opens in a new page after the choice was made. true when never chosen or when storage is blocked.
 */
export function readRememberMe(): boolean {
  try { return localStorage.getItem(REMEMBER_KEY) !== '0' } catch { return true }
}
export function saveRememberMe(on: boolean) {
  try { localStorage.setItem(REMEMBER_KEY, on ? '1' : '0') } catch { /* private mode: the default (true) applies */ }
}

const LAST_EMAIL_KEY = 'saadat-last-email'

/**
 * The e-mail of the last account signed in on this browser WITH "Keep me signed in": the e-mail sign-in field starts
 * filled with it, so a returning visitor does not type it again. Kept only in this browser (never sent anywhere);
 * a sign-in without "Keep me signed in" (shared device) forgets it, and so does "Not you?" or deleting the account.
 */
export function readLastEmail(): string {
  try { return localStorage.getItem(LAST_EMAIL_KEY) ?? '' } catch { return '' }
}
export function rememberLastEmail(email: string | null | undefined, keep: boolean) {
  try {
    if (keep && email) localStorage.setItem(LAST_EMAIL_KEY, email.trim().toLowerCase())
    else localStorage.removeItem(LAST_EMAIL_KEY)
  } catch { /* storage blocked: nothing is remembered */ }
}
export function forgetLastEmail() {
  try { localStorage.removeItem(LAST_EMAIL_KEY) } catch { /* storage blocked */ }
}

/**
 * Start-up check for a session signed in WITHOUT "Keep me signed in". Its refresh cookie dies with the browser, but
 * the user and access token cached in localStorage would survive it. A tab that is not part of the current browser
 * session (a new tab, or the browser was closed) keeps the cached session only if the cookie still refreshes it, so
 * the next person on a shared device starts signed out.
 */
export async function restoreSession() {
  const s = useAuthStore.getState()
  if (!s.user || s.remember || isSessionTab()) return
  if (await refreshSession()) markSessionTab()
  else useAuthStore.getState().clear()
}

const SIGN_OUT_WAIT_MS = 4000

/**
 * Sign-out for every button: ends the session on the server (POST /auth/logout, errors ignored), then forgets it
 * here (auth store + every cached query) and goes home. `server: false` skips the call (e.g. the account is gone).
 */
export function useSignOut() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [pending, setPending] = useState(false)
  const signOut = useCallback(async ({ server = true }: { server?: boolean } = {}) => {
    setPending(true)
    if (server) {
      // never keep someone waiting on a slow network: the local sign-out happens anyway
      await Promise.race([authApi.logout().catch(() => undefined), new Promise((r) => window.setTimeout(r, SIGN_OUT_WAIT_MS))])
    }
    // nothing more is sent with the old token
    useAuthStore.setState({ accessToken: null })
    // leave the protected page while the user is still known: once the store is empty, its guard would
    // send us to the login page instead of home
    navigate('/', { replace: true, flushSync: true })
    // the router keeps the scroll position: the home page starts at its top
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' })
    useAuthStore.getState().clear()
    qc.clear()
    forgetSessionTab()
  }, [navigate, qc])
  return { signOut, pending }
}
