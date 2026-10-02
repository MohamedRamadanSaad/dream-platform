// Single source of truth for the API contract. Mirrors docs/BUILD_PACK.md and docs/SPEC_v2.md.

export type DreamStatus = 'DRAFT' | 'IN_REVIEW' | 'AWAITING_USER_REPLY' | 'INTERPRETED' | 'CANCELLED'
export type OrderStatus = 'INITIATED' | 'SUCCESS' | 'FAILED' | 'EXPIRED' | 'REFUNDED' | 'SUSPICIOUS'
export type Role = 'USER' | 'INTERPRETER'
export type AuthProvider = 'GOOGLE' | 'MAGIC_LINK'
export type Gender = 'FEMALE' | 'MALE'
export type Currency = 'EGP' | 'SAR' | 'USD'
export type Locale = 'ar' | 'en'
export type Continent = 'AF' | 'AS' | 'EU' | 'NA' | 'SA' | 'OC'
export type PriceScope = 'GLOBAL' | 'CONTINENT' | 'GROUP' | 'COUNTRY'
export type NotificationType =
  | 'DREAM_SUBMITTED'
  | 'DREAM_RECEIVED'
  | 'INTERPRETER_QUESTION'
  | 'USER_REPLIED'
  | 'INTERPRETATION_READY'
  | 'PAYMENT_SUCCESS'
  | 'PROMOTION'
  | 'YOUTUBE_VIDEO'

