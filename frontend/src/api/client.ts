import type { ApiProblem } from './types'
import { useAuthStore } from '@/app/auth-store'

export const API_URL = (import.meta.env.VITE_API_URL as string | undefined) ?? ''

export class ApiError extends Error {
  status: number
  problem: ApiProblem
  constructor(problem: ApiProblem) {
    super(problem.detail ?? problem.title)
    this.status = problem.status
    this.problem = problem
  }
}

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
export type Query = Record<string, string | number | boolean | null | undefined>

interface RequestOptions {
  body?: unknown
  query?: Query
  signal?: AbortSignal
  retryOn401?: boolean
}

const baseUrl = () => (API_URL.startsWith('http') ? API_URL : window.location.origin + API_URL).replace(/\/$/, '')

/** Absolute API URL; empty / null / undefined query values are left out. */
export function apiUrl(path: string, query?: Query) {
  const url = new URL(baseUrl() + path)
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v))
    }
  }
  return url.toString()
}

let refreshing: Promise<boolean> | null = null

async function refreshToken(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await fetch(`${baseUrl()}/auth/refresh`, { method: 'POST', credentials: 'include' })
        if (!res.ok) return false
        const data = await res.json()
        useAuthStore.getState().setSession(data.accessToken, data.user)
        return true
      } catch {
        return false
      } finally {
        refreshing = null
      }
    })()
  }
  return refreshing
}

/** One round-trip with the Bearer token and Accept-Language; on 401 refreshes once and retries, else signs out. */
async function send(method: Method, path: string, opts: RequestOptions, accept: string): Promise<Response> {
  const token = useAuthStore.getState().accessToken
  const headers: Record<string, string> = { Accept: accept }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  headers['Accept-Language'] = useAuthStore.getState().locale

  const res = await fetch(apiUrl(path, opts.query), {
    method,
    headers,
    credentials: 'include',
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    signal: opts.signal,
  })

  if (res.status === 401 && opts.retryOn401 !== false && token) {
    const ok = await refreshToken()
    if (ok) return send(method, path, { ...opts, retryOn401: false }, accept)
    useAuthStore.getState().clear()
  }
  return res
}

async function problemOf(res: Response): Promise<ApiProblem> {
  const isJson = res.headers.get('content-type')?.includes('json')
  const data = isJson ? await res.json().catch(() => null) : null
  return data ?? { type: 'about:blank', title: res.statusText, status: res.status }
}

export async function request<T>(method: Method, path: string, opts: RequestOptions = {}): Promise<T> {
  const res = await send(method, path, opts, 'application/json')
  if (res.status === 204) return undefined as T
  if (!res.ok) throw new ApiError(await problemOf(res))
  const isJson = res.headers.get('content-type')?.includes('json')
  return (isJson ? await res.json() : null) as T
}

/** GET that returns the body as text (e.g. the text/html e-mail preview); errors still throw ApiError. */
export async function getText(path: string, query?: Query, accept = 'text/html, application/problem+json'): Promise<string> {
  const res = await send('GET', path, { query }, accept)
  if (!res.ok) throw new ApiError(await problemOf(res))
  return res.text()
}

export const http = {
  get: <T>(path: string, query?: Query) => request<T>('GET', path, { query }),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, { body }),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, { body }),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, { body }),
  delete: <T>(path: string) => request<T>('DELETE', path),
}

/** File name from Content-Disposition: RFC 5987 `filename*=UTF-8''…` first, then plain `filename=`. */
export function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null
  const star = /filename\*\s*=\s*([\w-]*)'[^']*'([^;]+)/i.exec(header)
  if (star) {
    try {
      const name = decodeURIComponent(star[2].trim().replace(/^"(.*)"$/, '$1'))
      if (name) return name
    } catch { /* malformed escape: fall back to filename= */ }
  }
  const plain = /filename\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;]+))/i.exec(header)
  const name = plain ? (plain[1] !== undefined ? plain[1].replace(/\\(.)/g, '$1') : plain[2].trim()) : ''
  return name || null
}

const EXTENSIONS: Record<string, string> = {
  'application/pdf': 'pdf',
  'text/csv': 'csv',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
}
function fallbackName(path: string, mime: string) {
  const base = path.split('/').filter((s) => s && !['admin', 'pdf', 'export'].includes(s)).join('-') || 'download'
  const ext = EXTENSIONS[mime.split(';')[0].trim().toLowerCase()]
  return ext ? `${base}.${ext}` : base
}

/**
 * Downloads a binary report (PDF / Excel) with the same auth flow as `request` and saves it under the
 * server's file name. Throws ApiError when the server answers with an error.
 */
export async function download(path: string, query?: Query): Promise<void> {
  const res = await send('GET', path, { query }, '*/*')
  if (!res.ok) throw new ApiError(await problemOf(res))
  const blob = await res.blob()
  const name = filenameFromDisposition(res.headers.get('content-disposition')) ?? fallbackName(path, blob.type)
  const href = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = href
  a.download = name
  a.rel = 'noopener'
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  a.remove()
  // revoke on the next turn — some browsers read the blob after click() returns
  window.setTimeout(() => URL.revokeObjectURL(href), 1500)
}

/** Fire-and-forget POST for analytics: no refresh flow, no retries, never throws, never signs anyone out. */
export function postQuietly(path: string, body: unknown): void {
  try {
    const token = useAuthStore.getState().accessToken
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (token) headers.Authorization = `Bearer ${token}`
    fetch(apiUrl(path), { method: 'POST', headers, body: JSON.stringify(body) }).catch(() => undefined)
  } catch {
    /* tracking must never break the page */
  }
}
