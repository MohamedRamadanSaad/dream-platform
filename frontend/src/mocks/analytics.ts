// Mock analytics for the preview: a deterministic, realistic traffic history (the same numbers on every call)
// plus the real POST /public/track hits recorded in this browser session. Honors from/to, the comparison range
// and the country / device / path filters (as on the server, filters scale the page-view metrics only).
import type * as T from '@/api/types'
import { db, ytVideos } from './data'

const DAY_MS = 864e5
const pad = (n: number) => String(n).padStart(2, '0')
export const isoDay = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const parse = (s: string) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d) }
const shift = (s: string, n: number) => { const d = parse(s); d.setDate(d.getDate() + n); return isoDay(d) }
const span = (a: string, b: string) => Math.round((parse(b).getTime() - parse(a).getTime()) / DAY_MS)
const isDay = (s: string) => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(parse(s).getTime())

/** Stable pseudo-random number in [0, 1) for a seed. */
function rand(seed: string) {
  let h = 2166136261
  for (let i = 0; i < seed.length; i++) { h ^= seed.charCodeAt(i); h = Math.imul(h, 16777619) }
  h ^= h >>> 13; h = Math.imul(h, 0x5bd1e995); h ^= h >>> 15
  return (h >>> 0) / 4294967296
}

const HISTORY_START = '2025-08-01'
const COUNTRY_SHARE: Record<string, number> = { EG: 0.31, SA: 0.26, AE: 0.08, KW: 0.05, MA: 0.05, JO: 0.04, DZ: 0.04, DE: 0.03, US: 0.03, GB: 0.025, QA: 0.02, TN: 0.015, FR: 0.015, TR: 0.01, OM: 0.01, BH: 0.01, CA: 0.005, AU: 0.005 }
const DEVICE_SHARE: Record<T.DeviceType, number> = { MOBILE: 0.71, DESKTOP: 0.23, TABLET: 0.06 }
const PAGE_SHARE: Record<string, number> = { '/': 0.36, '/me': 0.13, '/login': 0.1, '/me/new': 0.08, '/me/packages': 0.07, '/courses': 0.05, '/me/payments': 0.04, '/me/notifications': 0.035, '/me/profile': 0.03, '/onboarding': 0.025, '/me/checkout': 0.02, '/terms': 0.015, '/privacy': 0.01 }
const REFERRER_SHARE: [string, number][] = [['', 0.44], ['google.com', 0.21], ['youtube.com', 0.19], ['facebook.com', 0.06], ['instagram.com', 0.04], ['t.co', 0.025], ['wa.me', 0.02], ['bing.com', 0.015]]
// evenings are the busiest (business time zone), with a smaller bump after midday
const HOUR_WEIGHT = [3.2, 2.1, 1.4, 1, 0.8, 1, 1.6, 2.4, 3, 3.4, 3.8, 4.2, 4.8, 5.4, 5, 4.4, 4.3, 4.8, 5.4, 6.4, 7.4, 8.6, 8.1, 5.6]
// dreams are written late at night and right after waking up
const DREAM_HOUR_WEIGHT = [4, 2.5, 1.5, 1.2, 1.5, 3, 5.5, 6.5, 5.5, 4, 3.2, 3, 2.8, 2.6, 2.4, 2.4, 2.6, 2.8, 3, 3.6, 4.4, 5.6, 6.4, 5.2]
const WEEKDAY_WEIGHT = [1.04, 0.94, 0.9, 0.95, 1.06, 1.24, 1.16] // Sun..Sat — the weekend (Fri/Sat) is busier

export const USERS_PEAK_HOURS = HOUR_WEIGHT.map((w, h) => [h, w]).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([h]) => h)

interface Filter { country?: string; device?: T.DeviceType; path?: string }
const factor = (f: Filter) => (f.country ? COUNTRY_SHARE[f.country] ?? 0.002 : 1) * (f.device ? DEVICE_SHARE[f.device] : 1) * (f.path ? PAGE_SHARE[f.path] ?? 0.004 : 1)
const keyOf = (f: Filter) => `${f.country ?? ''}|${f.device ?? ''}|${f.path ?? ''}`

