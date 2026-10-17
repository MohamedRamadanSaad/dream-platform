# API contract

The TypeScript files are authoritative:

- `frontend/src/api/types.ts` — every DTO, enum and error shape
- `frontend/src/api/endpoints.ts` — every route, method, query and body

Rules the backend must enforce (the mock in `frontend/src/mocks/handlers.ts` enforces the same):

1. Prices are computed server-side only: country (from Cloudflare `CF-IPCountry`) → group → continent → global. The client never sends a price or a country.
2. Credits are a ledger (`credit_ledger`), never a counter. `POST /dreams/submit` deducts inside one transaction with `SELECT … FOR UPDATE` on the user row; returns 402 with `code: INSUFFICIENT_CREDITS` and never partially submits.
3. Orders move to SUCCESS only from a signature-verified webhook whose amount + currency match the stored order; `provider_txn_id` is unique (idempotent). Card-issuer country ≠ IP country → `SUSPICIOUS`, no credits.
4. After a successful order, drafts listed in `checkout_intent` are auto-submitted (same rules as /dreams/submit).
5. Dream status machine: DRAFT → IN_REVIEW → IN_PROGRESS ("Start interpreting", V39); IN_REVIEW | IN_PROGRESS ⇄ AWAITING_USER_REPLY → INTERPRETED; CANCELLED refunds the credit. Interpreter questions from IN_REVIEW or IN_PROGRESS; user replies only from AWAITING_USER_REPLY (back to IN_PROGRESS when started, else IN_REVIEW).
6. Testimonials only on INTERPRETED dreams owned by the caller, one per dream, `approved=false` until the interpreter approves.
7. `sla_hours_snapshot` is copied onto the dream at submit time; the SLA clock pauses while AWAITING_USER_REPLY.
8. Every write from the interpreter (prices, promotions, wait-time, manual credits, notes) lands in `audit_log`.
9. Auth: e-mail + password (since 2026-10-15, see "E-mail + password accounts" below), Google ID token (verified), passkey, or magic link/6-digit code (hashed, 15 min, single use; kept in the API, no longer offered on the login screen). `auth_identities` (provider `GOOGLE` / `MAGIC_LINK` / `PASSWORD`, provider_subject) → one account, many providers. Access JWT 15 min; refresh 30 d in HttpOnly cookie, rotated, reuse-detected.
10. Emails/push per event: DREAM_SUBMITTED (interpreter), DREAM_RECEIVED, DREAM_STARTED, INTERPRETER_QUESTION, USER_REPLIED (interpreter), INTERPRETATION_READY, PAYMENT_SUCCESS.

## E-mail + password accounts (V37, owner's decision of 2026-10-15)

All routes public (no access token). Sign-in answers = `AuthResponse` `{accessToken, expiresIn, user}` + refresh
cookie, exactly like `/auth/magic/verify`. `rememberMe` missing = true.

| Method | Route | Body | Result |
|---|---|---|---|
| POST | `/auth/signup/request` | `{email}` | 204, a `SIGN_UP` code is e-mailed (`email-code`; silently throttled with magic links: 5 per e-mail per TTL window, all purposes). 409 `ACCOUNT_EXISTS` when the active account already has a password (an account without one, opened with Google or the e-mail code, may sign up: completing sets the password on that same account) |
| POST | `/auth/password/forgot` | `{email}` | always 204; a `RESET_PASSWORD` code only when an active account exists |
| POST | `/auth/code/verify` | `{email, code, purpose: "SIGN_UP" \| "RESET_PASSWORD"}` | 200 `{ticket, expiresIn}` (seconds, 900): random single-use ticket bound to e-mail + purpose, stored as SHA-256. 401 `MAGIC_LINK_INVALID` / `MAGIC_LINK_EXPIRED` / `MAGIC_LINK_TOO_MANY_ATTEMPTS` (5 wrong codes burn it). A code of one purpose never verifies another, and `/auth/magic/verify` only accepts `SIGN_IN` codes and links |
| POST | `/auth/signup/complete` | `{ticket, password, acceptedTerms: true, rememberMe?}` | 200 AuthResponse; creates the account when missing (same defaults as a magic-link sign-up), sets the password and `terms_accepted_at`. 401 `TICKET_INVALID` (unknown / used / expired / other purpose), 422 `WEAK_PASSWORD` (ticket not used up), 400 validation when `acceptedTerms` is not true, 409 `ACCOUNT_EXISTS` if a password was set meanwhile |
| POST | `/auth/password/reset` | `{ticket, password, rememberMe?}` | 200 AuthResponse; new password, lockout cleared, every other device signed out (all older refresh-token families revoked), this device signed in. 401 `TICKET_INVALID`, 422 `WEAK_PASSWORD` |
| POST | `/auth/password/login` | `{email, password, rememberMe?}` | 200 AuthResponse. 401 `INVALID_CREDENTIALS` (unknown e-mail or wrong password: same answer, BCrypt runs on a dummy hash for unknown e-mails), 409 `PASSWORD_NOT_SET` (account without a password: use "forgot password" or Google), 429 `TOO_MANY_ATTEMPTS` with `retryAfterSeconds` (5 wrong passwords in a row lock password sign-in for 15 minutes; the 5th wrong one already answers 429; a correct password or a reset clears the counter) |

