import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import type { MailTemplateRow } from '@/api/types'
import { useAuthStore } from '@/app/auth-store'
import { Button, Empty, ErrorBox, Skeleton, Switch } from '@/components/ui'
import { Icon, type IconName } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { fmtNum } from '@/lib/utils'
import { EmailPreview, ThemePicker, useThemeName } from './EmailThemes'

// Each e-mail event is a BOOL setting `mail.event.<template>` (GET/PUT /admin/settings), saved with the bar at the
// bottom. Each e-mail's look (theme) comes from GET /admin/mail/templates and is saved at once on click.
// Only the templates listed here are shown, and only when the server knows them.
const PREFIX = 'mail.event.'
type GroupId = 'user' | 'you' | 'support'
const GROUPS: { id: GroupId; icon: IconName; templates: string[] }[] = [
  { id: 'user', icon: 'user', templates: ['magic-link', 'new-sign-in', 'welcome', 'dream-received', 'interpreter-question', 'reply-reminder', 'interpretation-ready', 'testimonial-request', 'testimonial-approved', 'payment-receipt', 'payment-failed', 'credits-adjusted', 'dream-cancelled', 'youtube-new-video', 'account-deleted'] },
  { id: 'you', icon: 'bell', templates: ['dream-submitted', 'user-replied', 'new-user', 'testimonial-received', 'payment-suspicious', 'interpreter-digest'] },
  { id: 'support', icon: 'chat', templates: ['support-auto-reply'] },
]
const GROUP_TEXT: Record<GroupId, { title: string; lead: string }> = {
  user: { title: 'admin.emails.toUser', lead: 'admin.emails.toUserLead' },
  you: { title: 'admin.emails.toYou', lead: 'admin.emails.toYouLead' },
  support: { title: 'admin.emails.toSupport', lead: 'admin.emails.toSupportLead' },
}
const TEMPLATES_KEY = ['admin', 'mail', 'templates'] as const
const THEMES_KEY = ['admin', 'mail', 'themes'] as const