/** Unfiltered views of a day: weekly rhythm, slow growth, noise and a few YouTube spikes. Today is partial. */
function baseViews(day: string, today: string) {
  if (day < HISTORY_START || day > today) return 0
  const growth = 0.55 + 0.45 * Math.max(0, 1 - span(day, today) / 420)
  const noise = 0.82 + rand(day) * 0.36
  const spike = rand(`${day}#yt`) > 0.965 ? 1.6 + rand(`${day}#k`) : 1
  let v = 260 * WEEKDAY_WEIGHT[parse(day).getDay()] * growth * noise * spike
  if (day === today) { const now = new Date(); v *= Math.max(0.04, (now.getHours() * 60 + now.getMinutes()) / 1440) }
  return v
}

function hitsOn(day: string, f: Filter) {
  return db.pageViews.filter((h) => isoDay(new Date(h.at)) === day && (!f.country || h.countryCode === f.country) && (!f.device || h.device === f.device) && (!f.path || h.path === f.path))
}

function dayStats(day: string, today: string, f: Filter) {
  const k = keyOf(f)
  const scaled = baseViews(day, today) * factor(f) * (k === '||' ? 1 : 0.85 + rand(day + k) * 0.3)
  const hits = hitsOn(day, f)
  const ratio = (f.path ? 0.7 : 0.56) + rand(`${day}v`) * 0.08
  return {
    views: Math.round(scaled) + hits.length,
    visitors: Math.round(scaled * ratio) + new Set(hits.map((h) => h.visitorId ?? h.sessionId)).size,
    visits: Math.round(scaled * ratio * 1.3) + new Set(hits.map((h) => h.sessionId)).size,
  }
}

/** Business numbers are never filtered by country / device / path. */
function business(day: string, today: string) {
  const visitors = baseViews(day, today) * 0.56
  const dreams = Math.round(visitors * (0.016 + rand(`${day}d`) * 0.012))
  return { signups: Math.round(visitors * (0.026 + rand(`${day}s`) * 0.014)), dreams, paid: Math.round(dreams * (0.62 + rand(`${day}p`) * 0.25)) }
}

function kpis(from: string, to: string, today: string, f: Filter): T.TrafficKpis {
  let views = 0, dailyVisitors = 0, visits = 0, signups = 0, dreams = 0, paidOrders = 0
  for (let d = from; d <= to; d = shift(d, 1)) {
    const s = dayStats(d, today, f)
    const b = business(d, today)
    views += s.views; dailyVisitors += s.visitors; visits += s.visits; signups += b.signups; dreams += b.dreams; paidOrders += b.paid
  }
  const visitors = from === to ? dailyVisitors : Math.round(dailyVisitors * 0.88) // returning visitors count once
  return { views, visitors, visits, signups, dreams, paidOrders, conversionRate: visitors ? Math.round((paidOrders / visitors) * 1000) / 10 : 0 }
}

const countryName = (cc: string, locale: string) => {
  const c = db.countries.find((x) => x.code === cc)
  return (locale.startsWith('en') ? c?.nameEn : c?.nameAr) ?? cc
}

function split<K extends string>(total: number, shares: [K, number][], seed: string) {
  const sum = shares.reduce((a, [, s]) => a + s, 0)
  return shares.map(([k, s]) => ({ key: k, views: Math.round((total * s * (0.9 + rand(seed + k) * 0.2)) / sum) })).sort((a, b) => b.views - a.views)
}