Password rule (server is the source of truth): 8 to 128 characters, at least one letter and one digit (any script,
Arabic letters and digits count). Hash: BCrypt strength 12 over the SHA-256 hex of the password (keeps every allowed
password within BCrypt's 72-byte input). Passwords, codes and tickets are never logged.
A password sign-in links identity (`PASSWORD`, lower-case e-mail) and does everything every sign-in does (interpreter
role sync, country, `user_sessions`, new device family, new-sign-in e-mail). Interpreter-domain e-mails may use
passwords; Google stays refused for them (`INTERPRETER_USE_MAGIC`), and Google stays refused for an existing account
without a Google identity (`USE_MAGIC_LINK`).
`UserDto` gains `hasPassword: boolean` and `termsAccepted: boolean` (`terms_accepted_at` not null; onboarding also sets
it, and V37 backfills it for already onboarded users). E-mail `email-code` (ar + en, no switch, never copied to the
Sent folder): model `code`, `ttlMinutes`, `purpose`; subject per purpose (`mail.email-code.<purpose>.subject`).

## Order numbers (V38)

Every order has `orderNumber` (string, e.g. `RN-15001`): `<prefix>-<n>`, n from sequence `order_number_seq` (starts at
15001), prefix from setting `orders.number_prefix` (default `RN`; letters and digits). The full string is stored, so a
prefix change never renames old orders; existing orders were numbered in creation order. Shown in `OrderDto`
(`/me/orders`, `/me/orders/{id}`, the 360° user view), admin order rows (`GET /admin/orders`), `InstapayOrderView`,
InstaPay transfer rows, `AdminDreamDetailDto.payment`, the dashboard `lastPackage`, the dream PDF payment block, and
the payment e-mails (`orderRef` = the order number: receipt "invoice number", payment-failed, payment-suspicious).
`id` / `orderId` (UUID) stay and are still used in routes.
`GET /admin/orders?q=` finds orders by number, ignoring case, with or without the prefix (`15001`, `rn-15001`,
`RN-15001`); a `q` that is not an order number gives an empty page. The go-live and pre-launch reset scripts restart
the sequence at 15001.

## E-mail design (themes) and support auto-reply

All `/admin/mail/*` routes are interpreter-only (like every `/admin/**` route). Unknown template or theme → 400 problem
with `code` `UNKNOWN_TEMPLATE` / `UNKNOWN_THEME`.

| Method | Route | Body / query | Response |
|---|---|---|---|
| GET | `/admin/mail/themes` | — | `MailThemeDto[]` `{key, nameAr, nameEn, headerImageUrl, footerImageUrl, pageBg, cardBg, accent, usedByDefault}` (absolute image URLs) |
| GET | `/admin/mail/templates` | — | `MailTemplateRow[]` `{template, theme, inherited, enabled, switchable}` for every template (incl. `magic-link`, `email-code`, `support-auto-reply`; the two code e-mails are not switchable) |
| PUT | `/admin/mail/templates/{template}/theme` | `{theme}` (blank = follow the default) | the updated `MailTemplateRow` |
| PUT | `/admin/mail/theme-default` | `{theme}` | `{theme}` |
| GET | `/admin/mail/preview` | `template`, `theme?`, `locale=ar\|en` | `text/html` — the e-mail rendered with sample data (`MailSamples`, name «أحمد» / "Ahmed") |
| POST | `/webhooks/mail/inbound` | any JSON from the mail host; header `Authorization: Bearer <MAIL_WEBHOOK_SECRET>` | 200 `{"status":"ok"}` or `{"status":"skipped","reason":"…"}`; 401 wrong secret; 503 secret not set |

Rules:
1. A theme changes only the header image (moon, stars, meteors), the page background and the footer image (stars) plus
   colours; the e-mail text never changes. Registry: backend classpath `mail/themes.json`; images:
   `frontend/public/email/themes/<key>/header.jpg` (1200×360) and `footer.jpg` (1200×300).
2. Theme of a template: setting `mail.theme.<template>` → (blank/unknown) `mail.theme.default` → (unknown) registry default
   `crescent-night`. Image URL = (`mail.assets_base_url` or `FRONTEND_URL`) + image path.
3. Auto-reply (`support-auto-reply`, switch `mail.event.support-auto-reply`): one bilingual e-mail (Arabic, then English).
   Skipped (reason in the response and the log) when: no valid sender (`no-sender`); sender on our domain
   (`interpreter.email_domain`) or our own address (`own-address`); noreply / mailer-daemon / postmaster / bounce
   (`automated-sender`); `Auto-Submitted` other than `no` (`auto-submitted`); `Precedence: bulk|list|junk` (`bulk`);
   `List-Id` (`mailing-list`); already auto-replied within `mail.auto_reply_cooldown_hours` (`cooldown`, from
   `email_log`); switched off (`disabled`); body not JSON (`invalid-payload`). The sender is found leniently: the first
   e-mail address under a key `from`, then `envelope_from`, then `sender`, at any depth.

## Support tickets (support mailbox)

Every e-mail that a person sends to the support mailbox (it passes the auto-reply guards: not `no-sender`,
`own-address`, `automated-sender`, `auto-submitted`, `bulk`, `mailing-list`) opens a ticket, even when the auto-reply
is skipped for its cooldown or switched off. Stored: the sender (e-mail + display name), the subject, the received time
and — since V28 (privacy change, the owner's decision) — `body`, the plain text the person wrote (see "Message text"
below). Repeated deliveries: same `Message-ID` = one ticket; without a Message-ID, the same sender +
subject within 2 minutes = one ticket. Interpreter-only routes:

| Method | Route | Body / query | Response |
|---|---|---|---|
| GET | `/admin/support/tickets` | `status=NEW\|IN_PROGRESS\|CLOSED`, `page`, `size` | `Page<SupportTicket>` newest first |
| GET | `/admin/support/tickets/counts` | — | `{new, inProgress, closed}` |
| GET | `/admin/support/tickets/{id}` | — | `SupportTicketDetail` (ticket + `events[]` oldest first) |
| POST | `/admin/support/tickets/{id}/in-progress` | `{message}` (trimmed, 1..2000) | `SupportTicketDetail`; 409 `TICKET_CLOSED` when closed |
| POST | `/admin/support/tickets/{id}/close` | `{message}` | `SupportTicketDetail`; 409 `TICKET_CLOSED` when already closed |
| POST | `/admin/support/tickets/{id}/fetch-body` | — | `SupportTicketDetail`; reads the text from the mailbox now when `body` is null (unchanged detail when IMAP is not configured, `support.fetch_body` is off or the message is not found); 404 unknown ticket |

`SupportTicket` (list rows) and `SupportTicketDetail` both carry `body: string | null`.

Message text (`body`): plain text only — LF line endings, trimmed, at most one blank line in a row, a quoted reply cut
at "On … wrote:" / «في … كتب …:» / "-----Original Message-----", at most 20,000 characters (cut with "…").
1. From the webhook payload when present (keys searched leniently: `text`, `text_body`/`textBody`, `plain`, `body` as
   text, `body.text`, `body.plain`, `content.text`; else `html`/`html_body`/`body.html` converted to text; else
   `snippet`/`preview`; sender/recipient/header/attachment parts ignored; base64 blobs and values over 1 MB ignored).
2. Otherwise read **read-only** over IMAP from INBOX (same account as SMTP; host `IMAP_HOST` or `smtp.`→`imap.`, port
   `IMAP_PORT` 993, timeouts 15 s) by Message-ID (else the last 50 messages, same sender + subject): text/plain part,
   else text/html converted; attachments skipped. Background attempts after 5 s, 30 s and 120 s; a job every 10 minutes
   retries up to 20 tickets of the last 30 days still without text (at most once per hour each). BOOL setting
   `support.fetch_body` (true) switches IMAP reading off. Logs never contain the text.

Rules: NEW → IN_PROGRESS (more updates allowed) → CLOSED (final); NEW → CLOSED directly. Each action e-mails the
sender synchronously (`support-in-progress` / `support-closed`: one bilingual e-mail, the interpreter's message escaped
with its line breaks, sent as a reply in the sender's thread via `In-Reply-To`/`References`), stores the outcome on the
event (`emailStatus` SENT / LOGGED / FAILED / DISABLED) and is audited. The action succeeds whatever the e-mail outcome.
Blank or too long message → 400 (`MESSAGE_REQUIRED` / `MESSAGE_TOO_LONG`), unknown ticket → 404.

## InstaPay manual transfers (Egypt, EGP), V34

Kashier refused the activity, so EGP checkouts (while `payments.instapay.enabled`) create an `INSTAPAY` order whose
`checkoutUrl` is the site's own page `/me/checkout/instapay/{orderId}`. Credits are added only when the interpreter
accepts; acceptance goes through `PaymentService.confirm` (txn id `INSTAPAY-REF-<reference>`), so coupons, receipt
e-mail and the auto-submit of attached drafts behave exactly like a gateway payment.

| Route | Body / result |
|---|---|
| `GET /me/orders/{id}/instapay` | `InstapayOrderView`: amount, receiving `address` + `link` (read from settings on every call), status, reference, sender, `attachedDreams`, `reviewNote` |
| `POST /me/orders/{id}/instapay` | `{reference, senderName, dreamIds?}`; INITIATED (not expired) or PENDING_REVIEW or rejected → `PENDING_REVIEW`. Errors: 422 `INVALID_REFERENCE` / `INVALID_SENDER` / `INVALID_DREAMS`, 409 `DUPLICATE_REFERENCE` / `ORDER_EXPIRED` / `ORDER_CLOSED` |
| `GET /admin/payments/instapay/transfers` | `{pending[], reviewed[]}` (pending oldest first, last 50 decisions) |
| `GET /admin/payments/instapay/transfers/counts` | `{pending}` (nav badge on Payments) |
| `POST /admin/payments/instapay/transfers/{id}/approve` | body `{reference}` = the number the interpreter checked → SUCCESS, credits, attached dreams submitted; 409 `NOT_PENDING`; 409 `REFERENCE_CHANGED` when the buyer changed the number since |
| `POST /admin/payments/instapay/transfers/{id}/reject` | `{note}` → FAILED (`TRANSFER_REJECTED`), user notified (in-app, push, payment-failed e-mail) and may resend corrected details |
| `GET/PUT /admin/payments/instapay/settings` | `{enabled, address, link, recipientName, expireHours}`; address must be `name@instapay`, link `https://ipn.eg/S/...` (422 `INVALID_ADDRESS` / `INVALID_LINK`) |

Rules: one reference pays one order (partial unique index on live statuses); reference digits are normalised (Arabic
digits, spaces); InstaPay orders expire after `payments.instapay.expire_hours` (default 24) when no details were sent.
New order status `PENDING_REVIEW`, provider `INSTAPAY`, notification types `TRANSFER_SUBMITTED` (interpreters) and
`PAYMENT_REJECTED` (payer).

## Payments review, attached dreams and "Start interpreting" (V39)

Binding detail: `docs/UX_PAYMENTS_REVIEW_CONTRACT.md`.

| Route | Body / result |
|---|---|
| `POST /admin/dreams/{id}/start` | `{expectedDays: 1..30}` → IN_REVIEW → IN_PROGRESS, `startedAt`, `expectedBy = now + days`; returns `AdminDreamDetail`. 409 `NOT_IN_REVIEW`; 400 when the days are out of range. Audit `DREAM_START`; the dreamer gets `DREAM_STARTED` (in-app + push + `dream-started` e-mail, ar/en) |
| `PUT /dreams/{id}` | allowed for DRAFT, or IN_REVIEW / AWAITING_USER_REPLY with `startedAt == null` (sets `userEditedAt` and sends the interpreters an in-app `DREAM_EDITED`, at most once per dream per hour, no e-mail); else 409 `NOT_EDITABLE`. Row-locked, so it cannot race the start |
| `DELETE /dreams/{id}` | DRAFT only (409 `NOT_DRAFT`); 409 `ATTACHED_TO_PAYMENT` (+ `orderId`, `dreamId`) while an INITIATED (not expired) or PENDING_REVIEW order holds it. A rejected order never holds it: the dream leaves that order |
| `GET /dreams?status=` | a `DreamStatus`, `ALL`, or the virtual `PENDING_PAYMENT` (drafts attached to a payment). `DRAFT` leaves the attached drafts out |
| `POST /dreams/submit` | 409 `ATTACHED_TO_PAYMENT` (+ `orderId`, `dreamId`) for a draft held by an INITIATED (not expired) or PENDING_REVIEW order; a draft of a rejected order may be submitted with existing credits and leaves that order |
| `POST /checkout`, `POST /me/orders/{id}/instapay` | 409 `DREAM_ALREADY_ATTACHED` (+ `orderId`, `dreamId`) when a dream is held by another INITIATED (not expired) or PENDING_REVIEW order of the user. A dream of a rejected order may be attached and leaves that order. 400 `TOO_MANY_DREAMS` (+ `credits`, `dreams`) when the order would carry more dreams (its intent ∪ the new ones) than the credits it buys. Resending the same order with its own dreams is fine |
| `POST /me/orders/{id}/release-dreams` | optional `{dreamIds?: uuid[]}` (all when absent or empty) → 200 `{released: uuid[]}`: the caller's order (INITIATED, PENDING_REVIEW, or rejected) lets go of those dreams, which return to the drafts. 404 for someone else's order, 409 `NOT_RELEASABLE` in any other state |
| `GET /admin/orders?scope=&q=&page&size` | `scope` = `REVIEW` (InstaPay PENDING_REVIEW, oldest submitted first) · `DOMESTIC` (country EG, newest first) · `INTERNATIONAL` (not EG or no country) · `ALL` (default, CSV export). `q` = order number (see "Order numbers"), combinable with any scope (`?scope=REVIEW&q=15001`). `Page<AdminOrderRow>` |
| `GET /admin/orders/counts` | `{review, domestic, international}` |
| `GET /admin/orders/{id}` | `AdminOrderDetail` (`orderNumber`, transfer + `reviewedByName`, buyer with age, dreams = intent ∪ dreams paid by its credits with full text, `canReview`) |
| `GET /admin/analytics/countries` | `baseCurrency: "EGP"`; each list holds only non-zero rows (cap 250), `topRevenue` ranked by settled EGP; `CountryStat.revenueByCurrency[]` = buyers' own currencies |
| `GET /admin/analytics/summary` | adds `pendingPayments`, `inProgress`, `revenueEgp` (settled EGP, last 30 days); `overdue` counts IN_REVIEW + IN_PROGRESS |
| `GET /me/dashboard` | adds `pendingPayment` and `inProgress`; `drafts` excludes attached drafts |

Shapes (field names = types.ts):

- `DreamSummary` / `DreamDetail` add `startedAt`, `expectedDays`, `editable` (DRAFT, or IN_REVIEW / AWAITING_USER_REPLY before the start), `payment: {orderId, status: PENDING_REVIEW|REJECTED, submittedAt, reviewNote} | null` (PENDING_REVIEW or rejected orders; a rejected one does not block the dream).
- `AdminDreamRow` adds `startedAt`, `expectedDays`, `userEditedAt`; `AdminDreamDetail` adds `startedAt`, `expectedDays`, `editable`, `userEditedAt` and keeps `payment` = the order that paid the dream.
- `AdminOrderRow` = `OrderDto` (incl. `orderNumber`) + `userName, userId, userEmail, settledAmountEgp, transferReference, transferSenderName, transferSubmittedAt, attachedDreams`.

Data: `dreams.started_at`, `dreams.expected_days` (1..30), `dreams.user_edited_at`, status `IN_PROGRESS`;
`orders.settled_amount_egp` set by `PaymentService.confirm` on SUCCESS (EGP → amount, else
`amount × fx[cur] / fx[EGP]` from `pricing.fx_to_usd`, half-up 2 places) and back-filled by V39; notification type
`DREAM_STARTED` (in-app/push/e-mail, text "…within {daysText}, by {expectedBy}" with Arabic number agreement) and
`DREAM_EDITED` (interpreters, in-app + push only); settings `mail.event.dream-started`, `mail.theme.dream-started`.

## Undo a mistaken InstaPay approval (V40)

Binding detail: `docs/REVOKE_APPROVAL_CONTRACT.md`. Flow: `SUCCESS --revoke--> FAILED/APPROVAL_REVOKED --buyer resends-->
PENDING_REVIEW --approve--> SUCCESS` (held dreams resume) or `--reject--> FAILED/TRANSFER_REJECTED` (dreams stay held,
the buyer may resend again).

| Route | Body / result |
|---|---|
| `POST /admin/payments/instapay/transfers/{id}/revoke` | `{note?: string ≤ 500}` → `TransferRow` (interpreter only, 403 otherwise; 404 unknown order). 409 `NOT_REVOCABLE` unless provider INSTAPAY and status SUCCESS; 409 `DREAM_ALREADY_INTERPRETED` when a dream paid by this order is INTERPRETED. One transaction: dreams paid by the order (open SUBMIT debits) in IN_REVIEW / IN_PROGRESS / AWAITING_USER_REPLY → `AWAITING_PAYMENT` (`heldOrderId`, SLA paused; text, messages, `startedAt`, `expectedDays` kept; each debit given back by a REFUND row); what is left of the order's credits is withdrawn (ledger reason `REVOKE`, one row per lot); coupon redemption + promotion use given back; order `FAILED`, `failureReason = APPROVAL_REVOKED`, `reviewNote` = note, `paidAt` / `settledAmountEgp` / provider txn id cleared (same reference can be confirmed again). Audit `INSTAPAY_REVOKED`. Audit `DREAM_HELD` per dream. `approvalRevokedAt` / `revokeNote` kept on the order. Buyer: notification `PAYMENT_REVOKED` (in-app + push, link `/me/checkout/instapay/{id}`, short neutral text + «رؤياك/رؤاك محفوظة») + e-mail `payment-review-needed` (ar/en, one per undo) |
| `POST /me/orders/{id}/instapay` | also accepts `FAILED` + `APPROVAL_REVOKED` → `PENDING_REVIEW` (same reference allowed). `TOO_MANY_DREAMS` counts the held dreams too |
| `POST /admin/payments/instapay/transfers/{id}/approve` | on SUCCESS the order's AWAITING_PAYMENT dreams resume first (one credit each from this order, SUBMIT row): `IN_PROGRESS` when `startedAt` is set, else `IN_REVIEW` (`AWAITING_USER_REPLY` when the interpreter's question was still unanswered); `expectedBy` moved forward by the held time; hold cleared; audit `DREAM_RESUMED`. The new PURCHASE keeps the first approval's expiry (expired leftovers are written off at once). Coupon / promotion use restored only if counted. No interpreter notification; buyer gets the usual PAYMENT_SUCCESS + receipt |
| `POST /dreams/submit` | also accepts the caller's `AWAITING_PAYMENT` dreams: resumed with any credits, they leave the held order; 402 `INSUFFICIENT_CREDITS` when short (nothing changes) |
| `POST /admin/dreams/{id}/cancel` | allowed for `AWAITING_PAYMENT`, no refund (the credit was already given back), hold cleared |
| `POST /admin/dreams/{id}/start` · `/messages` · `/interpretation` | 409 for `AWAITING_PAYMENT` (`NOT_IN_REVIEW` / `NOT_IN_REVIEW` / `INVALID_STATUS`) |
| `GET /admin/dreams?status=AWAITING_PAYMENT` | the held dreams, oldest hold first (`heldAt`); count in `GET /admin/analytics/summary` → `awaitingPayment` |
| `POST /me/orders/{id}/instapay` (other orders) | 409 `DUPLICATE_REFERENCE` for the reference of a revoked order (reserved until its buyer changes it) |
| `GET /dreams?status=PENDING_PAYMENT` | attached drafts + `AWAITING_PAYMENT` dreams (newest first); `?status=AWAITING_PAYMENT` lists only the held ones |
| `GET /me/dashboard` | `pendingPayment` = attached drafts + AWAITING_PAYMENT dreams; `inReview` / `inProgress` exclude them |
| `GET /admin/analytics/summary`, digest, insights | AWAITING_PAYMENT is not open work (not in `inReview` / `inProgress` / `overdue`); revenue counts SUCCESS only, so a revoked order drops out and a re-approved one counts once |

Shapes (field names = types.ts):

- `DreamStatus` adds `AWAITING_PAYMENT` (label «بانتظار تأكيد الدفع» / "Waiting for payment confirmation").
- `DreamPaymentLink.status`: `'PENDING_REVIEW' | 'REJECTED' | 'REVOKED'`. A held dream carries `payment = {orderId, status, submittedAt, reviewNote}`: `REVOKED` while the order is FAILED/APPROVAL_REVOKED, `PENDING_REVIEW` after the resend, `REJECTED` after a reject. Drafts in the intent of a revoked order show `REVOKED` too.
- `editable` for AWAITING_PAYMENT = `startedAt == null`.
- `AdminDreamDetail` adds `heldOrderId: uuid | null`.
- `AdminOrderDetail` adds `canRevoke: boolean` (INSTAPAY && SUCCESS && no INTERPRETED dream) and `revokeBlockedReason: 'DREAM_ALREADY_INTERPRETED' | null`; `dreams` also lists the dreams it holds.
- `InstapayOrderView` and `TransferRow` add `failureReason: string | null` (`TRANSFER_REJECTED` / `APPROVAL_REVOKED`) and `heldDreams: number`.
- `CreditLedgerEntry.reason` adds `REVOKE` (negative). `NotificationType` adds `PAYMENT_REVOKED`.
- `OrderDto` (`GET /me/orders`, `/me/orders/{id}`) adds `failureReason: string | null` (`APPROVAL_REVOKED` → the history shows «راجع بيانات الدفع»).
- BA/QA review: `TransferRow`, `InstapayOrderView` and `AdminOrderDetail` add `revokedAt: string | null`, `revokeNote: string | null` (the last undo, kept after the resend) next to `heldDreams`; `AdminOrderRow` adds `failureReason`, `revokedAt`, `revokeNote`, `heldDreams`. `InstapayOrderView.attachedDreams` counts only intent dreams that are still drafts. `AdminDreamRow` adds `heldOrderId`, `heldAt`; `AdminSummary` adds `awaitingPayment`.

Data: `dreams.held_order_id` (FK orders, partial index); ledger guards are now per `source_id` (PURCHASE: one per
order and previous PURCHASE, so a re-approval adds a second PURCHASE row naming the first; SUBMIT: one per dream and
lot; REFUND: one per dream and debit, `source_id` = the SUBMIT row it gives back); settings
`mail.event.payment-review-needed`, `mail.theme.payment-review-needed`.

## Web Push (installed app and browsers)

All routes need a signed-in user (USER or INTERPRETER). The public key comes from `GET /public/push-key`
(`{publicKey}`, empty when the server has no VAPID keys).

| Method | Route | Body | Result |
|---|---|---|---|
| POST | `/push/subscriptions` | `PushSubscriptionRequest` `{endpoint, keys: {p256dh, auth}, userAgent}` | 204. Upsert by `endpoint`: an endpoint already stored is moved to the caller (the latest account signed in on that browser), its keys are replaced and `failures` reset. The SPA posts it after "Turn on notifications", on every start and after every sign-in when the permission is already granted (`syncPushSubscription`), so a subscription the phone dropped comes back by itself |
| DELETE | `/push/subscriptions?endpoint=` | | 204; removes the caller's row for that endpoint (soft delete). Called on sign-out, the browser keeps its subscription |
| POST | `/push/test` | | 200 `PushTestResult` `{subscriptions, sent, configured}`: a test notification to the caller's own subscriptions only (title = brand name in the user's locale, body `push.test.body` ar/en, url `/me` or `/admin` by role). `configured=false` → the server lacks `VAPID_PRIVATE_KEY`/`VAPID_SUBJECT` (nothing sent); `subscriptions=0` → no device connected; `sent` = push services that accepted it |

