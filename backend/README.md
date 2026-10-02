# Backend — إلى سعادة الدارين

Spring Boot 3.3.5 · Java 21 · PostgreSQL 16 · Flyway · Gradle (Kotlin DSL, no wrapper committed).

Binding spec: [`docs/BACKEND_SPEC.md`](../docs/BACKEND_SPEC.md). API contract: `frontend/src/api/types.ts` + `endpoints.ts`.

## Run locally

```bash
# 1. Postgres (from deploy/)
cd deploy && POSTGRES_PASSWORD=dreams docker compose up -d postgres
# the compose file does not publish 5432; for a host-run backend either add `ports: ["5432:5432"]`
# locally or run: docker run -d --name pg -e POSTGRES_DB=dreams -e POSTGRES_USER=dreams \
#   -e POSTGRES_PASSWORD=dreams -p 5432:5432 postgres:16-alpine

# 2. Backend — a profile is mandatory (there is no default profile on purpose)
cd backend && gradle bootRun --args='--spring.profiles.active=local'
```

- API: <http://localhost:8080/api> (context path `/api`)
- Health: <http://localhost:8080/api/actuator/health>
- Swagger UI: <http://localhost:8080/api/swagger-ui.html>
- Frontend against the real API: `VITE_USE_MOCKS=false VITE_API_URL=http://localhost:8080/api npm run dev`

Local profile: mock payments, mock Google login (`idToken: "mock"`), `X-Country` header honoured, e-mails logged
when `SMTP_HOST` is empty, refresh cookie without `Secure`.

## Build & test

```bash
gradle build            # compile + unit + integration tests (needs Docker for Testcontainers)
gradle test --tests '*JwtServiceTest'
```

CI: `.github/workflows/backend.yml`. Docker image: `docker build -t saadat-backend backend/`.

## Configuration

- Infrastructure (secrets, URLs, switches): `app.*` → `com.saadat.config.props.AppProperties`, fed from env vars —
  every variable is documented in [`.env.example`](.env.example).
- Business settings the interpreter may change: table `app_settings`, read via `SettingsService`, keys in
  `SettingKeys`, editable through `GET/PUT /api/admin/settings`.
- Profiles: `local`, `dev` (staging), `prod`, `test` (`src/test/resources/application-test.yml`).

## Code layout (`com.saadat`)

| Package | Content |
|---|---|
| `common.api` | `ApiPaths` (every route), `PageResponse`, `Pages` |
| `common.error` | RFC 7807 handler + domain exceptions (`NotFound`, `Conflict`, `Forbidden`, `Unauthorized`, `PaymentRequired`, `Validation`, `NotConfigured`) |
| `common.security` | `SecurityConfig`, `JwtService`, `JwtAuthFilter`, `RateLimitFilter`, `AuthPrincipal` |
| `common.web` | `RequestIdFilter`, `CountryResolver`, `ClientIp`, `LogMask` |
| `common.audit` | `AuditService` + `audit_log` |
| `common.domain` | shared enums (`Role`, `DreamStatus`, `Currency`, `Locale`, …) |
| `settings` | `app_settings`, `SettingsService`, admin controller |
| `users`, `pricing`, `payments`, `dreams`, `notifications`, `youtube` | `domain` (entities) + `repo` (Spring Data) — services/controllers added per feature |
| `passkeys` | fingerprint / face sign-in (WebAuthn via webauthn4j, docs/PASSKEYS_CONTRACT.md): `WebAuthnRelyingParty` (RP ID + origins from `FRONTEND_URL`, overridable by `PASSKEY_RP_ID` / `PASSKEY_ORIGINS`), single-use challenges, `/me/passkeys/**`, `/auth/passkey/**` |
| `config` | Jackson, async pool, OpenAPI, clock, JSON log layout |

Conventions: controllers map `ApiPaths` constants directly (no literal paths); DTOs are records matching `types.ts`;
entities reference other aggregates by UUID (no JPA relations); inject `java.time.Clock` instead of `Instant.now()`
in services; every interpreter mutation calls `AuditService.record(...)`.
