// Fingerprint / face sign-in (passkeys) for the signed-in account: the list, and adding one on this device.
// Shared by the account page section and the "sign in faster next time" card.
import { useCallback, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { meApi } from '@/api/endpoints'
import { createPasskey, passkeyFailure, useFreshOptions, withOptions, type PasskeyFailure } from '@/lib/passkeys'
import type { PasskeyDto } from '@/api/types'

export const PASSKEYS_KEY = ['me', 'passkeys'] as const

export const usePasskeys = (enabled = true) => useQuery({ queryKey: PASSKEYS_KEY, queryFn: meApi.passkeys, enabled })

/** What went wrong while adding (a cancelled prompt says nothing). */
export type AddFailure = Exclude<PasskeyFailure, 'cancelled'>

/**
 * Adds a passkey on this device: options (fetched ahead while `ready`) → the device's prompt → saved on the server.
 * Call `start` straight from the tap. Resolves with the new passkey, or null (cancelled, or `failure` says why).
 */
export function useAddPasskey(ready: boolean) {
  const qc = useQueryClient()
  const prepared = useFreshOptions(meApi.passkeyRegistrationOptions, ready)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<AddFailure | null>(null)
  const start = useCallback((label?: string): Promise<PasskeyDto | null> => {
    setFailure(null)
    setBusy(true)
    const name = label?.trim()
    return withOptions(prepared, createPasskey)
      .then(({ requestId, credential }) => meApi.addPasskey({ requestId, credential, ...(name ? { label: name } : {}) }))
      .then((added) => {
        void qc.invalidateQueries({ queryKey: PASSKEYS_KEY })
        return added
      })
      .catch((e: unknown) => {
        const reason = passkeyFailure(e)
        if (reason !== 'cancelled') setFailure(reason)
        // the options are single use: a fresh set for the next try
        prepared.warm()
        return null
      })
      .finally(() => setBusy(false))
  }, [prepared, qc])
  const clearFailure = useCallback(() => setFailure(null), [])
  return { start, busy, failure, clearFailure }
}
