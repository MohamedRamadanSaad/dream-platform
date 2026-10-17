import { http, HttpResponse, delay } from 'msw'
import type * as T from '@/api/types'
import { INTERPRETER_DOMAIN } from '@/lib/utils'
import { db, resolvePrice, applyPromotion, balanceOf, toSummary, toDetail, uid, helpers, ytVideos, ytSeen, orderMeta, attachedOrder, isEditable, toEgp, nextOrderNumber, type MockOrder, type MockDream, type OrderMeta } from './data'
import { buildInsights, buildTraffic, deviceOf } from './analytics'
import { attachment, dreamsCsv, tinyPdf } from './reports'
import { defaultThemeKey, isMailTemplate, isMailTheme, mailPreviewHtml, mailTemplateRow, mailTemplateRows, mailThemes } from './mail'
import { supportAction, supportCounts, supportDetail, supportFetchBody, supportList } from './support'
import { endSession, listDevices, removeDevice, removeOtherDevices, startSession } from './devices'
import { finishRegistration, finishSignIn, listPasskeys, registrationOptions, removePasskey, signInOptions } from './passkeys'
import { checkCode, createUser, findUser, hasPassword, isFail, issueTicket, normEmail, passwordLogin, setPassword, spendTicket, strongEnough, type AuthFail } from './auth'

/**
 * Preview only: the public half of a throwaway P-256 key (its private half was never kept), so the push button can be
 * tried — the browser subscribes, nothing is ever sent.
 */
const MOCK_PUSH_KEY = 'BLG8Q3e-yg97PaYBiGpxgzU__5hoUDNQwxQl3TkAGm170BzckNCfAgIliFnPc_XnZUkzS6Cd4z2mMCX58eJLFJc'

/** Preview push subscriptions: endpoint → user id. */
const pushSubs = new Map<string, string>()

const BASE = (import.meta.env.VITE_API_URL as string) || ''
const u = (p: string) => `${BASE}${p}`
const lang = (req: Request) => req.headers.get('accept-language') ?? 'ar'

// the preview API is cross-origin: let the page read the file name of a download
const EXPOSE = { 'Access-Control-Expose-Headers': 'Content-Disposition' }
const pdfResponse = (lines: string[], name: string, asciiName?: string) =>
  new HttpResponse(tinyPdf([...lines, '', 'Preview file (mock data). The live site sends the full branded PDF.']), {
    headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': attachment(name, asciiName), ...EXPOSE },
  })

const problem = (status: number, title: string, detail?: string, extra: Partial<T.ApiProblem> = {}) =>
  HttpResponse.json({ type: `https://api.saadatu-aldarein.com/errors/${title.toLowerCase().replace(/\s+/g, '-')}`, title, status, detail, ...extra }, { status })

