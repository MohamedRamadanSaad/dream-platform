# Remember me, devices, new-sign-in alert & profile — contract

Shared by backend and frontend. JSON camelCase, instants ISO-8601 UTC. All routes in `ApiPaths`; business values in `app_settings`.

## 1. Remember me
- `POST /auth/google` body `{ idToken: string, rememberMe?: boolean }` and `POST /auth/magic/verify` body
  `{ token?, email?, code?, rememberMe?: boolean }`. Missing `rememberMe` = `true`.
- `rememberMe = true`: refresh-token lifetime = setting `auth.refresh_ttl_days` (migration raises the default from 30 to 90
  only if it is still 30); the refresh cookie is persistent (Max-Age).
- `rememberMe = false`: lifetime = new setting `auth.session_ttl_hours` (default 12); the refresh cookie is a browser-session
  cookie (no Max-Age / Expires).
- The mode is stored per refresh-token family (`refresh_tokens.persistent`) and kept by `/auth/refresh` rotation; every rotation
  slides the expiry (now + lifetime of that mode) and writes the cookie in the same mode.

## 2. Devices = the caller's active refresh-token families
- Access tokens carry the family id as claim `sid`; `AuthPrincipal` exposes it (`sessionId`, nullable for old tokens).
- A request whose `sid` family is revoked or expired is anonymous (same place as the DB role lookup in `JwtAuthFilter`), so
  signing a device out takes effect on its next request. Tokens without `sid` (issued before this change) stay valid until
  they expire.
- `GET /me/devices` → `DeviceDto[]`, most recently active first:
  ```ts
  interface DeviceDto {
    id: string                 // family id
    browser: string            // "Chrome", "Safari", "Edge", "Firefox", "Samsung Internet", "Opera", "Other"
    os: string                 // "Windows", "macOS", "iOS", "iPadOS", "Android", "Linux", "Other"
    deviceType: 'MOBILE' | 'TABLET' | 'DESKTOP'
    countryCode: string | null
    countryName: string | null // localized by Accept-Language
    signedInAt: string         // first token of the family
    lastActiveAt: string       // newest token of the family
    current: boolean           // family == sid of the calling access token
    persistent: boolean        // remember me on this device
  }
  ```
  Active = at least one non-revoked, non-expired token in the family. Browser/OS/type parsed from the stored User-Agent with a
  small built-in parser (no new dependency).
- `DELETE /me/devices/{id}` → revokes that family when it belongs to the caller, otherwise 404. 204.
- `POST /me/devices/sign-out-others` → revokes every family of the caller except the current one. 204.
- `POST /auth/logout` (existing) revokes the current family and clears the cookie; the frontend MUST call it on sign-out.

## 3. New sign-in alert (e-mail)
On a successful Google or magic-link sign-in (not on refresh), when the user already had an earlier session and the new
device (browser + os) matches none of the user's families from the last 90 days, send template `new-sign-in` (ar/en, existing
layout): device, country, time, and a button "Review my devices" → `{frontend}/me/profile#devices` for users,
`{frontend}/admin/profile#devices` for the interpreter. Toggle: BOOL setting `mail.event.new-sign-in` (default true).

## 4. Profile for every account
- `GET /me` and `PUT /me/preferences` (name, gender, birthDate, locale) already serve both roles. The interpreter gets the same
  profile page at `/admin/profile` (admin nav + header link): personal data, language/theme, devices, sign-out.
- Data fix: a Flyway migration sets `birth_date = 1988-03-06` for the active INTERPRETER accounts.
