# Passkeys (fingerprint / face sign-in) — contract

WebAuthn with discoverable credentials (resident keys) and required user verification. Relying-party ID = the site host
(`saadatu-aldarein.com` in production, `localhost` in local/test), allowed origin = `app.frontend-url`. Available to USER and
INTERPRETER accounts; adding a passkey requires an already signed-in session (so an interpreter passkey can only be created
after an e-mail-code sign-in on the site domain). Routes in `ApiPaths`; business values in `app_settings`.

## JSON transport
- Options are the standard WebAuthn JSON forms (binary fields base64url), so the browser can use
  `PublicKeyCredential.parseCreationOptionsFromJSON` / `parseRequestOptionsFromJSON` (the frontend keeps a small fallback converter).
- Credentials go back in the standard JSON form (`credential.toJSON()` shape): `id`, `rawId`, `type`, `authenticatorAttachment`,
  `clientExtensionResults`, `response` = { `clientDataJSON`, `attestationObject`, `transports` } for registration and
  { `clientDataJSON`, `authenticatorData`, `signature`, `userHandle` } for sign-in.

## Registration (signed in, both roles)
- `POST /me/passkeys/registration/options` → `{ requestId: string, publicKey: PublicKeyCredentialCreationOptionsJSON }`
  — challenge stored server-side, single use, lifetime = setting `auth.passkey_challenge_ttl_seconds` (300);
  `excludeCredentials` = the caller's passkeys; `residentKey: "required"`, `userVerification: "required"`, `attestation: "none"`;
  RP name = the brand name setting.
- `POST /me/passkeys/registration` body `{ requestId, credential, label? }` → `201 PasskeyDto`. `label` defaults to
  "<browser> · <os>" from the User-Agent (same parser as devices).
- `GET /me/passkeys` → `PasskeyDto[]` (newest first): `{ id: string; label: string; createdAt: string; lastUsedAt: string | null }`
- `DELETE /me/passkeys/{id}` → 204; 404 when it is not the caller's.
- E-mail event `passkey-added` (ar/en, existing layout) to the account owner with a link to the profile; toggle
  `mail.event.passkey-added` (default true).

## Sign-in (anonymous)
- `POST /auth/passkey/options` → `{ requestId: string, publicKey: PublicKeyCredentialRequestOptionsJSON }`
  (empty `allowCredentials` so the device offers its own passkeys; `userVerification: "required"`).
- `POST /auth/passkey/verify` body `{ requestId, credential, rememberMe?: boolean }` → `AuthResponse` + refresh cookie, exactly
  like `/auth/magic/verify` (remember me, device/session `sid`, new-sign-in alert, role sync on sign-in).
  Unknown credential, wrong signature, wrong origin/RP, reused or expired challenge, sign-count regression → 401 with problem
  code `PASSKEY_INVALID`. Deleting an account deletes its passkeys.
