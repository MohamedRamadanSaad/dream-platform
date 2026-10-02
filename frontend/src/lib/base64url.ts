// base64url without padding (RFC 4648 §5): how WebAuthn and Web Push carry binary values in JSON.

export function toBase64url(data: ArrayBuffer | ArrayBufferView): string {
  const bytes = data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
  let bin = ''
  // in slices: one String.fromCharCode call per byte would be slow, one for everything can overflow the stack
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** Accepts padded or unpadded input, and plain base64 too. Throws on characters outside the alphabet. */
export function fromBase64url(value: string): Uint8Array<ArrayBuffer> {
  const b64 = value.replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '')
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}
