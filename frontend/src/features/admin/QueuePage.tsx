import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { adminApi, reportsApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Empty, ErrorBox, Input, Modal, Skeleton, StatusBadge, Tabs } from '@/components/ui'
import { DownloadButton } from '@/components/ui/DownloadButton'
import { Icon } from '@/components/icons/Icon'
import { PageEnter, StaggerGroup } from '@/components/motion'
import { cn, flagEmoji, fmtDate, hoursLeft, toISODay } from '@/lib/utils'
import type { DreamStatus, Gender } from '@/api/types'

type Tab = 'IN_REVIEW' | 'AWAITING_USER_REPLY' | 'INTERPRETED' | 'CANCELLED'

interface ExportFilters { status: '' | Tab; from: string; to: string; country: string; gender: '' | Gender; q: string }
const NO_FILTERS: ExportFilters = { status: '', from: '', to: '', country: '', gender: '', q: '' }
const STATUS_LABEL: Record<Tab, string> = { IN_REVIEW: 'admin.status.IN_REVIEW', AWAITING_USER_REPLY: 'admin.status.AWAITING_USER_REPLY', INTERPRETED: 'admin.status.INTERPRETED', CANCELLED: 'admin.status.CANCELLED' }

/** "Export to Excel": choose filters, then download GET /admin/dreams/export (one row per dream). */
function ExportDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [f, setF] = useState<ExportFilters>(NO_FILTERS)
  const countries = useQuery({ queryKey: ['admin', 'countries-list'], queryFn: adminApi.countries_list, staleTime: 10 * 60_000, enabled: open })
  const set = (patch: Partial<ExportFilters>) => setF((x) => ({ ...x, ...patch }))
  const badRange = !!f.from && !!f.to && f.from > f.to
  const today = toISODay(new Date())
  const options = [...(countries.data ?? [])].sort((a, b) => (locale === 'ar' ? a.nameAr.localeCompare(b.nameAr, 'ar') : a.nameEn.localeCompare(b.nameEn, 'en')))
  return (
    <Modal open={open} onClose={onClose} title={t('reports.excelTitle')}
      footer={<div className="flex w-full flex-wrap items-center justify-between gap-3">
        <button type="button" className="py-2 text-xs text-fg-muted hover:text-fg" onClick={() => setF(NO_FILTERS)}>{t('reports.reset')}</button>
        <div className="flex items-start gap-2">
          <Button variant="ghost" onClick={onClose}>{t('common.close')}</Button>
          <DownloadButton variant="gold" size="md" disabled={badRange} label={t('reports.download')}
            run={() => reportsApi.adminDreamsExcel({ status: f.status || undefined, from: f.from || undefined, to: f.to || undefined, country: f.country || undefined, gender: f.gender || undefined, q: f.q.trim() || undefined })} />
        </div>
      </div>}>
      <p className="mb-5 text-sm font-light text-fg-muted">{t('reports.excelLead')}</p>
      <form className="grid grid-cols-2 gap-3" onSubmit={(e) => e.preventDefault()}>
        <label className="col-span-2"><span className="label">{t('reports.status')}</span>
          <select className="input" value={f.status} onChange={(e) => set({ status: e.target.value as ExportFilters['status'] })}>
            <option value="">{t('reports.anyStatus')}</option>
            {(Object.keys(STATUS_LABEL) as Tab[]).map((s) => <option key={s} value={s}>{t(STATUS_LABEL[s])}</option>)}
          </select>
        </label>
        <label className="min-w-0"><span className="label">{t('reports.from')}</span><Input type="date" dir="ltr" value={f.from} max={f.to || today} onChange={(e) => set({ from: e.target.value })} /></label>
        <label className="min-w-0"><span className="label">{t('reports.to')}</span><Input type="date" dir="ltr" value={f.to} min={f.from || undefined} max={today} onChange={(e) => set({ to: e.target.value })} /></label>
        <label className="min-w-0"><span className="label">{t('common.country')}</span>
          <select className="input" value={f.country} onChange={(e) => set({ country: e.target.value })}>
            <option value="">{t('reports.anyCountry')}</option>
            {options.map((c) => <option key={c.code} value={c.code}>{flagEmoji(c.code)} {locale === 'ar' ? c.nameAr : c.nameEn}</option>)}
          </select>
        </label>
        <label className="min-w-0"><span className="label">{t('reports.gender')}</span>
          <select className="input" value={f.gender} onChange={(e) => set({ gender: e.target.value as ExportFilters['gender'] })}>
            <option value="">{t('reports.anyGender')}</option>
            <option value="FEMALE">{t('auth.female')}</option>
            <option value="MALE">{t('auth.male')}</option>
          </select>
        </label>
        <label className="col-span-2"><span className="label">{t('reports.search')}</span><Input value={f.q} maxLength={200} placeholder={t('reports.searchPlaceholder')} onChange={(e) => set({ q: e.target.value })} /></label>
        {badRange && <p role="alert" className="col-span-2 text-xs text-bad-ink">{t('admin.traffic.badRange')}</p>}
      </form>
    </Modal>
  )
}