export interface ApiProblem {
  type: string
  title: string
  status: number
  detail?: string
  instance?: string
  code?: string
  errors?: Record<string, string[]>
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

// ---------- Auth ----------
export interface UserDto {
  id: string
  name: string
  email: string
  gender: Gender | null
  /** ISO date (YYYY-MM-DD); null until provided. */
  birthDate: string | null
  /** Derived by the server from birthDate. */
  age: number | null
  role: Role
  providers: AuthProvider[]
  locale: Locale
  countryCode: string
  countryName: string
  onboarded: boolean
  createdAt: string
}
export interface AuthResponse {
  accessToken: string
  expiresIn: number
  user: UserDto
}
export interface GoogleLoginRequest {
  idToken: string
  /** "Keep me signed in on this device"; missing = true (docs/SESSIONS_PROFILE_CONTRACT.md §1). */
  rememberMe?: boolean
}
export interface MagicRequest {
  email: string
}
export interface MagicVerifyRequest {
  token?: string
  email?: string
  code?: string
  /** Missing = true. */
  rememberMe?: boolean
}

/** GET /me/devices — one active refresh-token family of the caller, most recently active first. */
export interface DeviceDto {
  /** Family id. */
  id: string
  /** "Chrome", "Safari", "Edge", "Firefox", "Samsung Internet", "Opera", "Other". */
  browser: string
  /** "Windows", "macOS", "iOS", "iPadOS", "Android", "Linux", "Other". */
  os: string
  deviceType: DeviceType
  countryCode: string | null
  /** Localized by Accept-Language. */
  countryName: string | null
  /** First token of the family. */
  signedInAt: string
  /** Newest token of the family. */
  lastActiveAt: string
  /** This is the device making the request. */
  current: boolean
  /** Signed in with "Keep me signed in". */
  persistent: boolean
}
export interface OnboardingRequest {
  name: string
  gender: Gender
  birthDate: string
  acceptedTerms: true
}

// ---------- Public ----------
export interface WaitTime {
  busy: boolean
  minDays: number
  maxDays: number
  hours: number
  message: string
}
export interface PackageDto {
  id: string
  name: string
  description: string
  credits: number
  badge: string | null
  price: number
  originalPrice: number | null
  currency: Currency
  promotion: { label: string; endsAt: string } | null
  validityMonths: number | null
}
export interface Catalog {
  countryCode: string
  countryName: string
  currency: Currency
  packages: PackageDto[]
}
export interface Testimonial {
  id: string
  name: string
  rating: number
  comment: string
  date: string
}
export interface PublicStats {
  subscribers: string
  views: string
  videos: string
  /** Historical base + INTERPRETED dreams on the platform. */
  interpreted: number
}

// ---------- Dreams ----------
export interface DreamMessage {
  id: string
  senderRole: Role
  body: string
  createdAt: string
  readAt: string | null
}
export interface DreamSummary {
  id: string
  excerpt: string
  status: DreamStatus
  createdAt: string
  submittedAt: string | null
  interpretedAt: string | null
  expectedBy: string | null
  unreadMessages: number
}
export interface DreamDetail extends DreamSummary {
  text: string
  gender: Gender
  interpretation: { text: string; interpretedAt: string } | null
  messages: DreamMessage[]
  testimonial: { rating: number; comment: string; approved: boolean } | null
  credit: { ledgerEntryId: string; orderId: string | null } | null
}
export interface DreamDraftRequest {
  text: string
  gender: Gender
}
export interface SubmitDreamsRequest {
  dreamIds: string[]
}
export interface SubmitDreamsResponse {
  submitted: string[]
  remainingCredits: number
}
export interface TestimonialRequest {
  rating: number
  comment: string
}

// ---------- Credits & Orders ----------
export interface CreditLedgerEntry {
  id: string
  delta: number
  reason: 'PURCHASE' | 'SUBMIT' | 'REFUND' | 'MANUAL' | 'BONUS'
  orderId: string | null
  dreamId: string | null
  createdAt: string
}
export interface CreditsSummary {
  balance: number
  entries: CreditLedgerEntry[]
}
export interface CheckoutRequest {
  packageId: string
  couponCode?: string
  dreamIds?: string[]
}
export interface CheckoutResponse {
  orderId: string
  provider: 'PAYMOB' | 'MOR'
  checkoutUrl: string
  amount: number
  currency: Currency
  expiresAt: string
}
export interface OrderDto {
  id: string
  packageName: string
  credits: number
  amount: number
  currency: Currency
  status: OrderStatus
  provider: 'PAYMOB' | 'MOR'
  providerRef: string | null
  countryCode: string
  createdAt: string
  paidAt: string | null
}

// ---------- Notifications ----------
export interface NotificationDto {
  id: string
  type: NotificationType
  title: string
  body: string
  link: string | null
  createdAt: string
  readAt: string | null
}
export interface PushSubscriptionRequest {
  endpoint: string
  keys: { p256dh: string; auth: string }
  userAgent: string
}

// ---------- Me ----------
export interface DashboardSummary {
  drafts: number
  inReview: number
  awaitingReply: number
  interpreted: number
  credits: number
  unreadNotifications: number
  waitTime: WaitTime
}
export interface PreferencesRequest {
  locale?: Locale
  name?: string
  gender?: Gender
  birthDate?: string
  marketingOptIn?: boolean
}

// ---------- Admin ----------
export interface AdminSummary {
  inReview: number
  awaitingReply: number
  overdue: number
  interpretedToday: number
  revenue: { currency: Currency; amount: number }[]
  avgResponseHours: number
}
export interface CountryStat {
  countryCode: string
  countryName: string
  visits: number
  dreams: number
  revenueBase: number
  users: number
}
export interface AdminCountryDashboard {
  period: '7d' | '30d' | '1y' | 'all'
  baseCurrency: Currency
  topVisits: CountryStat[]
  topDreams: CountryStat[]
  topRevenue: CountryStat[]
}
export interface AdminDreamRow {
  id: string
  userId: string
  userName: string
  gender: Gender
  excerpt: string
  status: DreamStatus
  submittedAt: string
  slaDeadline: string
  overdue: boolean
  countryCode: string
}
export interface AdminDreamDetail extends DreamDetail {
  user: { id: string; name: string; email: string; countryCode: string; age: number | null }
  payment: {
    orderId: string
    payerName: string
    payerEmail: string
    paidAt: string
    packageName: string
    amount: number
    currency: Currency
    provider: string
    providerRef: string
    countryCode: string
  } | null
}
export interface InterpretationRequest {
  text: string
}
export interface WaitTimeSettings {
  busy: boolean
  normalHours: number
  busyMinDays: number
  busyMaxDays: number
  messageAr: string
  messageEn: string
  autoResetAt: string | null
}
export interface AdminPackage {
  id: string
  nameAr: string
  nameEn: string
  descriptionAr: string
  descriptionEn: string
  credits: number
  badge: string | null
  sortOrder: number
  active: boolean
  validityMonths: number | null
}
export interface PriceRule {
  id: string
  scope: PriceScope
  scopeId: string | null
  packageId: string
  price: number
  currency: Currency
}
export interface CountryDto {
  code: string
  nameAr: string
  nameEn: string
  continent: Continent
  defaultCurrency: Currency
  groupId: string | null
}
export interface CountryGroup {
  id: string
  name: string
  countryCodes: string[]
}
export interface PromotionDto {
  id: string
  name: string
  packageIds: string[]
  type: 'PERCENT' | 'FIXED' | 'BONUS'
  value: number
  startsAt: string
  endsAt: string
  maxUses: number | null
  usedCount: number
  scope: PriceScope
  scopeId: string | null
  active: boolean
}
export interface CouponDto {
  id: string
  code: string
  type: 'PERCENT' | 'FIXED'
  value: number
  maxUses: number | null
  perUserLimit: number
  usedCount: number
  expiresAt: string | null
  active: boolean
}
export type UserListKind = 'top-visits' | 'top-paying' | 'has-drafts' | 'high-rating' | 'low-rating' | 'all'
export interface AdminUserRow {
  id: string
  name: string
  email: string
  countryCode: string
  visits: number
  dreams: number
  drafts: number
  totalPaidBase: number
  avgRating: number | null
  lastSeenAt: string
}
export interface AdminUser360 extends Omit<AdminUserRow, 'dreams'> {
  dreams: number
  gender: Gender | null
  birthDate: string | null
  age: number | null
  locale: Locale
  providers: AuthProvider[]
  createdAt: string
  credits: CreditsSummary
  orders: OrderDto[]
  dreamList: DreamSummary[]
  testimonials: { dreamId: string; rating: number; comment: string; approved: boolean }[]
  notes: string
  tags: string[]
}
export interface AdminTestimonialRow {
  id: string
  userName: string
  dreamId: string
  rating: number
  comment: string
  approved: boolean
  createdAt: string
}

export interface YoutubeVideoDto { id: string; title: string; url: string; publishedAt: string; thumbnailUrl: string | null }
export interface YoutubeUnseen { count: number; latest: YoutubeVideoDto[] }

// ---------- Analytics, insights, reports & e-mail events (docs/ANALYTICS_REPORTS_CONTRACT.md) ----------
export type DeviceType = 'MOBILE' | 'TABLET' | 'DESKTOP'

/** POST /public/track — one call per route change; path without query (max 255). */
export interface TrackRequest {
  path: string
  referrer?: string | null
  sessionId: string
}

/** GET /admin/analytics/traffic — every param optional; default range = this calendar month up to today. */
export type TrafficQuery = {
  from?: string
  to?: string
  country?: string
  device?: DeviceType
  path?: string
}
export interface TrafficKpis {
  views: number
  visitors: number
  signups: number
  dreams: number
  paidOrders: number
  /** paidOrders / visitors * 100, one decimal. */
  conversionRate: number
}
export interface TrafficReport {
  from: string
  to: string
  compareFrom: string
  compareTo: string
  current: TrafficKpis
  previous: TrafficKpis
  daily: { date: string; views: number; visitors: number; previousViews: number }[]
  hourly: { hour: number; views: number; dreams: number }[]
  topPages: { path: string; views: number; visitors: number }[]
  topCountries: { countryCode: string; countryName: string; views: number; visitors: number }[]
  devices: { device: DeviceType; views: number }[]
  /** host '' = direct. */
  referrers: { host: string; views: number }[]
  records: {
    bestDay: { date: string; views: number } | null
    /** month = YYYY-MM */
    bestMonth: { month: string; views: number } | null
    totalViews: number
    totalVisitors: number
    /** Rank of the current month by views among all months (1 = best ever). */
    thisMonthRank: number | null
  }
}

export type InsightKind = 'SUCCESS' | 'INFO' | 'TIP' | 'WARNING'
export interface Insight {
  id: string
  kind: InsightKind
  title: string
  body: string
  /** Frontend route, e.g. '/admin/queue'. */
  link: string | null
}
export interface MyActivity {
  interpretedThisMonth: number
  interpretedLastMonth: number
  avgResponseHours: number
  slaHours: number
  onTimeRate: number
  myBusiestHours: number[]
  usersPeakHours: number[]
  streakDays: number
}
export interface InsightsResponse {
  items: Insight[]
  myActivity: MyActivity
}

/** GET/PUT /admin/settings — raw key/value map (BOOL values are 'true' / 'false'). PUT sends only changed keys. */
export type SettingsMap = Record<string, string>

/** GET /admin/mail/themes — one e-mail theme (header/footer images + colours). Image URLs are absolute. */
export interface MailThemeDto {
  key: string
  nameAr: string
  nameEn: string
  headerImageUrl: string
  footerImageUrl: string
  pageBg: string
  cardBg: string
  accent: string
  /** This is the current default theme (setting `mail.theme.default`). */
  usedByDefault: boolean
}

/**
 * GET /admin/mail/templates — one e-mail template. `theme` is the effective theme key; `inherited` = it has no
 * theme of its own and follows the default; `switchable` = false for the sign-in e-mail (always sent).
 */
export interface MailTemplateRow {
  template: string
  theme: string
  inherited: boolean
  enabled: boolean
  switchable: boolean
}

/** PUT /admin/mail/templates/{template}/theme and /admin/mail/theme-default. Blank theme on a template = follow the default. */
export interface MailThemeRequest { theme: string }

/** GET /admin/mail/preview (text/html). */
export interface MailPreviewQuery { template: string; theme?: string; locale: Locale }

/** GET /admin/dreams/export — `from/to` filter the submitted date; `q` searches name, e-mail and dream text. */
export type DreamsExportQuery = {
  status?: DreamStatus
  from?: string
  to?: string
  country?: string
  gender?: Gender
  q?: string
}
