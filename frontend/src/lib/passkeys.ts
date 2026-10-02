// Passkeys (WebAuthn): sign-in with the device's fingerprint, face or screen lock — docs/PASSKEYS_CONTRACT.md.
//
// Safari opens the fingerprint / face prompt only from the user's tap. So the options are fetched before the tap
// (useFreshOptions) and navigator.credentials.create / get runs straight from the click handler when they are ready.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { TFunction } from 'i18next'
import type { AuthenticationCredentialJSON, RegistrationCredentialJSON } from '@/api/types'
import { fromBase64url, toBase64url } from './base64url'
import { thisBrowser } from './userAgent'

// ---------- what this browser can do ----------

/** The WebAuthn statics, each optional: older browsers lack the JSON helpers and the capability check. */
interface PublicKeyCredentialStatics {
  isUserVerifyingPlatformAuthenticatorAvailable?: () => Promise<boolean>
  getClientCapabilities?: () => Promise<Record<string, boolean | undefined>>
  parseCreationOptionsFromJSON?: (o: PublicKeyCredentialCreationOptionsJSON) => PublicKeyCredentialCreationOptions
  parseRequestOptionsFromJSON?: (o: PublicKeyCredentialRequestOptionsJSON) => PublicKeyCredentialRequestOptions
}
const statics = () => window.PublicKeyCredential as unknown as PublicKeyCredentialStatics

/** WebAuthn is here: a secure page (https or localhost) in a browser that has it. */
export function hasWebAuthn(): boolean {
  try {
    return window.isSecureContext && typeof window.PublicKeyCredential === 'function'
      && typeof navigator.credentials?.create === 'function' && typeof navigator.credentials?.get === 'function'
  } catch {
    return false
  }
}

export interface PasskeySupport {
  /** The browser has WebAuthn. */
  webauthn: boolean
  /** This device has a fingerprint, face or screen lock the browser can use: a passkey can be added here. */
  platform: boolean
  /** Sign-in can work here: with this device, or with a phone nearby (the browser shows a QR code). */
  signIn: boolean
}
const NONE: PasskeySupport = { webauthn: false, platform: false, signIn: false }
/** A check that hangs must not keep the button hidden forever. */
const CHECK_MS = 3000

const settle = <T,>(p: Promise<T> | undefined, fallback: T): Promise<T> =>
  p ? Promise.race([p.catch(() => fallback), new Promise<T>((r) => window.setTimeout(() => r(fallback), CHECK_MS))]) : Promise.resolve(fallback)

let detected: Promise<PasskeySupport> | null = null
let known: PasskeySupport | null = null

/** Asked once per page load: the answer does not change while the page is open. */
export function detectPasskeySupport(): Promise<PasskeySupport> {
  detected ??= (async () => {
    if (!hasWebAuthn()) return NONE
    const S = statics()
    const [platform, caps] = await Promise.all([
      settle(S.isUserVerifyingPlatformAuthenticatorAvailable?.(), false),
      settle(S.getClientCapabilities?.(), undefined),
    ])
    return { webauthn: true, platform, signIn: platform || caps?.hybridTransport === true }
  })().then((s) => (known = s))
  return detected
}

/** What this browser can do with passkeys; null while the browser is being asked. */
export function usePasskeySupport(): PasskeySupport | null {
  const [support, setSupport] = useState<PasskeySupport | null>(known)
  useEffect(() => {
    if (support) return
    let live = true
    void detectPasskeySupport().then((s) => { if (live) setSupport(s) })
    return () => { live = false }
  }, [support])
  return support
}

// ---------- JSON ⇄ browser objects ----------

const descriptor = (d: PublicKeyCredentialDescriptorJSON): PublicKeyCredentialDescriptor => ({
  type: d.type as PublicKeyCredentialType,
  id: fromBase64url(d.id),
  ...(d.transports ? { transports: d.transports as AuthenticatorTransport[] } : {}),
})

/** Server JSON → creation options: the browser's own parser when it has one, else a small converter. */
export function toCreationOptions(json: PublicKeyCredentialCreationOptionsJSON): PublicKeyCredentialCreationOptions {
  const S = statics()
  if (typeof S.parseCreationOptionsFromJSON === 'function') {
    try { return S.parseCreationOptionsFromJSON(json) } catch { /* an older parser may refuse a newer field: convert here */ }
  }
  const { challenge, user, excludeCredentials, ...rest } = json
  return {
    ...(rest as unknown as Omit<PublicKeyCredentialCreationOptions, 'challenge' | 'user'>),
    challenge: fromBase64url(challenge),
    user: { ...user, id: fromBase64url(user.id) },
    ...(excludeCredentials ? { excludeCredentials: excludeCredentials.map(descriptor) } : {}),
  }
}

