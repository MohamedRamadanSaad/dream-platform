import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { meApi } from '@/api/endpoints'
import { useAuthStore, isInterpreter } from '@/app/auth-store'
import { Icon } from '@/components/icons/Icon'
import { cn, fmtDay } from '@/lib/utils'

/** "N of your credits expire on <date>": shown while the soonest expiry is within the notice window (server decides). */
export function CreditExpiryNotice({ className, withLink }: { className?: string; withLink?: boolean }) {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)
  const locale = useAuthStore((s) => s.locale)
  const { data } = useQuery({ queryKey: ['me', 'dashboard'], queryFn: meApi.dashboard, enabled: !!user && !isInterpreter(user) })
  const next = data?.nextExpiry
  if (!next || next.credits < 1) return null
  const date = fmtDay(next.at.slice(0, 10), locale, { day: 'numeric', month: 'long', year: 'numeric' })
  return (
    <div role="status" className={cn('card flex flex-wrap items-center gap-3 border-warn/40 bg-warn/5 p-4 text-sm', className)}>
      <span className="text-warn"><Icon name="clock" size={18} /></span>
      <span className="flex-1">{t('me.expiry.notice', { count: next.credits, date })}</span>
      {withLink && <Link to="/me/new" className="text-xs font-medium text-gold-ink">{t('me.expiry.use')}</Link>}
    </div>
  )
}