Sending (`WebPushSender`): payload JSON `{title, body, url}`, `TTL` 86400 s, `Urgency: high`, encoding `aes128gcm`
(RFC 8291, required by Apple's push service) with VAPID `Authorization: vapid t=…, k=…`; 404/410 deletes the
subscription. A start-up WARN is logged when `VAPID_PUBLIC_KEY` is set but the private key or subject is missing.

Service worker (`frontend/src/sw.ts`, vite-plugin-pwa `injectManifest`, served as `/sw.js` uncached): precache + SPA
fallback (denylist `/api`, `/ops/`), `push` → `showNotification(title, {body, icon, badge, data: {url}, tag: url,
renotify, lang: 'ar', dir: 'auto'})` for every push (a silent push makes iOS revoke the subscription),
`notificationclick` → focus an open page of the site and navigate to `url`, else open a new window.

## Payment filters, unpaid bookings, leftover withdrawal (V41)

Binding detail: `docs/PAYMENTS_FILTERS_CONTRACT.md`.

| Route | Body / result |
|---|---|
| `POST /admin/payments/orders/{id}/withdraw-leftover` | `{note?: string ≤ 500}` → `AdminOrderDetail` (interpreter only, 403 otherwise; 404 unknown). Any provider, order must be SUCCESS (else 409 `NOT_WITHDRAWABLE`); 409 `NOTHING_TO_WITHDRAW` when no unused, unexpired credit is left (also on a second call). One transaction (order row, then the user's balance): one ledger `REVOKE` row per lot of the order (expired credits not counted twice, never below 0); dreams untouched; order stays SUCCESS with `leftoverWithdrawnAt`, `leftoverWithdrawnCredits` (summed), `leftoverNote`. Audit `ORDER_LEFTOVER_WITHDRAWN`. A later undo + re-approval (V40) grants only `credits − leftoverWithdrawnCredits` (held dreams resume first), so withdrawn credits never come back; the order's credit counter keeps the package's credits. Buyer: `CREDITS_WITHDRAWN` (in-app + push, link `/me/payments`) + e-mail `credits-withdrawn` (ar/en, switch `mail.event.credits-withdrawn`, theme `mail.theme.credits-withdrawn`) |
| `GET /admin/orders?scope=&q=&page&size&…filters` | `scope` adds `UNPAID` (INITIATED or EXPIRED, newest first). `DOMESTIC` / `INTERNATIONAL` exclude INITIATED and EXPIRED. Filters (optional, combinable with scope and q): `status` (repeatable or comma list of OrderStatus), `provider` (INSTAPAY / MOR / MOCK / KASHIER), `country` (ISO-2), `currency` (EGP / SAR / USD), `from` / `to` (ISO dates on `created_at`, Asia/Riyadh days, `to` inclusive), `minAmount` / `maxAmount` (order currency), `packageId`, `flag` (`REVOKED` / `LEFTOVER_WITHDRAWN` / `RESENT`). Invalid value → 400 `INVALID_FILTER` + `field`. An INITIATED order past `expires_at` is returned with `status: "EXPIRED"` and matches `status=EXPIRED` (not `INITIATED`) |
| `GET /admin/orders/counts?q&…filters` | `{review, domestic, international, unpaid}`, honouring q and the filters EXCEPT the tab-specific `status` and `flag` (ignored, so another tab's badge never drops to 0) |
| `POST /admin/payments/orders/{id}/restore-leftover` | (round 3) no body → `AdminOrderDetail`. SUCCESS with `leftoverWithdrawnCredits > 0` whose lot has not expired, else 409 `NOTHING_TO_RESTORE`. One REFUND row per withdrawal (kind `LEFTOVER_RESTORED`, same order, the lot's ORIGINAL expiry; expired ones are not given back); clears `leftoverWithdrawnAt/Credits/Note/Reason`; audit `ORDER_LEFTOVER_RESTORED`; buyer `CREDITS_RESTORED` (in-app + push, link `/me/payments`, no e-mail) |

Shapes:
- `AdminOrderRow` adds `expiresAt: string`, `packageId: uuid | null`, `leftoverWithdrawnAt: string | null`.
- `AdminOrderDetail` adds `leftoverCredits: number` (unused, unexpired credits now), `interpretedDreams: number` (dreams paid by the order that are INTERPRETED), `canWithdrawLeftover: boolean` (SUCCESS && leftoverCredits > 0), `leftoverWithdrawnAt: string | null`, `leftoverWithdrawnCredits: number | null`, `leftoverNote: string | null`; its `status` shows EXPIRED for a lazily expired booking.
- `AdminOrderCounts` adds `unpaid`. `NotificationType` adds `CREDITS_WITHDRAWN`.

Round 3 (BA/QA):
- `withdraw-leftover` body is `{reason: 'UNCONFIRMED' | 'AMOUNT_SHORT' | 'OTHER', note?: string ≤ 500}` (reason required, 400 otherwise; 400 `NOTE_REQUIRED` + `field: "note"` for OTHER without a note). InstaPay orders only: any other provider or status → 409 `NOT_WITHDRAWABLE`. The buyer's text (in-app, push, e-mail) starts with the reason: «…لأننا لم نتمكن من تأكيد الدفع.» / «…لأن المبلغ الذي وصلنا أقل من قيمة الباقة.» / «…، والسبب: <note>.»; the order stores `leftover_reason`.
- A dream paid by an order whose leftover was withdrawn is cancelled WITHOUT a refund. `AdminDreamDetail.cancelRefunds: boolean` says whether a cancel would give the credit back now.
- `UNPAID` also holds FAILED orders failed at the gateway (any failure reason except `TRANSFER_REJECTED` / `APPROVAL_REVOKED`, or none); they leave DOMESTIC / INTERNATIONAL. An INITIATED order without a deadline stays INITIATED.
- Shapes: `AdminOrderRow.paidLater: boolean`, `leftoverWithdrawnCredits: number | null`, `leftoverReason: string | null`, `leftoverNote: string | null` (CSV) (the same user has a SUCCESS order created after this one); `AdminOrderDetail.leftoverReason: string | null`, `canRestoreLeftover: boolean`; `OrderDto.leftoverWithdrawnCredits: number | null`; `CreditLedgerEntry.kind: 'APPROVAL_REVOKED' | 'LEFTOVER_WITHDRAWN' | 'LEFTOVER_RESTORED' | null` (never null on a REVOKE row: V41 back-fills APPROVAL_REVOKED on existing REVOKE rows and undo-hold REFUND rows, and a CHECK requires it) and `orderNumber: string | null`; `NotificationType` adds `CREDITS_RESTORED`.
- A re-approval after an undo never revives credits that expired before (the new PURCHASE keeps the first one's expiry) nor credits withdrawn as leftover (it grants `credits − leftoverWithdrawnCredits`).

Owner rule 2026-10-17 (unpaid tab): `scope=UNPAID` (the LAST tab, "Unpaid bookings") = INITIATED, EXPIRED (incl. an INITIATED order past `expires_at`) or FAILED for ANY reason (gateway failure, `TRANSFER_REJECTED`, `APPROVAL_REVOKED`, none), newest first. `DOMESTIC` / `INTERNATIONAL` = everything else (SUCCESS, PENDING_REVIEW, REFUNDED, SUSPICIOUS). Counts follow the same rules; the flag filters (REVOKED, RESENT, LEFTOVER_WITHDRAWN) work on every tab. This replaces the round-3 rule that kept rejected / undone orders in Domestic / International.

## Users screen money in the user's own currency (owner request 2026-10-17)

`GET /admin/analytics/users` rows (`AdminUserRow`) and `GET /admin/users/{id}` (`AdminUser360`) add `totalPaid: number` + `totalPaidCurrency: 'EGP' | 'USD'` (the user's own: EGP when the country's default currency is EGP, i.e. Egypt, summed from `orders.settled_amount_egp`, the amount for an EGP order without it; USD for everyone else through `pricing.fx_to_usd`) and `totalPaidEgp: number`. `totalPaidBase` is kept for compatibility but is now EGP (= `totalPaidEgp`, it was USD); `list=top-paying` ranks by it, like the country dashboard. Only SUCCESS orders count (unchanged). The 360 `orders[]` already carry each order's own currency.
