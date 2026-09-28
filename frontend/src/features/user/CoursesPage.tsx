import { useTranslation } from 'react-i18next'
import { PublicHeader, Footer } from '@/components/layout'
import { Icon } from '@/components/icons/Icon'
import { PageEnter } from '@/components/motion'

const courses = [
  { id: 'c1', title: 'courses.c1', lessons: 12, progress: 0, soon: true },
  { id: 'c2', title: 'courses.c2', lessons: 8, progress: 0, soon: true },
]

export function CoursesPage({ publicView }: { publicView?: boolean }) {
  const { t } = useTranslation()
  const body = (
    <PageEnter className={publicView ? 'mx-auto max-w-5xl px-5 py-16' : ''}>
      <h1 className="font-display text-4xl">{t('nav.courses')}</h1>
      <p className="mb-8 text-sm font-light text-fg-muted">{t('courses.lead')}</p>
      <div className="grid gap-5 md:grid-cols-2">
        {courses.map((c) => (
          <div key={c.id} className="card card-hover p-6">
            <div className="mb-3 flex items-center justify-between"><span className="text-fg"><Icon name="play" size={28} strokeWidth={1.2} /></span>{c.soon && <span className="chip bg-gold/10 text-gold-deep">{t('courses.soon')}</span>}</div>
            <div className="font-display text-2xl">{t(c.title)}</div>
            <div className="text-xs text-fg-dim">{t('courses.lessons', { count: c.lessons })}</div>
            <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-surface-2"><div className="h-full bg-gold" style={{ width: `${c.progress}%` }} /></div>
          </div>
        ))}
      </div>
    </PageEnter>
  )
  if (!publicView) return body
  return <div><div className="bg-night"><PublicHeader /></div>{body}<Footer /></div>
}
