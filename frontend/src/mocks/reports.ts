// Mock report files for the preview: a tiny valid one-page PDF and the dreams export as CSV (UTF-8 + BOM).
// The real server renders branded PDFs (Arabic shaping, RTL) and a real .xlsx.
import type * as T from '@/api/types'
import { db } from './data'

/** One A4 page, Helvetica, ASCII lines only; xref offsets are computed so strict readers accept it. */
export function tinyPdf(lines: string[]): Uint8Array {
  const esc = (s: string) => s.replace(/[^\x20-\x7e]/g, '?').replace(/([()\\])/g, '\\$1')
  const text = lines.map((l, i) => `BT /F1 ${i === 0 ? 18 : 11} Tf 56 ${780 - i * 26} Td (${esc(l)}) Tj ET`).join('\n')
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${text.length} >>\nstream\n${text}\nendstream`,
  ]
  let out = '%PDF-1.4\n'
  const offsets = objects.map((o, i) => {
    const at = out.length
    out += `${i + 1} 0 obj\n${o}\nendobj\n`
    return at
  })
  const xref = out.length
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((n) => `${String(n).padStart(10, '0')} 00000 n \n`).join('')}`
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return new TextEncoder().encode(out)
}

/** attachment header with an ASCII fallback name and the RFC 5987 UTF-8 name. */
export function attachment(name: string, asciiFallback = name.replace(/[^\x20-\x7e]/g, '_')) {
  return `attachment; filename="${asciiFallback.replace(/"/g, '')}"; filename*=UTF-8''${encodeURIComponent(name)}`
}

type MockDream = (typeof db.dreams)[number]

const HEAD = {
  en: ['Dream ID', 'Submitted at', 'Status', 'Expected by', 'Interpreted at', 'User name', 'Email', 'Gender', 'Age', 'Country', 'Dream text', 'Interpretation', 'Messages'],
  ar: ['رقم الرؤيا', 'تاريخ التقديم', 'الحالة', 'الموعد المتوقع', 'تاريخ التفسير', 'اسم المستخدم', 'البريد', 'الجنس', 'العمر', 'البلد', 'نص الرؤيا', 'التفسير', 'عدد الرسائل'],
}

/** GET /admin/dreams/export as CSV: non-draft dreams matching status / from / to / country / gender / q. */
export function dreamsCsv(q: URLSearchParams, locale: string) {
  const status = q.get('status'), from = q.get('from'), to = q.get('to'), country = q.get('country'), gender = q.get('gender') as T.Gender | null
  const text = (q.get('q') ?? '').trim().toLowerCase()
  const rows = db.dreams.filter((d: MockDream) => {
    if (d.status === 'DRAFT' || !d.submittedAt) return false
    const u = db.users.find((x) => x.id === d.userId)
    const day = d.submittedAt.slice(0, 10)
    return (!status || d.status === status) && (!from || day >= from) && (!to || day <= to) && (!country || u?.countryCode === country)
      && (!gender || d.gender === gender) && (!text || [u?.name, u?.email, d.text].some((s) => s?.toLowerCase().includes(text)))
  })
  const cell = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`
  const lines = rows.map((d) => {
    const u = db.users.find((x) => x.id === d.userId)
    return [d.id, d.submittedAt, d.status, d.expectedBy, d.interpretedAt, u?.name, u?.email, d.gender, u?.age, u?.countryCode, d.text, d.interpretation?.text, d.messages.length].map(cell).join(',')
  })
  return `﻿${[HEAD[locale.startsWith('en') ? 'en' : 'ar'].map(cell).join(','), ...lines].join('\r\n')}\r\n`
}