export function EmailSettingsPage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const themeName = useThemeName()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['admin', 'settings'], queryFn: adminApi.settings })
  const themesQ = useQuery({ queryKey: THEMES_KEY, queryFn: adminApi.mailThemes })
  const templatesQ = useQuery({ queryKey: TEMPLATES_KEY, queryFn: adminApi.mailTemplates })
  const [draft, setDraft] = useState<Record<string, boolean>>({})
  const [preview, setPreview] = useState<MailTemplateRow | null>(null)
  const [savedTheme, setSavedTheme] = useState<string | null>(null) // template key, or '*' for the default
  const closePreview = useCallback(() => setPreview(null), [])

  useEffect(() => {
    if (!savedTheme) return
    const id = window.setTimeout(() => setSavedTheme(null), 2200)
    return () => window.clearTimeout(id)
  }, [savedTheme])

  const server = useMemo(() => {
    const out: Record<string, boolean> = {}
    for (const [k, v] of Object.entries(q.data ?? {})) if (k.startsWith(PREFIX)) out[k] = String(v).trim().toLowerCase() === 'true'
    return out
  }, [q.data])
  const rows = useMemo(() => new Map((templatesQ.data ?? []).map((r) => [r.template, r])), [templatesQ.data])
  const themes = themesQ.data ?? []
  const defaultTheme = themes.find((th) => th.usedByDefault)

  const changes: Record<string, string> = {}
  for (const [k, v] of Object.entries(draft)) if (k in server && server[k] !== v) changes[k] = String(v)
  const changed = Object.keys(changes).length

  const save = useMutation({
    mutationFn: () => adminApi.saveSettings(changes),
    onSuccess: (all) => {
      qc.setQueryData(['admin', 'settings'], all)
      setDraft({})
      qc.invalidateQueries({ queryKey: TEMPLATES_KEY })
    },
  })

  // per-template theme: optimistic, rolled back on error
  const setTheme = useMutation({
    mutationFn: ({ template, theme }: { template: string; theme: string }) => adminApi.setMailTemplateTheme(template, theme),
    onMutate: async ({ template, theme }) => {
      await qc.cancelQueries({ queryKey: TEMPLATES_KEY })
      const before = qc.getQueryData<MailTemplateRow[]>(TEMPLATES_KEY)
      qc.setQueryData<MailTemplateRow[]>(TEMPLATES_KEY, (list) => list?.map((r) => (r.template === template
        ? { ...r, theme: theme || defaultTheme?.key || r.theme, inherited: !theme }
        : r)))
      return { before }
    },
    onError: (_e, _v, ctx) => { if (ctx?.before) qc.setQueryData(TEMPLATES_KEY, ctx.before) },
    onSuccess: (row) => {
      qc.setQueryData<MailTemplateRow[]>(TEMPLATES_KEY, (list) => list?.map((r) => (r.template === row.template ? row : r)))
      setSavedTheme(row.template)
    },
  })

  const setDefault = useMutation({
    mutationFn: (theme: string) => adminApi.setMailDefaultTheme(theme),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: THEMES_KEY })
      qc.invalidateQueries({ queryKey: TEMPLATES_KEY })
      setSavedTheme('*')
    },
  })

  const shown = (tpl: string) => rows.has(tpl) || `${PREFIX}${tpl}` in server
  const groups = GROUPS.map((g) => ({ ...g, templates: g.templates.filter(shown) })).filter((g) => g.templates.length)
  const isOn = (key: string) => draft[key] ?? server[key]
  const loading = q.isLoading || templatesQ.isLoading

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

      {/* the look of every e-mail: the default theme */}
      <section aria-labelledby="emails-look" className="card mb-5 p-5">
        <div className="mb-4 flex items-start gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink"><Icon name="moon" size={18} /></span>
          <div className="min-w-0 flex-1">
            <h2 id="emails-look" className="font-medium">{t('admin.emails.themes.title')}</h2>
            <p className="text-xs leading-relaxed text-fg-muted">{t('admin.emails.themes.lead')}</p>
          </div>
          {savedTheme === '*' && <span role="status" className="shrink-0 text-xs text-ok-ink">✓ {t('admin.emails.themes.saved')}</span>}
        </div>
        {themesQ.isLoading ? (
          <Skeleton className="h-28" />
        ) : themesQ.isError ? (
          <ErrorBox onRetry={() => themesQ.refetch()} />
        ) : (
          <>
            <div className="mb-2 text-xs font-medium text-fg-muted">{t('admin.emails.themes.defaultLabel')}</div>
            <ThemePicker size="lg" themes={themes} value={defaultTheme?.key ?? ''} disabled={setDefault.isPending}
              label={t('admin.emails.themes.defaultLabel')} onPick={(key) => setDefault.mutate(key)} />
            <p className="mt-2 text-xs text-fg-dim">{t('admin.emails.themes.defaultHint')}</p>
            {setDefault.isError && <p role="alert" className="mt-2 text-xs text-bad-ink">{t('common.error')}</p>}
          </>
        )}
      </section>

      {loading ? (
        <div className="space-y-4"><Skeleton className="h-96" /><Skeleton className="h-64" /></div>
      ) : q.isError && templatesQ.isError ? (
        <ErrorBox onRetry={() => { q.refetch(); templatesQ.refetch() }} />
      ) : !groups.length ? (
        <Empty text={t('admin.emails.empty')} />
      ) : (
        <StaggerGroup className="space-y-5" stagger={0.1}>
          {groups.map((g) => {
            const switchable = g.templates.filter((tpl) => `${PREFIX}${tpl}` in server)
            const on = switchable.filter((tpl) => isOn(`${PREFIX}${tpl}`)).length
            return (
              <section key={g.id} aria-labelledby={`emails-${g.id}`} className="card overflow-hidden p-0">
                <header className="flex items-center gap-3 border-b border-line px-5 py-4">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-ink"><Icon name={g.icon} size={18} /></span>
                  <div className="min-w-0 flex-1">
                    <h2 id={`emails-${g.id}`} className="font-medium">{t(GROUP_TEXT[g.id].title)}</h2>
                    <p className="text-xs text-fg-muted">{t(GROUP_TEXT[g.id].lead)}</p>
                  </div>
                  {switchable.length > 0 && (
                    <span className="shrink-0 text-xs text-fg-dim">{t('admin.emails.onCount', { on: fmtNum(on, locale), all: fmtNum(switchable.length, locale) })}</span>
                  )}
                </header>
                <ul>
                  {g.templates.map((tpl) => {
                    const key = `${PREFIX}${tpl}`
                    const dirty = key in changes
                    const row = rows.get(tpl)
                    const label = t(`admin.emails.events.${tpl}.label`)
                    const current = themes.find((th) => th.key === row?.theme)
                    return (
                      <li key={tpl} className="border-b border-line px-5 py-4 last:border-0">
                        <div className="flex items-start justify-between gap-4">
                          <div className="min-w-0">
                            <div className="flex items-center gap-2 font-medium">
                              {label}
                              {dirty && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-gold" title={t('admin.emails.changed')} />}
                            </div>
                            <p className="mt-0.5 text-sm font-light leading-relaxed text-fg-muted">{t(`admin.emails.events.${tpl}.desc`)}</p>
                          </div>
                          {key in server ? (
                            <Switch checked={isOn(key)} label={label} onChange={(v) => { save.reset(); setDraft((d) => ({ ...d, [key]: v })) }} />
                          ) : row && !row.switchable ? (
                            <span className="chip shrink-0 bg-surface-2 text-xs text-fg-muted">{t('admin.emails.alwaysOn')}</span>
                          ) : null}
                        </div>
                        {row && themes.length > 0 && (
                          <div className="mt-3 rounded-xl bg-surface-2/60 p-3">
                            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                              <div className="min-w-0 text-xs text-fg-muted">
                                <span>{t('admin.emails.themes.current')}</span>{' '}
                                <span className="font-medium text-fg">{themeName(current)}</span>
                                {row.inherited && <span className="ms-1 text-fg-dim">· {t('admin.emails.themes.followsDefault')}</span>}
                                {savedTheme === tpl && <span role="status" className="ms-2 text-ok-ink">✓ {t('admin.emails.themes.saved')}</span>}
                                {setTheme.isError && setTheme.variables?.template === tpl && <span role="alert" className="ms-2 text-bad-ink">{t('common.error')}</span>}
                              </div>
                              <div className="flex gap-2">
                                {!row.inherited && (
                                  <Button size="sm" variant="link" disabled={setTheme.isPending} onClick={() => setTheme.mutate({ template: tpl, theme: '' })}>
                                    {t('admin.emails.themes.useDefault')}
                                  </Button>
                                )}
                                <Button size="sm" variant="ghost" onClick={() => setPreview(row)} aria-label={t('admin.emails.preview.open', { name: label })}>
                                  <Icon name="eye" size={16} /> {t('admin.emails.preview.button')}
                                </Button>
                              </div>
                            </div>
                            <ThemePicker themes={themes} value={row.theme} inherited={row.inherited} defaultKey={defaultTheme?.key}
                              label={t('admin.emails.themes.pick', { name: label })}
                              onPick={(theme) => setTheme.mutate({ template: tpl, theme })} />
                          </div>
                        )}
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

      {preview && <EmailPreview row={preview} themes={themes} onClose={closePreview} />}
    </PageEnter>
  )
}
