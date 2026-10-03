// Mock support mailbox tickets (/admin/support/tickets). Mirrors the backend: `body` = the text the person wrote (null
// until it arrives; fetch-body reads it from the "mailbox" below), closed is final, an in-progress ticket can get more
// updates, each action "e-mails" the sender (LOGGED here).
import type * as T from '@/api/types'

const MINUTE = 60_000
const ago = (minutes: number) => new Date(Date.now() - minutes * MINUTE).toISOString()
const MESSAGE_MAX = 2000

interface MockTicket extends Omit<T.SupportTicket, 'lastMessage' | 'lastMessageAt' | 'eventsCount'> { events: T.SupportTicketEvent[] }

let seq = 1000
let eventSeq = 0
const ev = (action: T.SupportTicketAction, message: string, minutesAgo: number): T.SupportTicketEvent =>
  ({ id: `ste-${++eventSeq}`, action, message, createdAt: ago(minutesAgo), actorName: 'فاطمة', emailStatus: 'SENT' })
const ticket = (from: [string, string | null], subject: string | null, body: string | null, minutesAgo: number, status: T.SupportTicketStatus = 'NEW', events: T.SupportTicketEvent[] = []): MockTicket => {
  const last = events[events.length - 1]
  return {
    id: `st-${++seq}`, number: seq, fromEmail: from[0], fromName: from[1], subject, body, receivedAt: ago(minutesAgo), status,
    updatedAt: last?.createdAt ?? ago(minutesAgo), closedAt: status === 'CLOSED' ? (last?.createdAt ?? ago(minutesAgo)) : null, events,
  }
}

const tickets: MockTicket[] = [
  ticket(['sara.alqahtani@gmail.com', 'سارة القحطاني'], 'لم تصلني رسالة التفسير', 'السلام عليكم ورحمة الله،\nأرسلت رؤياي قبل أسبوع ولم تصلني رسالة التفسير على البريد.\nجزاكم الله خيرًا.', 4200, 'CLOSED', [
    ev('IN_PROGRESS', 'وعليكم السلام، نراجع حالة رؤياك الآن.', 4100),
    ev('CLOSED', 'أرسلنا التفسير إلى بريدك مرة أخرى، وهو أيضًا في حسابك.\nجزاكم الله خيرًا.', 3900),
  ]),
  ticket(['john.miller@outlook.com', 'John Miller'], 'Payment went through twice', 'Hello,\nI paid for the 3-dream package and my card was charged twice today. Can you refund one of them?\nThanks, John', 2900, 'CLOSED', [
    ev('CLOSED', 'We refunded the second payment. It can take 5 to 10 days to show on your card.', 2700),
  ]),
  ticket(['m.hassan@yahoo.com', 'محمد حسن'], 'استفسار عن الباقات', 'السلام عليكم،\nما الفرق بين الباقات؟ وهل يمكن استخدام الرصيد لرؤى أفراد العائلة؟', 1500, 'IN_PROGRESS', [
    ev('IN_PROGRESS', 'شكرًا لسؤالك، سنرسل لك تفاصيل الباقات المناسبة قريبًا.', 1400),
  ]),
  ticket(['amina.k@hotmail.com', 'Amina K.'], 'Cannot sign in with the code', 'Hi, the 6-digit code never arrives in my inbox. I tried three times.', 600, 'IN_PROGRESS', [
    ev('IN_PROGRESS', 'We are checking your account now.', 560),
    ev('IN_PROGRESS', 'Please try again and check the spam folder for the code.', 300),
  ]),
  ticket(['noura.s@gmail.com', 'نورة السبيعي'], 'هل يمكن تعديل نص الرؤيا بعد الإرسال؟', 'السلام عليكم ورحمة الله وبركاته،\n\nأرسلت رؤيا أمس، ثم تذكرت تفاصيل مهمة لم أكتبها. رأيت أنني أمشي في بستان واسع فيه نخل كثير، وكان معي أخي الأكبر، ثم وصلنا إلى بئر ماؤها صافٍ جدًا.\nوبعدها رأيت والدتي، رحمها الله، تبتسم وتعطيني مصحفًا صغيرًا ملفوفًا بقماش أخضر.\n\nهل يمكنني تعديل نص الرؤيا أو إضافة هذه التفاصيل قبل أن تبدأ المعبرة في التفسير؟ وإن لم يكن ذلك ممكنًا، هل أرسلها رؤيا جديدة؟ لا أريد أن أخسر رصيدًا إضافيًا.\n\nوشكرًا لكم على جهودكم، بارك الله فيكم ونفع بكم.\nنورة', 95),
  ticket(['omar.f@icloud.com', null], 'Question about my dream', null, 40),
  ticket(['khaled.2026@gmail.com', 'خالد'], null, 'السلام عليكم، أريد تغيير البريد الإلكتروني المسجل في حسابي.', 12),
  ticket(['fatima.zahra@gmail.com', 'Fatima Zahra'], 'Gift a package to my mother', 'Salam,\nCan I buy a package as a gift for my mother? She lives in Egypt and I live in the UK.\nBest regards,\nFatima', 3),
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

/** Text the mock "mailbox" still holds for tickets whose body has not arrived yet (read by fetch-body). */
const mailbox: Record<string, string> = {
  'omar.f@icloud.com': 'Assalamu alaikum,\nI dreamt that I was flying over a green valley at dawn. I sent it last week. When can I expect the answer?\nOmar',
}

/** POST /admin/support/tickets/{id}/fetch-body: copies the text from the mock mailbox when it is still missing. */
export function supportFetchBody(id: string): T.SupportTicketDetail | null {
  const t = tickets.find((x) => x.id === id)
  if (!t) return null
  if (t.body === null && mailbox[t.fromEmail]) t.body = mailbox[t.fromEmail]
  return supportDetail(id)
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
