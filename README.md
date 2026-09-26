# dream-platform — إلى سعادة الدارين

Rebuild of saadatu-aldarein.com: a dream-interpretation platform (Arabic-first, English optional).

| Part | Stack | Status |
|---|---|---|
| `frontend/` | Vite · React 18 · TypeScript · Tailwind · GSAP (MorphSVG/SplitText/ScrollTrigger) · TanStack Query · react-i18next · MSW mocks · PWA | ✅ all screens, mocked API |
| `backend/` | Spring Boot 3.3 · Java 21 · Gradle (Kotlin DSL) · PostgreSQL · Flyway | ⏳ next |
| `docs/` | Spec v2 + build pack + API contract | ✅ |

## Run the frontend (mocked backend)

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173
```

`VITE_USE_MOCKS=true` (default in `.env.example`) serves the whole API from `src/mocks/` with MSW.
Set `VITE_USE_MOCKS=false` and `VITE_API_URL=https://api.<domain>` to hit the real backend — no code changes.

**Mock logins** (any 6-digit code works):
- user: `ummohamed@gmail.com` (Saudi, has credits, drafts, one open question)
- interpreter: `fatema@saadatu-aldarein.com`

## API contract
`frontend/src/api/types.ts` is the single source of truth for DTOs; `frontend/src/api/endpoints.ts` lists every route.
The backend must implement exactly these. Errors are RFC 7807 `application/problem+json`.

## Deploy target
Hostinger VPS (KVM 1, Ubuntu 24.04, Docker) behind Cloudflare DNS. See `docs/BUILD_PACK.md` Part D.
