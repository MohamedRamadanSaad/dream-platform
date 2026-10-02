// Browser, OS and device type from a User-Agent — the same small rules as the backend's parser (devices, passkey labels).
import type { DeviceType } from '@/api/types'

export interface UserAgentInfo {
  /** "Chrome", "Safari", "Edge", "Firefox", "Samsung Internet", "Opera", "Other". */
  browser: string
  /** "Windows", "macOS", "iOS", "iPadOS", "Android", "Linux", "Other". */
  os: string
  deviceType: DeviceType
}

export function parseUserAgent(ua: string): UserAgentInfo {
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
  const deviceType: DeviceType = /iPad|Tablet/i.test(ua) || (/Android/i.test(ua) && !/Mobile/i.test(ua)) ? 'TABLET'
    : /Mobi|iPhone|iPod|Android/i.test(ua) ? 'MOBILE'
    : 'DESKTOP'
  return { browser, os, deviceType }
}

/**
 * This browser, as the page sees it. iPadOS Safari presents itself as a Mac; a touch screen gives it away
 * (only the page can tell — the server reads "macOS").
 */
export function thisBrowser(): UserAgentInfo {
  const info = parseUserAgent(navigator.userAgent)
  if (info.os === 'macOS' && navigator.maxTouchPoints > 1) return { ...info, os: 'iPadOS', deviceType: 'TABLET' }
  return info
}
