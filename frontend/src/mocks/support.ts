// Mock support mailbox tickets (/admin/support/tickets). Mirrors the backend: the body of the incoming e-mail is never
// stored, closed is final, an in-progress ticket can get more updates, each action "e-mails" the sender (LOGGED here).
import type * as T from '@/api/types'

const MINUTE = 60_000
const ago = (minutes: number) => new Date(Date.now() - minutes * MINUTE).toISOString()
const MESSAGE_MAX = 2000

interface MockTicket extends Omit<T.SupportTicket, 'lastMessage' | 'lastMessageAt' | 'eventsCount'> { events: T.SupportTicketEvent[] }

let seq = 1000
let eventSeq = 0
const ev = (action: T.SupportTicketAction, message: string, minutesAgo: number): T.SupportTicketEvent =>
  ({ id: `ste-${++eventSeq}`, action, message, createdAt: ago(minutesAgo), actorName: 'فاطمة', emailStatus: 'SENT' })
const ticket = (from: [string, string | null], subject: string | null, minutesAgo: number, status: T.SupportTicketStatus = 'NEW', events: T.SupportTicketEvent[] = []): MockTicket => {
  const last = events[events.length - 1]
  return {
    id: `st-${++seq}`, number: seq, fromEmail: from[0], fromName: from[1], subject, receivedAt: ago(minutesAgo), status,
    updatedAt: last?.createdAt ?? ago(minutesAgo), closedAt: status === 'CLOSED' ? (last?.createdAt ?? ago(minutesAgo)) : null, events,
  }
}

const tickets: MockTicket[] = [
  ticket(['sara.alqahtani@gmail.com', 'سارة القحطاني'], 'لم تصلني رسالة التفسير', 4200, 'CLOSED', [
    ev('IN_PROGRESS', 'وعليكم السلام، نراجع حالة رؤياك الآن.', 4100),
    ev('CLOSED', 'أرسلنا التفسير إلى بريدك مرة أخرى، وهو أيضًا في حسابك.\nجزاكم الله خيرًا.', 3900),
  ]),
  ticket(['john.miller@outlook.com', 'John Miller'], 'Payment went through twice', 2900, 'CLOSED', [
    ev('CLOSED', 'We refunded the second payment. It can take 5 to 10 days to show on your card.', 2700),
  ]),
  ticket(['m.hassan@yahoo.com', 'محمد حسن'], 'استفسار عن الباقات', 1500, 'IN_PROGRESS', [
    ev('IN_PROGRESS', 'شكرًا لسؤالك، سنرسل لك تفاصيل الباقات المناسبة قريبًا.', 1400),
  ]),
  ticket(['amina.k@hotmail.com', 'Amina K.'], 'Cannot sign in with the code', 600, 'IN_PROGRESS', [
    ev('IN_PROGRESS', 'We are checking your account now.', 560),
    ev('IN_PROGRESS', 'Please try again and check the spam folder for the code.', 300),
  ]),
  ticket(['noura.s@gmail.com', 'نورة السبيعي'], 'هل يمكن تعديل نص الرؤيا بعد الإرسال؟', 95),
  ticket(['omar.f@icloud.com', null], 'Question about my dream', 40),
  ticket(['khaled.2026@gmail.com', 'خالد'], null, 12),
  ticket(['fatima.zahra@gmail.com', 'Fatima Zahra'], 'Gift a package to my mother', 3),
]

function toRow(t: MockTicket): T.SupportTicket {
  const { events, ...rest } = t
  const last = events[events.length - 1]
  return { ...rest, lastMessage: last?.message ?? null, lastMessageAt: last?.createdAt ?? null, eventsCount: events.length }
}

export function supportList(status: T.SupportTicketStatus | null): T.SupportTicket[] {
  return tickets.filter((t) => !status || t.status === status)
    .sort((a, b) => b.receivedAt.localeCompare(a.receivedAt) || b.number - a.number)
    .map(toRow)
}

export function supportCounts(): T.SupportTicketCounts {
  const n = (s: T.SupportTicketStatus) => tickets.filter((t) => t.status === s).length
  return { new: n('NEW'), inProgress: n('IN_PROGRESS'), closed: n('CLOSED') }
}

export function supportDetail(id: string): T.SupportTicketDetail | null {
  const t = tickets.find((x) => x.id === id)
  return t ? { ...toRow(t), events: [...t.events] } : null
}

export type SupportActionResult =
  | { ok: true; ticket: T.SupportTicketDetail }
  | { ok: false; status: 400 | 404 | 409; code?: string; detail: string }

export function supportAction(id: string, action: T.SupportTicketAction, rawMessage: unknown, actorName: string): SupportActionResult {
  const message = String(rawMessage ?? '').replace(/\r\n?/g, '\n').trim()
  if (!message) return { ok: false, status: 400, code: 'MESSAGE_REQUIRED', detail: 'The message is required' }
  if (message.length > MESSAGE_MAX) return { ok: false, status: 400, code: 'MESSAGE_TOO_LONG', detail: `The message must be at most ${MESSAGE_MAX} characters` }
  const t = tickets.find((x) => x.id === id)
  if (!t) return { ok: false, status: 404, detail: `Support ticket not found: ${id}` }
  if (t.status === 'CLOSED') return { ok: false, status: 409, code: 'TICKET_CLOSED', detail: 'The ticket is already closed' }
  const now = new Date().toISOString()
  t.events.push({ id: `ste-${++eventSeq}`, action, message, createdAt: now, actorName, emailStatus: 'LOGGED' })
  t.status = action === 'CLOSED' ? 'CLOSED' : 'IN_PROGRESS'
  t.updatedAt = now
  if (action === 'CLOSED') t.closedAt = now
  return { ok: true, ticket: supportDetail(id) as T.SupportTicketDetail }
}
