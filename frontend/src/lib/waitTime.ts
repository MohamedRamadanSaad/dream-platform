import type { TFunction } from 'i18next'

/** The one place that turns the interpreter's reply-time setting into words (home page, user dashboard, admin preview). */
export function replyTimeText(t: TFunction, w: { busy: boolean; hours: number; minDays: number; maxDays: number }) {
  if (!w.busy) return t('waitTime.within', { v: t('common.hourCount', { count: w.hours }) })
  if (w.minDays >= w.maxDays) return t('waitTime.within', { v: t('common.dayCount', { count: w.maxDays }) })
  return t('waitTime.between', { min: w.minDays, max: t('common.dayCount', { count: w.maxDays }) })
}