function records(today: string): T.TrafficReport['records'] {
  let bestDay: { date: string; views: number } | null = null
  let totalViews = 0
  const months = new Map<string, number>()
  for (let d = HISTORY_START; d <= today; d = shift(d, 1)) {
    const v = dayStats(d, today, {}).views
    totalViews += v
    if (!bestDay || v > bestDay.views) bestDay = { date: d, views: v }
    months.set(d.slice(0, 7), (months.get(d.slice(0, 7)) ?? 0) + v)
  }
  const ranked = [...months.entries()].sort((a, b) => b[1] - a[1])
  const rank = ranked.findIndex(([m]) => m === today.slice(0, 7)) + 1
  return { bestDay, bestMonth: ranked[0] ? { month: ranked[0][0], views: ranked[0][1] } : null, totalViews, totalVisitors: Math.round(totalViews * 0.47), totalVisits: Math.round(totalViews * 0.62), thisMonthRank: rank || null }
}

/** GET /admin/analytics/traffic — null when the dates are invalid (the handler answers 400). */
export function buildTraffic(q: URLSearchParams, locale: string): T.TrafficReport | null {
  const today = isoDay(new Date())
  const qFrom = q.get('from'), qTo = q.get('to')
  if ((qFrom && !isDay(qFrom)) || (qTo && !isDay(qTo))) return null
  const to = qTo && qTo < today ? qTo : today
  const from = qFrom ?? `${to.slice(0, 8)}01`
  if (from > to) return null
  const device = q.get('device') as T.DeviceType | null
  const f: Filter = { country: q.get('country') || undefined, device: device && device in DEVICE_SHARE ? device : undefined, path: q.get('path') || undefined }

  const days = span(from, to) + 1
  const compareTo = shift(from, -1)
  const compareFrom = shift(from, -days)
  const current = kpis(from, to, today, f)
  const previous = kpis(compareFrom, compareTo, today, f)

  const daily = Array.from({ length: days }, (_, i) => {
    const date = shift(from, i)
    const s = dayStats(date, today, f)
    return { date, views: s.views, visitors: s.visitors, visits: s.visits, previousViews: dayStats(shift(compareFrom, i), today, f).views }
  })

  const hw = HOUR_WEIGHT.reduce((a, b) => a + b, 0), dw = DREAM_HOUR_WEIGHT.reduce((a, b) => a + b, 0)
  const hourly = HOUR_WEIGHT.map((w, hour) => ({
    hour,
    views: Math.round((current.views * w * (0.9 + rand(`${from}${to}${hour}`) * 0.2)) / hw),
    dreams: Math.round((current.dreams * DREAM_HOUR_WEIGHT[hour]) / dw),
  }))

  // pages: the generated share of the known pages plus every path tracked in this session
  const tracked = db.pageViews.filter((h) => {
    const d = isoDay(new Date(h.at))
    return d >= from && d <= to && (!f.country || h.countryCode === f.country) && (!f.device || h.device === f.device)
  })
  const hitsFor = (path: string) => tracked.filter((h) => h.path === path).length
  const topPages = f.path
    ? [{ path: f.path, views: current.views, visitors: current.visitors }]
    : split(Math.max(0, current.views - tracked.length), Object.entries(PAGE_SHARE) as [string, number][], from)
        .map((p) => ({ path: p.key, views: p.views + hitsFor(p.key) }))
        .concat([...new Set(tracked.map((h) => h.path))].filter((p) => !(p in PAGE_SHARE)).map((p) => ({ path: p, views: hitsFor(p) })))
        .map((p) => ({ ...p, visitors: Math.max(1, Math.round(p.views * (0.55 + rand(`${p.path}pv`) * 0.2))) }))
  topPages.sort((a, b) => b.views - a.views)

  const topCountries = (f.country ? [{ key: f.country, views: current.views }] : split(current.views, Object.entries(COUNTRY_SHARE) as [string, number][], `${from}c`))
    .slice(0, 10)
    .map((c) => ({ countryCode: c.key, countryName: countryName(c.key, locale), views: c.views, visitors: Math.round(c.views * 0.58) }))
  const devices = (f.device ? [{ key: f.device, views: current.views }] : split(current.views, Object.entries(DEVICE_SHARE) as [T.DeviceType, number][], `${from}d`))
    .map((d) => ({ device: d.key, views: d.views }))
  const referrers = split(current.views, REFERRER_SHARE, `${from}r`).map((r) => ({ host: r.key, views: r.views }))

  return { from, to, compareFrom, compareTo, current, previous, daily, hourly, topPages: topPages.slice(0, 50), topCountries, devices, referrers, records: records(today) }
}

