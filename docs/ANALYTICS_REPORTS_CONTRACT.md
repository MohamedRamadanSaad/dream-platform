# Analytics, insights, reports & email events — contract

Shared contract for backend + frontend. JSON camelCase, dates ISO-8601 UTC, `date` fields `YYYY-MM-DD`
in the business time zone (setting `app.time_zone` / existing Cairo/Riyadh setting used by jobs).
All routes go in `ApiPaths`. `/admin/**` = ROLE_INTERPRETER.

## 1. Page-view tracking (every page visit recorded)

`POST /public/track` — open (token optional; if a valid Bearer is sent, attach userId). 204. Rate limit 60/min/IP.
```ts
interface TrackRequest { path: string; referrer?: string | null; sessionId: string } // path without query, max 255; sessionId = random id kept in sessionStorage
```
Server stores `page_views(id, path, session_id, visitor_id?, user_id null, country_code (server-detected, same CountryResolver), device 'MOBILE'|'TABLET'|'DESKTOP' (from User-Agent), referrer_host null, created_at)`.
Paths starting with `/admin` are ignored (not stored). Bots (UA contains bot|crawler|spider|preview) ignored.
"Visitors" = distinct session_id. The frontend sends one track call per route change.

## 2. Traffic analytics with filter

`GET /admin/analytics/traffic?from=YYYY-MM-DD&to=YYYY-MM-DD&country=XX&device=MOBILE&path=/x`
All params optional. Default range = current calendar month up to today. Comparison range = the same
number of days immediately before `from` (so default = this month vs the same days of last month).
```ts
interface TrafficKpis { views: number; visitors: number; signups: number; dreams: number; paidOrders: number; conversionRate: number /* paidOrders / visitors * 100, 1 decimal */ }
interface TrafficReport {
  from: string; to: string; compareFrom: string; compareTo: string
  current: TrafficKpis
  previous: TrafficKpis
  daily: { date: string; views: number; visitors: number; previousViews: number }[]   // one row per day of current range; previousViews = views on the aligned day of the compare range
  hourly: { hour: number; views: number; dreams: number }[]                            // 24 rows, 0..23 business time zone, over current range
  topPages: { path: string; views: number; visitors: number }[]                        // max 10
  topCountries: { countryCode: string; countryName: string; views: number; visitors: number }[] // max 10, name localized by Accept-Language
  devices: { device: 'MOBILE' | 'TABLET' | 'DESKTOP'; views: number }[]
  referrers: { host: string; views: number }[]                                         // max 8, '' = direct
  records: {                                                                          // all-time "score records"
    bestDay: { date: string; views: number } | null
    bestMonth: { month: string /* YYYY-MM */; views: number } | null
    totalViews: number
    totalVisitors: number
    thisMonthRank: number | null   // rank of the current month by views among all months (1 = best ever)
  }
}
```
signups = users.created_at in range; dreams = dreams.submitted_at in range; paidOrders = orders SUCCESS paid_at in range.
Country/device/path filters apply to the page-view metrics only.

## 3. Insights & recommendations (for the interpreter)

`GET /admin/analytics/insights` — localized by Accept-Language (texts from messages_ar/en.properties, no literals in Java).
```ts
type InsightKind = 'SUCCESS' | 'INFO' | 'TIP' | 'WARNING'
interface Insight { id: string; kind: InsightKind; title: string; body: string; link: string | null /* frontend route e.g. '/admin/queue' */ }
interface InsightsResponse {
  items: Insight[]   // sorted WARNING, TIP, SUCCESS, INFO; max 8
  myActivity: {
    interpretedThisMonth: number; interpretedLastMonth: number
    avgResponseHours: number          // submitted → interpreted, last 30 days
    slaHours: number                  // current SLA (busy ? busyMaxDays*24 : normalHours)
    onTimeRate: number                // % interpreted before expected_by, last 30 days
    myBusiestHours: number[]          // top 3 hours (0..23) she interprets in, last 60 days
    usersPeakHours: number[]          // top 3 hours users submit dreams / visit, last 30 days
    streakDays: number                // consecutive days (ending today/yesterday) with ≥1 interpretation
  }
}
```
Rules (each emits one insight when true; skip if not applicable):
- overdue dreams > 0 → WARNING, link `/admin/queue`
- dreams AWAITING_USER_REPLY older than 3 days → INFO (users not answering)
- queue (IN_REVIEW) larger than her 7-day average daily output × SLA days → TIP "turn on busy mode", link `/admin/wait-time`; busy mode on but queue small → TIP "turn busy mode off"
- testimonials pending approval > 0 → TIP, link `/admin/testimonials`
- visits this month vs last month (same days) up ≥10% → SUCCESS, down ≥10% → WARNING
- users peak hours differ from her busiest hours → TIP "interpret around HH:00 when users are most active, replies feel faster"
- best hour/day for publishing a YouTube video = users' peak hour → TIP
- top visiting country with views but 0 paid orders in 30 days → TIP "review the price in <country>", link `/admin/pricing`
- avgResponseHours < slaHours/2 → SUCCESS
- streakDays ≥ 3 → SUCCESS
- interpreted this month > last month → SUCCESS

