# Development Rules — distilled from the owner's instructions (chronological essence)

Binding rules for anyone continuing the dream-interpretation platform. Short, imperative, no exceptions unless the owner says so.

## A. Product & business
1. Payment first, interpretation second. Credits = dreams. Never let a dream reach the interpreter without a credit.
2. Interpreter promise: e-mail on receipt (with the reply window), e-mail when interpreted. Never say the service is closed — show a positive "reply time" (hours, or min–max days when busy).
3. Testimonials only from interpreted dreams, approved by the interpreter before display.
4. Follow-up questions per dream (chat) with e-mail + in-app + push on each step; user replies pause the SLA.
5. Prices shown per visitor country, decided by the **server** (Cloudflare country header). The user can never choose a country/currency. Order stores payment country; mismatch → SUSPICIOUS, no credits.
6. Currencies: EGP (Egypt via Paymob), SAR (Gulf group), USD (everyone else; Europe uses USD). Interpreter manages prices by continent → country, country groups/favourites, packages, promotions, coupons — all from the dashboard, nothing hardcoded.
7. Promotions must be religiously appropriate (no Mawlid offer). Seeds: Saudi National Day (SA), 6 October Victory (EG).
8. Interpreter dashboard opens on top countries (visits / dreams / payments); 360° user view; full CRUDs; orders CSV.
9. Courses module later (placeholder page only).

## B. Accounts & security
10. No passwords. Google sign-in + magic link / 6-digit code. No Facebook/Apple at launch.
11. Any e-mail on `@saadatu-aldarein.com` is an interpreter account and signs in **only** with the e-mail code (Google rejected). Domain is a DB setting (`interpreter.email_domain`); plus `interpreter.emails` list and `INTERPRETER_EMAILS` env for bootstrap.
12. Every user: name, gender, **date of birth** (age shown to the interpreter), country (server-detected). Initial-letter colour avatar instead of photos, everywhere.
13. Sign-out must exist on every device (profile page icon; sidebar on desktop). Login/account/sign-out are icons, not text.
14. Secure payments: signed webhooks only, idempotent, amounts verified server-side; mock provider only outside prod (`ProductionSafetyCheck`).

## C. Backend conventions
15. Spring Boot 3 / Java 21 / Gradle Kotlin DSL / PostgreSQL 16 / Flyway. Packages by domain under `com.saadat.*`; every route in one constants class `ApiPaths`.
16. All business settings live in the DB table `app_settings` (keys in `SettingKeys`), editable from the dashboard — no static strings for business values. Env vars only for infrastructure/secrets.
17. Profiles: local / dev / prod / test. Prod refuses mocks. Payments mocked via config until Paymob keys exist (Paymob last).
18. Professional Thymeleaf e-mails in the site's look, Arabic + English, sent from the site e-mail (variables ready; LOGGED when SMTP empty).
19. YouTube channel button = notification badge of unseen videos (feed polled server-side).
20. Every change must pass CI tests (Testcontainers). Backend compiles only in GitHub Actions from the sandbox; read `ci-logs` branch for results.

## D. Frontend conventions
21. Vite + React + TS + Tailwind + GSAP + TanStack Query + i18next + MSW mocks (mocks on for the Vercel preview, off in production).
22. Arabic default (RTL) + English (LTR). **No hardcoded Arabic in TSX** — everything in `i18n/ar.json` and `en.json`. English must be fully English, very simple and accurate. Quotes: « » / ﴿ ﴾ in Arabic, “ ” in English. Arrows flip with direction.
23. Dark + light themes; fonts IBM Plex Sans Arabic / IBM Plex Sans; brand colours from the original site (night, navy, gold, pearl).
24. Design: fancy and calm. Morph icons (before/after paths), text reveal animations (SplitText **by words only** for Arabic), animated night sky: real star shapes of mixed sizes twinkling, dust dots, frequent meteors, a moon crossing right→left behind all text with phases tied to screen position (thin crescent on entry, full at centre, crescent on exit; slower on phones), hadith "written in the sky" whose letters brighten over the moon disc.
25. Hero layout: hadith line → title → lead → CTA (two-line button with "join N+ people" + avatar stack, N from live stats) with reply-time right under it → YouTube button → three compact trust badges in one row (no Quran verse badge) → bottom hadith.
26. Stats counters count through the full number with digit grouping, then collapse to the compact label (1M+, 50K+).
27. PWA installable with push; credits pill in the header; notification bell; everything mobile-first and verified with Playwright screenshots at phone and desktop widths before pushing.
28. Hero remounts on language switch; language switch refetches server-localised data.

## E. Delivery & communication
29. Everything deployable from GitHub Actions: frontend → Vercel preview (manual or `VERCEL_TOKEN`), full stack → Hostinger VPS via `deploy-vps.yml` after `install.sh` (served at `/ops/install.sh` on the preview site because the repo is private). Replace the live domain directly through Cloudflare DNS.
30. At the end of each milestone give the owner a numbered list of exactly what he must configure (1, 2, 3…).
31. Reply to the owner in pure Arabic; any Latin term, command, path or value goes on its own line inside a code block (inline mixing breaks RTL reading). Keep explanations simple — one line per command: "this does X".
32. Don't use Lovable to build (credits limited); use Opus subagents with detailed directives for big execution tasks.
