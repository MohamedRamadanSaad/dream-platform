import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { reduced } from '@/components/motion'

/**
 * E-mails link to …/profile#devices or #passkeys: once the section is drawn (`ready`), bring it into view and light
 * it up for a moment.
 */
export function useAnchorArrival<T extends HTMLElement>(id: string, ready: boolean) {
  const { hash } = useLocation()
  const ref = useRef<T>(null)
  const arrived = useRef(false)
  const [flash, setFlash] = useState(false)
  useEffect(() => {
    if (hash !== `#${id}` || arrived.current || !ready) return
    const h = window.setTimeout(() => {
      arrived.current = true
      ref.current?.scrollIntoView({ behavior: reduced() ? 'auto' : 'smooth', block: 'start' })
      setFlash(true)
    }, 120)
    return () => window.clearTimeout(h)
  }, [hash, id, ready])
  useEffect(() => {
    if (!flash) return
    const h = window.setTimeout(() => setFlash(false), 2400)
    return () => window.clearTimeout(h)
  }, [flash])
  return { ref, flash }
}
