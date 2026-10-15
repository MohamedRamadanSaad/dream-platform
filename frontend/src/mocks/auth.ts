// Mock e-mail + password accounts (sign-up, sign-in, forgot password). Mirrors the backend contract:
// codes are e-mailed (here: any 6 digits work, 000000 is "wrong"), a verified code gives a single-use ticket,
// the ticket + password creates the account or resets the password. Passwords live only in this tab's memory.
import type * as T from '@/api/types'
import { INTERPRETER_DOMAIN } from '@/lib/utils'
import { db, helpers, uid } from './data'

/** Password of the preview's demo accounts (shown in the sign-in page's test-mode hint). */
export const DEMO_PASSWORD = 'saadat2026'

const passwords = new Map<string, string>(
  db.users.filter((u) => u.hasPassword).map((u) => [u.email.toLowerCase(), DEMO_PASSWORD]),
)
const failures = new Map<string, { count: number; lockedUntil: number }>()
const tickets = new Map<string, { email: string; purpose: T.CodePurpose; expiresAt: number }>()

const MAX_FAILURES = 5
const LOCK_MS = 15 * 60_000
const TICKET_MS = 15 * 60_000

export const normEmail = (e: string | undefined | null) => (e ?? '').trim().toLowerCase()
export const findUser = (email: string) => db.users.find((u) => u.email.toLowerCase() === email)

/** Same rule as the server: 8 to 128 characters, at least one letter and one digit. */
export const strongEnough = (p: string) => p.length >= 8 && p.length <= 128 && /\p{L}/u.test(p) && /\d/.test(p)

export type AuthFail = { status: number; title: string; code: string; detail: string; extra?: Partial<T.ApiProblem> }
const fail = (status: number, title: string, code: string, detail: string, extra?: Partial<T.ApiProblem>): AuthFail => ({ status, title, code, detail, extra })

export const hasPassword = (email: string) => passwords.has(email)

/** 6-digit code check: any 6 digits are accepted except 000000 (to try the error message). */
export function checkCode(code: string | undefined): AuthFail | null {
  if (!/^\d{6}$/.test(code ?? '') || code === '000000') return fail(401, 'Unauthorized', 'MAGIC_LINK_INVALID', 'The code is not correct.')
  return null
}

export function issueTicket(email: string, purpose: T.CodePurpose): T.TicketResponse {
  const ticket = `t-${uid()}${uid()}`
  tickets.set(ticket, { email, purpose, expiresAt: Date.now() + TICKET_MS })
  return { ticket, expiresIn: TICKET_MS / 1000 }
}

/** Spends the ticket (single use); null when unknown, used, expired or for another purpose. */
export function spendTicket(ticket: string | undefined, purpose: T.CodePurpose): string | null {
  const t = ticket ? tickets.get(ticket) : undefined
  if (!t) return null
  tickets.delete(ticket!)
  if (t.purpose !== purpose || t.expiresAt < Date.now()) return null
  return t.email
}

/** New account with the same defaults as the e-mail-link sign-up (country would come from the CF header). */
export function createUser(email: string, provider: T.AuthProvider): T.UserDto {
  const user: T.UserDto = {
    id: uid(), name: '', email, gender: null, birthDate: null, age: null,
    role: email.endsWith(INTERPRETER_DOMAIN) ? 'INTERPRETER' : 'USER', providers: [provider], locale: 'ar',
    countryCode: 'SA', countryName: 'السعودية', onboarded: false, createdAt: helpers.now(), marketingOptIn: true,
    hasPassword: false, termsAccepted: false,
  }
  db.users.push(user)
  return user
}

export function setPassword(user: T.UserDto, password: string) {
  const email = user.email.toLowerCase()
  passwords.set(email, password)
  failures.delete(email)
  user.hasPassword = true
  if (!user.providers.includes('PASSWORD')) user.providers = [...user.providers, 'PASSWORD']
}

/** POST /auth/password/login: the user, or why not. */
export function passwordLogin(emailRaw: string | undefined, password: string | undefined): T.UserDto | AuthFail {
  const email = normEmail(emailRaw)
  const lock = failures.get(email)
  if (lock && lock.lockedUntil > Date.now()) {
    return fail(429, 'Too Many Requests', 'TOO_MANY_ATTEMPTS', 'Too many wrong passwords.', { retryAfterSeconds: Math.ceil((lock.lockedUntil - Date.now()) / 1000) })
  }
  const user = findUser(email)
  if (user && !passwords.has(email)) return fail(409, 'Conflict', 'PASSWORD_NOT_SET', 'This account has no password yet.')
  if (!user || passwords.get(email) !== password) {
    if (user) {
      const f = { count: (lock?.lockedUntil ?? 0) > Date.now() ? lock!.count : (lock?.count ?? 0) + 1, lockedUntil: 0 }
      if (f.count >= MAX_FAILURES) f.lockedUntil = Date.now() + LOCK_MS
      failures.set(email, f)
    }
    return fail(401, 'Unauthorized', 'INVALID_CREDENTIALS', 'The e-mail or password is not correct.')
  }
  failures.delete(email)
  return user
}

export const isFail = (x: unknown): x is AuthFail => !!x && typeof x === 'object' && 'code' in x && 'status' in x && !('id' in x)
