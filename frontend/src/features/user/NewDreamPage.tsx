import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { dreamsApi } from '@/api/endpoints'
import { useAuthStore } from '@/app/auth-store'
import { Button, Label, Textarea } from '@/components/ui'
import { PageEnter } from '@/components/motion'
import { arrowNext } from '@/lib/utils'
import type { Gender } from '@/api/types'

export function NewDreamPage() {
  const { t } = useTranslation()
  const { id } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const user = useAuthStore((s) => s.user)
  const locale = useAuthStore((s) => s.locale)
  const [text, setText] = useState('')
  const [gender, setGender] = useState<Gender>(user?.gender ?? 'FEMALE')
  const [draftId, setDraftId] = useState<string | undefined>(id)
  const [savedAt, setSavedAt] = useState<number | null>(null)
  const timer = useRef<number | null>(null)

  const existing = useQuery({ queryKey: ['dream', id], queryFn: () => dreamsApi.get(id!), enabled: !!id })
  useEffect(() => { if (existing.data) { setText(existing.data.text); setGender(existing.data.gender) } }, [existing.data])

  const save = useMutation({
    mutationFn: (b: { text: string; gender: Gender }) => (draftId ? dreamsApi.updateDraft(draftId, b) : dreamsApi.createDraft(b)),
    onSuccess: (d) => { setDraftId(d.id); setSavedAt(Date.now()); qc.invalidateQueries({ queryKey: ['dreams'] }); qc.invalidateQueries({ queryKey: ['me'] }) },
  })

  // autosave 5s after typing stops (only once the text is long enough to be valid)
  useEffect(() => {
    if (text.trim().length < 20) return
    if (timer.current) window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => save.mutate({ text, gender }), 5000)
    return () => { if (timer.current) window.clearTimeout(timer.current) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, gender])

  const valid = text.trim().length >= 20
  const saveAndGo = async () => { const d = await save.mutateAsync({ text, gender }); navigate(`/me?submit=${d.id}`) }
  const saveAndSubmit = async () => {
    const d = await save.mutateAsync({ text, gender })
    try { await dreamsApi.submit({ dreamIds: [d.id] }); qc.invalidateQueries(); navigate(`/me/dreams/${d.id}`) }
    catch { navigate(`/me/packages?dreams=${d.id}`) }
  }

  return (
    <PageEnter className="mx-auto max-w-3xl">
      <h1 className="font-display text-4xl">{t('me.newDream.title')}</h1>
      <p className="mb-6 text-sm font-light text-fg-muted">{t('me.newDream.lead')}</p>
      <div className="card p-6">
        <Label>{t('auth.gender')}</Label>
        <div className="mb-5 grid grid-cols-2 gap-2">
          {(['FEMALE', 'MALE'] as Gender[]).map((g) => <button key={g} type="button" onClick={() => setGender(g)} className={`rounded-xl border px-4 py-2.5 text-sm transition-colors ${gender === g ? 'border-gold bg-gold/10 text-gold-ink' : 'border-line text-fg-muted'}`}>{t(g === 'FEMALE' ? 'auth.female' : 'auth.male')}</button>)}
        </div>
        <Label>{t('me.detail.dream')}</Label>
        <Textarea rows={12} value={text} onChange={(e) => setText(e.target.value)} placeholder={t('me.newDream.placeholder')} className="text-base" />
        <div className="mt-2 flex items-center justify-between text-xs text-fg-dim">
          <span>{t('me.newDream.chars', { n: text.length })}</span>
          <span>{save.isPending ? t('common.loading') : savedAt ? `✓ ${t('me.newDream.saved')}` : t('me.newDream.autosave')}</span>
        </div>
        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <Button variant="ghost" disabled={!valid} loading={save.isPending} onClick={saveAndGo}>{t('me.newDream.saveDraft')}</Button>
          <Button disabled={!valid} onClick={saveAndSubmit}>{t('me.newDream.saveAndSubmit')} {arrowNext(locale)}</Button>
        </div>
      </div>
    </PageEnter>
  )
}