/** Server JSON → request options (same approach). */
export function toRequestOptions(json: PublicKeyCredentialRequestOptionsJSON): PublicKeyCredentialRequestOptions {
  const S = statics()
  if (typeof S.parseRequestOptionsFromJSON === 'function') {
    try { return S.parseRequestOptionsFromJSON(json) } catch { /* convert here */ }
  }
  const { challenge, allowCredentials, ...rest } = json
  return {
    ...(rest as unknown as Omit<PublicKeyCredentialRequestOptions, 'challenge'>),
    challenge: fromBase64url(challenge),
    ...(allowCredentials ? { allowCredentials: allowCredentials.map(descriptor) } : {}),
  }
}

/**
 * "On this device": the device's own fingerprint / face / screen lock, so the browser goes straight to it instead of
 * offering a phone or a security key. Only when the server left the choice open.
 */
function onThisDevice(json: PublicKeyCredentialCreationOptionsJSON): PublicKeyCredentialCreationOptionsJSON {
  const sel = json.authenticatorSelection ?? {}
  return sel.authenticatorAttachment ? json : { ...json, authenticatorSelection: { ...sel, authenticatorAttachment: 'platform' } }
}

type Json = Record<string, unknown>
const str = (v: unknown) => (typeof v === 'string' ? v : undefined)

/** credential.toJSON() where the browser has it; some password-manager extensions hand back objects where it fails. */
function nativeJSON(c: PublicKeyCredential): { rawId?: unknown; clientExtensionResults?: unknown; response?: Json } | null {
  try {
    return typeof c.toJSON === 'function' ? (c.toJSON() as { response?: Json }) : null
  } catch {
    return null
  }
}

function extensionResults(c: PublicKeyCredential): Json {
  try { return JSON.parse(JSON.stringify(c.getClientExtensionResults?.() ?? {})) as Json } catch { return {} }
}

/** The fields both kinds share; only what the contract names is sent. */
function common(c: PublicKeyCredential, j: ReturnType<typeof nativeJSON>) {
  const attachment = str(c.authenticatorAttachment)
  return {
    id: c.id,
    rawId: str(j?.rawId) ?? toBase64url(c.rawId),
    type: c.type,
    ...(attachment ? { authenticatorAttachment: attachment } : {}),
    clientExtensionResults: (j?.clientExtensionResults as Json | undefined) ?? extensionResults(c),
  }
}

export function registrationJSON(c: PublicKeyCredential): RegistrationCredentialJSON {
  const j = nativeJSON(c)
  const r = c.response as AuthenticatorAttestationResponse
  const transports = j?.response?.transports
  return {
    ...common(c, j),
    response: {
      clientDataJSON: str(j?.response?.clientDataJSON) ?? toBase64url(r.clientDataJSON),
      attestationObject: str(j?.response?.attestationObject) ?? toBase64url(r.attestationObject),
      transports: Array.isArray(transports) ? (transports as string[]) : typeof r.getTransports === 'function' ? r.getTransports() : [],
    },
  }
}

export function authenticationJSON(c: PublicKeyCredential): AuthenticationCredentialJSON {
  const j = nativeJSON(c)
  const r = c.response as AuthenticatorAssertionResponse
  const userHandle = str(j?.response?.userHandle) ?? (r.userHandle && r.userHandle.byteLength ? toBase64url(r.userHandle) : undefined)
  return {
    ...common(c, j),
    response: {
      clientDataJSON: str(j?.response?.clientDataJSON) ?? toBase64url(r.clientDataJSON),
      authenticatorData: str(j?.response?.authenticatorData) ?? toBase64url(r.authenticatorData),
      signature: str(j?.response?.signature) ?? toBase64url(r.signature),
      ...(userHandle ? { userHandle } : {}),
    },
  }
}

// ---------- the two prompts ----------

/**
 * cancelled — the person closed the prompt, or it timed out: say nothing.
 * exists — this device already holds a passkey for the account (excludeCredentials).
 * unsupported — the device cannot make one. failed — anything else.
 */
export type PasskeyFailure = 'cancelled' | 'exists' | 'unsupported' | 'failed'

export class PasskeyError extends Error {
  readonly reason: PasskeyFailure
  constructor(reason: PasskeyFailure, cause?: unknown) {
    super(`passkey ${reason}`, { cause })
    this.name = 'PasskeyError'
    this.reason = reason
  }
}

export function passkeyFailure(e: unknown): PasskeyFailure {
  if (e instanceof PasskeyError) return e.reason
  const name = (e as { name?: unknown } | null)?.name
  if (name === 'NotAllowedError' || name === 'AbortError') return 'cancelled'
  if (name === 'InvalidStateError') return 'exists'
  if (name === 'NotSupportedError') return 'unsupported'
  return 'failed'
}
const asPasskeyError = (e: unknown) => (e instanceof PasskeyError ? e : new PasskeyError(passkeyFailure(e), e))

