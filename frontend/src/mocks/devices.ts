// Mock "devices" = the caller's active sign-in sessions (refresh-token families), as GET /me/devices returns them.
import type * as T from '@/api/types'
import { db, helpers, uid } from './data'

interface MockDevice {
  id: string
  userId: string
  browser: string
  os: string
  deviceType: T.DeviceType
  countryCode: string | null
  signedInAt: string
  lastActiveAt: string
  persistent: boolean
}

const hoursAgo = (h: number) => new Date(Date.now() - h * 36e5).toISOString()

/** Two other sessions per account, so the list, the per-device sign-out and "sign out all others" can be tried. */
const OTHERS: Record<T.Role, Omit<MockDevice, 'id' | 'userId'>[]> = {
  USER: [
    { browser: 'Chrome', os: 'Android', deviceType: 'MOBILE', countryCode: 'SA', signedInAt: helpers.daysAgo(12), lastActiveAt: helpers.daysAgo(1, 3), persistent: true },
    // not remembered: such a session ends 12 hours after its last use, so it was used today
    { browser: 'Edge', os: 'Windows', deviceType: 'DESKTOP', countryCode: 'AE', signedInAt: hoursAgo(5), lastActiveAt: hoursAgo(3), persistent: false },
  ],
  INTERPRETER: [
    { browser: 'Safari', os: 'iPadOS', deviceType: 'TABLET', countryCode: 'EG', signedInAt: helpers.daysAgo(30), lastActiveAt: hoursAgo(4), persistent: true },
    { browser: 'Firefox', os: 'Windows', deviceType: 'DESKTOP', countryCode: 'EG', signedInAt: hoursAgo(7), lastActiveAt: hoursAgo(1), persistent: false },
  ],
}

const devices: MockDevice[] = []
const seeded = new Set<string>()
/** userId → the session of this browser (the mock has no `sid` claim: one browser, one current session). */
const currentOf: Record<string, string> = {}

/** Browser, OS and device type from a User-Agent — the same small rules as the backend parser. */
export function parseUserAgent(ua: string): Pick<MockDevice, 'browser' | 'os' | 'deviceType'> {
  const browser = /SamsungBrowser/i.test(ua) ? 'Samsung Internet'
    : /OPR\/|Opera/i.test(ua) ? 'Opera'
    : /Edg(e|A|iOS)?\//i.test(ua) ? 'Edge'
    : /Firefox|FxiOS/i.test(ua) ? 'Firefox'
    : /Chrome|CriOS|Chromium/i.test(ua) ? 'Chrome'
    : /Safari/i.test(ua) ? 'Safari'
    : 'Other'
  const os = /iPad/i.test(ua) ? 'iPadOS'
    : /iPhone|iPod/i.test(ua) ? 'iOS'
    : /Android/i.test(ua) ? 'Android'
    : /Windows/i.test(ua) ? 'Windows'
    : /Mac OS X|Macintosh/i.test(ua) ? 'macOS'
    : /Linux|X11|CrOS/i.test(ua) ? 'Linux'
    : 'Other'
  const deviceType: T.DeviceType = /iPad|Tablet/i.test(ua) || (/Android/i.test(ua) && !/Mobile/i.test(ua)) ? 'TABLET'
    : /Mobi|iPhone|iPod|Android/i.test(ua) ? 'MOBILE'
    : 'DESKTOP'
  return { browser, os, deviceType }
}

function seedOthers(user: T.UserDto) {
  if (seeded.has(user.id)) return
  seeded.add(user.id)
  for (const d of OTHERS[user.role]) devices.push({ ...d, id: uid(), userId: user.id })
}

function addCurrent(user: T.UserDto, ua: string, persistent: boolean, signedInAt: string) {
  const id = uid()
  devices.push({ id, userId: user.id, ...parseUserAgent(ua), countryCode: user.countryCode, signedInAt, lastActiveAt: helpers.now(), persistent })
  currentOf[user.id] = id
}

/** A sign-in (Google or e-mail code) starts a new session for this browser; the old one of this browser ends. */
export function startSession(user: T.UserDto, ua: string, rememberMe = true) {
  seedOthers(user)
  endSession(user.id)
  addCurrent(user, ua, rememberMe, helpers.now())
}

/** POST /auth/logout: the session of this browser ends. */
export function endSession(userId: string) {
  const id = currentOf[userId]
  if (!id) return
  delete currentOf[userId]
  const i = devices.findIndex((d) => d.id === id)
  if (i >= 0) devices.splice(i, 1)
}

/** GET /me/devices — most recently active first; country names in the request's language. */
export function listDevices(user: T.UserDto, ua: string, locale: string): T.DeviceDto[] {
  seedOthers(user)
  // a page reload restarts the mock: the browser is still signed in (signed in a few days ago, remembered)
  if (!currentOf[user.id]) addCurrent(user, ua, true, helpers.daysAgo(3))
  const mine = devices.filter((d) => d.userId === user.id)
  const current = mine.find((d) => d.id === currentOf[user.id])
  if (current) current.lastActiveAt = helpers.now()
  const en = locale.startsWith('en')
  return mine
    .sort((a, b) => b.lastActiveAt.localeCompare(a.lastActiveAt))
    .map(({ userId: _owner, ...d }) => {
      const c = db.countries.find((x) => x.code === d.countryCode)
      return { ...d, countryName: c ? (en ? c.nameEn : c.nameAr) : d.countryCode, current: d.id === currentOf[user.id] }
    })
}

/** DELETE /me/devices/{id} — false when it is not one of the caller's sessions (404). */
export function removeDevice(userId: string, id: string): boolean {
  const i = devices.findIndex((d) => d.id === id && d.userId === userId)
  if (i < 0) return false
  devices.splice(i, 1)
  if (currentOf[userId] === id) delete currentOf[userId]
  return true
}

/** POST /me/devices/sign-out-others — every session except this browser's. */
export function removeOtherDevices(userId: string) {
  for (let i = devices.length - 1; i >= 0; i--) {
    if (devices[i].userId === userId && devices[i].id !== currentOf[userId]) devices.splice(i, 1)
  }
}