function currentUser(req: Request): T.UserDto | null {
  const auth = req.headers.get('authorization')
  if (!auth) return null
  const id = auth.replace('Bearer mock-', '')
  return db.users.find((x) => x.id === id) ?? null
}
const requireUser = (req: Request) => {
  const me = currentUser(req)
  if (!me) throw problem(401, 'Unauthenticated')
  return me
}
const requireAdmin = (req: Request) => {
  const me = requireUser(req)
  if (me.role !== 'INTERPRETER') throw problem(403, 'Forbidden')
  return me
}
const authProblem = (f: AuthFail) => problem(f.status, f.title, f.detail, { code: f.code, ...f.extra })
const session = (user: T.UserDto): T.AuthResponse => ({ accessToken: `mock-${user.id}`, expiresIn: 900, user })
const page = <X,>(items: X[], q: URLSearchParams): T.Page<X> => {
  const p = Number(q.get('page') ?? 0), s = Number(q.get('size') ?? 20)
  return { items: items.slice(p * s, p * s + s), page: p, size: s, total: items.length }
}
const wrap = (fn: (info: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => Response | Promise<Response>) =>
  async (info: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => {
    await delay(250)
    try { return await fn(info) } catch (e) { if (e instanceof Response) return e; throw e }
  }


// ---------- payments: shared success path + InstaPay manual transfers ----------
const instapayMeta: Map<string, OrderMeta> = orderMeta

/** What a confirmed payment does: credits, receipt notification, then the attached drafts are submitted. */
function completeOrder(order: MockOrder, dreamIds: string[]) {
  order.status = 'SUCCESS'; order.paidAt = helpers.now(); order.providerRef ??= `${order.provider}-${uid().toUpperCase()}`
  order.settledAmountEgp = toEgp(order.amount, order.currency); order.failureReason = null
  db.ledger.push({ id: uid(), userId: order.userId, delta: order.credits, reason: 'PURCHASE', orderId: order.id, dreamId: null, createdAt: helpers.now() })
  db.notifications.unshift({ id: uid(), userId: order.userId, type: 'PAYMENT_SUCCESS', title: 'تم الدفع', body: `أُضيفت ${order.credits} رؤى إلى رصيدك.`, link: '/me/payments', createdAt: helpers.now(), readAt: null })
  const hours = db.waitTime.busy ? db.waitTime.busyMaxDays * 24 : db.waitTime.normalHours
  for (const id of dreamIds) {
    const d = db.dreams.find((x) => x.id === id && x.userId === order.userId && x.status === 'DRAFT')
    if (!d || balanceOf(order.userId) < 1) continue
    const entry = { id: uid(), userId: order.userId, delta: -1, reason: 'SUBMIT' as const, orderId: order.id, dreamId: d.id, createdAt: helpers.now() }
    db.ledger.push(entry)
    Object.assign(d, { status: 'IN_REVIEW', submittedAt: helpers.now(), expectedBy: new Date(Date.now() + hours * 36e5).toISOString(), credit: { ledgerEntryId: entry.id, orderId: order.id } })
  }
  // dreams held by a revoked approval of this order resume from its own credits (contract §3)
  for (const d of db.dreams.filter((x) => x.heldOrderId === order.id && x.status === 'AWAITING_PAYMENT')) {
    if (balanceOf(order.userId) < 1) break
    const entry = { id: uid(), userId: order.userId, delta: -1, reason: 'SUBMIT' as const, orderId: order.id, dreamId: d.id, createdAt: helpers.now() }
    db.ledger.push(entry)
    resumeHeld(d, { ledgerEntryId: entry.id, orderId: order.id })
  }
}

/**
 * AWAITING_PAYMENT → back where it was: AWAITING_USER_REPLY while the interpreter's last question is unanswered (the
 * clock stays paused), else IN_PROGRESS (started) or IN_REVIEW with the deadline moved forward by the paused time.
 */
function resumeHeld(d: MockDream, credit: MockDream['credit']) {
  const asking = d.messages.length > 0 && d.messages[d.messages.length - 1].senderRole === 'INTERPRETER'
  if (asking) { Object.assign(d, { status: 'AWAITING_USER_REPLY', heldOrderId: null, credit }); return }
  const paused = d.slaPausedAt ? Date.now() - new Date(d.slaPausedAt).getTime() : 0
  const expectedBy = d.expectedBy ? new Date(new Date(d.expectedBy).getTime() + paused).toISOString() : d.expectedBy
  Object.assign(d, { status: d.startedAt ? 'IN_PROGRESS' : 'IN_REVIEW', expectedBy, slaPausedAt: null, heldOrderId: null, credit })
}
/** The undo fields every order view carries (contract follow-up): when, her note, and the dreams on hold. */
const undoInfo = (o: MockOrder) => {
  const m = instapayMeta.get(o.id)
  return { revokedAt: m?.revokedAt ?? null, revokeNote: m?.revokeNote ?? null, heldDreams: db.dreams.filter((d) => d.heldOrderId === o.id && d.status === 'AWAITING_PAYMENT').length }
}

/** A cancel gives the credit back unless none is held (on hold) or its payment's leftover was withdrawn. */
const cancelRefunds = (d: MockDream) => {
  if (d.status === 'AWAITING_PAYMENT' || !d.credit) return false
  const o = d.credit.orderId ? (db.orders as MockOrder[]).find((x) => x.id === d.credit!.orderId) : null
  return !o?.leftoverWithdrawnAt
}
/** Dreams whose credit came from this order (a SUBMIT row with the order as its source). */
const paidFrom = (o: MockOrder) => {
  const ids = new Set(db.ledger.filter((l) => l.orderId === o.id && l.reason === 'SUBMIT' && l.dreamId).map((l) => l.dreamId!))
  return db.dreams.filter((d) => ids.has(d.id) || d.credit?.orderId === o.id)
}
const revokeBlocked = (o: MockOrder): 'DREAM_ALREADY_INTERPRETED' | null => (paidFrom(o).some((d) => d.status === 'INTERPRETED') ? 'DREAM_ALREADY_INTERPRETED' : null)

/** A dream lives in a live order when it is attached to it, or when its credit came from it. */
const orderDreams = (o: MockOrder) => {
  const ids = new Set(instapayMeta.get(o.id)?.dreamIds ?? [])
  return db.dreams.filter((d) => ids.has(d.id) || d.credit?.orderId === o.id || d.heldOrderId === o.id).sort((a, b) => a.createdAt.localeCompare(b.createdAt))
}
const adminOrderRow = (o: MockOrder): T.AdminOrderRow => {
  const usr = db.users.find((x) => x.id === o.userId)
  const m = instapayMeta.get(o.id)
  return {
    id: o.id, orderNumber: o.orderNumber, packageName: o.packageName, credits: o.credits, amount: o.amount, currency: o.currency, status: effStatus(o), provider: o.provider, providerRef: o.providerRef, countryCode: o.countryCode, createdAt: o.createdAt, paidAt: o.paidAt,
    userName: usr?.name ?? '', userId: o.userId, userEmail: usr?.email ?? '', settledAmountEgp: o.settledAmountEgp ?? null,
    transferReference: m?.reference ?? null, transferSenderName: m?.senderName ?? null, transferSubmittedAt: m?.submittedAt ?? null, attachedDreams: orderDreams(o).length,
    failureReason: o.failureReason ?? null, ...undoInfo(o),
    expiresAt: m?.expiresAt ?? null, packageId: packageIdOf(o), leftoverWithdrawnAt: o.leftoverWithdrawnAt ?? null,
    leftoverWithdrawnCredits: o.leftoverWithdrawnCredits ?? null, leftoverReason: o.leftoverReason ?? null, leftoverNote: o.leftoverNote ?? null,
    // a booking the buyer paid later through another order
    paidLater: UNPAID_OF(o) && (db.orders as MockOrder[]).some((x) => x.userId === o.userId && x.status === 'SUCCESS' && x.createdAt > o.createdAt && new Date(x.createdAt).getTime() - new Date(o.createdAt).getTime() < 3 * 864e5),
  }
}
/** A booking past its deadline reads EXPIRED even before the expiry job ran (contract §3). */
const effStatus = (o: MockOrder): T.OrderStatus => {
  const exp = instapayMeta.get(o.id)?.expiresAt
  return o.status === 'INITIATED' && exp && exp < helpers.now() ? 'EXPIRED' : o.status
}
/**
 * What a user paid (SUCCESS orders): in his own currency (EGP for Egypt, USD for everyone else) and in EGP (settled
 * EGP, the ranking base, also sent as totalPaidBase).
 */
const paidOf = (usr: { id: string; countryCode: string }) => {
  const paid = (db.orders as MockOrder[]).filter((o) => o.userId === usr.id && o.status === 'SUCCESS')
  const egp = paid.reduce((a, o) => a + (o.settledAmountEgp ?? toEgp(o.amount, o.currency)), 0)
  const usd = paid.reduce((a, o) => a + (o.currency === 'SAR' ? o.amount / 3.75 : o.currency === 'EGP' ? o.amount / 48 : o.amount), 0)
  const eg = usr.countryCode === 'EG'
  return { totalPaidBase: egp, totalPaidEgp: egp, totalPaid: eg ? egp : usd, totalPaidCurrency: (eg ? 'EGP' : 'USD') as T.Currency }
}
const UNPAID: T.OrderStatus[] = ['INITIATED', 'EXPIRED', 'FAILED']
/**
 * Unpaid bookings, the last tab (owner rule 2026-10-17): never paid (INITIATED / EXPIRED, lazily expired too) and every
 * FAILED order whatever the reason (gateway failure, rejected transfer, undone approval).
 */
const UNPAID_OF = (o: MockOrder) => UNPAID.includes(effStatus(o))
const scoped = (scope: string | null) => {
  const all = db.orders as MockOrder[]
  if (scope === 'REVIEW') return all.filter((o) => o.provider === 'INSTAPAY' && o.status === 'PENDING_REVIEW').sort((a, b) => (instapayMeta.get(a.id)?.submittedAt ?? a.createdAt).localeCompare(instapayMeta.get(b.id)?.submittedAt ?? b.createdAt))
  const newest = (xs: MockOrder[]) => [...xs].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  const paidSide = (o: MockOrder) => !UNPAID_OF(o)
  if (scope === 'DOMESTIC') return newest(all.filter((o) => o.countryCode === 'EG' && paidSide(o)))
  if (scope === 'INTERNATIONAL') return newest(all.filter((o) => o.countryCode !== 'EG' && paidSide(o)))
  if (scope === 'UNPAID') return newest(all.filter((o) => !paidSide(o)))
  return newest(all)
}

/** Unused credits of an order's lot (purchase minus what was spent, refunded or withdrawn from it). */
const lotLeft = (orderId: string) => Math.max(0, db.ledger.filter((l) => l.orderId === orderId).reduce((a, l) => a + l.delta, 0))
/** The lot a new submit spends from: the oldest paid order of the user that still has credits (else none). */
const lotFor = (userId: string) => (db.orders as MockOrder[]).filter((o) => o.userId === userId && o.status === 'SUCCESS' && lotLeft(o.id) > 0).sort((a, b) => (a.paidAt ?? a.createdAt).localeCompare(b.paidAt ?? b.createdAt))[0]?.id ?? null
const leftoverOf = (o: MockOrder) => (o.status === 'SUCCESS' ? Math.min(lotLeft(o.id), Math.max(0, balanceOf(o.userId))) : 0)
const packageIdOf = (o: MockOrder) => db.packages.find((p) => p.nameAr === o.packageName || p.nameEn === o.packageName)?.id ?? null

type FilterParse = { ok: true; f: T.AdminOrderFilters } | { ok: false; field: string }
/** GET /admin/orders filters like the server: unknown values → 400 INVALID_FILTER with the field. */
function parseFilters(q: URLSearchParams): FilterParse {
  const f: T.AdminOrderFilters = {}
  const STAT: T.OrderStatus[] = ['INITIATED', 'PENDING_REVIEW', 'SUCCESS', 'FAILED', 'EXPIRED', 'REFUNDED', 'SUSPICIOUS']
  const st = q.getAll('status').flatMap((x) => x.split(',')).filter(Boolean)
  if (st.some((x) => !STAT.includes(x as T.OrderStatus))) return { ok: false, field: 'status' }
  if (st.length) f.status = st as T.OrderStatus[]
  const one = <X extends string>(k: keyof T.AdminOrderFilters, list: readonly X[] | null, re?: RegExp): string | null => {
    const v = q.get(k)
    if (v == null || v === '') return null
    if ((list && !list.includes(v as X)) || (re && !re.test(v))) return k
    ;(f as Record<string, unknown>)[k] = v
    return null
  }
  const bad = one('provider', ['INSTAPAY', 'MOR', 'MOCK', 'KASHIER']) ?? one('country', null, /^[A-Z]{2}$/) ?? one('currency', ['EGP', 'SAR', 'USD'])
    ?? one('from', null, /^\d{4}-\d{2}-\d{2}$/) ?? one('to', null, /^\d{4}-\d{2}-\d{2}$/) ?? one('packageId', null) ?? one('flag', ['REVOKED', 'RESENT', 'LEFTOVER_WITHDRAWN'])
  if (bad) return { ok: false, field: bad }
  for (const k of ['minAmount', 'maxAmount'] as const) {
    const v = q.get(k)
    if (v == null || v === '') continue
    const n = Number(v)
    if (!Number.isFinite(n) || n < 0) return { ok: false, field: k }
    f[k] = n
  }
  // a real calendar day, and ranges the right way round
  const realDay = (v?: string) => !v || (() => { const d = new Date(`${v}T00:00:00Z`); return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === v })()
  if (!realDay(f.from)) return { ok: false, field: 'from' }
  if (!realDay(f.to)) return { ok: false, field: 'to' }
  if (f.from && f.to && f.from > f.to) return { ok: false, field: 'to' }
  if (f.minAmount != null && f.maxAmount != null && f.minAmount > f.maxAmount) return { ok: false, field: 'maxAmount' }
  return { ok: true, f }
}
const day = (iso: string) => new Date(new Date(iso).getTime() + 3 * 36e5).toISOString().slice(0, 10) // Riyadh day
function applyFilters(rows: MockOrder[], f: T.AdminOrderFilters, raw: string) {
  const m = raw ? /^#?\s*(?:([A-Za-z][A-Za-z0-9]*?)\s*-?\s*)?(\d{1,18})$/.exec(raw) : null
  return rows.filter((o) => {
    const meta = instapayMeta.get(o.id)
    if (raw && (!m || !(m[1] ? o.orderNumber.toUpperCase() === `${m[1].toUpperCase()}-${m[2]}` : o.orderNumber.endsWith(`-${m[2]}`)))) return false
    if (f.status && !f.status.includes(effStatus(o))) return false
    if (f.provider && o.provider !== f.provider) return false
    if (f.country && o.countryCode !== f.country) return false
    if (f.currency && o.currency !== f.currency) return false
    if (f.from && day(o.createdAt) < f.from) return false
    if (f.to && day(o.createdAt) > f.to) return false
    if (f.minAmount != null && o.amount < f.minAmount) return false
    if (f.maxAmount != null && o.amount > f.maxAmount) return false
    if (f.packageId && packageIdOf(o) !== f.packageId) return false
    if (f.flag === 'REVOKED' && !meta?.revokedAt) return false
    if (f.flag === 'RESENT' && !(meta?.revokedAt && o.status === 'PENDING_REVIEW')) return false
    if (f.flag === 'LEFTOVER_WITHDRAWN' && !o.leftoverWithdrawnAt) return false
    return true
  })
}
const adminOrderDetail = (o: MockOrder): T.AdminOrderDetail => {
  const usr = db.users.find((x) => x.id === o.userId)!
  const m = instapayMeta.get(o.id)
  return {
    id: o.id, orderNumber: o.orderNumber, status: effStatus(o), failureReason: o.failureReason ?? null, provider: o.provider, providerRef: o.providerRef, packageName: o.packageName, credits: o.credits, amount: o.amount, currency: o.currency,
    settledAmountEgp: o.settledAmountEgp ?? null, discount: o.discount ?? 0, couponCode: o.couponCode ?? null, countryCode: o.countryCode, countrySource: o.countrySource ?? 'CF_IPCOUNTRY',
    createdAt: o.createdAt, paidAt: o.paidAt, expiresAt: m?.expiresAt ?? null,
    transfer: m?.reference ? { reference: m.reference, senderName: m.senderName ?? '', submittedAt: m.submittedAt ?? o.createdAt, reviewedAt: m.reviewedAt, reviewedByName: m.reviewedByName ?? null, reviewNote: m.reviewNote } : null,
    user: { id: usr.id, name: usr.name, email: usr.email, countryCode: usr.countryCode, age: usr.age ?? null },
    dreams: orderDreams(o).map((d) => ({ id: d.id, status: d.status, text: d.text, createdAt: d.createdAt, submittedAt: d.submittedAt })),
    canReview: o.provider === 'INSTAPAY' && o.status === 'PENDING_REVIEW',
    canRevoke: o.provider === 'INSTAPAY' && o.status === 'SUCCESS' && !revokeBlocked(o),
    revokeBlockedReason: o.provider === 'INSTAPAY' && o.status === 'SUCCESS' ? revokeBlocked(o) : null,
    ...undoInfo(o),
    leftoverCredits: leftoverOf(o), interpretedDreams: paidFrom(o).filter((d) => d.status === 'INTERPRETED').length, canWithdrawLeftover: o.provider === 'INSTAPAY' && leftoverOf(o) > 0,
    leftoverWithdrawnAt: o.leftoverWithdrawnAt ?? null, leftoverWithdrawnCredits: o.leftoverWithdrawnCredits ?? null, leftoverNote: o.leftoverNote ?? null,
    leftoverReason: o.leftoverReason ?? null, canRestoreLeftover: !!o.leftoverRestorable && (o.leftoverWithdrawnCredits ?? 0) > 0,
  }
}
/** Dreams the interpreter has to work on (contract §1: IN_PROGRESS counts as open work too). */
const OPEN: T.DreamStatus[] = ['IN_REVIEW', 'IN_PROGRESS']

/** The payment's counter as the backend computes it: dreams that used it (oldest first) and what is left. */
const orderCounter = (orderId: string, credits: number, dreamId: string) => {
  const used = db.ledger.filter((l) => l.orderId === orderId && l.reason === 'SUBMIT' && l.dreamId).sort((a, b) => a.createdAt.localeCompare(b.createdAt)).map((l) => l.dreamId!)
  // what is left of the lot: spent, refunded, withdrawn and restored credits all count
  return { credits, creditsLeft: lotLeft(orderId), dreamNumber: used.indexOf(dreamId) + 1, creditsExpired: false }
}

const instapayView = (o: MockOrder): T.InstapayOrderView => {
  const m = instapayMeta.get(o.id)!
  return {
    orderId: o.id, orderNumber: o.orderNumber, status: o.status, packageName: o.packageName, credits: o.credits, amount: o.amount, currency: o.currency,
    address: db.settings['payments.instapay.address'] ?? '', link: db.settings['payments.instapay.link'] ?? '', recipientName: db.settings['payments.instapay.recipient_name'] ?? '',
    createdAt: o.createdAt, expiresAt: m.expiresAt, reference: m.reference, senderName: m.senderName, submittedAt: m.submittedAt, reviewNote: m.reviewNote,
    failureReason: o.failureReason ?? null, heldDreams: undoInfo(o).heldDreams,
    // drafts only: a dream on hold is counted under heldDreams (attached + held = the dreams that go on at approval)
    attachedDreams: m.dreamIds.filter((id) => db.dreams.some((d) => d.id === id && d.status === 'DRAFT')).length,
    revokedAt: m.revokedAt ?? null, revokeNote: m.revokeNote ?? null,
  }
}
const transferRow = (o: MockOrder): T.InstapayTransferRow => {
  const m = instapayMeta.get(o.id)!
  const usr = db.users.find((x) => x.id === o.userId)
  return { orderId: o.id, orderNumber: o.orderNumber, userId: o.userId, userName: usr?.name ?? '', userEmail: usr?.email ?? '', packageName: o.packageName, credits: o.credits, amount: o.amount, currency: o.currency, status: o.status, reference: m.reference, senderName: m.senderName, createdAt: o.createdAt, submittedAt: m.submittedAt, reviewedAt: m.reviewedAt, reviewNote: m.reviewNote, attachedDreams: m.dreamIds.filter((id) => db.dreams.some((d) => d.id === id && d.status === 'DRAFT')).length, failureReason: o.failureReason ?? null, ...undoInfo(o) }
}
const toDigits = (raw: string) => raw.replace(/[\u0660-\u0669]/g, (d) => String(d.charCodeAt(0) - 0x660)).replace(/[\u06f0-\u06f9]/g, (d) => String(d.charCodeAt(0) - 0x6f0)).replace(/[\s-]/g, '')
const instapayOrders = () => (db.orders as MockOrder[]).filter((o) => o.provider === 'INSTAPAY' && instapayMeta.has(o.id))
const pendingTransfer = (id: string) => {
  const o = instapayOrders().find((x) => x.id === id)
  if (!o) throw problem(404, 'Not found')
  if (o.status !== 'PENDING_REVIEW') throw problem(409, 'Conflict', 'This transfer was already reviewed', { code: 'NOT_PENDING' })
  return o
}

const waitTimePublic = (locale: string): T.WaitTime => {
  const w = db.waitTime
  return { busy: w.busy, minDays: w.busyMinDays, maxDays: w.busyMaxDays, hours: w.normalHours, message: locale.startsWith('en') ? w.messageEn : w.messageAr }
}
const ageOf = (d: string) => Math.floor((Date.now() - new Date(d).getTime()) / (365.25 * 864e5))
const countryOf = (req: Request) => currentUser(req)?.countryCode ?? 'SA' // server would read CF-IPCountry
// a page cannot set the User-Agent header itself: the mock reads the browser's own
const uaOf = (req: Request) => req.headers.get('user-agent') ?? navigator.userAgent

export const handlers = [
  // ---------- auth ----------
  http.post(u('/auth/google'), wrap(async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as T.GoogleLoginRequest
    const user = db.users[0]
    startSession(user, uaOf(request), body.rememberMe ?? true)
    return HttpResponse.json(session(user))
  })),
  http.post(u('/auth/magic/request'), wrap(async () => new HttpResponse(null, { status: 204 }))),
  http.post(u('/auth/magic/verify'), wrap(async ({ request }) => {
    const body = (await request.json()) as T.MagicVerifyRequest
    const email = body.email?.toLowerCase()
    let user = db.users.find((x) => x.email.toLowerCase() === email)
    if (!user && email) {
      user = createUser(email, 'MAGIC_LINK')
    }
    if (!user) user = db.users[0]
    // rule: any e-mail on the site domain is an interpreter account (backend: setting interpreter.email_domain)
    if (email && email.endsWith(INTERPRETER_DOMAIN)) user.role = 'INTERPRETER'
    startSession(user, uaOf(request), body.rememberMe ?? true)
    return HttpResponse.json(session(user))
  })),
  // ---------- e-mail + password ----------
  http.post(u('/auth/signup/request'), wrap(async ({ request }) => {
    const email = normEmail(((await request.json().catch(() => ({}))) as Partial<T.SignupRequest>).email)
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return problem(400, 'Validation failed', 'A valid e-mail is required', { errors: { email: ['invalid'] } })
    if (hasPassword(email)) return problem(409, 'Conflict', 'An account with this e-mail already exists', { code: 'ACCOUNT_EXISTS' })
    return new HttpResponse(null, { status: 204 })
  })),
  http.post(u('/auth/password/forgot'), wrap(async () => new HttpResponse(null, { status: 204 }))),
  http.post(u('/auth/code/verify'), wrap(async ({ request }) => {
    const b = (await request.json().catch(() => ({}))) as Partial<T.CodeVerifyRequest>
    const bad = checkCode(b.code)
    if (bad) return authProblem(bad)
    const email = normEmail(b.email)
    // like the server: a reset code is only e-mailed to an existing account, so any other code is wrong
    if (b.purpose === 'RESET_PASSWORD' && !findUser(email)) return problem(401, 'Unauthorized', 'The code is not correct.', { code: 'MAGIC_LINK_INVALID' })
    if (b.purpose !== 'SIGN_UP' && b.purpose !== 'RESET_PASSWORD') return problem(400, 'Validation failed')
    return HttpResponse.json(issueTicket(email, b.purpose))
  })),
  http.post(u('/auth/signup/complete'), wrap(async ({ request }) => {
    const b = (await request.json().catch(() => ({}))) as Partial<T.SignupCompleteRequest>
    if (b.acceptedTerms !== true) return problem(400, 'Validation failed', 'The terms must be accepted', { errors: { acceptedTerms: ['must be true'] } })
    const email = spendTicket(b.ticket, 'SIGN_UP')
    if (!email) return problem(401, 'Unauthorized', 'The ticket is not valid', { code: 'TICKET_INVALID' })
    if (!strongEnough(b.password ?? '')) return problem(422, 'Unprocessable', 'The password is too weak', { code: 'WEAK_PASSWORD' })
    if (hasPassword(email)) return problem(409, 'Conflict', 'An account with this e-mail already exists', { code: 'ACCOUNT_EXISTS' })
    const user = findUser(email) ?? createUser(email, 'PASSWORD')
    setPassword(user, b.password!)
    user.termsAccepted = true
    startSession(user, uaOf(request), b.rememberMe ?? true)
    return HttpResponse.json(session(user))
  })),
  http.post(u('/auth/password/reset'), wrap(async ({ request }) => {
    const b = (await request.json().catch(() => ({}))) as Partial<T.PasswordResetRequest>
    const email = spendTicket(b.ticket, 'RESET_PASSWORD')
    const user = email ? findUser(email) : undefined
    if (!user) return problem(401, 'Unauthorized', 'The ticket is not valid', { code: 'TICKET_INVALID' })
    if (!strongEnough(b.password ?? '')) return problem(422, 'Unprocessable', 'The password is too weak', { code: 'WEAK_PASSWORD' })
    setPassword(user, b.password!)
    startSession(user, uaOf(request), b.rememberMe ?? true)
    return HttpResponse.json(session(user))
  })),
  http.post(u('/auth/password/login'), wrap(async ({ request }) => {
    const b = (await request.json().catch(() => ({}))) as Partial<T.PasswordLoginRequest>
    const r = passwordLogin(b.email, b.password)
    if (isFail(r)) return authProblem(r)
    startSession(r, uaOf(request), b.rememberMe ?? true)
    return HttpResponse.json(session(r))
  })),
  http.post(u('/auth/refresh'), wrap(async () => problem(401, 'Unauthenticated'))),
  http.post(u('/auth/logout'), wrap(async ({ request }) => {
    const me = currentUser(request)
    if (me) endSession(me.id)
    return new HttpResponse(null, { status: 204 })
  })),
  http.post(u('/auth/onboarding'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const b = (await request.json()) as T.OnboardingRequest
    Object.assign(me, { name: b.name, gender: b.gender, birthDate: b.birthDate, age: ageOf(b.birthDate), onboarded: true, termsAccepted: true })
    return HttpResponse.json(me)
  })),
  // fingerprint / face sign-in (docs/PASSKEYS_CONTRACT.md); like /auth/magic/verify on success
  http.post(u('/auth/passkey/options'), wrap(async () => HttpResponse.json(signInOptions()))),
  http.post(u('/auth/passkey/verify'), wrap(async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as Partial<T.PasskeyVerifyRequest>
    const user = finishSignIn(body, uaOf(request))
    if (!user) return problem(401, 'Unauthorized', 'This passkey cannot sign in to an account.', { code: 'PASSKEY_INVALID' })
    startSession(user, uaOf(request), body.rememberMe ?? true)
    return HttpResponse.json(session(user))
  })),

  // ---------- public ----------
  http.get(u('/public/catalog'), wrap(async ({ request }) => {
    const cc = countryOf(request)
    const c = db.countries.find((x) => x.code === cc)
    const locale = request.headers.get('accept-language') ?? 'ar'
    const packages: T.PackageDto[] = db.packages.filter((p) => p.active).sort((a, b) => a.sortOrder - b.sortOrder).map((p) => {
      const r = resolvePrice(cc, p.id)
      const a = applyPromotion(p.id, r.price, cc)
      return { id: p.id, name: locale.startsWith('en') ? p.nameEn : p.nameAr, description: locale.startsWith('en') ? p.descriptionEn : p.descriptionAr, credits: p.credits, badge: p.badge, price: a.price, originalPrice: a.original, currency: r.currency, promotion: a.promo, validityMonths: p.validityMonths }
    })
    return HttpResponse.json({ countryCode: cc, countryName: (locale.startsWith('en') ? c?.nameEn : c?.nameAr) ?? cc, currency: packages[0]?.currency ?? 'USD', packages } satisfies T.Catalog)
  })),
  http.post(u('/public/unsubscribe'), wrap(async ({ request }) => {
    const b = (await request.json()) as { u: string; t: string }
    const usr = db.users.find((x) => x.id === b.u)
    if (!usr || !b.t) return problem(422, 'Invalid link')
    usr.marketingOptIn = false
    return new HttpResponse(null, { status: 204 })
  })),
  http.get(u('/public/legal'), wrap(async () => HttpResponse.json({ name: db.settings['brand.legal_name'] ?? '', taxRegistrationNo: db.settings['brand.tax_registration_no'] ?? '', supportEmail: 'support@saadatu-aldarein.com', supportPhone: db.settings['brand.support_phone'] ?? '', supportHoursAr: db.settings['brand.support_hours.ar'] ?? '', supportHoursEn: db.settings['brand.support_hours.en'] ?? '' }))),
  http.get(u('/public/wait-time'), wrap(async ({ request }) => HttpResponse.json(waitTimePublic(request.headers.get('accept-language') ?? 'ar')))),
  http.get(u('/public/testimonials'), wrap(async () => HttpResponse.json({ items: [
    { id: 't1', name: 'أم محمد', rating: 5, comment: 'تحقق والحمد لله بعد أسبوعين، وكان التفسير هادئاً ومطمئناً.', date: helpers.daysAgo(20) },
    { id: 't2', name: 'خالد', rating: 5, comment: 'سألتني المعبّرة سؤالين قبل التفسير، وكان الفرق واضحاً في الدقة.', date: helpers.daysAgo(35) },
    { id: 't3', name: 'Sara', rating: 4, comment: 'Calm, deep and honest. No exaggeration at all.', date: helpers.daysAgo(50) },
  ] }))),
  http.get(u('/public/stats'), wrap(async () => HttpResponse.json({ subscribers: '50K+', views: '1M+', videos: '230+', interpreted: 2000 + db.dreams.filter((d) => d.status === 'INTERPRETED').length } satisfies T.PublicStats))),
  http.get(u('/public/push-key'), wrap(async () => HttpResponse.json({ publicKey: MOCK_PUSH_KEY } satisfies T.PushKey))),
  http.post(u('/public/track'), wrap(async ({ request }) => {
    const b = (await request.json().catch(() => null)) as T.TrackRequest | null
    if (!b?.path || !b.sessionId) return problem(400, 'Validation failed')
    if (!b.path.startsWith('/admin')) {
      db.pageViews.push({ path: b.path.slice(0, 255), referrer: b.referrer ?? null, sessionId: b.sessionId, visitorId: b.visitorId?.trim() || null, device: deviceOf(request.headers.get('user-agent') ?? navigator.userAgent), countryCode: countryOf(request), at: helpers.now() })
    }
    return new HttpResponse(null, { status: 204 })
  })),

  // ---------- me ----------
  http.get(u('/me'), wrap(async ({ request }) => HttpResponse.json(requireUser(request)))),
  // the preview keeps the demo account: deleting only ends this session (the server anonymises the account)
  http.delete(u('/me'), wrap(async ({ request }) => {
    endSession(requireUser(request).id)
    return new HttpResponse(null, { status: 204 })
  })),
  http.get(u('/me/dashboard'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const mine = db.dreams.filter((d) => d.userId === me.id)
    const s: T.DashboardSummary = {
      drafts: mine.filter((d) => d.status === 'DRAFT' && !attachedOrder(d)).length,
      pendingPayment: mine.filter((d) => attachedOrder(d)).length,
      inReview: mine.filter((d) => d.status === 'IN_REVIEW').length,
      inProgress: mine.filter((d) => d.status === 'IN_PROGRESS').length,
      awaitingReply: mine.filter((d) => d.status === 'AWAITING_USER_REPLY').length,
      interpreted: mine.filter((d) => d.status === 'INTERPRETED').length,
      credits: balanceOf(me.id),
      unreadNotifications: db.notifications.filter((n) => n.userId === me.id && !n.readAt).length,
      waitTime: waitTimePublic(request.headers.get('accept-language') ?? 'ar'),
      nextExpiry: balanceOf(me.id) > 0 ? { credits: Math.min(2, balanceOf(me.id)), at: new Date(Date.now() + 24 * 864e5).toISOString() } : null,
      usedCredits: Math.max(0, -db.ledger.filter((l) => l.userId === me.id && (l.reason === 'SUBMIT' || (l.reason === 'REFUND' && l.dreamId))).reduce((a, l) => a + l.delta, 0)),
      // the preview has no visit history: show "yesterday evening" for the demo account
      lastVisitAt: me.id === 'u1' ? new Date(Date.now() - 20 * 3600e3).toISOString() : null,
      lastPackage: (() => {
        const o = db.orders.filter((x) => x.userId === me.id && x.status === 'SUCCESS').sort((a, b) => (b.paidAt ?? '').localeCompare(a.paidAt ?? ''))[0]
        return o ? { orderId: o.id, name: o.packageName, credits: o.credits, amount: o.amount, currency: o.currency, paidAt: o.paidAt ?? o.createdAt } : null
      })(),
    }
    return HttpResponse.json(s)
  })),
  http.put(u('/me/preferences'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const b = (await request.json()) as T.PreferencesRequest
    Object.assign(me, b, b.birthDate ? { age: ageOf(b.birthDate) } : {})
    return HttpResponse.json(me)
  })),
  // fingerprint / face sign-ins of the caller (docs/PASSKEYS_CONTRACT.md)
  http.get(u('/me/passkeys'), wrap(async ({ request }) => HttpResponse.json(listPasskeys(requireUser(request))))),
  http.post(u('/me/passkeys/registration/options'), wrap(async ({ request }) => HttpResponse.json(registrationOptions(requireUser(request))))),
  http.post(u('/me/passkeys/registration'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const body = (await request.json().catch(() => ({}))) as Partial<T.PasskeyRegistrationRequest>
    const added = finishRegistration(me, body, uaOf(request))
    return added ? HttpResponse.json(added, { status: 201 }) : problem(400, 'Bad request', 'The new passkey was not accepted.', { code: 'PASSKEY_INVALID' })
  })),
  http.delete(u('/me/passkeys/:id'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    return removePasskey(me.id, String(params.id)) ? new HttpResponse(null, { status: 204 }) : problem(404, 'Not found')
  })),
  // devices = the caller's active sign-in sessions (docs/SESSIONS_PROFILE_CONTRACT.md §2)
  http.get(u('/me/devices'), wrap(async ({ request }) => HttpResponse.json(listDevices(requireUser(request), uaOf(request), lang(request))))),
  http.post(u('/me/devices/sign-out-others'), wrap(async ({ request }) => {
    removeOtherDevices(requireUser(request).id)
    return new HttpResponse(null, { status: 204 })
  })),
  http.delete(u('/me/devices/:id'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    return removeDevice(me.id, String(params.id)) ? new HttpResponse(null, { status: 204 }) : problem(404, 'Not found')
  })),
  http.get(u('/me/credits'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const entries = db.ledger.filter((l) => l.userId === me.id).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map(({ userId: _u, ...l }) => ({ ...l, kind: l.kind ?? null, orderNumber: l.orderId ? db.orders.find((o) => o.id === l.orderId)?.orderNumber ?? null : null }))
    return HttpResponse.json({ balance: balanceOf(me.id), entries } satisfies T.CreditsSummary)
  })),
  http.get(u('/me/orders'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const q = new URL(request.url).searchParams
    return HttpResponse.json(page(db.orders.filter((o) => o.userId === me.id).sort((a, b) => b.createdAt.localeCompare(a.createdAt)), q))
  })),
  http.get(u('/me/orders/:id'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const o = db.orders.find((x) => x.id === params.id && x.userId === me.id)
    return o ? HttpResponse.json(o) : problem(404, 'Not found')
  })),

  // ---------- dreams ----------
  http.get(u('/dreams'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const q = new URL(request.url).searchParams
    const st = q.get('status')
    const match = (d: MockDream) => st === 'PENDING_PAYMENT' ? !!attachedOrder(d) : st === 'DRAFT' ? d.status === 'DRAFT' && !attachedOrder(d) : (!st || d.status === st)
    const list = db.dreams.filter((d) => d.userId === me.id && match(d)).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(toSummary)
    return HttpResponse.json(page(list, q))
  })),
  http.get(u('/dreams/:id'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const d = db.dreams.find((x) => x.id === params.id && x.userId === me.id)
    if (!d) return problem(404, 'Not found')
    d.messages.forEach((m) => { if (m.senderRole === 'INTERPRETER') m.readAt ??= helpers.now() })
    return HttpResponse.json(toDetail(d))
  })),
  http.post(u('/dreams'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const b = (await request.json()) as T.DreamDraftRequest
    if (!b.text || b.text.trim().length < 20) return problem(400, 'Validation failed', 'النص قصير جداً', { errors: { text: ['min 20'] } })
    const drafts = db.dreams.filter((d) => d.userId === me.id && d.status === 'DRAFT').length
    if (drafts >= 20) return problem(422, 'Draft limit', 'الحد الأقصى 20 مسودة')
    const d: MockDream = { id: uid(), userId: me.id, gender: b.gender, status: 'DRAFT', createdAt: helpers.now(), submittedAt: null, interpretedAt: null, expectedBy: null, text: b.text, messages: [], interpretation: null, testimonial: null, credit: null }
    db.dreams.unshift(d)
    return HttpResponse.json(toDetail(d), { status: 201 })
  })),
  http.put(u('/dreams/:id'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const d = db.dreams.find((x) => x.id === params.id && x.userId === me.id)
    if (!d) return problem(404, 'Not found')
    // DRAFT, or IN_REVIEW before the interpreter pressed "Start interpreting" (contract §1)
    if (!isEditable(d)) return problem(409, 'Conflict', 'This dream can no longer be edited', { code: 'NOT_EDITABLE' })
    const b = (await request.json()) as T.DreamDraftRequest
    if (!b.text || b.text.trim().length < 20) return problem(400, 'Validation failed', 'Text too short', { errors: { text: ['min 20'] } })
    Object.assign(d, { text: b.text, gender: b.gender }, d.status !== 'DRAFT' ? { userEditedAt: helpers.now() } : {})
    if (d.status !== 'DRAFT') {
      for (const it of db.users.filter((x) => x.role === 'INTERPRETER')) {
        db.notifications.unshift({ id: uid(), userId: it.id, type: 'DREAM_EDITED', title: 'عدّل صاحب الرؤيا نصّها', body: `عدّل ${me.name} نص رؤيا قبل أن تبدئي تعبيرها.`, link: `/admin/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
      }
    }
    return HttpResponse.json(toDetail(d))
  })),
  http.delete(u('/dreams/:id'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const i = db.dreams.findIndex((x) => x.id === params.id && x.userId === me.id)
    if (i < 0) return problem(404, 'Not found')
    if (db.dreams[i].status !== 'DRAFT') return problem(409, 'Conflict', 'Only drafts can be deleted', { code: 'NOT_DRAFT' })
    if (attachedOrder(db.dreams[i])?.status === 'PENDING_REVIEW') return problem(409, 'Conflict', 'This draft is attached to a payment', { code: 'ATTACHED_TO_PAYMENT' })
    db.dreams.splice(i, 1)
    return new HttpResponse(null, { status: 204 })
  })),
  http.post(u('/dreams/submit'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const b = (await request.json()) as T.SubmitDreamsRequest
    // drafts, and dreams held by a revoked approval (they resume with ordinary credits, contract §3)
    const picked = b.dreamIds.map((id) => db.dreams.find((d) => d.id === id && d.userId === me.id && ((d.status === 'DRAFT' && attachedOrder(d)?.status !== 'PENDING_REVIEW') || d.status === 'AWAITING_PAYMENT'))).filter(Boolean) as MockDream[]
    if (picked.length !== b.dreamIds.length) return problem(409, 'Conflict', 'بعض الرؤى ليست مسودات')
    const bal = balanceOf(me.id)
    if (bal < picked.length) return problem(402, 'Payment required', `تحتاج ${picked.length - bal} رؤيا إضافية`, { code: 'INSUFFICIENT_CREDITS' })
    for (const d of picked.filter((x) => x.status === 'AWAITING_PAYMENT')) {
      const lot = lotFor(me.id)
      const entry = { id: uid(), userId: me.id, delta: -1, reason: 'SUBMIT' as const, orderId: lot, dreamId: d.id, createdAt: helpers.now() }
      db.ledger.push(entry)
      resumeHeld(d, { ledgerEntryId: entry.id, orderId: lot })
    }
    const drafts = picked.filter((x) => x.status === 'DRAFT')
    const w = db.waitTime
    const hours = w.busy ? w.busyMaxDays * 24 : w.normalHours
    for (const d of drafts) {
      const lot = lotFor(me.id)
      const entry = { id: uid(), userId: me.id, delta: -1, reason: 'SUBMIT' as const, orderId: lot, dreamId: d.id, createdAt: helpers.now() }
      db.ledger.push(entry)
      Object.assign(d, { status: 'IN_REVIEW', submittedAt: helpers.now(), expectedBy: new Date(Date.now() + hours * 36e5).toISOString(), credit: { ledgerEntryId: entry.id, orderId: lot } })
      db.notifications.unshift({ id: uid(), userId: me.id, type: 'DREAM_RECEIVED', title: 'وصلت رؤياك', body: 'سنبدأ دراستها، وسيصلك إشعار عند جاهزية التفسير.', link: `/me/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
      db.notifications.unshift({ id: uid(), userId: 'admin', type: 'DREAM_SUBMITTED', title: 'رؤية جديدة', body: `${me.name} قدّمت رؤيا جديدة.`, link: `/admin/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
    }
    return HttpResponse.json({ submitted: picked.map((d) => d.id), remainingCredits: balanceOf(me.id) } satisfies T.SubmitDreamsResponse)
  })),
  http.post(u('/dreams/:id/messages'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const d = db.dreams.find((x) => x.id === params.id && x.userId === me.id)
    if (!d) return problem(404, 'Not found')
    if (d.status !== 'AWAITING_USER_REPLY') return problem(409, 'Conflict', 'لا يوجد استفسار مفتوح')
    const { body } = (await request.json()) as { body: string }
    const m: T.DreamMessage = { id: uid(), senderRole: 'USER', body, createdAt: helpers.now(), readAt: null }
    d.messages.push(m)
    d.status = d.startedAt ? 'IN_PROGRESS' : 'IN_REVIEW'
    db.notifications.unshift({ id: uid(), userId: 'admin', type: 'USER_REPLIED', title: 'رد على استفسارك', body: `${me.name} ردّت على استفسارك.`, link: `/admin/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
    return HttpResponse.json(m, { status: 201 })
  })),
  http.post(u('/dreams/:id/testimonial'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const d = db.dreams.find((x) => x.id === params.id && x.userId === me.id)
    if (!d) return problem(404, 'Not found')
    if (d.status !== 'INTERPRETED') return problem(409, 'Conflict', 'التقييم متاح بعد التفسير فقط')
    if (d.testimonial) return problem(409, 'Conflict', 'قيّمت هذه الرؤيا من قبل')
    const b = (await request.json()) as T.TestimonialRequest
    d.testimonial = { rating: b.rating, comment: b.comment, approved: false }
    return new HttpResponse(null, { status: 201 })
  })),
  http.get(u('/dreams/:id/pdf'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const d = db.dreams.find((x) => x.id === params.id && x.userId === me.id && x.status !== 'DRAFT')
    if (!d) return problem(404, 'Not found')
    return pdfResponse(['Saadat Al-Darain - your dream', `Dream ${d.id} - ${d.status}`], `dream-${d.id}.pdf`)
  })),
  http.get(u('/me/dreams/pdf'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const mine = db.dreams.filter((d) => d.userId === me.id && d.status !== 'DRAFT')
    return pdfResponse(['Saadat Al-Darain - all my dreams', `${mine.length} dreams`], 'my-dreams.pdf')
  })),

  // ---------- checkout ----------
  http.post(u('/checkout'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const b = (await request.json()) as T.CheckoutRequest
    const pkg = db.packages.find((p) => p.id === b.packageId && p.active)
    if (!pkg) return problem(404, 'Not found')
    const r = resolvePrice(me.countryCode, pkg.id)
    let { price } = applyPromotion(pkg.id, r.price, me.countryCode)
    if (b.couponCode) {
      const c = db.coupons.find((x) => x.active && x.code.toLowerCase() === b.couponCode!.toLowerCase())
      if (!c) return problem(422, 'Invalid coupon', 'كود الخصم غير صالح')
      price = c.type === 'PERCENT' ? Math.round(price * (1 - c.value / 100)) : Math.max(0, price - c.value)
    }
    // like production: no payment method outside Egypt yet
    if (r.currency !== 'EGP' || db.settings['payments.instapay.enabled'] !== 'true') return problem(422, 'Unprocessable', 'Payment is available inside Egypt only for now', { code: 'PAYMENT_EGYPT_ONLY' })
    const live = (b.dreamIds ?? []).find((id) => db.orders.some((o) => o.userId === me.id && (o.status === 'PENDING_REVIEW' || (o.status === 'INITIATED' && (instapayMeta.get(o.id)?.expiresAt ?? '') > helpers.now())) && instapayMeta.get(o.id)?.dreamIds.includes(id)))
    if (live) return problem(409, 'Conflict', 'This dream is already attached to another payment', { code: 'DREAM_ALREADY_ATTACHED' })
    if ((b.dreamIds?.length ?? 0) > pkg.credits) return problem(400, 'Bad Request', 'More dreams than the package credits', { code: 'TOO_MANY_DREAMS' })
    // a dream from a rejected payment moves to this one
    for (const id of b.dreamIds ?? []) for (const o of db.orders.filter((x) => x.userId === me.id && x.status === 'FAILED')) {
      const m = instapayMeta.get(o.id); if (m) m.dreamIds = m.dreamIds.filter((x) => x !== id)
    }
    const order: MockOrder = { id: uid(), orderNumber: nextOrderNumber(), userId: me.id, packageName: pkg.nameAr, credits: pkg.credits, amount: price, currency: r.currency, status: 'INITIATED', provider: 'INSTAPAY', providerRef: null, countryCode: me.countryCode, createdAt: helpers.now(), paidAt: null }
    db.orders.unshift(order)
    const hours = Number(db.settings['payments.instapay.expire_hours'] ?? 24)
    instapayMeta.set(order.id, { dreamIds: b.dreamIds ?? [], reference: null, senderName: null, submittedAt: null, reviewedAt: null, reviewNote: null, expiresAt: new Date(Date.now() + hours * 36e5).toISOString() })
    return HttpResponse.json({ orderId: order.id, provider: 'INSTAPAY', checkoutUrl: `/me/checkout/instapay/${order.id}`, amount: price, currency: r.currency, expiresAt: instapayMeta.get(order.id)!.expiresAt } satisfies T.CheckoutResponse)
  })),


  // ---------- InstaPay (manual transfer, Egypt) ----------
  // dreams leave the order and return to Drafts; the order itself stays as it is
  http.post(u('/me/orders/:id/release-dreams'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const o = instapayOrders().find((x) => x.id === params.id && x.userId === me.id)
    if (!o) return problem(404, 'Not found')
    const m = instapayMeta.get(o.id)!
    const releasable = o.status === 'INITIATED' || o.status === 'PENDING_REVIEW' || (o.status === 'FAILED' && o.failureReason === 'TRANSFER_REJECTED')
    if (!releasable) return problem(409, 'Conflict', 'These dreams cannot leave this order now', { code: 'NOT_RELEASABLE' })
    const b = (await request.json().catch(() => ({}))) as { dreamIds?: string[] }
    const ids = b.dreamIds?.length ? b.dreamIds : [...m.dreamIds]
    m.dreamIds = m.dreamIds.filter((x) => !ids.includes(x))
    return new HttpResponse(null, { status: 204 })
  })),
  http.get(u('/me/orders/:id/instapay'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const o = instapayOrders().find((x) => x.id === params.id && x.userId === me.id)
    if (!o) return problem(404, 'Not found')
    const m = instapayMeta.get(o.id)!
    if (o.status === 'INITIATED' && m.expiresAt < helpers.now()) o.status = 'EXPIRED'
    return HttpResponse.json(instapayView(o))
  })),
  http.post(u('/me/orders/:id/instapay'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const o = instapayOrders().find((x) => x.id === params.id && x.userId === me.id)
    if (!o) return problem(404, 'Not found')
    const m = instapayMeta.get(o.id)!
    const b = (await request.json()) as T.InstapaySubmitRequest
    const reopened = o.status === 'FAILED' && !!m.reference
    if (o.status === 'INITIATED' && m.expiresAt < helpers.now()) { o.status = 'EXPIRED'; return problem(409, 'Conflict', 'Order expired', { code: 'ORDER_EXPIRED' }) }
    if (o.status !== 'INITIATED' && o.status !== 'PENDING_REVIEW' && !reopened) return problem(409, 'Conflict', 'Closed', { code: 'ORDER_CLOSED' })
    const ref = toDigits(b.reference)
    if (!/^\d{6,24}$/.test(ref)) return problem(422, 'Unprocessable', 'Bad reference', { code: 'INVALID_REFERENCE' })
    if ((b.senderName ?? '').trim().length < 3) return problem(422, 'Unprocessable', 'Bad sender', { code: 'INVALID_SENDER' })
    // a revoked order keeps its reference (its buyer may resend it), so no other order can use it
    const taken = instapayOrders().some((x) => x.id !== o.id && instapayMeta.get(x.id)!.reference === ref && (['PENDING_REVIEW', 'SUCCESS', 'SUSPICIOUS', 'REFUNDED'].includes(x.status) || x.failureReason === 'APPROVAL_REVOKED'))
    if (taken) return problem(409, 'Conflict', 'Reference used', { code: 'DUPLICATE_REFERENCE' })
    if (new Set([...m.dreamIds, ...(b.dreamIds ?? [])]).size > o.credits) return problem(400, 'Bad Request', 'More dreams than the package credits', { code: 'TOO_MANY_DREAMS' })
    for (const id of b.dreamIds ?? []) if (!m.dreamIds.includes(id)) m.dreamIds.push(id)
    const first = o.status === 'INITIATED' || reopened
    Object.assign(m, { reference: ref, senderName: b.senderName.trim(), submittedAt: first ? helpers.now() : m.submittedAt ?? helpers.now() }, reopened ? { reviewNote: null, reviewedAt: null } : {})
    o.status = 'PENDING_REVIEW'; o.failureReason = null
    if (first) {
      for (const it of db.users.filter((x) => x.role === 'INTERPRETER')) {
        db.notifications.unshift({ id: uid(), userId: it.id, type: 'TRANSFER_SUBMITTED', title: 'تحويل إنستاباي بانتظار التأكيد', body: `أرسل ${me.name} تحويلاً بمبلغ ${o.amount} ${o.currency}، الرقم المرجعي ${ref}.`, link: `/admin/orders/${o.id}`, createdAt: helpers.now(), readAt: null })
      }
    }
    return HttpResponse.json(instapayView(o))
  })),
  http.get(u('/admin/payments/instapay/transfers/counts'), wrap(async ({ request }) => {
    requireAdmin(request)
    return HttpResponse.json({ pending: instapayOrders().filter((o) => o.status === 'PENDING_REVIEW').length })
  })),
  http.get(u('/admin/payments/instapay/transfers'), wrap(async ({ request }) => {
    requireAdmin(request)
    const all = instapayOrders()
    const pending = all.filter((o) => o.status === 'PENDING_REVIEW').sort((a, b) => (instapayMeta.get(a.id)!.submittedAt ?? '').localeCompare(instapayMeta.get(b.id)!.submittedAt ?? ''))
    const reviewed = all.filter((o) => instapayMeta.get(o.id)!.reviewedAt).sort((a, b) => instapayMeta.get(b.id)!.reviewedAt!.localeCompare(instapayMeta.get(a.id)!.reviewedAt!)).slice(0, 50)
    return HttpResponse.json({ pending: pending.map(transferRow), reviewed: reviewed.map(transferRow) } satisfies T.InstapayTransferList)
  })),
  http.post(u('/admin/payments/instapay/transfers/:id/approve'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const me = requireAdmin(request)
    const o = pendingTransfer(String(params.id))
    const m = instapayMeta.get(o.id)!
    const b = (await request.json().catch(() => ({}))) as { reference?: string }
    if ((b.reference ?? '').replace(/\s/g, '') !== m.reference) return problem(409, 'Conflict', 'The transfer reference was changed, check it again', { code: 'REFERENCE_CHANGED' })
    m.reviewedAt = helpers.now(); m.reviewedByName = me.name
    o.providerRef = `INSTAPAY-REF-${m.reference}`
    completeOrder(o, m.dreamIds)
    return HttpResponse.json(transferRow(o))
  })),
  http.post(u('/admin/payments/instapay/transfers/:id/reject'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const me = requireAdmin(request)
    const o = pendingTransfer(String(params.id))
    const m = instapayMeta.get(o.id)!
    const b = (await request.json().catch(() => ({}))) as { note?: string }
    Object.assign(m, { reviewedAt: helpers.now(), reviewedByName: me.name, reviewNote: b.note?.trim() || null })
    o.status = 'FAILED'; o.failureReason = 'TRANSFER_REJECTED'
    db.notifications.unshift({ id: uid(), userId: o.userId, type: 'PAYMENT_REJECTED', title: 'لم نجد تحويلك', body: `لم نجد تحويلاً مطابقاً لطلب ${o.packageName}، ولم يُضف أي رصيد.`, link: `/me/checkout/instapay/${o.id}`, createdAt: helpers.now(), readAt: null })
    return HttpResponse.json(transferRow(o))
  })),
  // "Undo approval" (docs/REVOKE_APPROVAL_CONTRACT.md §2): credits back out, paid dreams held, the buyer reviews and resends
  http.post(u('/admin/payments/instapay/transfers/:id/revoke'), wrap(async ({ request, params }) => {
    const me = requireAdmin(request)
    const o = instapayOrders().find((x) => x.id === params.id)
    if (!o) return problem(404, 'Not found')
    if (o.status !== 'SUCCESS') return problem(409, 'Conflict', 'Only an approved InstaPay payment can be taken back', { code: 'NOT_REVOCABLE' })
    if (revokeBlocked(o)) return problem(409, 'Conflict', 'A dream paid by this order was already interpreted', { code: 'DREAM_ALREADY_INTERPRETED' })
    const m = instapayMeta.get(o.id)!
    const b = (await request.json().catch(() => ({}))) as { note?: string }
    const note = (b.note ?? '').trim()
    if (note.length > 500) return problem(400, 'Validation failed', 'note: at most 500 characters', { errors: { note: ['max 500'] } })
    const held: string[] = []
    for (const d of paidFrom(o).filter((x) => x.status === 'IN_REVIEW' || x.status === 'IN_PROGRESS' || x.status === 'AWAITING_USER_REPLY')) {
      db.ledger.push({ id: uid(), userId: o.userId, delta: 1, reason: 'REFUND', orderId: o.id, dreamId: d.id, createdAt: helpers.now() })
      Object.assign(d, { status: 'AWAITING_PAYMENT', heldOrderId: o.id, slaPausedAt: d.slaPausedAt ?? helpers.now(), credit: null })
      held.push(d.id)
    }
    // what is left of the order's lot leaves the balance (never below 0)
    const lot = db.ledger.filter((l) => l.orderId === o.id).reduce((a, l) => a + l.delta, 0)
    const take = Math.min(lot, balanceOf(o.userId))
    if (take > 0) db.ledger.push({ id: uid(), userId: o.userId, delta: -take, reason: 'REVOKE', orderId: o.id, dreamId: null, createdAt: helpers.now(), kind: 'APPROVAL_REVOKED' })
    Object.assign(m, { reviewedAt: helpers.now(), reviewedByName: me.name, reviewNote: note || null, revokedAt: helpers.now(), revokeNote: note || null })
    Object.assign(o, { status: 'FAILED', failureReason: 'APPROVAL_REVOKED', settledAmountEgp: null, paidAt: null, providerRef: null })
    db.notifications.unshift({ id: uid(), userId: o.userId, type: 'PAYMENT_REVOKED', title: 'نحتاج إلى مراجعتك لبيانات الدفع', body: `لم تتمكن المعبّرة من تأكيد تحويل طلب ${o.packageName}. رؤاك محفوظة، راجع رقم العملية واسم المحوِّل والمبلغ وأرسلها مرة أخرى.`, link: `/me/checkout/instapay/${o.id}`, createdAt: helpers.now(), readAt: null })
    return HttpResponse.json(transferRow(o))
  })),
  http.get(u('/admin/payments/instapay/settings'), wrap(async ({ request }) => {
    requireAdmin(request)
    const st = db.settings
    return HttpResponse.json({ enabled: st['payments.instapay.enabled'] === 'true', address: st['payments.instapay.address'] ?? '', link: st['payments.instapay.link'] ?? '', recipientName: st['payments.instapay.recipient_name'] ?? '', expireHours: Number(st['payments.instapay.expire_hours'] ?? 24) } satisfies T.InstapaySettings)
  })),
  http.put(u('/admin/payments/instapay/settings'), wrap(async ({ request }) => {
    requireAdmin(request)
    const b = (await request.json()) as T.InstapaySettings
    const address = b.address.trim().toLowerCase()
    if (!/^[a-z0-9][a-z0-9._-]{1,62}@instapay$/.test(address)) return problem(422, 'Unprocessable', 'Bad address', { code: 'INVALID_ADDRESS' })
    if (!/^https:\/\/ipn\.eg\/S\/[A-Za-z0-9._-]+\/instapay\/[A-Za-z0-9]+$/.test(b.link.trim())) return problem(422, 'Unprocessable', 'Bad link', { code: 'INVALID_LINK' })
    Object.assign(db.settings, { 'payments.instapay.enabled': String(b.enabled), 'payments.instapay.address': address, 'payments.instapay.link': b.link.trim(), 'payments.instapay.recipient_name': (b.recipientName ?? '').trim(), 'payments.instapay.expire_hours': String(b.expireHours) })
    return HttpResponse.json({ ...b, address, link: b.link.trim(), recipientName: (b.recipientName ?? '').trim() })
  })),

  // ---------- notifications ----------
  http.get(u('/notifications'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const q = new URL(request.url).searchParams
    return HttpResponse.json(page(db.notifications.filter((n) => n.userId === me.id), q))
  })),
  http.post(u('/notifications/read-all'), wrap(async ({ request }) => {
    const me = requireUser(request)
    db.notifications.forEach((n) => { if (n.userId === me.id) n.readAt ??= helpers.now() })
    return new HttpResponse(null, { status: 204 })
  })),
  http.post(u('/notifications/:id/read'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const n = db.notifications.find((x) => x.id === params.id && x.userId === me.id)
    if (n) n.readAt ??= helpers.now()
    return new HttpResponse(null, { status: 204 })
  })),
  // preview: subscriptions kept in memory (endpoint → account, the latest account wins like the server); the test
  // answers as a server without push keys would not: it says "sent" so the button can be tried, nothing is delivered
  http.post(u('/push/subscriptions'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const b = await request.json() as T.PushSubscriptionRequest
    if (!b?.endpoint || !b.keys?.p256dh || !b.keys.auth) return problem(400, 'Bad Request', 'endpoint and keys are required')
    pushSubs.set(b.endpoint, me.id)
    return new HttpResponse(null, { status: 204 })
  })),
  http.delete(u('/push/subscriptions'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const endpoint = new URL(request.url).searchParams.get('endpoint') ?? ''
    if (pushSubs.get(endpoint) === me.id) pushSubs.delete(endpoint)
    return new HttpResponse(null, { status: 204 })
  })),
  http.post(u('/push/test'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const n = [...pushSubs.values()].filter((id) => id === me.id).length
    return HttpResponse.json({ subscriptions: n, sent: n, configured: true } satisfies T.PushTestResult)
  })),

  // ---------- youtube as notification ----------
  http.get(u('/youtube/unseen'), wrap(async ({ request }) => {
    const me = requireUser(request)
    // like the server: since the last press, else since the account was created
    const seenAt = ytSeen[me.id] ?? new Date(me.createdAt).getTime()
    const unseen = ytVideos.filter((v) => new Date(v.publishedAt).getTime() > seenAt)
    return HttpResponse.json({ count: unseen.length, latest: unseen.slice(0, 20) } satisfies T.YoutubeUnseen)
  })),
  http.post(u('/youtube/seen'), wrap(async ({ request }) => {
    const me = requireUser(request)
    ytSeen[me.id] = Date.now()
    return new HttpResponse(null, { status: 204 })
  })),
  http.post(u('/webhooks/mock/:orderId'), wrap(async () => new HttpResponse(null, { status: 200 }))),

  // ---------- admin ----------
  http.get(u('/admin/analytics/summary'), wrap(async ({ request }) => {
    requireAdmin(request)
    const all = db.dreams
    const since = Date.now() - 30 * 864e5
    const paid = (db.orders as MockOrder[]).filter((o) => o.status === 'SUCCESS' && o.paidAt && new Date(o.paidAt).getTime() >= since)
    const byCur = new Map<T.Currency, number>()
    for (const o of paid) byCur.set(o.currency, (byCur.get(o.currency) ?? 0) + o.amount)
    const s: T.AdminSummary = {
      pendingPayments: instapayOrders().filter((o) => o.status === 'PENDING_REVIEW').length,
      inReview: all.filter((d) => d.status === 'IN_REVIEW').length,
      inProgress: all.filter((d) => d.status === 'IN_PROGRESS').length,
      awaitingReply: all.filter((d) => d.status === 'AWAITING_USER_REPLY').length,
      awaitingPayment: all.filter((d) => d.status === 'AWAITING_PAYMENT').length,
      overdue: all.filter((d) => OPEN.includes(d.status) && d.expectedBy && new Date(d.expectedBy) < new Date()).length,
      interpretedToday: 2,
      revenue: [...byCur].map(([currency, amount]) => ({ currency, amount })).sort((a, b) => toEgp(b.amount, b.currency) - toEgp(a.amount, a.currency)),
      revenueEgp: Math.round(paid.reduce((a, o) => a + (o.settledAmountEgp ?? toEgp(o.amount, o.currency)), 0) * 100) / 100,
      avgResponseHours: 31,
    }
    return HttpResponse.json(s)
  })),
  http.get(u('/admin/analytics/countries'), wrap(async ({ request }) => {
    requireAdmin(request)
    const q = new URL(request.url).searchParams
    const en = lang(request).startsWith('en')
    // preview extras on top of the seeded rows, so every list has more than one page (7 per page)
    const EXTRA_DREAMS: Record<string, number> = { EG: 38, SA: 27, AE: 6, MA: 4, DE: 3, KW: 2, JO: 2, US: 1, TN: 1 }
    const EXTRA_REVENUE: Record<string, [T.Currency, number][]> = {
      EG: [['EGP', 18450]], SA: [['SAR', 1240]], AE: [['USD', 96]], KW: [['USD', 54]], DE: [['USD', 70]], MA: [['USD', 27]], US: [['USD', 39]], QA: [['USD', 15]],
    }
    const stat = (cc: string): T.CountryStat => {
      const c = db.countries.find((x) => x.code === cc)
      const users = db.users.filter((x) => x.countryCode === cc && x.role === 'USER')
      const dreams = db.dreams.filter((d) => users.some((x) => x.id === d.userId) && d.status !== 'DRAFT').length
      const byCur = new Map<T.Currency, number>()
      for (const o of (db.orders as MockOrder[]).filter((x) => x.countryCode === cc && x.status === 'SUCCESS')) byCur.set(o.currency, (byCur.get(o.currency) ?? 0) + o.amount)
      for (const [cur, amount] of EXTRA_REVENUE[cc] ?? []) byCur.set(cur, (byCur.get(cur) ?? 0) + amount)
      const revenueByCurrency = [...byCur].map(([currency, amount]) => ({ currency, amount }))
      return {
        countryCode: cc, countryName: (en ? c?.nameEn : c?.nameAr) ?? cc, visits: db.visits.filter((v) => v.countryCode === cc).reduce((a, v) => a + v.count, 0),
        dreams: dreams + (EXTRA_DREAMS[cc] ?? 0), revenueBase: Math.round(revenueByCurrency.reduce((a, r) => a + toEgp(r.amount, r.currency), 0)),
        users: users.length + (cc === 'EG' ? 210 : cc === 'SA' ? 160 : 20), revenueByCurrency,
      }
    }
    const codes = [...new Set([...db.visits.map((v) => v.countryCode), ...Object.keys(EXTRA_REVENUE)])]
    const stats = codes.map(stat)
    // contract §5: only rows above zero for that metric, every row (no top-8 cut)
    const r: T.AdminCountryDashboard = {
      period: (q.get('period') as T.AdminCountryDashboard['period']) ?? '30d', baseCurrency: 'EGP',
      topVisits: stats.filter((x) => x.visits > 0).sort((a, b) => b.visits - a.visits),
      topDreams: stats.filter((x) => x.dreams > 0).sort((a, b) => b.dreams - a.dreams),
      topRevenue: stats.filter((x) => x.revenueBase > 0).sort((a, b) => b.revenueBase - a.revenueBase),
    }
    return HttpResponse.json(r)
  })),
  http.get(u('/admin/analytics/traffic'), wrap(async ({ request }) => {
    requireAdmin(request)
    const r = buildTraffic(new URL(request.url).searchParams, lang(request))
    return r ? HttpResponse.json(r) : problem(400, 'Validation failed', 'from/to must be YYYY-MM-DD and from <= to')
  })),
  http.get(u('/admin/analytics/insights'), wrap(async ({ request }) => {
    requireAdmin(request)
    return HttpResponse.json(buildInsights(lang(request)))
  })),
  // must stay above /admin/dreams/:id
  http.get(u('/admin/dreams/export'), wrap(async ({ request }) => {
    requireAdmin(request)
    const csv = dreamsCsv(new URL(request.url).searchParams, lang(request))
    return new HttpResponse(csv, { headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': attachment('dreams.csv'), ...EXPOSE } })
  })),
  http.get(u('/admin/dreams/:id/pdf'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const d = db.dreams.find((x) => x.id === params.id && x.status !== 'DRAFT')
    if (!d) return problem(404, 'Not found')
    return pdfResponse(['Saadat Al-Darain - dream report', `Dream ${d.id} - ${d.status}`, `${d.messages.length} messages`], `dream-${d.id}.pdf`)
  })),
  http.get(u('/admin/users/:id/pdf'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const usr = db.users.find((x) => x.id === params.id)
    if (!usr) return problem(404, 'Not found')
    const dreams = db.dreams.filter((d) => d.userId === usr.id && d.status !== 'DRAFT')
    return pdfResponse(['Saadat Al-Darain - user report', `User ${usr.id} - ${dreams.length} dreams`], `user-${usr.name || usr.id}.pdf`, `user-${usr.id}.pdf`)
  })),
  http.get(u('/admin/settings'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(db.settings) })),
  http.put(u('/admin/settings'), wrap(async ({ request }) => {
    requireAdmin(request)
    const body = (await request.json()) as T.SettingsMap
    const errors: Record<string, string[]> = {}
    for (const [k, v] of Object.entries(body)) {
      if (!(k in db.settings)) errors[k] = ['unknown setting']
      else if (k.startsWith('mail.event.') && v !== 'true' && v !== 'false') errors[k] = ['must be true or false']
    }
    if (Object.keys(errors).length) return problem(422, 'Validation failed', undefined, { errors })
    Object.assign(db.settings, body)
    return HttpResponse.json(db.settings)
  })),
  // e-mail look (themes per template, default theme, HTML preview)
  http.get(u('/admin/mail/themes'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(mailThemes()) })),
  http.get(u('/admin/mail/templates'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(mailTemplateRows()) })),
  http.put(u('/admin/mail/templates/:template/theme'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const template = String(params.template)
    const theme = String(((await request.json()) as T.MailThemeRequest)?.theme ?? '').trim()
    if (!isMailTemplate(template)) return problem(400, 'Bad Request', `Unknown e-mail template: ${template}`, { code: 'UNKNOWN_TEMPLATE' })
    if (theme && !isMailTheme(theme)) return problem(400, 'Bad Request', `Unknown e-mail theme: ${theme}`, { code: 'UNKNOWN_THEME' })
    db.settings[`mail.theme.${template}`] = theme
    return HttpResponse.json(mailTemplateRow(template))
  })),
  http.put(u('/admin/mail/theme-default'), wrap(async ({ request }) => {
    requireAdmin(request)
    const theme = String(((await request.json()) as T.MailThemeRequest)?.theme ?? '').trim()
    if (!isMailTheme(theme)) return problem(400, 'Bad Request', `Unknown e-mail theme: ${theme}`, { code: 'UNKNOWN_THEME' })
    db.settings['mail.theme.default'] = theme
    return HttpResponse.json({ theme: defaultThemeKey() } satisfies T.MailThemeRequest)
  })),
  http.get(u('/admin/mail/preview'), wrap(async ({ request }) => {
    requireAdmin(request)
    const q = new URL(request.url).searchParams
    const template = q.get('template') ?? ''
    const theme = q.get('theme')
    if (!isMailTemplate(template)) return problem(400, 'Bad Request', `Unknown e-mail template: ${template}`, { code: 'UNKNOWN_TEMPLATE' })
    if (theme && !isMailTheme(theme)) return problem(400, 'Bad Request', `Unknown e-mail theme: ${theme}`, { code: 'UNKNOWN_THEME' })
    const locale: T.Locale = q.get('locale') === 'en' ? 'en' : 'ar'
    return new HttpResponse(mailPreviewHtml(template, theme, locale), { headers: { 'Content-Type': 'text/html; charset=utf-8' } })
  })),
  // ---------- support mailbox tickets (counts before :id) ----------
  http.get(u('/admin/support/tickets/counts'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(supportCounts()) })),
  http.get(u('/admin/support/tickets'), wrap(async ({ request }) => {
    requireAdmin(request)
    const q = new URL(request.url).searchParams
    const st = q.get('status')
    const status = st === 'NEW' || st === 'IN_PROGRESS' || st === 'CLOSED' ? st : null
    return HttpResponse.json(page(supportList(status), q))
  })),
  http.get(u('/admin/support/tickets/:id'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const t = supportDetail(String(params.id))
    return t ? HttpResponse.json(t) : problem(404, 'Not found')
  })),
  http.post(u('/admin/support/tickets/:id/fetch-body'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const t = supportFetchBody(String(params.id))
    return t ? HttpResponse.json(t) : problem(404, 'Not found')
  })),
  ...(['in-progress', 'close'] as const).map((path) => http.post(u(`/admin/support/tickets/:id/${path}`), wrap(async ({ request, params }) => {
    const me = requireAdmin(request)
    const body = (await request.json().catch(() => ({}))) as Partial<T.SupportTicketMessageRequest>
    const r = supportAction(String(params.id), path === 'close' ? 'CLOSED' : 'IN_PROGRESS', body.message, me.name || me.email)
    if (!r.ok) return problem(r.status, r.status === 409 ? 'Conflict' : r.status === 404 ? 'Not found' : 'Bad Request', r.detail, r.code ? { code: r.code } : {})
    return HttpResponse.json(r.ticket)
  }))),
  http.get(u('/admin/dreams'),wrap(async ({ request }) => {
    requireAdmin(request)
    const q = new URL(request.url).searchParams
    const st = q.get('status')
    const rows: T.AdminDreamRow[] = db.dreams.filter((d) => d.status !== 'DRAFT' && (!st || d.status === st)).map((d) => {
      const usr = db.users.find((x) => x.id === d.userId)!
      return { id: d.id, userId: usr.id, userName: usr.name, gender: d.gender, excerpt: toSummary(d).excerpt, status: d.status, submittedAt: d.submittedAt!, slaDeadline: d.expectedBy!, overdue: OPEN.includes(d.status) && !!d.expectedBy && new Date(d.expectedBy) < new Date(), countryCode: usr.countryCode, startedAt: d.startedAt ?? null, expectedDays: d.expectedDays ?? null, userEditedAt: d.userEditedAt ?? null, heldOrderId: d.heldOrderId ?? null, heldAt: d.status === 'AWAITING_PAYMENT' ? d.slaPausedAt ?? null : null }
    }).sort((a, b) => (st === 'AWAITING_PAYMENT' ? (a.heldAt ?? '').localeCompare(b.heldAt ?? '') : a.submittedAt.localeCompare(b.submittedAt)))
    return HttpResponse.json(page(rows, q))
  })),
  http.get(u('/admin/dreams/:id'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const d = db.dreams.find((x) => x.id === params.id)
    if (!d) return problem(404, 'Not found')
    const usr = db.users.find((x) => x.id === d.userId)!
    const order = d.credit?.orderId ? db.orders.find((o) => o.id === d.credit!.orderId) : null
    d.messages.forEach((m) => { if (m.senderRole === 'USER') m.readAt ??= helpers.now() })
    const { payment: _ignored, ...detail } = toDetail(d)
    const r: T.AdminDreamDetail = { ...detail, userEditedAt: d.userEditedAt ?? null, heldOrderId: d.heldOrderId ?? null, cancelRefunds: cancelRefunds(d), user: { id: usr.id, name: usr.name, email: usr.email, countryCode: usr.countryCode, age: usr.age },
      payment: order ? { orderId: order.id, orderNumber: order.orderNumber, payerName: usr.name, payerEmail: usr.email, paidAt: order.paidAt ?? order.createdAt, packageName: order.packageName, amount: order.amount, currency: order.currency, provider: order.provider, providerRef: order.providerRef ?? '-', countryCode: order.countryCode, ...orderCounter(order.id, order.credits, d.id) } : null }
    return HttpResponse.json(r)
  })),
  http.post(u('/admin/dreams/:id/messages'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const d = db.dreams.find((x) => x.id === params.id)
    if (!d || !OPEN.includes(d.status)) return problem(409, 'Conflict')
    const { body } = (await request.json()) as { body: string }
    const m: T.DreamMessage = { id: uid(), senderRole: 'INTERPRETER', body, createdAt: helpers.now(), readAt: null }
    d.messages.push(m); d.status = 'AWAITING_USER_REPLY'
    db.notifications.unshift({ id: uid(), userId: d.userId, type: 'INTERPRETER_QUESTION', title: 'استفسار من المعبّرة', body: 'أرسلت المعبّرة استفساراً عن رؤياك، يرجى الرد لإكمال التفسير.', link: `/me/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
    return HttpResponse.json(m, { status: 201 })
  })),
  http.post(u('/admin/dreams/:id/interpretation'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const d = db.dreams.find((x) => x.id === params.id)
    if (!d || !['IN_REVIEW', 'IN_PROGRESS', 'AWAITING_USER_REPLY'].includes(d.status)) return problem(409, 'Conflict')
    const { text } = (await request.json()) as T.InterpretationRequest
    d.interpretation = { text, interpretedAt: helpers.now() }; d.status = 'INTERPRETED'; d.interpretedAt = helpers.now()
    db.notifications.unshift({ id: uid(), userId: d.userId, type: 'INTERPRETATION_READY', title: 'تم تفسير رؤياك', body: 'التفسير جاهز الآن في حسابك.', link: `/me/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
    return new HttpResponse(null, { status: 204 })
  })),
  http.post(u('/admin/dreams/:id/cancel'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const d = db.dreams.find((x) => x.id === params.id)
    if (!d) return problem(404, 'Not found')
    if (!['IN_REVIEW', 'IN_PROGRESS', 'AWAITING_USER_REPLY', 'AWAITING_PAYMENT'].includes(d.status)) return problem(409, 'Conflict', 'This dream can no longer be cancelled')
    const refund = cancelRefunds(d)
    if (refund) db.ledger.push({ id: uid(), userId: d.userId, delta: 1, reason: 'REFUND', orderId: d.credit?.orderId ?? null, dreamId: d.id, createdAt: helpers.now() })
    Object.assign(d, { status: 'CANCELLED', heldOrderId: null })
    db.notifications.unshift({ id: uid(), userId: d.userId, type: 'DREAM_RECEIVED', title: 'أُلغيت رؤياك', body: refund ? 'أُلغيت رؤياك وأُعيد رصيدها إليك.' : 'أُلغيت رؤياك.', link: `/me/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
    return new HttpResponse(null, { status: 204 })
  })),
  // "Start interpreting": the text becomes final and the user is told how many days it will take
  http.post(u('/admin/dreams/:id/start'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const d = db.dreams.find((x) => x.id === params.id)
    if (!d) return problem(404, 'Not found')
    if (d.status !== 'IN_REVIEW') return problem(409, 'Conflict', 'Only a dream in review can be started', { code: 'NOT_IN_REVIEW' })
    const b = (await request.json().catch(() => ({}))) as Partial<T.StartDreamRequest>
    const days = Number(b.expectedDays)
    if (!Number.isInteger(days) || days < 1 || days > 30) return problem(400, 'Validation failed', 'expectedDays must be 1..30', { errors: { expectedDays: ['1..30'] } })
    Object.assign(d, { status: 'IN_PROGRESS', startedAt: helpers.now(), expectedDays: days, expectedBy: new Date(Date.now() + days * 864e5).toISOString() })
    db.notifications.unshift({ id: uid(), userId: d.userId, type: 'DREAM_STARTED', title: 'بدأ تعبير رؤياك', body: `بدأت المعبّرة تعبير رؤياك، والمتوقع خلال ${days} أيام إن شاء الله.`, link: `/me/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
    const usr = db.users.find((x) => x.id === d.userId)!
    const order = d.credit?.orderId ? db.orders.find((o) => o.id === d.credit!.orderId) : null
    const { payment: _ignored, ...detail } = toDetail(d)
    return HttpResponse.json({ ...detail, userEditedAt: d.userEditedAt ?? null, user: { id: usr.id, name: usr.name, email: usr.email, countryCode: usr.countryCode, age: usr.age }, payment: order ? { orderId: order.id, orderNumber: order.orderNumber, payerName: usr.name, payerEmail: usr.email, paidAt: order.paidAt ?? order.createdAt, packageName: order.packageName, amount: order.amount, currency: order.currency, provider: order.provider, providerRef: order.providerRef ?? '-', countryCode: order.countryCode, ...orderCounter(order.id, order.credits, d.id) } : null } satisfies T.AdminDreamDetail)
  })),
  http.get(u('/admin/wait-time'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(db.waitTime) })),
  http.put(u('/admin/wait-time'), wrap(async ({ request }) => { requireAdmin(request); Object.assign(db.waitTime, await request.json()); return HttpResponse.json(db.waitTime) })),

  http.get(u('/admin/packages'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(db.packages) })),
  http.post(u('/admin/packages'), wrap(async ({ request }) => { requireAdmin(request); const p = { id: uid(), ...(await request.json() as object) } as T.AdminPackage; db.packages.push(p); return HttpResponse.json(p, { status: 201 }) })),
  http.put(u('/admin/packages/:id'), wrap(async ({ request, params }) => { requireAdmin(request); const p = db.packages.find((x) => x.id === params.id); if (!p) return problem(404, 'Not found'); Object.assign(p, await request.json()); return HttpResponse.json(p) })),
  http.delete(u('/admin/packages/:id'), wrap(async ({ request, params }) => { requireAdmin(request); const p = db.packages.find((x) => x.id === params.id); if (p) p.active = false; return new HttpResponse(null, { status: 204 }) })),

  http.get(u('/admin/countries'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(db.countries) })),
  http.get(u('/admin/country-groups'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(db.groups) })),
  http.post(u('/admin/country-groups'), wrap(async ({ request }) => {
    requireAdmin(request); const g = { id: uid(), ...(await request.json() as object) } as T.CountryGroup; db.groups.push(g)
    db.countries.forEach((c) => { if (g.countryCodes.includes(c.code)) c.groupId = g.id })
    return HttpResponse.json(g, { status: 201 })
  })),
  http.put(u('/admin/country-groups/:id'), wrap(async ({ request, params }) => {
    requireAdmin(request); const g = db.groups.find((x) => x.id === params.id); if (!g) return problem(404, 'Not found')
    Object.assign(g, await request.json())
    db.countries.forEach((c) => { if (c.groupId === g.id && !g.countryCodes.includes(c.code)) c.groupId = null; if (g.countryCodes.includes(c.code)) c.groupId = g.id })
    return HttpResponse.json(g)
  })),
  http.delete(u('/admin/country-groups/:id'), wrap(async ({ request, params }) => { requireAdmin(request); db.groups = db.groups.filter((x) => x.id !== params.id); db.countries.forEach((c) => { if (c.groupId === params.id) c.groupId = null }); return new HttpResponse(null, { status: 204 }) })),
  http.get(u('/admin/price-rules'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(db.priceRules) })),
  // same rule as the backend PriceResolver: COUNTRY > GROUP > CONTINENT > GLOBAL, only prices in the country's currency
  http.get(u('/admin/pricing/gaps'), wrap(async ({ request }) => {
    requireAdmin(request)
    const active = db.packages.filter((p) => p.active)
    const gaps: T.PricingGap[] = []
    for (const c of db.countries) {
      const missing = active.filter((p) => {
        const rules = db.priceRules.filter((r) => r.packageId === p.id && r.currency === c.defaultCurrency)
        return !rules.some((r) => (r.scope === 'COUNTRY' && r.scopeId === c.code) || (r.scope === 'GROUP' && r.scopeId === c.groupId) || (r.scope === 'CONTINENT' && r.scopeId === c.continent) || r.scope === 'GLOBAL')
      })
      if (missing.length) {
        const g = db.groups.find((x) => x.id === c.groupId)
        gaps.push({ countryCode: c.code, nameAr: c.nameAr, nameEn: c.nameEn, currency: c.defaultCurrency, groupId: c.groupId ?? null, groupName: g?.name ?? null, allMissing: missing.length === active.length, missing: missing.map((p) => ({ id: p.id, nameAr: p.nameAr, nameEn: p.nameEn })) })
      }
    }
    return HttpResponse.json(gaps)
  })),
  http.post(u('/admin/price-rules'), wrap(async ({ request }) => {
    requireAdmin(request); const b = (await request.json()) as Omit<T.PriceRule, 'id'>
    const existing = db.priceRules.find((r) => r.scope === b.scope && r.scopeId === b.scopeId && r.packageId === b.packageId)
    if (existing) { Object.assign(existing, b); return HttpResponse.json(existing) }
    const r = { id: uid(), ...b }; db.priceRules.push(r); return HttpResponse.json(r, { status: 201 })
  })),
  http.put(u('/admin/price-rules/:id'), wrap(async ({ request, params }) => { requireAdmin(request); const r = db.priceRules.find((x) => x.id === params.id); if (!r) return problem(404, 'Not found'); Object.assign(r, await request.json()); return HttpResponse.json(r) })),
  http.delete(u('/admin/price-rules/:id'), wrap(async ({ request, params }) => { requireAdmin(request); db.priceRules = db.priceRules.filter((x) => x.id !== params.id); return new HttpResponse(null, { status: 204 }) })),

  http.get(u('/admin/promotions'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(db.promotions) })),
  http.post(u('/admin/promotions'), wrap(async ({ request }) => { requireAdmin(request); const p = { id: uid(), usedCount: 0, ...(await request.json() as object) } as T.PromotionDto; db.promotions.push(p); return HttpResponse.json(p, { status: 201 }) })),
  http.put(u('/admin/promotions/:id'), wrap(async ({ request, params }) => { requireAdmin(request); const p = db.promotions.find((x) => x.id === params.id); if (!p) return problem(404, 'Not found'); Object.assign(p, await request.json()); return HttpResponse.json(p) })),
  http.delete(u('/admin/promotions/:id'), wrap(async ({ request, params }) => { requireAdmin(request); db.promotions = db.promotions.filter((x) => x.id !== params.id); return new HttpResponse(null, { status: 204 }) })),
  http.get(u('/admin/coupons'), wrap(async ({ request }) => { requireAdmin(request); return HttpResponse.json(db.coupons) })),
  http.post(u('/admin/coupons'), wrap(async ({ request }) => { requireAdmin(request); const c = { id: uid(), usedCount: 0, ...(await request.json() as object) } as T.CouponDto; db.coupons.push(c); return HttpResponse.json(c, { status: 201 }) })),
  http.put(u('/admin/coupons/:id'), wrap(async ({ request, params }) => { requireAdmin(request); const c = db.coupons.find((x) => x.id === params.id); if (!c) return problem(404, 'Not found'); Object.assign(c, await request.json()); return HttpResponse.json(c) })),
  http.delete(u('/admin/coupons/:id'), wrap(async ({ request, params }) => { requireAdmin(request); db.coupons = db.coupons.filter((x) => x.id !== params.id); return new HttpResponse(null, { status: 204 }) })),

  http.get(u('/admin/analytics/users'), wrap(async ({ request }) => {
    requireAdmin(request)
    const q = new URL(request.url).searchParams
    const list = q.get('list') ?? 'all'
    const qq = (q.get('q') ?? '').toLowerCase()
    const rows: T.AdminUserRow[] = db.users.filter((x) => x.role === 'USER').map((usr) => {
      const mine = db.dreams.filter((d) => d.userId === usr.id)
      const rated = mine.filter((d) => d.testimonial).map((d) => d.testimonial!.rating)
      return { id: usr.id, name: usr.name, email: usr.email, countryCode: usr.countryCode,
        visits: db.visits.filter((v) => v.userId === usr.id).reduce((a, v) => a + v.count, 0),
        dreams: mine.filter((d) => d.status !== 'DRAFT').length, drafts: mine.filter((d) => d.status === 'DRAFT').length,
        ...paidOf(usr),
        avgRating: rated.length ? rated.reduce((a, b) => a + b, 0) / rated.length : null, lastSeenAt: helpers.daysAgo(Math.floor(Math.random() * 5)) }
    }).filter((r) => !qq || r.name.toLowerCase().includes(qq) || r.email.toLowerCase().includes(qq))
    const sorted = list === 'top-visits' ? rows.sort((a, b) => b.visits - a.visits) : list === 'top-paying' ? rows.sort((a, b) => b.totalPaidBase - a.totalPaidBase)
      : list === 'has-drafts' ? rows.filter((r) => r.drafts > 0) : list === 'high-rating' ? rows.filter((r) => (r.avgRating ?? 0) >= 4) : list === 'low-rating' ? rows.filter((r) => r.avgRating !== null && r.avgRating < 3) : rows
    return HttpResponse.json(page(sorted, q))
  })),
  http.get(u('/admin/users/:id'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const usr = db.users.find((x) => x.id === params.id)
    if (!usr) return problem(404, 'Not found')
    const mine = db.dreams.filter((d) => d.userId === usr.id)
    const rated = mine.filter((d) => d.testimonial)
    const notes = db.userNotes[usr.id] ?? { notes: '', tags: [] }
    const r: T.AdminUser360 = {
      id: usr.id, name: usr.name, email: usr.email, countryCode: usr.countryCode, birthDate: usr.birthDate, age: usr.age, visits: db.visits.filter((v) => v.userId === usr.id).reduce((a, v) => a + v.count, 0),
      dreams: mine.filter((d) => d.status !== 'DRAFT').length, drafts: mine.filter((d) => d.status === 'DRAFT').length,
      ...paidOf(usr),
      avgRating: rated.length ? rated.reduce((a, d) => a + d.testimonial!.rating, 0) / rated.length : null, lastSeenAt: helpers.daysAgo(1),
      gender: usr.gender, locale: usr.locale, providers: usr.providers, createdAt: usr.createdAt,
      credits: { balance: balanceOf(usr.id), entries: db.ledger.filter((l) => l.userId === usr.id) },
      orders: db.orders.filter((o) => o.userId === usr.id), dreamList: mine.map(toSummary),
      testimonials: rated.map((d) => ({ dreamId: d.id, ...d.testimonial! })), notes: notes.notes, tags: notes.tags,
    }
    return HttpResponse.json(r)
  })),
  http.put(u('/admin/users/:id/notes'), wrap(async ({ request, params }) => { requireAdmin(request); db.userNotes[String(params.id)] = (await request.json()) as { notes: string; tags: string[] }; return new HttpResponse(null, { status: 204 }) })),
  http.post(u('/admin/users/:id/credits'), wrap(async ({ request, params }) => {
    requireAdmin(request); const { delta } = (await request.json()) as { delta: number; reason: string }
    db.ledger.push({ id: uid(), userId: String(params.id), delta, reason: 'MANUAL', orderId: null, dreamId: null, createdAt: helpers.now() })
    return new HttpResponse(null, { status: 204 })
  })),
  http.get(u('/admin/testimonials'), wrap(async ({ request }) => {
    requireAdmin(request); const q = new URL(request.url).searchParams; const ap = q.get('approved')
    const rows: T.AdminTestimonialRow[] = db.dreams.filter((d) => d.testimonial && (ap === null || String(d.testimonial.approved) === ap)).map((d) => ({ id: d.id, userName: db.users.find((x) => x.id === d.userId)!.name, dreamId: d.id, rating: d.testimonial!.rating, comment: d.testimonial!.comment, approved: d.testimonial!.approved, createdAt: d.interpretedAt! }))
    return HttpResponse.json(page(rows, q))
  })),
  http.patch(u('/admin/testimonials/:id'), wrap(async ({ request, params }) => { requireAdmin(request); const d = db.dreams.find((x) => x.id === params.id); if (d?.testimonial) d.testimonial.approved = ((await request.json()) as { approved: boolean }).approved; return new HttpResponse(null, { status: 204 }) })),
  // contract §3: counts and :id stay above the list's catch-all order
  // the counts honour the same filters, so a tab badge matches its filtered list
  http.get(u('/admin/orders/counts'), wrap(async ({ request }) => {
    requireAdmin(request); const q = new URL(request.url).searchParams
    const pf = parseFilters(q)
    if (!pf.ok) return problem(400, 'Bad Request', `Invalid filter: ${pf.field}`, { code: 'INVALID_FILTER', errors: { [pf.field]: ['invalid'] } })
    // status and flag do not narrow the badges (each tab has its own statuses)
    const f = { ...pf.f, status: undefined, flag: undefined }
    const n = (s: string) => applyFilters(scoped(s), f, (q.get('q') ?? '').trim()).length
    return HttpResponse.json({ review: n('REVIEW'), domestic: n('DOMESTIC'), international: n('INTERNATIONAL'), unpaid: n('UNPAID') } satisfies T.AdminOrderCounts)
  })),
  // withdraw the unused credits of a paid order (contract §2): dreams untouched, order stays SUCCESS
  http.post(u('/admin/payments/orders/:id/withdraw-leftover'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const o = (db.orders as MockOrder[]).find((x) => x.id === params.id)
    if (!o) return problem(404, 'Not found')
    if (o.status !== 'SUCCESS' || o.provider !== 'INSTAPAY') return problem(409, 'Conflict', 'Only a successful InstaPay order', { code: 'NOT_WITHDRAWABLE' })
    const left = leftoverOf(o)
    if (left < 1) return problem(409, 'Conflict', 'Nothing left to withdraw', { code: 'NOTHING_TO_WITHDRAW' })
    const b = (await request.json().catch(() => ({}))) as { reason?: string; note?: string }
    const reason = b.reason as T.WithdrawReason
    if (!['UNCONFIRMED', 'AMOUNT_SHORT', 'OTHER'].includes(reason)) return problem(400, 'Validation failed', 'reason is required', { errors: { reason: ['invalid'] } })
    const note = (b.note ?? '').trim()
    if (reason === 'OTHER' && !note) return problem(400, 'Validation failed', 'A note is required for OTHER', { code: 'NOTE_REQUIRED' })
    if (note.length > 500) return problem(400, 'Validation failed', 'note: at most 500 characters', { errors: { note: ['max 500'] } })
    db.ledger.push({ id: uid(), userId: o.userId, delta: -left, reason: 'REVOKE', orderId: o.id, dreamId: null, createdAt: helpers.now(), kind: 'LEFTOVER_WITHDRAWN' })
    Object.assign(o, { leftoverWithdrawnAt: helpers.now(), leftoverWithdrawnCredits: left, leftoverNote: note || null, leftoverReason: reason, leftoverRestorable: true })
    // after «خصم» the count is genitive: «رؤيتين»
    const words = left === 1 ? 'رؤيا واحدة' : left === 2 ? 'رؤيتين' : left <= 10 ? `${left} رؤى` : `${left} رؤيا`
    db.notifications.unshift({ id: uid(), userId: o.userId, type: 'CREDITS_WITHDRAWN', title: 'خصم من رصيدك', body: `تم خصم ${words} من رصيد الطلب ${o.orderNumber} لأننا لم نتمكن من تأكيد الدفع. رؤاك التي فُسِّرت أو التي قيد التعبير لا تتأثر. للاستفسار تواصل معنا.`, link: '/me/payments', createdAt: helpers.now(), readAt: null })
    return HttpResponse.json(adminOrderDetail(o))
  })),
  // give back the credits of the last withdrawal
  http.post(u('/admin/payments/orders/:id/restore-leftover'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const o = (db.orders as MockOrder[]).find((x) => x.id === params.id)
    if (!o) return problem(404, 'Not found')
    const n = o.leftoverWithdrawnCredits ?? 0
    if (!o.leftoverRestorable || n < 1) return problem(409, 'Conflict', 'Nothing to restore', { code: 'NOTHING_TO_RESTORE' })
    db.ledger.push({ id: uid(), userId: o.userId, delta: n, reason: 'REFUND', orderId: o.id, dreamId: null, createdAt: helpers.now(), kind: 'LEFTOVER_RESTORED' })
    Object.assign(o, { leftoverWithdrawnAt: null, leftoverWithdrawnCredits: null, leftoverNote: null, leftoverReason: null, leftoverRestorable: false })
    const words = n === 1 ? 'رؤيا واحدة' : n === 2 ? 'رؤيتين' : n <= 10 ? `${n} رؤى` : `${n} رؤيا`
    db.notifications.unshift({ id: uid(), userId: o.userId, type: 'CREDITS_RESTORED', title: 'أُعيد رصيدك', body: `تمت إعادة ${words} إلى رصيدك من الطلب ${o.orderNumber}.`, link: '/me/payments', createdAt: helpers.now(), readAt: null })
    return HttpResponse.json(adminOrderDetail(o))
  })),
  http.get(u('/admin/orders/:id'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const o = (db.orders as MockOrder[]).find((x) => x.id === params.id)
    return o ? HttpResponse.json(adminOrderDetail(o)) : problem(404, 'Not found')
  })),
  http.get(u('/admin/orders'), wrap(async ({ request }) => {
    requireAdmin(request); const q = new URL(request.url).searchParams
    const pf = parseFilters(q)
    if (!pf.ok) return problem(400, 'Bad Request', `Invalid filter: ${pf.field}`, { code: 'INVALID_FILTER', errors: { [pf.field]: ['invalid'] } })
    // q like the server (OrderNumbers.parseQuery): "15001", "rn-15001", "#RN 15001"; any other text finds nothing
    const rows = applyFilters(scoped(q.get('scope')), pf.f, (q.get('q') ?? '').trim())
    return HttpResponse.json(page(rows.map(adminOrderRow), q))
  })),
]
