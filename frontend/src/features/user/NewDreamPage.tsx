import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { dreamsApi } from '@/api/endpoints'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/app/auth-store'
import { Button, Label, Skeleton, Textarea } from '@/components/ui'
import { toast } from '@/components/ui/Toaster'
import { PageEnter } from '@/components/motion'
import { arrowBack, arrowNext } from '@/lib/utils'
import type { Gender } from '@/api/types'

const isNotEditable = (e: unknown) => e instanceof ApiError && e.status === 409 && (e.problem.code === 'NOT_EDITABLE' || e.problem.code === 'NOT_DRAFT')

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
  // a submitted dream (or one waiting on a payment) only gets its text saved: no autosave, no submit / checkout step
  const textOnly = !!existing.data && (existing.data.status !== 'DRAFT' || !!existing.data.payment)
  const locked = !!existing.data && !existing.data.editable

  /** The interpreter started meanwhile: say so and go back to the dream. */
  const leaveLocked = () => {
    toast.error(t('me.newDream.notEditable'))
    qc.invalidateQueries({ queryKey: ['dream', id] })
    navigate(`/me/dreams/${id}`, { replace: true })
  }
  useEffect(() => { if (locked) leaveLocked() }, [locked]) // eslint-disable-line react-hooks/exhaustive-deps

  const save = useMutation({
    mutationFn: (b: { text: string; gender: Gender }) => (draftId ? dreamsApi.update(draftId, b) : dreamsApi.createDraft(b)),
    meta: { toast: false },
    onSuccess: (d) => {
      setDraftId(d.id); setSavedAt(Date.now())
      qc.setQueryData(['dream', d.id], d)
      qc.invalidateQueries({ queryKey: ['dreams'] }); qc.invalidateQueries({ queryKey: ['me'] })
    },
    onError: (e) => { if (isNotEditable(e)) leaveLocked(); else toast.error(t('common.saveFailed')) },
  })

  // autosave drafts 5s after typing stops (only once the text is long enough to be valid)
  useEffect(() => {
    if (textOnly || text.trim().length < 20 || (id && !existing.data)) return
    if (timer.current) window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => save.mutate({ text, gender }), 5000)
    return () => { if (timer.current) window.clearTimeout(timer.current) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, gender, textOnly])

  const valid = text.trim().length >= 20
  const saveAndGo = async () => { const d = await save.mutateAsync({ text, gender }); navigate(`/me?submit=${d.id}`) }
  const saveAndSubmit = async () => {
    const d = await save.mutateAsync({ text, gender })
    try { await dreamsApi.submit({ dreamIds: [d.id] }); qc.invalidateQueries(); navigate(`/me/dreams/${d.id}`) }
    catch { navigate(`/me/packages?dreams=${d.id}`) }
  }
  const saveChanges = async () => {
    try {
      const d = await save.mutateAsync({ text, gender })
      toast.success(t('me.newDream.changesSaved'))
      navigate(`/me/dreams/${d.id}`)
    } catch { /* handled in onError */ }
  }

  if (id && existing.isLoading) return <div className="mx-auto max-w-3xl space-y-4"><Skeleton className="h-10 w-56" /><Skeleton className="h-96" /></div>

  return (
    <PageEnter className="mx-auto max-w-3xl">
      {textOnly && <Link to={`/me/dreams/${id}`} className="mb-3 inline-block text-sm text-fg-muted hover:text-fg">{arrowBack(locale)} {t('common.back')}</Link>}
      <h1 className="font-display text-4xl">{t(textOnly ? 'me.newDream.editTitle' : 'me.newDream.title')}</h1>
      <p className="mb-6 text-sm font-light text-fg-muted">{t(textOnly ? 'me.newDream.editLead' : 'me.newDream.lead')}</p>
      <div className="card p-6">
        <Label>{t('auth.gender')}</Label>
        <div className="mb-5 grid grid-cols-2 gap-2">
          {(['FEMALE', 'MALE'] as Gender[]).map((g) => <button key={g} type="button" aria-pressed={gender === g} onClick={() => setGender(g)} className={`rounded-xl border px-4 py-2.5 text-sm transition-colors ${gender === g ? 'border-gold bg-gold/10 text-gold-ink' : 'border-line text-fg-muted'}`}>{t(g === 'FEMALE' ? 'auth.female' : 'auth.male')}</button>)}
        </div>
        <Label>{t('me.detail.dream')}</Label>
        <Textarea rows={12} value={text} onChange={(e) => setText(e.target.value)} placeholder={t('me.newDream.placeholder')} className="text-base" />
        <div className="mt-2 flex items-center justify-between text-xs text-fg-dim">
          <span>{t('me.newDream.chars', { n: text.length })}</span>
          {!textOnly && <span>{save.isPending ? t('common.loading') : savedAt ? `✓ ${t('me.newDream.saved')}` : t('me.newDream.autosave')}</span>}
        </div>
        <div className="mt-6 flex flex-wrap justify-end gap-3">
          {textOnly ? (
            <>
              <Link to={`/me/dreams/${id}`} className="btn btn-md btn-ghost">{t('common.cancel')}</Link>
              <Button disabled={!valid || text === existing.data?.text && gender === existing.data?.gender} loading={save.isPending} onClick={saveChanges}>{t('me.newDream.saveChanges')}</Button>
            </>
          ) : (
            <>
              <Button variant="ghost" disabled={!valid} loading={save.isPending} onClick={saveAndGo}>{t('me.newDream.saveDraft')}</Button>
              <Button disabled={!valid} onClick={saveAndSubmit}>{t('me.newDream.saveAndSubmit')} {arrowNext(locale)}</Button>
            </>
          )}
        </div>
      </div>
    </PageEnter>
  )
}
