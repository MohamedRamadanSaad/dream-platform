// Single source of truth for the API contract. Mirrors docs/BUILD_PACK.md and docs/SPEC_v2.md.

/**
 * AWAITING_PAYMENT: the interpreter took back the approval of the payment that paid for this dream
 * (docs/REVOKE_APPROVAL_CONTRACT.md). The dream is kept and waits for that payment to be confirmed again.
 */
export type DreamStatus = 'DRAFT' | 'IN_REVIEW' | 'IN_PROGRESS' | 'AWAITING_USER_REPLY' | 'AWAITING_PAYMENT' | 'INTERPRETED' | 'CANCELLED'
/** GET /dreams?status= also takes the virtual PENDING_PAYMENT: drafts attached to a payment under review or rejected, and AWAITING_PAYMENT dreams. */
export type DreamListFilter = DreamStatus | 'PENDING_PAYMENT' | 'ALL'
export type OrderStatus = 'INITIATED' | 'PENDING_REVIEW' | 'SUCCESS' | 'FAILED' | 'EXPIRED' | 'REFUNDED' | 'SUSPICIOUS'
/** INSTAPAY: manual transfer inside Egypt, confirmed by the interpreter. */
export type PaymentProvider = 'KASHIER' | 'MOR' | 'INSTAPAY'
export type Role = 'USER' | 'INTERPRETER'
export type AuthProvider = 'GOOGLE' | 'MAGIC_LINK' | 'PASSWORD'
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
  | 'TRANSFER_SUBMITTED'
  | 'PAYMENT_REJECTED'
  /** To the interpreter: a dreamer changed a dream's text before she started (link /admin/dreams/:id). */
  | 'DREAM_EDITED'
  /** To the buyer: the interpreter took back the approval of an InstaPay payment (link /me/checkout/instapay/:orderId). */
  | 'PAYMENT_REVOKED'