// ---------------------------------------------------------------- insights

const fmt = (n: number, ar: boolean) => n.toLocaleString(ar ? 'ar-EG-u-nu-latn' : 'en-US')
const hourText = (h: number, ar: boolean) => new Intl.DateTimeFormat(ar ? 'ar-EG-u-nu-latn' : 'en-US', { hour: 'numeric' }).format(new Date(2000, 0, 1, h))

const TEXT = {
  ar: {
    overdue: (n: string) => ({ title: `رؤى تجاوزت موعدها: ${n}`, body: 'تجاوزت هذه الرؤى مدة الرد الموعودة. ابدئي بالأقدم، ويصل لصاحبها إشعار فور نشر التفسير.' }),
    awaiting: (n: string) => ({ title: `أصحاب رؤى لم يردّوا منذ ٣ أيام: ${n}`, body: 'سألتِهم قبل أكثر من ثلاثة أيام ولم يصل رد بعد. تُرسل لهم رسالة تذكير تلقائياً، وعدّاد المدة متوقف حتى يردّوا.' }),
    busyOff: { title: 'وضع الضغط مفعّل والقائمة قصيرة', body: 'عدد الرؤى المنتظرة قليل الآن. إن أوقفتِ وضع الضغط يرى الناس مدة رد أقصر فيطمئنون أكثر.' },
    busyOn: { title: 'القائمة أطول من طاقتك المعتادة', body: 'فعّلي وضع الضغط ليرى الناس مدة رد واقعية قبل أن يرسلوا رؤاهم.' },
    testimonials: (n: string) => ({ title: `تقييمات بانتظار اعتمادك: ${n}`, body: 'التقييمات المعتمدة تظهر في الصفحة الرئيسية وتطمئن الزوار الجدد.' }),
    visitsUp: (p: string) => ({ title: `الزيارات ارتفعت ${p}٪ هذا الشهر`, body: 'مقارنةً بالأيام نفسها من الشهر الماضي. المحتوى يصل إلى الناس، بارك الله فيه.' }),
    visitsDown: (p: string) => ({ title: `الزيارات انخفضت ${p}٪ هذا الشهر`, body: 'مقارنةً بالأيام نفسها من الشهر الماضي. فيديو جديد على القناة يعيد الزوار عادةً.' }),
    peakHours: (h: string) => ({ title: `الزوار أنشط ما يكونون قرب ${h}`, body: 'إن فسّرتِ قرب هذا الوقت يصل الرد وأصحاب الرؤى متصلون، فيبدو الرد أسرع.' }),
    youtube: (day: string, h: string) => ({ title: 'أفضل وقت لنشر الفيديو القادم', body: `يوم ${day} قرب ${h}، وهو وقت ذروة زوار الموقع.` }),
    price: (c: string) => ({ title: `زيارات كثيرة من ${c} بلا مدفوعات`, body: 'لم تتم أي عملية دفع من هذا البلد خلال ٣٠ يوماً رغم الزيارات. ربما يكون السعر هناك مرتفعاً، فراجعيه.' }),
    fast: (h: string) => ({ title: 'ردودك سريعة، ما شاء الله', body: `متوسط ردك ${h} ساعة، أي أقل من نصف المدة الموعودة.` }),
    streak: (n: string) => ({ title: `${n} أيام متتالية من العطاء`, body: 'فسّرتِ رؤيا واحدة على الأقل كل يوم. بارك الله في جهدك.' }),
    more: (a: string, b: string) => ({ title: 'فسّرتِ هذا الشهر أكثر من الشهر الماضي', body: `${a} رؤيا حتى الآن مقابل ${b} في الشهر الماضي.` }),
  },
  en: {
    overdue: (n: string) => ({ title: `Late dreams: ${n}`, body: 'These dreams passed the promised reply time. Start with the oldest one. The dreamer is told as soon as you publish.' }),
    awaiting: (n: string) => ({ title: `No reply for 3 days: ${n}`, body: 'You asked them a question more than three days ago. A reminder e-mail goes out automatically, and their timer is paused.' }),
    busyOff: { title: 'Busy mode is on, but the queue is short', body: 'Few dreams are waiting now. If you turn busy mode off, people see a shorter reply time.' },
    busyOn: { title: 'The queue is longer than usual', body: 'Turn on busy mode so people see a realistic reply time before they send a dream.' },
    testimonials: (n: string) => ({ title: `Reviews waiting for you: ${n}`, body: 'Approved reviews appear on the home page and reassure new visitors.' }),
    visitsUp: (p: string) => ({ title: `Visits are up ${p}% this month`, body: 'Compared with the same days last month. Your content is reaching people.' }),
    visitsDown: (p: string) => ({ title: `Visits are down ${p}% this month`, body: 'Compared with the same days last month. A new video on the channel usually brings visitors back.' }),
    peakHours: (h: string) => ({ title: `Visitors are most active around ${h}`, body: 'If you interpret near this time, people are online when your reply arrives, so it feels faster.' }),
    youtube: (day: string, h: string) => ({ title: 'Best time for your next video', body: `${day} around ${h}, when the site has the most visitors.` }),
    price: (c: string) => ({ title: `Many visits from ${c}, but no payments`, body: 'Nobody from this country paid in the last 30 days, even with many visits. The price there may be too high.' }),
    fast: (h: string) => ({ title: 'Your replies are fast', body: `You reply in ${h} hours on average, less than half of the promised time.` }),
    streak: (n: string) => ({ title: `${n} days in a row`, body: 'You interpreted at least one dream every day. May Allah bless your effort.' }),
    more: (a: string, b: string) => ({ title: 'More interpretations than last month', body: `${a} so far this month, against ${b} last month.` }),
  },
}

