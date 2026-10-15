# UX + payments review — contract (2026-10-15)

Binding contract between backend and frontend for branch `feat/ux-payments-review`. Migration **V39** (reserved in the project coordination doc; V37/V38 went to passwords and order numbers).

Owner's requests, in his order:
1. Interpreter dashboard: the FIRST tile is "payments that need review".
2. Recommendations ("توصيات لكِ") become cards side by side that the user swipes between (horizontal scroll-snap), small screen footprint.
3. Top countries by visits / by dreams: pagination, 7 per page.
4. Countries with 0 for that metric are NOT listed (no illogical ranking). Empty list → empty state.
5. Top countries by payments ranked by the amount received **in EGP**. Each order keeps the buyer's own amount/currency as a reference; the ranking and totals use EGP.
6. Payments admin: one table with tabs **Needs review** (first, default) · **Domestic** (Egypt) · **International** (outside Egypt). Click a row → order page with Approve / Reject. Attached dreams shown as expandable rows (dropdown) with the dream text.
7. InstaPay account settings move to their own menu item "InstaPay settings" (set once, not shown on the payments page).
8. User side: a dream attached to a payment is no longer a draft: it shows in a tab "Attached to a payment" with status "Payment under review" (or "Payment rejected").
9. The user may edit the dream text until the interpreter presses **"Start interpreting"** (جاري التعبير). After that it is final and its status is `IN_PROGRESS`. When pressing it, the interpreter picks the expected number of days; the user then sees how long it will take.

---

## 1. Dream statuses

`DreamStatus`: `DRAFT, IN_REVIEW, IN_PROGRESS, AWAITING_USER_REPLY, INTERPRETED, CANCELLED`.

Transitions:
- `IN_REVIEW → IN_PROGRESS`: `POST /api/admin/dreams/{id}/start` body `{ "expectedDays": 1..30 }` (409 `NOT_IN_REVIEW` otherwise). Sets `startedAt=now`, `expectedDays`, `expectedBy = now + expectedDays days`, clears `slaPausedAt`. Notifies the user (in-app + push + e-mail `dream-started`, ar/en) with the number of days. Audit `DREAM_START`.
- `IN_REVIEW | IN_PROGRESS → AWAITING_USER_REPLY` (ask), and user reply returns to `IN_PROGRESS` when `startedAt != null`, else `IN_REVIEW`.
- `IN_REVIEW | IN_PROGRESS | AWAITING_USER_REPLY → INTERPRETED | CANCELLED` (unchanged rules).
- Overdue = status in (IN_REVIEW, IN_PROGRESS) and `expectedBy < now`.
- Everywhere the code treated `IN_REVIEW` as "open work" (digest job, insights, summary counts, user dashboard counts, analytics) also counts `IN_PROGRESS`.

User edit: `PUT /api/dreams/{id}` `{text, gender}` is allowed when status is `DRAFT`, or `IN_REVIEW` with `startedAt == null`. Otherwise 409 `NOT_EDITABLE` (keep `NOT_DRAFT` for drafts-only operations like delete). The row is locked (`FOR UPDATE`) in both edit and start so they cannot race. Editing an IN_REVIEW dream sets `userEditedAt` (shown to the interpreter as "edited by the dreamer").

`DELETE /api/dreams/{id}`: DRAFT only, and 409 `ATTACHED_TO_PAYMENT` when the draft is attached to a live order (see 2).

## 2. Dreams attached to a payment

A DRAFT dream is "attached" when its id is in the `checkout_intent.dreamIds` of one of the user's orders with status `PENDING_REVIEW`, or `FAILED` with `failure_reason = 'TRANSFER_REJECTED'` (latest such order wins).

- `GET /api/dreams?status=DRAFT` **excludes** attached dreams.
- `GET /api/dreams?status=PENDING_PAYMENT` (virtual filter) returns only attached dreams.
- `InstapayService.submit`/checkout: a dream already attached to another live order (INITIATED not expired, PENDING_REVIEW, or rejected-and-resendable) of the same user → 409 `DREAM_ALREADY_ATTACHED`.
- `/me/dashboard` counts gain `pendingPayment` (number of attached dreams); `drafts` excludes them.

DTO additions (`DreamSummaryDto` and `DreamDetailDto`):
```
startedAt: string|null
expectedDays: number|null
editable: boolean                 // DRAFT, or IN_REVIEW && startedAt==null
payment: null | { orderId, status: 'PENDING_REVIEW'|'REJECTED', submittedAt, reviewNote|null }
```
`AdminDreamRow` + `AdminDreamDetailDto` add `startedAt`, `expectedDays`, `userEditedAt`.

## 3. Admin orders

