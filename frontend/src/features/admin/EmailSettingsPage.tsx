import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Empty, ErrorBox, Skeleton, Switch } from '@/components/ui'
import { Icon, type IconName } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { fmtNum } from '@/lib/utils'

// Each e-mail event is a BOOL setting `mail.event.<template>` (GET/PUT /admin/settings). Only keys the server
// returns are shown, and only the templates listed here (unknown keys are ignored). Sign-in e-mails are not here.
const PREFIX = 'mail.event.'
const GROUPS: { id: 'user' | 'you'; icon: IconName; templates: string[] }[] = [
  { id: 'user', icon: 'user', templates: ['welcome', 'dream-received', 'interpreter-question', 'reply-reminder', 'interpretation-ready', 'testimonial-request', 'testimonial-approved', 'payment-receipt', 'payment-failed', 'credits-adjusted', 'dream-cancelled', 'youtube-new-video', 'account-deleted'] },
  { id: 'you', icon: 'bell', templates: ['dream-submitted', 'user-replied', 'new-user', 'testimonial-received', 'payment-suspicious', 'interpreter-digest'] },
]

export function EmailSettingsPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['admin', 'settings'], queryFn: adminApi.settings })
  const [draft, setDraft] = useState<Record<string, boolean>>({})

  const server = useMemo(() => {
    const out: Record<string, boolean> = {}
    for (const [k, v] of Object.entries(q.data ?? {})) if (k.startsWith(PREFIX)) out[k] = String(v).trim().toLowerCase() === 'true'
    return out
  }, [q.data])
  const changes: Record<string, string> = {}
  for (const [k, v] of Object.entries(draft)) if (k in server && server[k] !== v) changes[k] = String(v)
  const changed = Object.keys(changes).length

  const save = useMutation({
    mutationFn: () => adminApi.saveSettings(changes),
    onSuccess: (all) => { qc.setQueryData(['admin', 'settings'], all); setDraft({}) },
  })
  const groups = GROUPS.map((g) => ({ ...g, templates: g.templates.filter((tpl) => `${PREFIX}${tpl}` in server) })).filter((g) => g.templates.length)
  const isOn = (key: string) => draft[key] ?? server[key]

  return (
    <PageEnter className="mx-auto max-w-3xl pb-24">
      <div className="mb-6 flex items-start gap-4">
        <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink"><Icon name="mail" size={24} /></span>
        <div className="min-w-0">
          <h1 className="font-display text-4xl">{t('admin.emails.title')}</h1>
          <p className="mt-1 text-sm font-light leading-relaxed text-fg-muted">{t('admin.emails.lead')}</p>
          {save.isSuccess && !changed && <p role="status" className="mt-2 text-sm text-ok-ink">✓ {t('admin.emails.saved')}</p>}
        </div>
      </div>

      {q.isLoading ? (
        <div className="space-y-4"><Skeleton className="h-96" /><Skeleton className="h-64" /></div>
      ) : q.isError ? (
        <ErrorBox onRetry={() => q.refetch()} />
      ) : !groups.length ? (
        <Empty text={t('admin.emails.empty')} />
      ) : (
        <StaggerGroup className="space-y-5" stagger={0.1}>
          {groups.map((g) => {
            const on = g.templates.filter((tpl) => isOn(`${PREFIX}${tpl}`)).length
            return (
              <section key={g.id} aria-labelledby={`emails-${g.id}`} className="card overflow-hidden p-0">
                <header className="flex items-center gap-3 border-b border-line px-5 py-4">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink"><Icon name={g.icon} size={18} /></span>
                  <div className="min-w-0 flex-1">
                    <h2 id={`emails-${g.id}`} className="font-medium">{t(`admin.emails.${g.id === 'user' ? 'toUser' : 'toYou'}`)}</h2>
                    <p className="text-xs text-fg-muted">{t(`admin.emails.${g.id === 'user' ? 'toUserLead' : 'toYouLead'}`)}</p>
                  </div>
                  <span className="shrink-0 text-xs text-fg-dim">{t('admin.emails.onCount', { on: fmtNum(on, locale), all: fmtNum(g.templates.length, locale) })}</span>
                </header>
                <ul>
                  {g.templates.map((tpl) => {
                    const key = `${PREFIX}${tpl}`
                    const dirty = key in changes
                    return (
                      <li key={tpl} className="flex items-start justify-between gap-4 border-b border-line px-5 py-4 last:border-0">
                        <div className="min-w-0">
                          <div className="flex items-center gap-2 font-medium">
                            {t(`admin.emails.events.${tpl}.label`)}
                            {dirty && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-gold" title={t('admin.emails.changed')} />}
                          </div>
                          <p className="mt-0.5 text-sm font-light leading-relaxed text-fg-muted">{t(`admin.emails.events.${tpl}.desc`)}</p>
                        </div>
                        <Switch checked={isOn(key)} label={t(`admin.emails.events.${tpl}.label`)} onChange={(v) => { save.reset(); setDraft((d) => ({ ...d, [key]: v })) }} />
                      </li>
                    )
                  })}
                </ul>
              </section>
            )
          })}
        </StaggerGroup>
      )}

      {changed > 0 && (
        <div role="region" aria-label={t('admin.emails.unsaved', { count: changed, n: fmtNum(changed, locale) })} className="fixed inset-x-4 bottom-20 z-40 mx-auto flex max-w-2xl flex-wrap items-center justify-between gap-3 rounded-xl2 bg-night p-4 text-pearl shadow-calm md:bottom-6">
          <div className="text-sm">
            {t('admin.emails.unsaved', { count: changed, n: fmtNum(changed, locale) })}
            {save.isError && <div role="alert" className="mt-1 text-xs text-bad-ink">{t('common.error')}</div>}
          </div>
          <div className="flex gap-2">
            <Button size="sm" variant="ghost" className="border-navy text-pearl hover:border-gold" onClick={() => { setDraft({}); save.reset() }}>{t('admin.emails.discard')}</Button>
            <Button size="sm" loading={save.isPending} onClick={() => save.mutate()}>{t('admin.emails.save')}</Button>
          </div>
        </div>
      )}
    </PageEnter>
  )
}
