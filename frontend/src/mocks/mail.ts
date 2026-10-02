// Mock e-mail look (GET/PUT /admin/mail/*). Mirrors the backend: mail.theme.<template> → mail.theme.default →
// registry default; the preview is a small stand-in for the real Thymeleaf e-mail.
import type * as T from '@/api/types'
import { db, MAIL_TEMPLATES, MAIL_THEMES } from './data'

const REGISTRY_DEFAULT = 'crescent-night'
const known = (key: string | undefined | null) => !!key && MAIL_THEMES.some((t) => t.key === key)
const imageUrl = (key: string, part: 'header' | 'footer') => `/email/themes/${key}/${part}.jpg`

export const isMailTemplate = (template: string) => (MAIL_TEMPLATES as readonly string[]).includes(template)
export const isMailTheme = known

export function defaultThemeKey(): string {
  const d = db.settings['mail.theme.default']
  return known(d) ? d : REGISTRY_DEFAULT
}

export function mailThemes(): T.MailThemeDto[] {
  const d = defaultThemeKey()
  return MAIL_THEMES.map((t) => ({ ...t, headerImageUrl: imageUrl(t.key, 'header'), footerImageUrl: imageUrl(t.key, 'footer'), usedByDefault: t.key === d }))
}

export function mailTemplateRow(template: string): T.MailTemplateRow {
  const own = db.settings[`mail.theme.${template}`]
  const switchable = template !== 'magic-link'
  return {
    template,
    theme: known(own) ? own : defaultThemeKey(),
    inherited: !known(own),
    enabled: switchable ? db.settings[`mail.event.${template}`] !== 'false' : true,
    switchable,
  }
}

export const mailTemplateRows = () => MAIL_TEMPLATES.map(mailTemplateRow)

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)

/** A simplified preview of the themed layout (the live API renders the real template with sample data). */
export function mailPreviewHtml(template: string, themeKey: string | null, locale: T.Locale): string {
  const key = known(themeKey) ? (themeKey as string) : mailTemplateRow(template).theme
  const t = MAIL_THEMES.find((x) => x.key === key) ?? MAIL_THEMES[0]
  const en = locale === 'en'
  const dir = en ? 'ltr' : 'rtl'
  const font = en ? "'IBM Plex Sans', 'IBM Plex Sans Arabic', Arial, sans-serif" : "'IBM Plex Sans Arabic', 'IBM Plex Sans', Tahoma, Arial, sans-serif"
  const text = en
    ? { brand: 'Saadat Al-Darain', tagline: 'Dream interpretation with clarity and depth', hello: 'Assalamu alaikum Ahmed,', body: `This is a preview of the “${template}” e-mail with sample data. The live site shows the real text.`, cta: 'Open', yt: 'Watch our latest videos on our YouTube channel', site: 'Please visit our website' }
    : { brand: 'إلى سعادة الدارين', tagline: 'تفسير الرؤى بوضوح وعمق نحو سعادة الدارين', hello: 'السلام عليكم أحمد،', body: `هذه معاينة لرسالة «${template}» ببيانات تجريبية. يعرض الموقع الحقيقي النص الكامل.`, cta: 'فتح', yt: 'لمتابعة أحدث الفيديوهات، تفضّل بزيارة قناتنا على يوتيوب', site: 'يُرجى زيارة موقعنا' }
  const bg = (part: 'header' | 'footer') => `background-color:${t.pageBg};background-image:url(${imageUrl(t.key, part)});background-size:cover;background-position:center;`
  return `<!DOCTYPE html><html lang="${locale}" dir="${dir}"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+Arabic:wght@400;600;700&family=IBM+Plex+Sans:wght@400;600&display=swap" rel="stylesheet"></head>
<body style="margin:0;padding:24px 12px;background:${t.pageBg};font-family:${font};">
<table role="presentation" width="100%" style="max-width:600px;margin:0 auto;border-collapse:separate;border-radius:18px;overflow:hidden;background:${t.cardBg};">
<tr><td align="center" style="height:180px;padding:0 24px;${bg('header')}"><div style="font-size:28px;font-weight:700;color:${t.accent};">${esc(text.brand)}</div><div style="margin-top:8px;font-size:14px;color:#F4EFE6;">${esc(text.tagline)}</div></td></tr>
<tr><td style="padding:32px 28px;color:#0A1128;font-size:16px;line-height:1.9;"><p style="margin:0 0 16px;font-weight:600;font-size:18px;">${esc(text.hello)}</p><p style="margin:0 0 24px;">${esc(text.body)}</p>
<p style="text-align:center;margin:0;"><a href="#" style="display:inline-block;padding:14px 38px;border-radius:999px;background:${t.accent};color:#0A1128;font-weight:600;text-decoration:none;">${esc(text.cta)}</a></p></td></tr>
<tr><td align="center" style="height:150px;padding:24px;color:#F4EFE6;font-size:13px;line-height:1.8;${bg('footer')}"><div><a href="#" style="color:${t.accent};font-weight:600;text-decoration:none;">${esc(text.yt)}</a></div><div>${esc(text.site)} <a href="#" dir="ltr" style="color:${t.accent};text-decoration:none;">saadatu-aldarein.com</a></div><div style="opacity:.8;font-size:12px;">© 2026 ${esc(text.brand)}</div></td></tr>
</table></body></html>`
}