/** While the user owes a reply the reply-time clock is stopped, so no countdown is shown. */
export function PausedChip() {
  const { t } = useTranslation()
  return <span className="chip bg-surface-2 text-fg-muted"><span className="h-1.5 w-1.5 rounded-full bg-current" />{t('admin.dream.timerPaused')}</span>
}

export function SlaChip({ deadline, overdue }: { deadline: string; overdue: boolean }) {
  const { t } = useTranslation()
  const h = hoursLeft(deadline)
  const tone = overdue || h < 0 ? 'bg-danger/10 text-danger' : h < 12 ? 'bg-warn/10 text-warn' : 'bg-success/10 text-success'
  // full words with Arabic dual/plural ("متأخرة 4 ساعات", "خلال يومين"), never "4س"
  const span = (x: number) => (x < 48 ? t('common.hourCount', { count: Math.max(1, Math.round(x)) }) : t('common.dayCount', { count: Math.round(x / 24) }))
  const label = h < 0 ? t('admin.queue.overdueBy', { x: span(-h) }) : t('admin.queue.within', { x: span(h) })
  return <span className={cn('chip font-medium', tone)}><span className="h-1.5 w-1.5 rounded-full bg-current" />{label}</span>
}

export function AdminQueuePage() {
  const { t } = useTranslation()
  const locale = useAuthStore((s) => s.locale)
  const [params, setParams] = useSearchParams()
  const tab = (params.get('status') as Tab) || 'IN_REVIEW'
  const q = useQuery({ queryKey: ['admin', 'dreams', tab], queryFn: () => adminApi.dreams(tab as DreamStatus, 0, 50) })
  const [exportOpen, setExportOpen] = useState(false)
  return (
    <PageEnter>
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h1 className="font-display text-4xl">{t('admin.queue.title')}</h1>
        <Button variant="ghost" size="sm" onClick={() => setExportOpen(true)} aria-haspopup="dialog"><Icon name="download" size={15} />{t('reports.excel')}</Button>
      </div>
      <ExportDialog open={exportOpen} onClose={() => setExportOpen(false)} />
      <Tabs value={tab} onChange={(v) => setParams({ status: v })} items={[
        { value: 'IN_REVIEW', label: t('admin.status.IN_REVIEW') }, { value: 'AWAITING_USER_REPLY', label: t('admin.status.AWAITING_USER_REPLY') },
        { value: 'INTERPRETED', label: t('admin.status.INTERPRETED') }, { value: 'CANCELLED', label: t('admin.status.CANCELLED') },
      ]} />
      <div className="mt-6">
        {q.isLoading ? <div className="space-y-3">{[1, 2, 3].map((i) => <Skeleton key={i} className="h-24" />)}</div>
          : q.isError ? <ErrorBox onRetry={() => q.refetch()} />
          : !q.data?.items.length ? <Empty text={t('common.none')} />
          : <StaggerGroup className="space-y-3" stagger={0.05}>
            {q.data.items.map((r) => (
              <Link key={r.id} to={`/admin/dreams/${r.id}`} className={cn('card card-hover flex flex-col gap-3 p-5 md:flex-row md:items-center', r.overdue && 'border-danger/40')}>
                <div className="min-w-0 flex-1">
                  <div className="mb-1 flex flex-wrap items-center gap-2 text-sm"><span className="font-medium">{r.userName}</span><span className="text-fg-dim">· {r.countryCode} · {t(r.gender === 'FEMALE' ? 'auth.female' : 'auth.male')}</span><StatusBadge status={r.status} audience="interpreter" /></div>
                  <p className="line-clamp-2 text-sm font-light text-fg-muted">{r.excerpt}</p>
                </div>
                <div className="flex items-center gap-3 text-xs text-fg-dim md:flex-col md:items-end">
                  <span>{t('admin.queue.submitted')} {fmtDate(r.submittedAt, locale, true)}</span>
                  {r.status === 'AWAITING_USER_REPLY' ? <PausedChip /> : r.status !== 'INTERPRETED' && r.status !== 'CANCELLED' && <SlaChip deadline={r.slaDeadline} overdue={r.overdue} />}
                </div>
              </Link>
            ))}
          </StaggerGroup>}
      </div>
    </PageEnter>
  )
}