export interface ApiProblem {
  type: string
  title: string
  status: number
  detail?: string
  instance?: string
  code?: string
  errors?: Record<string, string[]>
  /** 429 TOO_MANY_ATTEMPTS (password sign-in): seconds until the account can try again. */
  retryAfterSeconds?: number
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
  /** New-video e-mails on. */
  marketingOptIn: boolean
  /** The account has a password (sign-in with e-mail + password works). */
  hasPassword: boolean
  /** The terms were accepted (at sign-up or onboarding): onboarding does not ask again. */
  termsAccepted: boolean
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

// ---------- E-mail + password (sign-up, sign-in, forgot password) ----------
/** POST /auth/signup/request: 204 (code e-mailed) or 409 ACCOUNT_EXISTS. */
export interface SignupRequest { email: string }
/** POST /auth/password/forgot: always 204. */
export interface ForgotRequest { email: string }
export type CodePurpose = 'SIGN_UP' | 'RESET_PASSWORD'
/** POST /auth/code/verify → TicketResponse; 401 MAGIC_LINK_INVALID / _EXPIRED / _TOO_MANY_ATTEMPTS. */
export interface CodeVerifyRequest { email: string; code: string; purpose: CodePurpose }
/** Single-use ticket for the password step (valid 15 minutes). Kept in memory only, never in storage. */
export interface TicketResponse { ticket: string; expiresIn: number }
/** POST /auth/signup/complete → AuthResponse; 401 TICKET_INVALID, 422 WEAK_PASSWORD, 409 ACCOUNT_EXISTS. */
export interface SignupCompleteRequest { ticket: string; password: string; acceptedTerms: true; rememberMe?: boolean }
/** POST /auth/password/reset → AuthResponse; 401 TICKET_INVALID, 422 WEAK_PASSWORD. */
export interface PasswordResetRequest { ticket: string; password: string; rememberMe?: boolean }
/** POST /auth/password/login → AuthResponse; 401 INVALID_CREDENTIALS, 409 PASSWORD_NOT_SET, 429 TOO_MANY_ATTEMPTS. */
export interface PasswordLoginRequest { email: string; password: string; rememberMe?: boolean }

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

// ---------- Passkeys: fingerprint / face sign-in (docs/PASSKEYS_CONTRACT.md) ----------
// Options and credentials travel in the standard WebAuthn JSON forms (binary fields are base64url strings).

/** POST /me/passkeys/registration/options — the challenge is single use and expires (setting auth.passkey_challenge_ttl_seconds). */
export interface PasskeyRegistrationOptions {
  requestId: string
  publicKey: PublicKeyCredentialCreationOptionsJSON
}
/** POST /auth/passkey/options — empty allowCredentials: the device offers its own passkeys. */
export interface PasskeySignInOptions {
  requestId: string
  publicKey: PublicKeyCredentialRequestOptionsJSON
}
interface PasskeyCredentialJSON {
  id: string
  rawId: string
  type: string
  authenticatorAttachment?: string
  clientExtensionResults: Record<string, unknown>
}
/** A new passkey, as `credential.toJSON()` gives it (only the fields the contract names). */
export interface RegistrationCredentialJSON extends PasskeyCredentialJSON {
  response: { clientDataJSON: string; attestationObject: string; transports: string[] }
}
/** A passkey sign-in, as `credential.toJSON()` gives it. */
export interface AuthenticationCredentialJSON extends PasskeyCredentialJSON {
  response: { clientDataJSON: string; authenticatorData: string; signature: string; userHandle?: string }
}
/** POST /me/passkeys/registration → 201 PasskeyDto. A missing label becomes "<browser> · <os>" on the server. */
export interface PasskeyRegistrationRequest {
  requestId: string
  credential: RegistrationCredentialJSON
  label?: string
}
/** POST /auth/passkey/verify → AuthResponse (+ refresh cookie), like /auth/magic/verify. 401 code PASSKEY_INVALID. */
export interface PasskeyVerifyRequest {
  requestId: string
  credential: AuthenticationCredentialJSON
  /** Missing = true. */
  rememberMe?: boolean
}
/** GET /me/passkeys — newest first. */
export interface PasskeyDto {
  id: string
  label: string
  createdAt: string
  lastUsedAt: string | null
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
/** GET /public/push-key — the VAPID public key (base64url); empty when push is not configured on the server. */
export interface PushKey {
  publicKey: string | null
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
  /** When the interpreter pressed "Start interpreting" (status IN_PROGRESS from then on); null before. */
  startedAt: string | null
  /** Days the interpreter expects to need, chosen when she started. */
  expectedDays: number | null
  /** The user may still change the text (DRAFT, or not yet started by the interpreter): follow this flag only. */
  editable: boolean
  /** The payment this draft (or AWAITING_PAYMENT dream) waits on, or that was rejected / revoked; null otherwise. */
  payment: DreamPaymentLink | null
}
/**
 * REVOKED: the approval was taken back and the buyer must review and resend the transfer details
 * (only for AWAITING_PAYMENT dreams); PENDING_REVIEW after he resent them; REJECTED after a reject.
 */
export interface DreamPaymentLink {
  orderId: string
  status: 'PENDING_REVIEW' | 'REJECTED' | 'REVOKED'
  submittedAt: string | null
  reviewNote: string | null
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
  /** REVOKE: credits of an InstaPay payment whose approval the interpreter took back. */
  reason: 'PURCHASE' | 'SUBMIT' | 'REFUND' | 'MANUAL' | 'BONUS' | 'EXPIRE' | 'REVOKE'
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
  provider: PaymentProvider
  checkoutUrl: string
  amount: number
  currency: Currency
  expiresAt: string
}
export interface OrderDto {
  id: string
  /** Readable order number shown to people (e.g. RN-15001); routes still use the id. */
  orderNumber: string
  packageName: string
  credits: number
  amount: number
  currency: Currency
  status: OrderStatus
  provider: PaymentProvider
  providerRef: string | null
  countryCode: string
  createdAt: string
  paidAt: string | null
}

// ---------- InstaPay (manual transfer, Egypt) ----------
/** GET /me/orders/{id}/instapay: the payment page. The receiving account is the interpreter's current setting. */
export interface InstapayOrderView {
  orderId: string
  orderNumber: string
  status: OrderStatus
  packageName: string
  credits: number
  amount: number
  currency: Currency
  address: string
  link: string
  recipientName: string
  createdAt: string
  expiresAt: string
  reference: string | null
  senderName: string | null
  submittedAt: string | null
  reviewNote: string | null
  attachedDreams: number
  /** Why a FAILED order failed: TRANSFER_REJECTED, or APPROVAL_REVOKED when the interpreter took back her approval. */
  failureReason?: string | null
}
export interface InstapaySubmitRequest {
  reference: string
  senderName: string
  dreamIds?: string[]
}
export interface InstapayTransferRow {
  orderId: string
  orderNumber: string
  userId: string
  userName: string
  userEmail: string
  packageName: string
  credits: number
  amount: number
  currency: Currency
  status: OrderStatus
  reference: string | null
  senderName: string | null
  createdAt: string
  submittedAt: string | null
  reviewedAt: string | null
  reviewNote: string | null
  attachedDreams: number
}
export interface InstapayTransferList {
  pending: InstapayTransferRow[]
  reviewed: InstapayTransferRow[]
}
export interface InstapaySettings {
  enabled: boolean
  address: string
  link: string
  recipientName: string
  expireHours: number
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
/** POST /push/test: devices on the account, how many push services accepted the test, whether the server can send at all. */
export interface PushTestResult {
  subscriptions: number
  sent: number
  configured: boolean
}

// ---------- Me ----------
export interface DashboardSummary {
  /** Drafts not attached to a payment. */
  drafts: number
  /** Drafts attached to a payment under review (or rejected). */
  pendingPayment: number
  inReview: number
  /** Started by the interpreter. */
  inProgress: number
  awaitingReply: number
  interpreted: number
  credits: number
  unreadNotifications: number
  waitTime: WaitTime
  /** Soonest credit expiry within the notice window, else null. */
  nextExpiry: { credits: number; at: string } | null
  /** Credits spent on dreams so far (dream refunds given back are subtracted). */
  usedCredits: number
  /** When the user was last on the site before this visit; null on the first visit. */
  lastVisitAt: string | null
  /** The latest package the user paid for; null when none yet. */
  lastPackage: { orderId: string; name: string; credits: number; amount: number; currency: Currency; paidAt: string } | null
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
  /** InstaPay transfers waiting for the interpreter. */
  pendingPayments: number
  inReview: number
  inProgress: number
  awaitingReply: number
  overdue: number
  interpretedToday: number
  /** Per currency, buyers' own amounts: reference only. */
  revenue: { currency: Currency; amount: number }[]
  /** Settled EGP total of the last 30 days. */
  revenueEgp: number
  avgResponseHours: number
}
export interface CountryStat {
  countryCode: string
  countryName: string
  visits: number
  dreams: number
  /** Settled EGP (the base currency). */
  revenueBase: number
  users: number
  /** The buyers' own currencies: reference only. */
  revenueByCurrency: { currency: Currency; amount: number }[]
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
  startedAt: string | null
  expectedDays: number | null
  userEditedAt: string | null
}
export interface AdminDreamDetail extends Omit<DreamDetail, 'payment'> {
  userEditedAt: string | null
  /** AWAITING_PAYMENT: the order whose approval was taken back (the dream waits for it); null otherwise. */
  heldOrderId?: string | null
  user: { id: string; name: string; email: string; countryCode: string; age: number | null }
  payment: {
    orderId: string
    orderNumber: string
    payerName: string
    payerEmail: string
    paidAt: string
    packageName: string
    amount: number
    currency: Currency
    provider: string
    providerRef: string
    countryCode: string
    /** Dreams the package gave (bonus included). */
    credits: number
    /** Dreams this same payment can still pay for. */
    creditsLeft: number
    /** This dream's place among the payment's dreams (1 = first; 0 = unknown). */
    dreamNumber: number
    creditsExpired: boolean
  } | null
}
export interface InterpretationRequest {
  text: string
}
/** POST /admin/dreams/{id}/start: IN_REVIEW → IN_PROGRESS (409 NOT_IN_REVIEW otherwise). */
export interface StartDreamRequest {
  expectedDays: number
}

// ---------- Admin orders (docs/UX_PAYMENTS_REVIEW_CONTRACT.md §3) ----------
/** REVIEW = InstaPay transfers waiting (oldest first); DOMESTIC = Egypt; INTERNATIONAL = outside Egypt; ALL = every order. */
export type OrderScope = 'REVIEW' | 'DOMESTIC' | 'INTERNATIONAL' | 'ALL'
export interface AdminOrderRow extends OrderDto {
  userName: string
  userId: string
  userEmail: string
  settledAmountEgp: number | null
  transferReference: string | null
  transferSenderName: string | null
  transferSubmittedAt: string | null
  attachedDreams: number
}
export interface AdminOrderCounts {
  review: number
  domestic: number
  international: number
}
export interface AdminOrderDetail {
  id: string
  /** Readable order number (e.g. RN-15001): the page title. */
  orderNumber: string
  status: OrderStatus
  failureReason: string | null
  provider: PaymentProvider
  providerRef: string | null
  packageName: string
  credits: number
  amount: number
  currency: Currency
  settledAmountEgp: number | null
  discount: number
  couponCode: string | null
  countryCode: string
  countrySource: string | null
  createdAt: string
  paidAt: string | null
  expiresAt: string | null
  transfer: { reference: string; senderName: string; submittedAt: string; reviewedAt: string | null; reviewedByName: string | null; reviewNote: string | null } | null
  user: { id: string; name: string; email: string; countryCode: string; age: number | null }
  dreams: { id: string; status: DreamStatus; text: string; createdAt: string; submittedAt: string | null }[]
  /** INSTAPAY and PENDING_REVIEW. */
  canReview: boolean
  /** INSTAPAY and SUCCESS and no dream paid from it is INTERPRETED: "Undo approval" is possible. */
  canRevoke: boolean
  /** Why "Undo approval" is not possible on a SUCCESS InstaPay order; null otherwise. */
  revokeBlockedReason: 'DREAM_ALREADY_INTERPRETED' | null
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
/** GET /admin/pricing/gaps: a country that cannot buy some active packages (no price in its currency). */
export interface PricingGap {
  countryCode: string
  nameAr: string
  nameEn: string
  currency: Currency
  groupId: string | null
  groupName: string | null
  /** true when the country cannot buy any active package at all */
  allMissing: boolean
  missing: { id: string; nameAr: string; nameEn: string }[]
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
  /** The visit: a cookie that ends after 30 minutes without a page view. */
  sessionId: string
  /** The browser: a cookie kept for a year, so a person coming back is still one visitor. */
  visitorId?: string
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
  /** Distinct browsers: one person coming back counts once. */
  visitors: number
  /** Distinct visits: a new visit starts after 30 minutes without a page view. */
  visits: number
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
  daily: { date: string; views: number; visitors: number; visits: number; previousViews: number }[]
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
    totalVisits: number
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

/** GET /public/legal: seller details and support contacts (empty strings when not set). */
export interface LegalInfo { name: string; taxRegistrationNo: string; supportEmail: string; supportPhone: string; supportHoursAr: string; supportHoursEn: string }

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

// ---------- support mailbox tickets (/admin/support/tickets) ----------

/** NEW → IN_PROGRESS (more updates allowed) → CLOSED (final); NEW → CLOSED directly. */
export type SupportTicketStatus = 'NEW' | 'IN_PROGRESS' | 'CLOSED'
export type SupportTicketAction = 'IN_PROGRESS' | 'CLOSED'
/** E-mail outcome of an interpreter message (LOGGED = no SMTP configured; DISABLED = that e-mail is switched off). */
export type SupportEmailStatus = 'QUEUED' | 'SENT' | 'FAILED' | 'LOGGED' | 'DISABLED'

/**
 * One e-mail a person sent to the support mailbox: the sender, the subject, when it arrived and `body` = the plain
 * text the person wrote (null until it arrives; POST …/fetch-body asks the server to read it from the mailbox now).
 * `number` is the human ticket number (#1001…). `lastMessage`/`lastMessageAt` = the interpreter's latest message
 * (null while NEW).
 */
export interface SupportTicket {
  id: string
  number: number
  fromEmail: string
  fromName: string | null
  subject: string | null
  receivedAt: string
  status: SupportTicketStatus
  updatedAt: string
  closedAt: string | null
  lastMessage: string | null
  lastMessageAt: string | null
  eventsCount: number
  body: string | null
}

/** One interpreter message on a ticket (oldest first in the detail). */
export interface SupportTicketEvent {
  id: string
  action: SupportTicketAction
  message: string
  createdAt: string
  actorName: string | null
  emailStatus: SupportEmailStatus | null
}

/** GET /admin/support/tickets/{id} */
export interface SupportTicketDetail extends SupportTicket { events: SupportTicketEvent[] }

/** GET /admin/support/tickets/counts */
export interface SupportTicketCounts { new: number; inProgress: number; closed: number }

/** POST /admin/support/tickets/{id}/in-progress and /close — trimmed, 1..2000 characters; e-mailed to the sender. */
export interface SupportTicketMessageRequest { message: string }

/** GET /admin/dreams/export — `from/to` filter the submitted date; `q` searches name, e-mail and dream text. */
export type DreamsExportQuery = {
  status?: DreamStatus
  from?: string
  to?: string
  country?: string
  gender?: Gender
  q?: string
}
