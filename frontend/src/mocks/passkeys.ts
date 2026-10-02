// Mock passkeys (docs/PASSKEYS_CONTRACT.md). A mock cannot check signatures: it accepts any well-formed credential whose
// client data carries a challenge it issued (right type, this origin, not expired, single use) and keeps the list in memory.
import type * as T from '@/api/types'
import { fromBase64url, toBase64url } from '@/lib/base64url'
import { parseUserAgent } from '@/lib/userAgent'
import { db, helpers, uid } from './data'

interface MockPasskey {
  id: string
  credentialId: string
  userId: string
  label: string
  createdAt: string
  lastUsedAt: string | null
}
interface Challenge {
  kind: 'webauthn.create' | 'webauthn.get'
  challenge: string
  userId: string | null
  expiresAt: number
}

/** Setting auth.passkey_challenge_ttl_seconds (default 300). */
const TTL_MS = 300_000
const RP_NAME = 'إلى سعادة الدارين'

const passkeys: MockPasskey[] = []
/** Removed here, but still on the device: signing in with one is refused (PASSKEY_INVALID). */
const removed = new Set<string>()
const challenges = new Map<string, Challenge>()

const random = (n = 32) => toBase64url(crypto.getRandomValues(new Uint8Array(n)))
/** The WebAuthn user handle: opaque bytes the device gives back on sign-in (here: the account id). */
const handleOf = (userId: string) => toBase64url(new TextEncoder().encode(userId))
function userOfHandle(handle: string | undefined): T.UserDto | null {
  if (!handle) return null
  try {
    const id = new TextDecoder().decode(fromBase64url(handle))
    return db.users.find((u) => u.id === id) ?? null
  } catch {
    return null
  }
}
const toDto = ({ id, label, createdAt, lastUsedAt }: MockPasskey): T.PasskeyDto => ({ id, label, createdAt, lastUsedAt })
const isText = (v: unknown): v is string => typeof v === 'string' && v.length > 0

function issue(kind: Challenge['kind'], userId: string | null) {
  const requestId = uid() + uid()
  const challenge = random()
  challenges.set(requestId, { kind, challenge, userId, expiresAt: Date.now() + TTL_MS })
  return { requestId, challenge }
}

/** The challenge of `requestId`, spent; null when unknown, expired, of the other kind or not the one in the client data. */
function spend(requestId: unknown, kind: Challenge['kind'], clientDataJSON: unknown): Challenge | null {
  if (!isText(requestId) || !isText(clientDataJSON)) return null
  const c = challenges.get(requestId)
  challenges.delete(requestId)
  if (!c || c.kind !== kind || c.expiresAt < Date.now()) return null
  try {
    const data = JSON.parse(new TextDecoder().decode(fromBase64url(clientDataJSON))) as { type?: string; challenge?: string; origin?: string }
    return data.type === kind && data.challenge === c.challenge && data.origin === location.origin ? c : null
  } catch {
    return null
  }
}

/** POST /me/passkeys/registration/options */
export function registrationOptions(user: T.UserDto): T.PasskeyRegistrationOptions {
  const { requestId, challenge } = issue('webauthn.create', user.id)
  return {
    requestId,
    publicKey: {
      rp: { id: location.hostname, name: RP_NAME },
      user: { id: handleOf(user.id), name: user.email, displayName: user.name || user.email },
      challenge,
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
      timeout: 120_000,
      attestation: 'none',
      authenticatorSelection: { residentKey: 'required', requireResidentKey: true, userVerification: 'required' },
      excludeCredentials: passkeys.filter((p) => p.userId === user.id).map((p) => ({ type: 'public-key', id: p.credentialId, transports: ['internal', 'hybrid'] })),
      extensions: { credProps: true },
    },
  }
}

/** POST /me/passkeys/registration — null when the request is not acceptable (400). */
export function finishRegistration(user: T.UserDto, body: Partial<T.PasskeyRegistrationRequest>, ua: string): T.PasskeyDto | null {
  const cred = body.credential
  if (!cred || !isText(cred.id) || !isText(cred.rawId) || cred.type !== 'public-key' || !isText(cred.response?.attestationObject)) return null
  const c = spend(body.requestId, 'webauthn.create', cred.response?.clientDataJSON)
  if (!c || c.userId !== user.id || passkeys.some((p) => p.credentialId === cred.id)) return null
  const { browser, os } = parseUserAgent(ua)
  const p: MockPasskey = {
    id: uid(), credentialId: cred.id, userId: user.id,
    label: body.label?.trim().slice(0, 60) || `${browser} · ${os}`,
    createdAt: helpers.now(), lastUsedAt: null,
  }
  passkeys.push(p)
  removed.delete(cred.id)
  return toDto(p)
}

/** GET /me/passkeys — newest first. */
export const listPasskeys = (user: T.UserDto): T.PasskeyDto[] =>
  passkeys.filter((p) => p.userId === user.id).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(toDto)

/** DELETE /me/passkeys/{id} — false when it is not one of the caller's (404). */
export function removePasskey(userId: string, id: string): boolean {
  const i = passkeys.findIndex((p) => p.id === id && p.userId === userId)
  if (i < 0) return false
  removed.add(passkeys[i].credentialId)
  passkeys.splice(i, 1)
  return true
}

/** POST /auth/passkey/options — empty allowCredentials: the device offers its own passkeys. */
export function signInOptions(): T.PasskeySignInOptions {
  const { requestId, challenge } = issue('webauthn.get', null)
  return { requestId, publicKey: { challenge, rpId: location.hostname, allowCredentials: [], userVerification: 'required', timeout: 120_000 } }
}

/** POST /auth/passkey/verify — the account, or null (401 PASSKEY_INVALID). */
export function finishSignIn(body: Partial<T.PasskeyVerifyRequest>, ua: string): T.UserDto | null {
  const cred = body.credential
  if (!cred || !isText(cred.id) || cred.type !== 'public-key' || !isText(cred.response?.authenticatorData) || !isText(cred.response?.signature)) return null
  if (!spend(body.requestId, 'webauthn.get', cred.response?.clientDataJSON)) return null
  let p = passkeys.find((x) => x.credentialId === cred.id)
  if (!p) {
    // a page reload restarts the mock while the device keeps its passkey: known again through its user handle
    const user = removed.has(cred.id) ? null : userOfHandle(cred.response?.userHandle)
    if (!user) return null
    const { browser, os } = parseUserAgent(ua)
    p = { id: uid(), credentialId: cred.id, userId: user.id, label: `${browser} · ${os}`, createdAt: helpers.now(), lastUsedAt: null }
    passkeys.push(p)
  }
  p.lastUsedAt = helpers.now()
  return db.users.find((u) => u.id === p.userId) ?? null
}
