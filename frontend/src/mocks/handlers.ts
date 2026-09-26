import { http, HttpResponse, delay } from 'msw'
import type * as T from '@/api/types'
import { db, resolvePrice, applyPromotion, balanceOf, toSummary, toDetail, uid, helpers } from './data'

const BASE = (import.meta.env.VITE_API_URL as string) || ''
const u = (p: string) => `${BASE}${p}`

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

const waitTimePublic = (locale: string): T.WaitTime => {
  const w = db.waitTime
  return { busy: w.busy, minDays: w.busyMinDays, maxDays: w.busyMaxDays, hours: w.normalHours, message: locale.startsWith('en') ? w.messageEn : w.messageAr }
}
const countryOf = (req: Request) => currentUser(req)?.countryCode ?? 'SA' // server would read CF-IPCountry

export const handlers = [
  // ---------- auth ----------
  http.post(u('/auth/google'), wrap(async () => HttpResponse.json(session(db.users[0])))),
  http.post(u('/auth/magic/request'), wrap(async () => new HttpResponse(null, { status: 204 }))),
  http.post(u('/auth/magic/verify'), wrap(async ({ request }) => {
    const body = (await request.json()) as T.MagicVerifyRequest
    const email = body.email?.toLowerCase()
    let user = db.users.find((x) => x.email.toLowerCase() === email)
    if (!user && email) {
      user = { id: uid(), name: '', email, gender: null, role: 'USER', providers: ['MAGIC_LINK'], locale: 'ar', countryCode: 'SA', countryName: 'السعودية', onboarded: false, createdAt: helpers.now() }
      db.users.push(user)
    }
    if (!user) user = db.users[0]
    // admin shortcut: email of interpreter → interpreter session
    return HttpResponse.json(session(user))
  })),
  http.post(u('/auth/refresh'), wrap(async () => problem(401, 'Unauthenticated'))),
  http.post(u('/auth/logout'), wrap(async () => new HttpResponse(null, { status: 204 }))),
  http.post(u('/auth/onboarding'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const b = (await request.json()) as T.OnboardingRequest
    Object.assign(me, { name: b.name, gender: b.gender, onboarded: true })
    return HttpResponse.json(me)
  })),

  // ---------- public ----------
  http.get(u('/public/catalog'), wrap(async ({ request }) => {
    const cc = countryOf(request)
    const c = db.countries.find((x) => x.code === cc)
    const locale = request.headers.get('accept-language') ?? 'ar'
    const packages: T.PackageDto[] = db.packages.filter((p) => p.active).sort((a, b) => a.sortOrder - b.sortOrder).map((p) => {
      const r = resolvePrice(cc, p.id)
      const a = applyPromotion(p.id, r.price)
      return { id: p.id, name: locale.startsWith('en') ? p.nameEn : p.nameAr, description: locale.startsWith('en') ? p.descriptionEn : p.descriptionAr, credits: p.credits, badge: p.badge, price: a.price, originalPrice: a.original, currency: r.currency, promotion: a.promo, validityMonths: p.validityMonths }
    })
    return HttpResponse.json({ countryCode: cc, countryName: c?.nameAr ?? cc, currency: packages[0]?.currency ?? 'USD', packages } satisfies T.Catalog)
  })),
  http.get(u('/public/wait-time'), wrap(async ({ request }) => HttpResponse.json(waitTimePublic(request.headers.get('accept-language') ?? 'ar')))),
  http.get(u('/public/testimonials'), wrap(async () => HttpResponse.json({ items: [
    { id: 't1', name: 'أم محمد', rating: 5, comment: 'تحقق والحمد لله بعد أسبوعين، وكان التفسير هادئاً ومطمئناً.', date: helpers.daysAgo(20) },
    { id: 't2', name: 'خالد', rating: 5, comment: 'سألتني المعبّرة سؤالين قبل التفسير، وكان الفرق واضحاً في الدقة.', date: helpers.daysAgo(35) },
    { id: 't3', name: 'Sara', rating: 4, comment: 'Calm, deep and honest. No exaggeration at all.', date: helpers.daysAgo(50) },
  ] }))),
  http.get(u('/public/stats'), wrap(async () => HttpResponse.json({ subscribers: '50K+', views: '1M+', videos: '230+' }))),

  // ---------- me ----------
  http.get(u('/me'), wrap(async ({ request }) => HttpResponse.json(requireUser(request)))),
  http.get(u('/me/dashboard'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const mine = db.dreams.filter((d) => d.userId === me.id)
    const s: T.DashboardSummary = {
      drafts: mine.filter((d) => d.status === 'DRAFT').length,
      inReview: mine.filter((d) => d.status === 'IN_REVIEW').length,
      awaitingReply: mine.filter((d) => d.status === 'AWAITING_USER_REPLY').length,
      interpreted: mine.filter((d) => d.status === 'INTERPRETED').length,
      credits: balanceOf(me.id),
      unreadNotifications: db.notifications.filter((n) => n.userId === me.id && !n.readAt).length,
      waitTime: waitTimePublic(request.headers.get('accept-language') ?? 'ar'),
    }
    return HttpResponse.json(s)
  })),
  http.put(u('/me/preferences'), wrap(async ({ request }) => {
    const me = requireUser(request)
    Object.assign(me, await request.json())
    return HttpResponse.json(me)
  })),
  http.get(u('/me/credits'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const entries = db.ledger.filter((l) => l.userId === me.id).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
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
    const list = db.dreams.filter((d) => d.userId === me.id && (!st || d.status === st)).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(toSummary)
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
    const d = { id: uid(), userId: me.id, gender: b.gender, status: 'DRAFT' as T.DreamStatus, createdAt: helpers.now(), submittedAt: null, interpretedAt: null, expectedBy: null, text: b.text, messages: [], interpretation: null, testimonial: null, credit: null }
    db.dreams.unshift(d)
    return HttpResponse.json(toDetail(d), { status: 201 })
  })),
  http.put(u('/dreams/:id'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const d = db.dreams.find((x) => x.id === params.id && x.userId === me.id)
    if (!d) return problem(404, 'Not found')
    if (d.status !== 'DRAFT') return problem(409, 'Conflict', 'لا يمكن تعديل رؤيا مقدَّمة')
    const b = (await request.json()) as T.DreamDraftRequest
    Object.assign(d, { text: b.text, gender: b.gender })
    return HttpResponse.json(toDetail(d))
  })),
  http.delete(u('/dreams/:id'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const i = db.dreams.findIndex((x) => x.id === params.id && x.userId === me.id && x.status === 'DRAFT')
    if (i < 0) return problem(404, 'Not found')
    db.dreams.splice(i, 1)
    return new HttpResponse(null, { status: 204 })
  })),
  http.post(u('/dreams/submit'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const b = (await request.json()) as T.SubmitDreamsRequest
    const drafts = b.dreamIds.map((id) => db.dreams.find((d) => d.id === id && d.userId === me.id && d.status === 'DRAFT')).filter(Boolean) as typeof db.dreams
    if (drafts.length !== b.dreamIds.length) return problem(409, 'Conflict', 'بعض الرؤى ليست مسودات')
    const bal = balanceOf(me.id)
    if (bal < drafts.length) return problem(402, 'Payment required', `تحتاج ${drafts.length - bal} رؤيا إضافية`, { code: 'INSUFFICIENT_CREDITS' })
    const w = db.waitTime
    const hours = w.busy ? w.busyMaxDays * 24 : w.normalHours
    for (const d of drafts) {
      const entry = { id: uid(), userId: me.id, delta: -1, reason: 'SUBMIT' as const, orderId: null, dreamId: d.id, createdAt: helpers.now() }
      db.ledger.push(entry)
      Object.assign(d, { status: 'IN_REVIEW', submittedAt: helpers.now(), expectedBy: new Date(Date.now() + hours * 36e5).toISOString(), credit: { ledgerEntryId: entry.id, orderId: null } })
      db.notifications.unshift({ id: uid(), userId: me.id, type: 'DREAM_RECEIVED', title: 'وصلت رؤياك', body: 'سنبدأ دراستها، وسيصلك إشعار عند جاهزية التفسير.', link: `/me/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
      db.notifications.unshift({ id: uid(), userId: 'admin', type: 'DREAM_SUBMITTED', title: 'رؤية جديدة', body: `${me.name} قدّمت رؤيا جديدة.`, link: `/admin/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
    }
    return HttpResponse.json({ submitted: drafts.map((d) => d.id), remainingCredits: balanceOf(me.id) } satisfies T.SubmitDreamsResponse)
  })),
  http.post(u('/dreams/:id/messages'), wrap(async ({ request, params }) => {
    const me = requireUser(request)
    const d = db.dreams.find((x) => x.id === params.id && x.userId === me.id)
    if (!d) return problem(404, 'Not found')
    if (d.status !== 'AWAITING_USER_REPLY') return problem(409, 'Conflict', 'لا يوجد استفسار مفتوح')
    const { body } = (await request.json()) as { body: string }
    const m: T.DreamMessage = { id: uid(), senderRole: 'USER', body, createdAt: helpers.now(), readAt: null }
    d.messages.push(m)
    d.status = 'IN_REVIEW'
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

  // ---------- checkout ----------
  http.post(u('/checkout'), wrap(async ({ request }) => {
    const me = requireUser(request)
    const b = (await request.json()) as T.CheckoutRequest
    const pkg = db.packages.find((p) => p.id === b.packageId && p.active)
    if (!pkg) return problem(404, 'Not found')
    const r = resolvePrice(me.countryCode, pkg.id)
    let { price } = applyPromotion(pkg.id, r.price)
    if (b.couponCode) {
      const c = db.coupons.find((x) => x.active && x.code.toLowerCase() === b.couponCode!.toLowerCase())
      if (!c) return problem(422, 'Invalid coupon', 'كود الخصم غير صالح')
      price = c.type === 'PERCENT' ? Math.round(price * (1 - c.value / 100)) : Math.max(0, price - c.value)
    }
    const order: T.OrderDto & { userId: string } = { id: uid(), userId: me.id, packageName: pkg.nameAr, credits: pkg.credits, amount: price, currency: r.currency, status: 'INITIATED', provider: r.currency === 'EGP' ? 'PAYMOB' : 'MOR', providerRef: null, countryCode: me.countryCode, createdAt: helpers.now(), paidAt: null }
    db.orders.unshift(order)
    // mock: simulate webhook success after 4s, then auto-submit selected drafts
    setTimeout(() => {
      order.status = 'SUCCESS'; order.paidAt = helpers.now(); order.providerRef = `${order.provider}-${uid().toUpperCase()}`
      db.ledger.push({ id: uid(), userId: me.id, delta: pkg.credits, reason: 'PURCHASE', orderId: order.id, dreamId: null, createdAt: helpers.now() })
      db.notifications.unshift({ id: uid(), userId: me.id, type: 'PAYMENT_SUCCESS', title: 'تم الدفع', body: `أُضيفت ${pkg.credits} رؤى إلى رصيدك.`, link: '/me/payments', createdAt: helpers.now(), readAt: null })
      if (b.dreamIds?.length) {
        const hours = db.waitTime.busy ? db.waitTime.busyMaxDays * 24 : db.waitTime.normalHours
        for (const id of b.dreamIds) {
          const d = db.dreams.find((x) => x.id === id && x.userId === me.id && x.status === 'DRAFT')
          if (!d || balanceOf(me.id) < 1) continue
          const entry = { id: uid(), userId: me.id, delta: -1, reason: 'SUBMIT' as const, orderId: order.id, dreamId: d.id, createdAt: helpers.now() }
          db.ledger.push(entry)
          Object.assign(d, { status: 'IN_REVIEW', submittedAt: helpers.now(), expectedBy: new Date(Date.now() + hours * 36e5).toISOString(), credit: { ledgerEntryId: entry.id, orderId: order.id } })
        }
      }
    }, 4000)
    return HttpResponse.json({ orderId: order.id, provider: order.provider, checkoutUrl: `/checkout/mock/${order.id}`, amount: price, currency: r.currency, expiresAt: new Date(Date.now() + 30 * 6e4).toISOString() } satisfies T.CheckoutResponse)
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
  http.post(u('/push/subscriptions'), wrap(async () => new HttpResponse(null, { status: 204 }))),

  // ---------- admin ----------
  http.get(u('/admin/analytics/summary'), wrap(async ({ request }) => {
    requireAdmin(request)
    const all = db.dreams
    const s: T.AdminSummary = {
      inReview: all.filter((d) => d.status === 'IN_REVIEW').length,
      awaitingReply: all.filter((d) => d.status === 'AWAITING_USER_REPLY').length,
      overdue: all.filter((d) => d.status === 'IN_REVIEW' && d.expectedBy && new Date(d.expectedBy) < new Date()).length,
      interpretedToday: 2,
      revenue: [{ currency: 'SAR', amount: 3420 }, { currency: 'EGP', amount: 8950 }, { currency: 'USD', amount: 310 }],
      avgResponseHours: 31,
    }
    return HttpResponse.json(s)
  })),
  http.get(u('/admin/analytics/countries'), wrap(async ({ request }) => {
    requireAdmin(request)
    const q = new URL(request.url).searchParams
    const stat = (cc: string): T.CountryStat => {
      const c = db.countries.find((x) => x.code === cc)
      const users = db.users.filter((x) => x.countryCode === cc && x.role === 'USER')
      const dreams = db.dreams.filter((d) => users.some((x) => x.id === d.userId) && d.status !== 'DRAFT').length
      const rev = db.orders.filter((o) => o.countryCode === cc && o.status === 'SUCCESS').reduce((a, o) => a + (o.currency === 'SAR' ? o.amount * 3.75 : o.currency === 'EGP' ? o.amount * 0.021 : o.amount), 0)
      return { countryCode: cc, countryName: c?.nameAr ?? cc, visits: db.visits.filter((v) => v.countryCode === cc).reduce((a, v) => a + v.count, 0), dreams: dreams + (cc === 'EG' ? 38 : cc === 'SA' ? 27 : cc === 'AE' ? 6 : cc === 'MA' ? 4 : cc === 'DE' ? 3 : 1), revenueBase: Math.round(rev + (cc === 'EG' ? 620 : cc === 'SA' ? 910 : cc === 'AE' ? 190 : cc === 'MA' ? 60 : cc === 'DE' ? 70 : 15)), users: users.length + (cc === 'EG' ? 210 : cc === 'SA' ? 160 : 20) }
    }
    const codes = [...new Set(db.visits.map((v) => v.countryCode))]
    const stats = codes.map(stat)
    const r: T.AdminCountryDashboard = {
      period: (q.get('period') as T.AdminCountryDashboard['period']) ?? '30d', baseCurrency: 'USD',
      topVisits: [...stats].sort((a, b) => b.visits - a.visits).slice(0, 8),
      topDreams: [...stats].sort((a, b) => b.dreams - a.dreams).slice(0, 8),
      topRevenue: [...stats].sort((a, b) => b.revenueBase - a.revenueBase).slice(0, 8),
    }
    return HttpResponse.json(r)
  })),
  http.get(u('/admin/dreams'), wrap(async ({ request }) => {
    requireAdmin(request)
    const q = new URL(request.url).searchParams
    const st = q.get('status')
    const rows: T.AdminDreamRow[] = db.dreams.filter((d) => d.status !== 'DRAFT' && (!st || d.status === st)).map((d) => {
      const usr = db.users.find((x) => x.id === d.userId)!
      return { id: d.id, userId: usr.id, userName: usr.name, gender: d.gender, excerpt: toSummary(d).excerpt, status: d.status, submittedAt: d.submittedAt!, slaDeadline: d.expectedBy!, overdue: d.status === 'IN_REVIEW' && !!d.expectedBy && new Date(d.expectedBy) < new Date(), countryCode: usr.countryCode }
    }).sort((a, b) => a.submittedAt.localeCompare(b.submittedAt))
    return HttpResponse.json(page(rows, q))
  })),
  http.get(u('/admin/dreams/:id'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const d = db.dreams.find((x) => x.id === params.id)
    if (!d) return problem(404, 'Not found')
    const usr = db.users.find((x) => x.id === d.userId)!
    const order = d.credit?.orderId ? db.orders.find((o) => o.id === d.credit!.orderId) : null
    d.messages.forEach((m) => { if (m.senderRole === 'USER') m.readAt ??= helpers.now() })
    const r: T.AdminDreamDetail = { ...toDetail(d), user: { id: usr.id, name: usr.name, email: usr.email, countryCode: usr.countryCode },
      payment: order ? { orderId: order.id, payerName: usr.name, payerEmail: usr.email, paidAt: order.paidAt ?? order.createdAt, packageName: order.packageName, amount: order.amount, currency: order.currency, provider: order.provider, providerRef: order.providerRef ?? '—', countryCode: order.countryCode } : null }
    return HttpResponse.json(r)
  })),
  http.post(u('/admin/dreams/:id/messages'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const d = db.dreams.find((x) => x.id === params.id)
    if (!d || d.status !== 'IN_REVIEW') return problem(409, 'Conflict')
    const { body } = (await request.json()) as { body: string }
    const m: T.DreamMessage = { id: uid(), senderRole: 'INTERPRETER', body, createdAt: helpers.now(), readAt: null }
    d.messages.push(m); d.status = 'AWAITING_USER_REPLY'
    db.notifications.unshift({ id: uid(), userId: d.userId, type: 'INTERPRETER_QUESTION', title: 'استفسار من المعبّرة', body: 'أرسلت المعبّرة استفساراً عن رؤياك، يرجى الرد لإكمال التفسير.', link: `/me/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
    return HttpResponse.json(m, { status: 201 })
  })),
  http.post(u('/admin/dreams/:id/interpretation'), wrap(async ({ request, params }) => {
    requireAdmin(request)
    const d = db.dreams.find((x) => x.id === params.id)
    if (!d || !['IN_REVIEW', 'AWAITING_USER_REPLY'].includes(d.status)) return problem(409, 'Conflict')
    const { text } = (await request.json()) as T.InterpretationRequest
    d.interpretation = { text, interpretedAt: helpers.now() }; d.status = 'INTERPRETED'; d.interpretedAt = helpers.now()
    db.notifications.unshift({ id: uid(), userId: d.userId, type: 'INTERPRETATION_READY', title: 'تم تفسير رؤياك', body: 'التفسير جاهز الآن في حسابك.', link: `/me/dreams/${d.id}`, createdAt: helpers.now(), readAt: null })
    return new HttpResponse(null, { status: 204 })
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
        totalPaidBase: db.orders.filter((o) => o.userId === usr.id && o.status === 'SUCCESS').reduce((a, o) => a + (o.currency === 'SAR' ? o.amount / 3.75 : o.currency === 'EGP' ? o.amount / 48 : o.amount), 0),
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
      id: usr.id, name: usr.name, email: usr.email, countryCode: usr.countryCode, visits: db.visits.filter((v) => v.userId === usr.id).reduce((a, v) => a + v.count, 0),
      dreams: mine.filter((d) => d.status !== 'DRAFT').length, drafts: mine.filter((d) => d.status === 'DRAFT').length,
      totalPaidBase: db.orders.filter((o) => o.userId === usr.id && o.status === 'SUCCESS').reduce((a, o) => a + (o.currency === 'SAR' ? o.amount / 3.75 : o.currency === 'EGP' ? o.amount / 48 : o.amount), 0),
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
  http.get(u('/admin/orders'), wrap(async ({ request }) => {
    requireAdmin(request); const q = new URL(request.url).searchParams
    return HttpResponse.json(page(db.orders.map((o) => ({ ...o, userName: db.users.find((x) => x.id === o.userId)?.name ?? '' })).sort((a, b) => b.createdAt.localeCompare(a.createdAt)), q))
  })),
]