## 4. Reports / downloads (binary responses, `Content-Disposition: attachment`)

Interpreter:
- `GET /admin/dreams/{id}/pdf` → PDF of one dream: person (name, e-mail, gender, birth date, age, country, joined, credits balance, total dreams), dream text, status, dates (submitted, expected, interpreted), all messages, interpretation, payment (order, amount, currency).
- `GET /admin/users/{id}/pdf` → PDF: same person block + every non-draft dream of that user with its interpretation.
- `GET /admin/dreams/export?status=&from=&to=&country=&gender=&q=` → `.xlsx`. One row per non-draft dream matching the filters (`from/to` on submitted_at date; `q` searches user name/email/dream text). Columns (header language by Accept-Language): dream id, submitted at, status, expected by, interpreted at, user name, email, gender, age, country, dream text, interpretation, messages count. Frozen header, auto-filter, RTL sheet when Arabic.

User (owner only):
- `GET /dreams/{id}/pdf` → PDF of own non-draft dream (no e-mail / internal payment refs).
- `GET /me/dreams/pdf` → PDF of all own non-draft dreams with interpretations.

PDF: server-rendered from a Thymeleaf HTML template with the brand look (night/navy/gold/pearl), IBM Plex Sans Arabic embedded, correct Arabic shaping + RTL for `ar`, LTR for `en` (locale = Accept-Language for admin, user's locale for user).

Frontend downloads via `fetch` with the Bearer token → blob → save (filename from Content-Disposition).

## 5. E-mail events (one e-mail per meaningful action, ar + en, existing layout)

Already present: magic-link, dream-submitted (interpreter), dream-received (user), interpreter-question, user-replied (interpreter), interpretation-ready, payment-receipt, payment-suspicious, reply-reminder, testimonial-request, interpreter-digest, youtube-new-video.
New:
| template | to | when |
|---|---|---|
| `welcome` | user | onboarding completed |
| `payment-failed` | user | order → FAILED (provider said not successful) |
| `dream-cancelled` | user | interpreter cancels a dream (reason + credit refunded) |
| `credits-adjusted` | user | interpreter manual credit change (delta, reason, new balance) |
| `testimonial-approved` | user | interpreter approves the user's testimonial |
| `account-deleted` | user | user deletes account (sent before anonymising) |
| `new-user` | interpreter(s) | a new user finishes onboarding (name, country, age) |
| `testimonial-received` | interpreter(s) | a user submits a testimonial (awaiting approval) |

Each event is toggleable by a BOOL setting `mail.event.<template>` (default true), editable from `/admin/settings`.

## Backend clarifications (implementation notes, no field changes)

- **Business time zone** = setting `schedule.time_zone` (the one the jobs use). All `date`/`hour`/`month` buckets use it.
- **Tracking**: `path` is validated (non-blank, ≤ 255, starts with `/`) → 400 otherwise; a query string / trailing `/`
  is stripped before storing. `sessionId` ≤ 100 chars. Referrers from the site itself count as direct (`''`), the host
  is stored without `www.`. When only the configured default country is known (no Cloudflare header), the signed-in
  user's stored country is used, else none (such views are left out of `topCountries`).
- **Traffic**: `from > to` → 422 `INVALID_RANGE`; a range is at most 366 days; unknown `device` / malformed `country`
  → 422. `devices` always lists the three devices (most views first). `records` are all-time and unfiltered.
  `signups` counts accounts with role USER.
- **Insights**: insight ids = rule names (`overdue`, `awaiting-reply`, `busy-on`, `busy-off`, `testimonials-pending`,
  `traffic-up`, `traffic-down`, `peak-hours`, `youtube-time`, `country-price`, `fast-response`, `streak`,
  `interpreted-up`). The overdue link is `/admin/queue` as written above — the SPA queue route today is `/admin/dreams`,
  so the frontend should map/redirect `/admin/queue`. "Visits" in the traffic rule = visitors (distinct sessions),
  this month's days 1..today vs the same day numbers of last month (skipped when last month had none). Thresholds are
  settings: `insights.awaiting_reply_days` (3), `insights.traffic_change_percent` (10), `insights.streak_min_days` (3).
  The product has one interpreter, so `myActivity` counts every interpretation.
- **PDF file names**: dream → `dream-<first 8 chars of id>.pdf`, user → `user-<first 8>.pdf`, `/me/dreams/pdf` →
  `dreams-YYYY-MM-DD.pdf`. CORS exposes `Content-Disposition`.
- **PDF Arabic text** is printed without diacritics (tashkeel: harakat, tanween, shadda, Quranic signs): the PDF engine
  cannot position combining marks, so they would float beside or collide with the letters. Letters, words and order
  are unchanged; the stored texts keep their marks.
- **Excel**: `gender` filters (and shows) the dream's gender; rows are newest submission first; row cap
  `reports.excel_max_rows` (50000).
- **E-mail switches**: BOOL settings `mail.event.<template>` for every template except `magic-link` (always sent);
  `youtube-new-video` goes to users with `marketingOptIn = true` for newly published videos only.
