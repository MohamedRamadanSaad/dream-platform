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

interface RequestOptions {
  body?: unknown
  query?: Record<string, string | number | boolean | undefined>
  signal?: AbortSignal
  retryOn401?: boolean
}

let refreshing: Promise<boolean> | null = null

async function refreshToken(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await fetch(`${API_URL}/auth/refresh`, { method: 'POST', credentials: 'include' })
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

export async function request<T>(method: Method, path: string, opts: RequestOptions = {}): Promise<T> {
  const url = new URL(path, API_URL || window.location.origin)
  if (opts.query) {
    for (const [k, v] of Object.entries(opts.query)) {
      if (v !== undefined && v !== '') url.searchParams.set(k, String(v))
    }
  }
  const token = useAuthStore.getState().accessToken
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  headers['Accept-Language'] = useAuthStore.getState().locale

  const res = await fetch(url.toString(), {
    method,
    headers,
    credentials: 'include',
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    signal: opts.signal,
  })

  if (res.status === 401 && opts.retryOn401 !== false && token) {
    const ok = await refreshToken()
    if (ok) return request<T>(method, path, { ...opts, retryOn401: false })
    useAuthStore.getState().clear()
  }

  if (res.status === 204) return undefined as T

  const isJson = res.headers.get('content-type')?.includes('json')
  const data = isJson ? await res.json() : null
  if (!res.ok) {
    const problem: ApiProblem = data ?? { type: 'about:blank', title: res.statusText, status: res.status }
    throw new ApiError(problem)
  }
  return data as T
}

export const http = {
  get: <T>(path: string, query?: RequestOptions['query']) => request<T>('GET', path, { query }),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, { body }),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, { body }),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, { body }),
  delete: <T>(path: string) => request<T>('DELETE', path),
}
