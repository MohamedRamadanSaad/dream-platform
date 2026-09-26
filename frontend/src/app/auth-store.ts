import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Locale, UserDto } from '@/api/types'

interface AuthState {
  accessToken: string | null
  user: UserDto | null
  locale: Locale
  theme: 'dark' | 'light'
  setSession: (token: string, user: UserDto) => void
  setUser: (user: UserDto) => void
  clear: () => void
  setLocale: (l: Locale) => void
  setTheme: (t: 'dark' | 'light') => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      user: null,
      locale: 'ar',
      theme: 'light',
      setSession: (accessToken, user) => set({ accessToken, user, locale: user.locale ?? 'ar' }),
      setUser: (user) => set({ user }),
      clear: () => set({ accessToken: null, user: null }),
      setLocale: (locale) => set({ locale }),
      setTheme: (theme) => set({ theme }),
    }),
    { name: 'saadat-auth', partialize: (s) => ({ accessToken: s.accessToken, user: s.user, locale: s.locale, theme: s.theme }) },
  ),
)

export const isInterpreter = (u: UserDto | null) => u?.role === 'INTERPRETER'
