# Revoke a mistaken InstaPay approval + fake-transfer warning (2026-10-17)

Binding contract for branch `feat/revoke-approval`. Migration **V40** (claimed in the project coordination doc).

## Owner's request
1. The interpreter approved an InstaPay payment by mistake and its dream is now being interpreted. The dream must NOT be deleted. The interpreter can take the approval back: the payment goes to the **user** for review (he checks / corrects and resends his transfer details), then back to the interpreter's "Needs review" tab. The user gets an in-app notification, a push and an e-mail. The dream's status becomes «بانتظار تأكيد الدفع» ("Waiting for payment confirmation") until the payment is approved again.
2. A warning on the payment page: a fake transfer gets the account closed; please make sure of the payment details.

## 1. States

Dream status `AWAITING_PAYMENT` (new). Column `dreams.held_order_id uuid NULL` = the order whose approval was revoked.

Order after revoke: `status = FAILED`, `failure_reason = 'APPROVAL_REVOKED'`, `review_note` = interpreter's note, `reviewed_by/at` = now, `settled_amount_egp = NULL`, `paid_at = NULL`, `provider_txn_id = NULL` (so the same transfer reference can be confirmed again later; `confirm()` uses the txn id for duplicate detection).

Flow: `SUCCESS --revoke--> FAILED/APPROVAL_REVOKED --user resends details--> PENDING_REVIEW --approve--> SUCCESS` (held dreams resume) or `--reject--> FAILED/TRANSFER_REJECTED` (held dreams stay held; the user may resend again).

## 2. Revoke endpoint