const ORDER: Record<T.InsightKind, number> = { WARNING: 0, TIP: 1, SUCCESS: 2, INFO: 3 }

/** GET /admin/analytics/insights — texts follow Accept-Language like the server's messages_ar/en. */
export function buildInsights(locale: string): T.InsightsResponse {
  const ar = !locale.startsWith('en')
  const tx = ar ? TEXT.ar : TEXT.en
  const now = Date.now()
  const today = isoDay(new Date())
  const month = today.slice(0, 7)
  const w = db.waitTime
  const slaHours = w.busy ? w.busyMaxDays * 24 : w.normalHours
  const myActivity: T.MyActivity = {
    interpretedThisMonth: 3 + db.dreams.filter((d) => d.status === 'INTERPRETED' && d.interpretedAt && isoDay(new Date(d.interpretedAt)).startsWith(month)).length,
    interpretedLastMonth: 27,
    avgResponseHours: 31,
    slaHours,
    onTimeRate: 94,
    myBusiestHours: [10, 11, 13],
    usersPeakHours: USERS_PEAK_HOURS,
    streakDays: 5,
  }

  const items: T.Insight[] = []
  const add = (id: string, kind: T.InsightKind, t: { title: string; body: string }, link: string | null = null) => items.push({ id, kind, ...t, link })

  const overdue = db.dreams.filter((d) => d.status === 'IN_REVIEW' && d.expectedBy && new Date(d.expectedBy).getTime() < now).length
  if (overdue) add('overdue', 'WARNING', tx.overdue(fmt(overdue, ar)), '/admin/queue')

  const silent = db.dreams.filter((d) => {
    const asked = [...d.messages].reverse().find((m) => m.senderRole === 'INTERPRETER')
    return d.status === 'AWAITING_USER_REPLY' && asked && now - new Date(asked.createdAt).getTime() > 3 * DAY_MS
  }).length
  if (silent) add('awaiting-users', 'INFO', tx.awaiting(fmt(silent, ar)))

  const queue = db.dreams.filter((d) => d.status === 'IN_REVIEW').length
  const capacity = 3.2 * (slaHours / 24) // her 7-day average daily output × SLA days
  if (!w.busy && queue > capacity) add('busy-on', 'TIP', tx.busyOn, '/admin/wait-time')
  if (w.busy && queue < capacity / 2) add('busy-off', 'TIP', tx.busyOff, '/admin/wait-time')

  const pending = db.dreams.filter((d) => d.testimonial && !d.testimonial.approved).length
  if (pending) add('testimonials', 'TIP', tx.testimonials(fmt(pending, ar)), '/admin/testimonials')

  const monthStart = `${month}-01`
  const days = span(monthStart, today) + 1
  const thisMonth = kpis(monthStart, today, today, {}).views
  const lastMonth = kpis(shift(monthStart, -days), shift(monthStart, -1), today, {}).views
  const change = lastMonth ? Math.round(((thisMonth - lastMonth) / lastMonth) * 100) : 0
  if (change >= 10) add('visits-up', 'SUCCESS', tx.visitsUp(fmt(change, ar)))
  if (change <= -10) add('visits-down', 'WARNING', tx.visitsDown(fmt(-change, ar)))

  if (!myActivity.usersPeakHours.some((h) => myActivity.myBusiestHours.includes(h))) add('peak-hours', 'TIP', tx.peakHours(hourText(myActivity.usersPeakHours[0], ar)))

  // (mock) only suggested when no video went out in the last week
  const lastVideo = Math.max(...ytVideos.map((v) => new Date(v.publishedAt).getTime()))
  if (now - lastVideo > 7 * DAY_MS) {
    const friday = new Date(2026, 0, 2) // a Friday: the busiest weekday
    add('youtube-time', 'TIP', tx.youtube(new Intl.DateTimeFormat(ar ? 'ar-EG-u-nu-latn' : 'en-US', { weekday: 'long' }).format(friday), hourText(myActivity.usersPeakHours[0], ar)))
  }

  const paid30 = new Set(db.orders.filter((o) => o.status === 'SUCCESS' && o.paidAt && now - new Date(o.paidAt).getTime() < 30 * DAY_MS).map((o) => o.countryCode))
  const unpaid = Object.entries(COUNTRY_SHARE).sort((a, b) => b[1] - a[1]).slice(0, 3).find(([cc]) => !paid30.has(cc))
  if (unpaid) add(`price-${unpaid[0]}`, 'TIP', tx.price(countryName(unpaid[0], locale)), '/admin/pricing')

  if (myActivity.avgResponseHours < slaHours / 2) add('fast-replies', 'SUCCESS', tx.fast(fmt(myActivity.avgResponseHours, ar)))
  if (myActivity.streakDays >= 3) add('streak', 'SUCCESS', tx.streak(fmt(myActivity.streakDays, ar)))
  if (myActivity.interpretedThisMonth > myActivity.interpretedLastMonth) add('more-than-last-month', 'SUCCESS', tx.more(fmt(myActivity.interpretedThisMonth, ar), fmt(myActivity.interpretedLastMonth, ar)))

  items.sort((a, b) => ORDER[a.kind] - ORDER[b.kind])
  return { items: items.slice(0, 8), myActivity }
}

/** Rough device class from the User-Agent, like the server does. */
export function deviceOf(ua: string): T.DeviceType {
  if (/ipad|tablet/i.test(ua)) return 'TABLET'
  if (/mobi|android|iphone/i.test(ua)) return 'MOBILE'
  return 'DESKTOP'
}
