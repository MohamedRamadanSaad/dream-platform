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

## E-mail design (themes) and support auto-reply

All `/admin/mail/*` routes are interpreter-only (like every `/admin/**` route). Unknown template or theme → 400 problem
with `code` `UNKNOWN_TEMPLATE` / `UNKNOWN_THEME`.

| Method | Route | Body / query | Response |
|---|---|---|---|
| GET | `/admin/mail/themes` | — | `MailThemeDto[]` `{key, nameAr, nameEn, headerImageUrl, footerImageUrl, pageBg, cardBg, accent, usedByDefault}` (absolute image URLs) |
| GET | `/admin/mail/templates` | — | `MailTemplateRow[]` `{template, theme, inherited, enabled, switchable}` for every template (incl. `magic-link`, `support-auto-reply`) |
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