`GET /api/admin/orders?scope=REVIEW|DOMESTIC|INTERNATIONAL|ALL&page&size` (default `ALL`, keeps old behaviour):
- `REVIEW`: provider INSTAPAY and status PENDING_REVIEW, oldest submitted first.
- `DOMESTIC`: `country_code = 'EG'`, newest first, all statuses.
- `INTERNATIONAL`: `country_code <> 'EG'` (or null), newest first.
Response `Page<AdminOrderRow>`; `AdminOrderRow` adds `userId, userEmail, settledAmountEgp, transferReference, transferSenderName, transferSubmittedAt, attachedDreams (int)`.

`GET /api/admin/orders/counts` → `{ review, domestic, international }` (for tab counts).

`GET /api/admin/orders/{id}` → `AdminOrderDetail`:
```
id, status, failureReason, provider, providerRef, packageName, credits, amount, currency, settledAmountEgp,
discount, couponCode|null, countryCode, countrySource, createdAt, paidAt, expiresAt,
transfer: null | { reference, senderName, submittedAt, reviewedAt, reviewedByName, reviewNote },
user: { id, name, email, countryCode, age },
dreams: [ { id, status, text, createdAt, submittedAt } ]   // dreams in the order's intent, plus dreams whose credit came from this order
canReview: boolean                                           // INSTAPAY && PENDING_REVIEW
```
Approve / reject stay on the existing InstaPay endpoints (`/admin/payments/instapay/transfers/{id}/approve` with `{reference}`, `/reject` with `{note}`).

## 4. Settled amount in EGP

`orders.settled_amount_egp numeric(12,2)` (nullable). Set when an order becomes SUCCESS:
- currency EGP → `amount`;
- otherwise the provider's settled EGP amount when it reports one; else `amount / fx_to_usd[EGP] * fx_to_usd[currency]` (rounded half-up, 2 places).
V39 back-fills SUCCESS/REFUNDED/SUSPICIOUS-with-paid_at EGP orders with `amount`, and others with the FX formula using the current `pricing.fx_to_usd` setting.

## 5. Analytics

`GET /api/admin/analytics/countries?period=` → `AdminCountryDashboardDto`:
```
period, baseCurrency: "EGP",
topVisits:  CountryStatDto[]   // only visits > 0, desc, ALL rows (no top-8 cut, cap 250)
topDreams:  CountryStatDto[]   // only dreams > 0
topRevenue: CountryStatDto[]   // only revenueBase > 0, ranked by revenueBase (= EGP)
CountryStatDto: countryCode, countryName, visits, dreams, revenueBase (EGP), users,
                revenueByCurrency: [{ currency, amount }]   // the buyers' own currencies, reference only
```
`GET /api/admin/analytics/summary` → `AdminSummaryDto` adds `pendingPayments` (INSTAPAY PENDING_REVIEW count), `inProgress`, `revenueEgp` (sum of settled EGP, last 30 days). `revenue[]` (per currency) stays as a reference.

## 6. Frontend

- Dashboard `QueueStrip`: first tile "Payments to review" (gold, danger tone when > 0) → `/admin/orders?tab=REVIEW`; then in review, in progress, awaiting, overdue, today, revenue (EGP total, reference currencies under it).
- Insights: a horizontal swipe row (CSS scroll-snap, one card ≈ 85% width on phones, 3 visible on desktop), dot indicator, arrows on desktop that flip with direction. No "show more".
- Top countries cards: 7 rows per page, prev/next pager with "page x of y", empty state when no rows. Revenue card shows EGP value; reference currencies in a small line.
- Traffic "top countries" panel: page size 7.
- `/admin/orders`: tabs Needs review (default) · Domestic · International, counts in the tabs, paged table (20 per page), CSV export kept; rows are links to `/admin/orders/:id`.
- `/admin/orders/:id`: order page with Approve (modal confirming the reference) / Reject (modal with note), attached dreams as an accordion showing the full text.
- `/admin/instapay-settings`: the existing `InstapaySettingsCard`, own menu item (icon `wallet`/`gear`) under Payments.
- Admin dream page: "Start interpreting" button (IN_REVIEW only) → modal with day chips 1,2,3,5,7,10,14 plus a number input (1..30), default from wait settings; shows "Edited by the dreamer" when `userEditedAt`.
- Admin queue: new tab IN_PROGRESS.
- User dreams page tabs: Drafts · Attached to a payment · In review · Being interpreted · Awaiting your reply · Interpreted. Attached rows: badge "Payment under review" / "Payment rejected" + link to `/me/checkout/instapay/:orderId`.
- User dream page: edit button while `editable`; for IN_PROGRESS shows "Being interpreted, expected within N days (by <date>)".
- All texts in `ar.json` + `en.json`; MSW mocks updated for every new route/field.
