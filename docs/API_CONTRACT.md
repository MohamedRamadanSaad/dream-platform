# API contract

The TypeScript files are authoritative:

- `frontend/src/api/types.ts` — every DTO, enum and error shape
- `frontend/src/api/endpoints.ts` — every route, method, query and body

Rules the backend must enforce (the mock in `frontend/src/mocks/handlers.ts` enforces the same):

1. Prices are computed server-side only: country (from Cloudflare `CF-IPCountry`) → group → continent → global. The client never sends a price or a country.
2. Credits are a ledger (`credit_ledger`), never a counter. `POST /dreams/submit` deducts inside one transaction with `SELECT … FOR UPDATE` on the user row; returns 402 with `code: INSUFFICIENT_CREDITS` and never partially submits.
3. Orders move to SUCCESS only from a signature-verified webhook whose amount + currency match the stored order; `provider_txn_id` is unique (idempotent). Card-issuer country ≠ IP country → `SUSPICIOUS`, no credits.
4. After a successful order, drafts listed in `checkout_intent` are auto-submitted (same rules as /dreams/submit).
5. Dream status machine: DRAFT → IN_REVIEW ⇄ AWAITING_USER_REPLY → INTERPRETED; CANCELLED refunds the credit. Interpreter questions only from IN_REVIEW; user replies only from AWAITING_USER_REPLY.
6. Testimonials only on INTERPRETED dreams owned by the caller, one per dream, `approved=false` until the interpreter approves.
7. `sla_hours_snapshot` is copied onto the dream at submit time; the SLA clock pauses while AWAITING_USER_REPLY.
8. Every write from the interpreter (prices, promotions, wait-time, manual credits, notes) lands in `audit_log`.
9. Auth: Google ID token (verified) or magic link/6-digit code (hashed, 15 min, single use). `auth_identities` (provider, provider_subject) → one account, many providers. Access JWT 15 min; refresh 30 d in HttpOnly cookie, rotated, reuse-detected.
10. Emails/push per event: DREAM_SUBMITTED (interpreter), DREAM_RECEIVED, INTERPRETER_QUESTION, USER_REPLIED (interpreter), INTERPRETATION_READY, PAYMENT_SUCCESS.
