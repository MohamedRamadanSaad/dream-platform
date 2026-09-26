// Typed endpoint functions — one per route in the contract. Screens import from here only.
import { http } from './client'
import type * as T from './types'

export const authApi = {
  google: (body: T.GoogleLoginRequest) => http.post<T.AuthResponse>('/auth/google', body),
  magicRequest: (body: T.MagicRequest) => http.post<void>('/auth/magic/request', body),
  magicVerify: (body: T.MagicVerifyRequest) => http.post<T.AuthResponse>('/auth/magic/verify', body),
  refresh: () => http.post<T.AuthResponse>('/auth/refresh'),
  logout: () => http.post<void>('/auth/logout'),
  onboarding: (body: T.OnboardingRequest) => http.post<T.UserDto>('/auth/onboarding', body),
}

export const publicApi = {
  catalog: () => http.get<T.Catalog>('/public/catalog'),
  waitTime: () => http.get<T.WaitTime>('/public/wait-time'),
  testimonials: () => http.get<{ items: T.Testimonial[] }>('/public/testimonials'),
  stats: () => http.get<T.PublicStats>('/public/stats'),
}

export const meApi = {
  get: () => http.get<T.UserDto>('/me'),
  dashboard: () => http.get<T.DashboardSummary>('/me/dashboard'),
  preferences: (body: T.PreferencesRequest) => http.put<T.UserDto>('/me/preferences', body),
  credits: () => http.get<T.CreditsSummary>('/me/credits'),
  orders: (page = 0, size = 20) => http.get<T.Page<T.OrderDto>>('/me/orders', { page, size }),
  deleteAccount: () => http.delete<void>('/me'),
}

export const dreamsApi = {
  list: (status?: T.DreamStatus | 'ALL', page = 0, size = 20) =>
    http.get<T.Page<T.DreamSummary>>('/dreams', { status: status === 'ALL' ? undefined : status, page, size }),
  get: (id: string) => http.get<T.DreamDetail>(`/dreams/${id}`),
  createDraft: (body: T.DreamDraftRequest) => http.post<T.DreamDetail>('/dreams', body),
  updateDraft: (id: string, body: T.DreamDraftRequest) => http.put<T.DreamDetail>(`/dreams/${id}`, body),
  deleteDraft: (id: string) => http.delete<void>(`/dreams/${id}`),
  submit: (body: T.SubmitDreamsRequest) => http.post<T.SubmitDreamsResponse>('/dreams/submit', body),
  reply: (id: string, body: string) => http.post<T.DreamMessage>(`/dreams/${id}/messages`, { body }),
  testimonial: (id: string, body: T.TestimonialRequest) => http.post<void>(`/dreams/${id}/testimonial`, body),
}

export const checkoutApi = {
  create: (body: T.CheckoutRequest) => http.post<T.CheckoutResponse>('/checkout', body),
  order: (id: string) => http.get<T.OrderDto>(`/me/orders/${id}`),
}

export const notificationsApi = {
  list: (page = 0, size = 20) => http.get<T.Page<T.NotificationDto>>('/notifications', { page, size }),
  readAll: () => http.post<void>('/notifications/read-all'),
  read: (id: string) => http.post<void>(`/notifications/${id}/read`),
  subscribePush: (body: T.PushSubscriptionRequest) => http.post<void>('/push/subscriptions', body),
  unsubscribePush: (endpoint: string) => http.delete<void>(`/push/subscriptions?endpoint=${encodeURIComponent(endpoint)}`),
}

