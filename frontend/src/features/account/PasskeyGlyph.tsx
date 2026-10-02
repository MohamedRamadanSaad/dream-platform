import { useEffect, useState } from 'react'
import { Icon } from '@/components/icons/Icon'
import { reduced } from '@/components/motion'

/**
 * The passkey icon turning from a fingerprint into a face and back every few seconds — "fingerprint or face" without
 * words. Still when motion is reduced. Only this icon re-renders on each turn.
 */
export function FingerprintOrFace({ size, every = 2600 }: { size: number; every?: number }) {
  const [face, setFace] = useState(false)
  useEffect(() => {
    if (reduced()) return
    const h = window.setInterval(() => setFace((v) => !v), every)
    return () => window.clearInterval(h)
  }, [every])
  return <Icon name="passkey" size={size} active={face} />
}