/** Opens the device's prompt to add a passkey. Call it straight from the tap: nothing awaited before it. */
export async function createPasskey(options: PublicKeyCredentialCreationOptionsJSON): Promise<RegistrationCredentialJSON> {
  try {
    const cred = await navigator.credentials.create({ publicKey: toCreationOptions(onThisDevice(options)) })
    if (!cred) throw new PasskeyError('cancelled')
    return registrationJSON(cred as PublicKeyCredential)
  } catch (e) {
    throw asPasskeyError(e)
  }
}

/** Opens the device's prompt to sign in with one of its passkeys. Call it straight from the tap. */
export async function getPasskey(options: PublicKeyCredentialRequestOptionsJSON): Promise<AuthenticationCredentialJSON> {
  try {
    const cred = await navigator.credentials.get({ publicKey: toRequestOptions(options) })
    if (!cred) throw new PasskeyError('cancelled')
    return authenticationJSON(cred as PublicKeyCredential)
  } catch (e) {
    throw asPasskeyError(e)
  }
}

// ---------- options fetched before the tap ----------

/** Fetched options are used this long, then fetched again (the server's challenge lives 5 minutes by default). */
const FRESH_MS = 120_000

interface Slot<T> { at: number; promise: Promise<T>; value?: T }

export interface FreshOptions<T> {
  /** Fetches a set unless a fresh one is ready or on its way. */
  warm: () => void
  /** The ready set, handed over at once (single use) — or null. */
  take: () => T | null
  /** The fresh set, fetched now if needed (single use). */
  next: () => Promise<T>
}

/**
 * Keeps one set of single-use options ready so the device's prompt can open inside the tap. `enabled` fetches the
 * first set and refreshes it when the page is shown again.
 */
export function useFreshOptions<T>(fetcher: () => Promise<T>, enabled: boolean): FreshOptions<T> {
  const slot = useRef<Slot<T> | null>(null)
  const fetchRef = useRef(fetcher)
  fetchRef.current = fetcher
  const warm = useCallback(() => {
    const s = slot.current
    if (s && Date.now() - s.at < FRESH_MS) return
    const entry: Slot<T> = { at: Date.now(), promise: fetchRef.current() }
    entry.promise.then((v) => { entry.value = v }, () => { if (slot.current === entry) slot.current = null })
    slot.current = entry
  }, [])
  const take = useCallback((): T | null => {
    const s = slot.current
    if (!s || s.value === undefined || Date.now() - s.at >= FRESH_MS) return null
    slot.current = null
    return s.value
  }, [])
  const next = useCallback((): Promise<T> => {
    warm()
    const s = slot.current as Slot<T>
    slot.current = null
    return s.promise
  }, [warm])
  useEffect(() => {
    if (!enabled) return
    warm()
    const onShow = () => { if (document.visibilityState === 'visible') warm() }
    document.addEventListener('visibilitychange', onShow)
    return () => document.removeEventListener('visibilitychange', onShow)
  }, [enabled, warm])
  return useMemo(() => ({ warm, take, next }), [warm, take, next])
}

/**
 * One tap: the options (ready, or fetched now) → the device's prompt. When the options are ready the prompt opens
 * synchronously inside the tap.
 */
export function withOptions<K, C>(prepared: FreshOptions<{ requestId: string; publicKey: K }>, ask: (options: K) => Promise<C>): Promise<{ requestId: string; credential: C }> {
  const run = (o: { requestId: string; publicKey: K }) => ask(o.publicKey).then((credential) => ({ requestId: o.requestId, credential }))
  const ready = prepared.take()
  return ready ? run(ready) : prepared.next().then(run)
}

// ---------- small things ----------

/** Default name of a passkey added here: "Chrome · Android" in the page language (the person can change it). */
export function passkeyLabel(t: TFunction): string {
  const { browser, os } = thisBrowser()
  return `${t(`me.devices.browsers.${browser}`, { defaultValue: browser })} · ${t(`me.devices.os.${os}`, { defaultValue: os })}`
}

const OFFER_KEY = 'saadat-passkey-offer'
/** The "sign in faster next time" card was answered on this device (turned on, or "not now"). */
export function passkeyOfferDone(): boolean {
  try { return localStorage.getItem(OFFER_KEY) === 'done' } catch { return false }
}
export function markPasskeyOfferDone() {
  try { localStorage.setItem(OFFER_KEY, 'done') } catch { /* storage blocked: the card may come back after the next sign-in */ }
}
