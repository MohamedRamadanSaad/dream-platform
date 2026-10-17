# Payments: withdraw leftover credits, filters, unpaid bookings tab (2026-10-17)

Binding contract for branch `feat/payments-filters`. Migration **V41** (claimed in the project coordination doc).

## Owner's request
1. From the payment information attached to a dream (admin dream page), the interpreter can open that payment's order page.
2. When a dream paid by the payment is already interpreted, the order page tells her clearly: a dream from this payment was interpreted, so the approval cannot be undone. Then it asks: «هل تريدين خصم الرصيد المتبقي من هذه الدفعة؟». If she says yes, the credits of this payment that the user has not used yet are removed from the user's balance.
3. Filters on the payments page.
4. A tab on the payments page for bookings that were made but not paid.
5. Test and deploy.

## 1. Link from the dream to its payment
`AdminDreamDetailDto.payment.orderId` already exists. Frontend: the payment card on `/admin/dreams/:id` gets a clear link «فتح الدفعة RN-…» / "Open payment RN-…" to `/admin/orders/:orderId` (also for a held dream via `heldOrderId`, already linked).

## 2. Withdraw the unused credits of a payment
`POST /api/admin/payments/orders/{id}/withdraw-leftover` body `{ "note": string ≤ 500, optional }` → `AdminOrderDetail` (interpreter only).
- Allowed for any provider when the order status is SUCCESS. 409 `NOTHING_TO_WITHDRAW` when the order's lot has 0 unused, unexpired credits. 409 `NOT_WITHDRAWABLE` for other statuses. 404 unknown.
- In ONE transaction (order row locked, then the user's credit balance, same lock order as approve/revoke): one ledger row per lot of this order with reason `REVOKE` and the negative amount of what is left (never below 0, expired credits not counted twice). Dreams are NOT touched (interpreted stay interpreted, others keep their status and their credit). Order stays SUCCESS; new columns `orders.leftover_withdrawn_at`, `orders.leftover_withdrawn_credits int`, `orders.leftover_note`. Audit `ORDER_LEFTOVER_WITHDRAWN` (credits, note).
- Buyer is told: in-app + push `CREDITS_WITHDRAWN` (link `/me/payments`) and e-mail template `credits-withdrawn` (ar + en, same layout/theme handling and on/off setting as the other templates). Neutral text: «تم خصم N من رصيد الطلب RN-… لأننا لم نتمكن من تأكيد الدفع. رؤاك التي فُسِّرت أو التي قيد التعبير لا تتأثر. للاستفسار تواصل معنا.» with correct Arabic plurals for N; English equivalent.
- A second call → 409 `NOTHING_TO_WITHDRAW`.

`AdminOrderDetail` adds:
```
leftoverCredits: number          // unused, unexpired credits of this order right now
interpretedDreams: number        // dreams paid by this order that are INTERPRETED
canWithdrawLeftover: boolean     // SUCCESS && leftoverCredits > 0
leftoverWithdrawnAt: string|null, leftoverWithdrawnCredits: number|null, leftoverNote: string|null
```
(`canRevoke` / `revokeBlockedReason` unchanged.)

## 3. Admin orders: filters + unpaid bookings tab
`GET /api/admin/orders` gains scope `UNPAID` and filters, all optional and combinable with `scope`, `q`, `page`, `size`:
- `status` (repeatable or comma list of OrderStatus), `provider` (INSTAPAY | MOR | MOCK | KASHIER), `country` (ISO-2), `currency`, `from` / `to` (ISO date, on `created_at`, Riyadh day bounds; `to` inclusive), `minAmount` / `maxAmount` (order currency), `packageId`, `flag` (`REVOKED` = has `approval_revoked_at`; `LEFTOVER_WITHDRAWN`; `RESENT` = revoked then PENDING_REVIEW).
- Invalid values → 400 `INVALID_FILTER` with the field name.
- `scope=UNPAID`: status INITIATED or EXPIRED (a booking: checkout created, no transfer sent / never paid), newest first. INITIATED rows whose `expires_at` has passed are reported as EXPIRED in the JSON (`status: 'EXPIRED'`) even if the expiry job has not run yet.
- `DOMESTIC` and `INTERNATIONAL` now exclude INITIATED and EXPIRED (they live in UNPAID).
- `GET /api/admin/orders/counts` → `{ review, domestic, international, unpaid }`; counts honour the same filters when passed (so tab badges match the filtered view).
- `AdminOrderRow` adds `expiresAt` and `packageId` if missing.
- CSV export (frontend) uses the current scope + filters.

## 4. Frontend
- Order page: when `interpretedDreams > 0`, the undo button is replaced by a clear notice «فُسِّرت رؤيا من هذه الدفعة، لذلك لا يمكن التراجع عن الموافقة.» and, when `canWithdrawLeftover`, a question card «هل تريدين خصم الرصيد المتبقي من هذه الدفعة؟ (N)» with «نعم، اخصمي الرصيد» / «لا» → confirm modal (shows N, optional note) → POST, toast, refresh. Also offered (secondary) on any SUCCESS order with leftover credits. After withdrawal: a line «خُصم N من الرصيد المتبقي في <date>» + note.
- Payments page: tabs Needs review · Domestic · International · **Unpaid bookings** («حجوزات لم تُدفع» / "Unpaid bookings"), counts in tabs. Unpaid rows show order number, user, package, amount, created, «تنتهي خلال …» / «انتهت» chip.
- Filters bar (collapsible «تصفية» button on phones, inline on desktop): status (chips, multi), payment method, country (select from countries present), currency, date range (from/to), amount range, package, special flags (revoked / resent / leftover withdrawn). Active filters show as removable chips with a «مسح الكل» action. Filters live in the URL query so Back and shared links work, and they combine with the tab and the order-number search. Debounce amount inputs.
- Dream page payment card: link to the order (§1).
- Notification type `CREDITS_WITHDRAWN` label + icon; credit history shows the REVOKE row as «خصم الرصيد المتبقي للطلب RN-…».
- MSW mocks for every new route, filter and state (include unpaid bookings, some expired, an order with an interpreted dream and leftover credits).
- i18n ar + en, no hardcoded text, no em dash, RTL-correct, phone first.

## 5. Implementation notes and round-3 fixes (2026-10-17)
- Withdrawal: InstaPay + SUCCESS only (409 `NOT_WITHDRAWABLE`); body `{reason: UNCONFIRMED|AMOUNT_SHORT|OTHER, note?}`, note required for OTHER (400 `NOTE_REQUIRED`); `orders.leftover_reason`; the buyer's texts start with the reason. REVOKE rows carry `credit_ledger.kind = LEFTOVER_WITHDRAWN` (an undone approval: `APPROVAL_REVOKED`).
- Restore: `POST /api/admin/payments/orders/{id}/restore-leftover` → `AdminOrderDetail`; REFUND rows of kind `LEFTOVER_RESTORED` with the lot's original expiry (expired ones are not given back); fields cleared; audit `ORDER_LEFTOVER_RESTORED`; buyer `CREDITS_RESTORED` (in-app + push). 409 `NOTHING_TO_RESTORE`. `canRestoreLeftover` on the detail.
- Cancel of a dream paid by a withdrawn order: no refund; `AdminDreamDetail.cancelRefunds`.
- A re-approval after an undo grants `credits − leftover_withdrawn_credits` and keeps the first expiry.
- UNPAID includes gateway FAILED orders; `AdminOrderRow.paidLater`; counts ignore `status` and `flag`.
- Credit history: `CreditLedgerEntry.kind` + `orderNumber`; `OrderDto.leftoverWithdrawnCredits`.

## 6. Owner rule 2026-10-17: every failed booking in "Unpaid bookings"
Failed bookings and booked-but-unpaid bookings sit together in the LAST tab.
- `scope=UNPAID` = INITIATED, EXPIRED (incl. lazily expired) or FAILED whatever the reason (gateway, TRANSFER_REJECTED, APPROVAL_REVOKED, none), newest first.
- `DOMESTIC` / `INTERNATIONAL` = SUCCESS, PENDING_REVIEW, REFUNDED, SUSPICIOUS. Counts follow the same rules.
- Flag filters (REVOKED, RESENT, LEFTOVER_WITHDRAWN) keep working on any tab.
- Frontend: in the Unpaid tab the status chips are INITIATED, EXPIRED, FAILED and the special-case flags are offered; each row shows a reason chip: «لم يُرسل التحويل» / "No transfer sent" (INITIATED), «انتهى الحجز» / "Booking expired" (EXPIRED), «رُفض التحويل» / "Transfer rejected" (TRANSFER_REJECTED), «تم التراجع عن الموافقة» / "Approval undone" (APPROVAL_REVOKED), «فشل الدفع» / "Payment failed" (any other failure); the deadline and paid-later chips stay. Rejected and undone rows open the order page as before.