export const adminApi = {
  summary: () => http.get<T.AdminSummary>('/admin/analytics/summary'),
  countries: (period: T.AdminCountryDashboard['period']) =>
    http.get<T.AdminCountryDashboard>('/admin/analytics/countries', { period }),
  dreams: (status: T.DreamStatus | 'ALL', page = 0, size = 20) =>
    http.get<T.Page<T.AdminDreamRow>>('/admin/dreams', { status: status === 'ALL' ? undefined : status, page, size }),
  dream: (id: string) => http.get<T.AdminDreamDetail>(`/admin/dreams/${id}`),
  ask: (id: string, body: string) => http.post<T.DreamMessage>(`/admin/dreams/${id}/messages`, { body }),
  interpret: (id: string, body: T.InterpretationRequest) => http.post<void>(`/admin/dreams/${id}/interpretation`, body),
  cancel: (id: string, reason: string) => http.post<void>(`/admin/dreams/${id}/cancel`, { reason }),

  waitTime: () => http.get<T.WaitTimeSettings>('/admin/wait-time'),
  saveWaitTime: (body: T.WaitTimeSettings) => http.put<T.WaitTimeSettings>('/admin/wait-time', body),

  packages: () => http.get<T.AdminPackage[]>('/admin/packages'),
  savePackage: (p: Partial<T.AdminPackage> & { id?: string }) =>
    p.id ? http.put<T.AdminPackage>(`/admin/packages/${p.id}`, p) : http.post<T.AdminPackage>('/admin/packages', p),
  deletePackage: (id: string) => http.delete<void>(`/admin/packages/${id}`),

  countries_list: () => http.get<T.CountryDto[]>('/admin/countries'),
  groups: () => http.get<T.CountryGroup[]>('/admin/country-groups'),
  saveGroup: (g: Partial<T.CountryGroup> & { id?: string }) =>
    g.id ? http.put<T.CountryGroup>(`/admin/country-groups/${g.id}`, g) : http.post<T.CountryGroup>('/admin/country-groups', g),
  deleteGroup: (id: string) => http.delete<void>(`/admin/country-groups/${id}`),
  priceRules: () => http.get<T.PriceRule[]>('/admin/price-rules'),
  savePriceRule: (r: Omit<T.PriceRule, 'id'> & { id?: string }) =>
    r.id ? http.put<T.PriceRule>(`/admin/price-rules/${r.id}`, r) : http.post<T.PriceRule>('/admin/price-rules', r),
  deletePriceRule: (id: string) => http.delete<void>(`/admin/price-rules/${id}`),

  promotions: () => http.get<T.PromotionDto[]>('/admin/promotions'),
  savePromotion: (p: Partial<T.PromotionDto> & { id?: string }) =>
    p.id ? http.put<T.PromotionDto>(`/admin/promotions/${p.id}`, p) : http.post<T.PromotionDto>('/admin/promotions', p),
  deletePromotion: (id: string) => http.delete<void>(`/admin/promotions/${id}`),
  coupons: () => http.get<T.CouponDto[]>('/admin/coupons'),
  saveCoupon: (c: Partial<T.CouponDto> & { id?: string }) =>
    c.id ? http.put<T.CouponDto>(`/admin/coupons/${c.id}`, c) : http.post<T.CouponDto>('/admin/coupons', c),
  deleteCoupon: (id: string) => http.delete<void>(`/admin/coupons/${id}`),

  users: (list: T.UserListKind, page = 0, size = 20, q = '') =>
    http.get<T.Page<T.AdminUserRow>>('/admin/analytics/users', { list, page, size, q }),
  user: (id: string) => http.get<T.AdminUser360>(`/admin/users/${id}`),
  saveUserNotes: (id: string, notes: string, tags: string[]) => http.put<void>(`/admin/users/${id}/notes`, { notes, tags }),
  adjustCredits: (id: string, delta: number, reason: string) => http.post<void>(`/admin/users/${id}/credits`, { delta, reason }),

  testimonials: (approved?: boolean) => http.get<T.Page<T.AdminTestimonialRow>>('/admin/testimonials', { approved }),
  setTestimonial: (id: string, approved: boolean) => http.patch<void>(`/admin/testimonials/${id}`, { approved }),
  orders: (page = 0, size = 20) => http.get<T.Page<T.OrderDto & { userName: string }>>('/admin/orders', { page, size }),
}