`POST /api/admin/payments/instapay/transfers/{id}/revoke` body `{ "note": string ≤ 500, optional }` → `TransferRow` (interpreter only).
- 404 unknown order. 409 `NOT_REVOCABLE` unless provider INSTAPAY and status SUCCESS.
- 409 `DREAM_ALREADY_INTERPRETED` when any dream paid from this order (SUBMIT ledger row with `source_id` = order) is INTERPRETED: the interpretation was already delivered.
- In ONE transaction, order row locked and the user's credit balance locked (same lock order as approve/submit, no deadlock):
  1. Every dream whose SUBMIT ledger entry came from this order and whose status is IN_REVIEW, IN_PROGRESS or AWAITING_USER_REPLY: status → `AWAITING_PAYMENT`, `held_order_id` = order, `sla_paused_at = now` (if not already paused). Text, messages, `startedAt`, `expectedDays` are kept. A REFUND ledger row (+1, `source_id` = order, ref the dream) gives back the consumed credit so the next step removes it cleanly. CANCELLED dreams are untouched (already refunded).
  2. One ledger row with new reason `REVOKE` removes what is left of the order's lot (balance after step 1, never below 0 for that lot; expired credits already removed are not removed twice).
  3. Coupon / promotion usage counted at confirm is reversed (so a later approval does not count twice).
  4. Order fields as in §1. Audit `INSTAPAY_REVOKED` (before/after status, note, held dream ids).
  5. Notify the buyer: in-app + push type `PAYMENT_REVOKED` (link `/me/checkout/instapay/{orderId}`), and e-mail template `payment-review-needed` (ar + en, the site's mail layout/theme like the other templates). Text: the interpreter could not confirm the transfer; the dream is kept and waits for the payment to be confirmed; please check the transfer number, the sender name and the amount and send them again; a fake or wrong transfer gets the account closed. Arabic natural, no em dash, « » quotes.

## 3. Resubmission and re-approval
- `POST /me/orders/{id}/instapay` accepts FAILED orders whose reason is `TRANSFER_REJECTED` **or** `APPROVAL_REVOKED` → PENDING_REVIEW (the same reference is allowed). The REFERENCE_CHANGED check on approve stays.
- `PaymentService.confirm` on SUCCESS: after `addPurchase` and the attached-drafts auto-submit, **resume** the dreams with `held_order_id` = this order and status AWAITING_PAYMENT: consume one credit each from this order's lot (`consumeForDream`, SUBMIT row with `source_id` = order), status → IN_PROGRESS when `startedAt != null`, else IN_REVIEW; `expected_by` moved forward by the paused time; clear `sla_paused_at` and `held_order_id`. No extra interpreter notification (the approval is hers). The buyer gets the usual payment-success notification and receipt.
- `POST /dreams/submit` also accepts AWAITING_PAYMENT dreams of the caller: resumes them with ordinary credits (any lot), clears the hold. 402 when the balance is too low (nothing changes).
- Admin `cancel` of an AWAITING_PAYMENT dream: allowed, **no refund** (no credit is held), clears the hold.
- Admin start / ask / interpret refuse AWAITING_PAYMENT (existing status checks; add a test).

## 4. Read models
- `DreamStatus` + `AWAITING_PAYMENT` everywhere (TS type too). Not "open work": excluded from inReview / inProgress / overdue counts, digest, insights; SLA overdue never for it.
- User list: `GET /dreams?status=PENDING_PAYMENT` includes AWAITING_PAYMENT dreams (with drafts attached to live orders); `/me/dashboard` `pendingPayment` counts them; `inReview` excludes them.
- `DreamSummaryDto.payment` for a held dream: `{ orderId, status, submittedAt, reviewNote }` with status `'REVOKED'` while the order is FAILED/APPROVAL_REVOKED, `'PENDING_REVIEW'` after the user resent, `'REJECTED'` after a reject. TS: `DreamPaymentLink.status` becomes `'PENDING_REVIEW' | 'REJECTED' | 'REVOKED'`.
- `editable` for AWAITING_PAYMENT = `startedAt == null` (same rule as before).
- Admin: `AdminOrderDetail` adds `canRevoke: boolean` (INSTAPAY && SUCCESS && no INTERPRETED dream) and `revokeBlockedReason: 'DREAM_ALREADY_INTERPRETED' | null`; dreams list shows AWAITING_PAYMENT. Admin queue: AWAITING_PAYMENT is not in IN_REVIEW/IN_PROGRESS tabs; the "all" listing may show it. `AdminDreamDetailDto` adds `heldOrderId`.
- InstaPay `OrderView` (user payment page): exposes `failureReason` so the page can show the revoked copy (check existing fields).
- Revenue / settled EGP / reports already count SUCCESS only: verify a revoked order disappears from them.

## 5. Migration V40 `V40__revoke_instapay_approval.sql`
dreams status CHECK + `AWAITING_PAYMENT`; `dreams.held_order_id uuid REFERENCES orders(id)` + partial index where not null; credit_ledger reason CHECK + `REVOKE` (pattern of V23: drop the CHECK whatever its name, re-add named); notifications type CHECK + `PAYMENT_REVOKED` (keep every value from V39); mail channel setting for `payment-review-needed` if templates are gated by settings (follow V39 `dream-started`). No new table.

## 6. Frontend
- InstaPay payment page (`InstapayPage.tsx`): a red `role="alert"` box right above the send button, always shown when the form is shown: «تنبيه: إذا كان التحويل وهميًا أو غير صحيح فسيتم إغلاق الحساب. يرجى التأكد من رقم العملية واسم المحوِّل والمبلغ قبل الإرسال.» / "Warning: a fake or wrong transfer will close the account. Please check the transfer number, the sender name and the amount before you send." Same warning in the resend form.
- Revoked state on that page: title «نحتاج إلى مراجعتك لبيانات الدفع» / "Please review your payment details", lead: the interpreter could not confirm the transfer, your dream is kept and waits for the payment to be confirmed; the interpreter's note if any; the form prefilled to resend.
- Admin order page (`AdminOrderPage.tsx`): for SUCCESS InstaPay orders a quiet danger button «التراجع عن الموافقة» / "Undo approval" → modal: what will happen (credits withdrawn, dreams kept as «بانتظار تأكيد الدفع», the buyer is asked to review and resend), optional note, confirm. Disabled with the reason when `canRevoke` is false. Banner on revoked orders: «تم التراجع عن الموافقة، بانتظار مراجعة المستخدم» / "Approval undone, waiting for the buyer to review". Double-submit guard; invalidate order, lists, counts, summary.
- Status label AWAITING_PAYMENT: «بانتظار تأكيد الدفع» / "Waiting for payment confirmation" (user + admin). User dream page: card with the payment status (revoked / under review / rejected), link to the payment page, edit while `editable`. Dreams tab «مرفقة بدفعة» lists them. Admin dream page: banner, no start/ask/interpret buttons.
- Notification type `PAYMENT_REVOKED` label + icon.
- MSW mocks for every route/state above.
