import { cn } from '@/lib/utils'

/** Eight calm hues; the name picks one deterministically so a user always gets the same colour. */
const HUES = [
  ['#D4AF37', '#0a1128'], // gold
  ['#16244a', '#EADBAA'], // navy
  ['#2f6f73', '#f4efe6'], // teal
  ['#7a4b8a', '#f4efe6'], // plum
  ['#b5623a', '#fff7ec'], // clay
  ['#3d6b4f', '#f4efe6'], // moss
  ['#8a2e3b', '#fbe9ea'], // wine
  ['#4a5b8c', '#f4efe6'], // slate blue
]

export function avatarColors(name: string): [string, string] {
  let h = 0
  for (const ch of name) h = (h * 31 + ch.codePointAt(0)!) >>> 0
  return HUES[h % HUES.length] as [string, string]
}

/** First letter of the (first) name; falls back to ✦ when empty. */
export function initialOf(name?: string | null) {
  const t = (name ?? '').trim()
  if (!t) return '✦'
  const first = t.split(/\s+/)[0]
  // Arabic names starting with "أم"/"أبو"/"عبد" read better with the next word's letter
  const skip = ['أم', 'ام', 'أبو', 'ابو', 'عبد', 'عبدال', 'المعبرة', 'المعبّرة', 'الشيخ', 'الشيخة', 'الدكتور', 'الدكتورة', 'د.', 'interpreter', 'sheikh', 'sheikha', 'dr', 'dr.', 'mr', 'mrs', 'ms', 'umm', 'abu', 'abdul', 'abd']
  const parts = t.split(/\s+/)
  const word = skip.includes(first.toLowerCase()) && parts[1] ? parts[1] : first
  return Array.from(word)[0]!.toUpperCase()
}

export function Avatar({ name, size = 36, className, ring }: { name?: string | null; size?: number; className?: string; ring?: boolean }) {
  const [bg, fg] = avatarColors(name ?? '')
  return (
    <span
      aria-hidden="true"
      className={cn('inline-flex shrink-0 select-none items-center justify-center rounded-full font-display leading-none', ring && 'ring-2 ring-[color:var(--bg)]', className)}
      style={{ width: size, height: size, background: bg, color: fg, fontSize: Math.round(size * 0.45) }}
    >
      {initialOf(name)}
    </span>
  )
}

/** Overlapping stack of avatars (e.g. "join 2,000+ people"). */
export function AvatarStack({ names, size = 26, className }: { names: string[]; size?: number; className?: string }) {
  return (
    <span className={cn('inline-flex items-center', className)} dir="ltr">
      {names.map((n, i) => <Avatar key={i} name={n} size={size} ring className={i > 0 ? '-ms-2' : ''} />)}
    </span>
  )
}
