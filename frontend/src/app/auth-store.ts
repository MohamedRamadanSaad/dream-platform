import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Locale, UserDto } from '@/api/types'

interface AuthState {
  accessToken: string | null
  user: UserDto | null
  /**
   * false = signed in without "Keep me signed in on this device": the server's refresh cookie lasts only as long as
   * the browser, so the session cached here must not outlive it either (see restoreSession in ./session).
   */
  remember: boolean
  locale: Locale
  theme: 'dark' | 'light'
  /** `remember` is given on sign-in; a refresh leaves the session's mode as it is. */
  setSession: (token: string, user: UserDto, remember?: boolean) => void
  setUser: (user: UserDto) => void
  clear: () => void
  setLocale: (l: Locale) => void
  setTheme: (t: 'dark' | 'light') => void
}

/** Marks this tab as part of the current browser session (sessionStorage dies with the browser). */
const TAB_KEY = 'saadat-session-tab'
export function markSessionTab() {
  try { sessionStorage.setItem(TAB_KEY, '1') } catch { /* storage blocked: the next start checks with the server */ }
}
export function isSessionTab() {
  try { return sessionStorage.getItem(TAB_KEY) === '1' } catch { return false }
}
export function forgetSessionTab() {
  try { sessionStorage.removeItem(TAB_KEY) } catch { /* nothing to forget */ }
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      user: null,
      remember: true,
      locale: 'ar',
      theme: 'light',
      setSession: (accessToken, user, remember) => {
        if (remember === false) markSessionTab()
        set((s) => ({ accessToken, user, locale: user.locale ?? 'ar', remember: remember ?? s.remember }))
      },
      setUser: (user) => set({ user }),
      clear: () => set({ accessToken: null, user: null, remember: true }),
      setLocale: (locale) => set({ locale }),
      setTheme: (theme) => set({ theme }),
    }),
    { name: 'saadat-auth', partialize: (s) => ({ accessToken: s.accessToken, user: s.user, remember: s.remember, locale: s.locale, theme: s.theme }) },
  ),
)

export const isInterpreter = (u: UserDto | null) => u?.role === 'INTERPRETER'
