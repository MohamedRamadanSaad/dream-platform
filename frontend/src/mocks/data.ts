// In-memory mock backend state. Behaves like the real server: credits are a ledger, statuses transition by rules.
import type * as T from '@/api/types'

const now = () => new Date().toISOString()
const daysAgo = (d: number, h = 0) => new Date(Date.now() - d * 864e5 - h * 36e5).toISOString()
const daysAhead = (d: number) => new Date(Date.now() + d * 864e5).toISOString()
export const uid = () => Math.random().toString(36).slice(2, 10)

export type MockOrder = T.OrderDto & {
  userId: string
  settledAmountEgp?: number | null
  failureReason?: string | null
  discount?: number
  couponCode?: string | null
  countrySource?: string | null
}
export type MockDream = Omit<T.DreamDetail, 'excerpt' | 'unreadMessages' | 'editable' | 'payment' | 'startedAt' | 'expectedDays'> & {
  userId: string
  startedAt?: string | null
  expectedDays?: number | null
  /** The dreamer changed the text while it waited for the interpreter. */
  userEditedAt?: string | null
}
/** InstaPay transfer details of an order (the checkout intent's dreams, the buyer's reference, the review). */
export type OrderMeta = { dreamIds: string[]; reference: string | null; senderName: string | null; submittedAt: string | null; reviewedAt: string | null; reviewedByName?: string | null; reviewNote: string | null; expiresAt: string }
export const orderMeta = new Map<string, OrderMeta>()

/** pricing.fx_to_usd: one unit of the currency in USD. */
export const FX_TO_USD: Record<T.Currency, number> = { EGP: 0.0208, SAR: 0.2667, USD: 1 }
/** The order amount in EGP (contract §4): EGP as is, else amount / fx[EGP] * fx[currency], rounded to 2 places. */
export const toEgp = (amount: number, currency: T.Currency) => (currency === 'EGP' ? amount : Math.round((amount / FX_TO_USD.EGP) * FX_TO_USD[currency] * 100) / 100)

export const db = {
  users: [
    {
      id: 'u1', name: 'أم محمد', email: 'ummohamed@gmail.com', gender: 'FEMALE', birthDate: '1988-03-12', age: 38, role: 'USER', providers: ['GOOGLE', 'PASSWORD'],
      locale: 'ar', countryCode: 'SA', countryName: 'السعودية', onboarded: true, createdAt: daysAgo(40), hasPassword: true, termsAccepted: true,
    },
    {
      // interpreter accounts sign in with e-mail + password (never Google); birth date as set by the sessions/profile migration
      id: 'admin', name: 'المعبرة فاطمة', email: 'fatema@saadatu-aldarein.com', gender: 'FEMALE', birthDate: '1988-03-06', age: 38, role: 'INTERPRETER',
      providers: ['MAGIC_LINK', 'PASSWORD'], locale: 'ar', countryCode: 'EG', countryName: 'مصر', onboarded: true, createdAt: daysAgo(400), hasPassword: true, termsAccepted: true,
    },
    { id: 'u2', name: 'خالد العتيبي', email: 'khaled@example.com', gender: 'MALE', birthDate: '1995-11-02', age: 30, role: 'USER', providers: ['MAGIC_LINK'], locale: 'ar', countryCode: 'SA', countryName: 'السعودية', onboarded: true, createdAt: daysAgo(20), hasPassword: false, termsAccepted: true },
    { id: 'u3', name: 'Sara Ahmed', email: 'sara@example.de', gender: 'FEMALE', birthDate: '2001-07-21', age: 25, role: 'USER', providers: ['GOOGLE'], locale: 'en', countryCode: 'DE', countryName: 'ألمانيا', onboarded: true, createdAt: daysAgo(12), hasPassword: false, termsAccepted: true },
    { id: 'u4', name: 'منى عبد الله', email: 'mona@example.com', gender: 'FEMALE', role: 'USER', providers: ['GOOGLE', 'PASSWORD'], locale: 'ar', countryCode: 'EG', countryName: 'مصر', onboarded: true, createdAt: daysAgo(60), hasPassword: true, termsAccepted: true },
    { id: 'u5', name: 'يوسف حسن', email: 'youssef.hassan@example.com', gender: 'MALE', birthDate: '1990-05-14', age: 36, role: 'USER', providers: ['MAGIC_LINK'], locale: 'ar', countryCode: 'EG', countryName: 'مصر', onboarded: true, createdAt: daysAgo(90), hasPassword: false, termsAccepted: true },
    { id: 'u6', name: 'Omar Haddad', email: 'omar.haddad@example.com', gender: 'MALE', birthDate: '1985-09-30', age: 41, role: 'USER', providers: ['GOOGLE'], locale: 'en', countryCode: 'JO', countryName: 'الأردن', onboarded: true, createdAt: daysAgo(70), hasPassword: false, termsAccepted: true },
  ] as T.UserDto[],

  currentUserId: 'u1' as string,

  waitTime: {
    busy: true, normalHours: 48, busyMinDays: 2, busyMaxDays: 3,
    messageAr: 'نمنح كل رؤيا حقها من الدراسة المتأنية وتحليل الرموز.',
    messageEn: 'Every dream gets the careful study it deserves.',
    autoResetAt: null,
  } as T.WaitTimeSettings,

  packages: [
    { id: 'p1', nameAr: 'تفسير رؤيا واحدة', nameEn: 'One dream', descriptionAr: 'تفسير دقيق ومفصل لرؤيا واحدة، يقدم لك الوضوح والسكينة.', descriptionEn: 'A precise, detailed interpretation of one dream.', credits: 1, badge: null, sortOrder: 1, active: true, validityMonths: 12 },
    { id: 'p2', nameAr: 'تفسير رؤيتين', nameEn: 'Two dreams', descriptionAr: 'باقة مثالية لتفسير رؤيتين متصلتين أو منفصلتين، مع تحليل أعمق للرسائل.', descriptionEn: 'Ideal for two related or separate dreams, with deeper analysis.', credits: 2, badge: 'الأكثر طلباً', sortOrder: 2, active: true, validityMonths: 12 },
    { id: 'p3', nameAr: 'تفسير ثلاث رؤى', nameEn: 'Three dreams', descriptionAr: 'باقة شاملة لتفسير ثلاث رؤى، تمنحك رؤية متكاملة لرسائل رؤاك.', descriptionEn: 'A complete package for three dreams.', credits: 3, badge: 'أوفر', sortOrder: 3, active: true, validityMonths: 12 },
  ] as T.AdminPackage[],

  countries: [
    { code: 'EG', nameAr: 'مصر', nameEn: 'Egypt', continent: 'AF', defaultCurrency: 'EGP', groupId: null },
    { code: 'SA', nameAr: 'السعودية', nameEn: 'Saudi Arabia', continent: 'AS', defaultCurrency: 'USD', groupId: 'g1' },
    { code: 'AE', nameAr: 'الإمارات', nameEn: 'UAE', continent: 'AS', defaultCurrency: 'USD', groupId: 'g1' },
    { code: 'KW', nameAr: 'الكويت', nameEn: 'Kuwait', continent: 'AS', defaultCurrency: 'USD', groupId: 'g1' },
    { code: 'QA', nameAr: 'قطر', nameEn: 'Qatar', continent: 'AS', defaultCurrency: 'USD', groupId: 'g1' },
    { code: 'BH', nameAr: 'البحرين', nameEn: 'Bahrain', continent: 'AS', defaultCurrency: 'USD', groupId: 'g1' },
    { code: 'OM', nameAr: 'عُمان', nameEn: 'Oman', continent: 'AS', defaultCurrency: 'USD', groupId: 'g1' },
    { code: 'JO', nameAr: 'الأردن', nameEn: 'Jordan', continent: 'AS', defaultCurrency: 'USD', groupId: null },
    { code: 'MA', nameAr: 'المغرب', nameEn: 'Morocco', continent: 'AF', defaultCurrency: 'USD', groupId: 'g2' },
    { code: 'DZ', nameAr: 'الجزائر', nameEn: 'Algeria', continent: 'AF', defaultCurrency: 'USD', groupId: 'g2' },
    { code: 'TN', nameAr: 'تونس', nameEn: 'Tunisia', continent: 'AF', defaultCurrency: 'USD', groupId: 'g2' },
    { code: 'DE', nameAr: 'ألمانيا', nameEn: 'Germany', continent: 'EU', defaultCurrency: 'USD', groupId: null },
    { code: 'FR', nameAr: 'فرنسا', nameEn: 'France', continent: 'EU', defaultCurrency: 'USD', groupId: null },
    { code: 'GB', nameAr: 'بريطانيا', nameEn: 'United Kingdom', continent: 'EU', defaultCurrency: 'USD', groupId: null },
    { code: 'TR', nameAr: 'تركيا', nameEn: 'Türkiye', continent: 'AS', defaultCurrency: 'USD', groupId: null },
    { code: 'US', nameAr: 'الولايات المتحدة', nameEn: 'United States', continent: 'NA', defaultCurrency: 'USD', groupId: null },
    { code: 'CA', nameAr: 'كندا', nameEn: 'Canada', continent: 'NA', defaultCurrency: 'USD', groupId: null },
    { code: 'AU', nameAr: 'أستراليا', nameEn: 'Australia', continent: 'OC', defaultCurrency: 'USD', groupId: null },
    { code: 'BR', nameAr: 'البرازيل', nameEn: 'Brazil', continent: 'SA', defaultCurrency: 'USD', groupId: null },
  ] as T.CountryDto[],

  groups: [
    { id: 'g1', name: 'الخليج', countryCodes: ['SA', 'AE', 'KW', 'QA', 'BH', 'OM'] },
    { id: 'g2', name: 'المغرب العربي', countryCodes: ['MA', 'DZ', 'TN'] },
  ] as T.CountryGroup[],

  priceRules: [
    // global fallback in USD
    { id: 'r1', scope: 'GLOBAL', scopeId: null, packageId: 'p1', price: 15, currency: 'USD' },
    { id: 'r2', scope: 'GLOBAL', scopeId: null, packageId: 'p2', price: 27, currency: 'USD' },
    { id: 'r3', scope: 'GLOBAL', scopeId: null, packageId: 'p3', price: 39, currency: 'USD' },
    // Egypt
    { id: 'r4', scope: 'COUNTRY', scopeId: 'EG', packageId: 'p1', price: 199, currency: 'EGP' },
    { id: 'r5', scope: 'COUNTRY', scopeId: 'EG', packageId: 'p2', price: 349, currency: 'EGP' },
    { id: 'r6', scope: 'COUNTRY', scopeId: 'EG', packageId: 'p3', price: 499, currency: 'EGP' },
    // Gulf group (in USD since V30)
    { id: 'r7', scope: 'GROUP', scopeId: 'g1', packageId: 'p1', price: 7, currency: 'USD' },
    { id: 'r8', scope: 'GROUP', scopeId: 'g1', packageId: 'p2', price: 12, currency: 'USD' },
    { id: 'r9', scope: 'GROUP', scopeId: 'g1', packageId: 'p3', price: 23, currency: 'USD' },
    // Europe continent
    { id: 'r10', scope: 'CONTINENT', scopeId: 'EU', packageId: 'p1', price: 19, currency: 'USD' },
    { id: 'r11', scope: 'CONTINENT', scopeId: 'EU', packageId: 'p2', price: 35, currency: 'USD' },
    { id: 'r12', scope: 'CONTINENT', scopeId: 'EU', packageId: 'p3', price: 49, currency: 'USD' },
  ] as T.PriceRule[],

  promotions: [
    { id: 'pr1', name: 'عرض اليوم الوطني السعودي', packageIds: ['p2'], type: 'PERCENT', value: 20, startsAt: daysAgo(1), endsAt: daysAhead(6), maxUses: 100, usedCount: 23, scope: 'COUNTRY', scopeId: 'SA', active: true },
    { id: 'pr2', name: 'عرض نصر أكتوبر', packageIds: ['p2'], type: 'PERCENT', value: 15, startsAt: daysAhead(3), endsAt: daysAhead(10), maxUses: 200, usedCount: 0, scope: 'COUNTRY', scopeId: 'EG', active: true },
  ] as T.PromotionDto[],

  coupons: [
    { id: 'c1', code: 'BARAKA10', type: 'PERCENT', value: 10, maxUses: 200, perUserLimit: 1, usedCount: 41, expiresAt: daysAhead(30), active: true },
  ] as T.CouponDto[],

  ledger: [
    { id: 'l1', userId: 'u1', delta: 3, reason: 'PURCHASE', orderId: 'o1', dreamId: null, createdAt: daysAgo(9) },
    { id: 'l2', userId: 'u1', delta: -1, reason: 'SUBMIT', orderId: null, dreamId: 'd2', createdAt: daysAgo(8) },
    { id: 'l3', userId: 'u1', delta: -1, reason: 'SUBMIT', orderId: null, dreamId: 'd3', createdAt: daysAgo(1, 5) },
    { id: 'l4', userId: 'u1', delta: 2, reason: 'PURCHASE', orderId: 'o2', dreamId: null, createdAt: daysAgo(2) },
  ] as (T.CreditLedgerEntry & { userId: string })[],

  orders: [
    { id: 'o1', orderNumber: 'RN-15002', userId: 'u1', packageName: 'تفسير ثلاث رؤى', credits: 3, amount: 129, currency: 'SAR', status: 'SUCCESS', provider: 'MOR', providerRef: 'MOR-88213', countryCode: 'SA', createdAt: daysAgo(9), paidAt: daysAgo(9) },
    { id: 'o2', orderNumber: 'RN-15005', userId: 'u1', packageName: 'تفسير رؤيتين', credits: 2, amount: 71, currency: 'SAR', status: 'SUCCESS', provider: 'MOR', providerRef: 'MOR-90114', countryCode: 'SA', createdAt: daysAgo(2), paidAt: daysAgo(2) },
    { id: 'o3', orderNumber: 'RN-15001', userId: 'u1', packageName: 'تفسير رؤيا واحدة', credits: 1, amount: 49, currency: 'SAR', status: 'FAILED', provider: 'MOR', providerRef: null, countryCode: 'SA', createdAt: daysAgo(15), paidAt: null },
    { id: 'o4', orderNumber: 'RN-15004', userId: 'u4', packageName: 'تفسير رؤيا واحدة', credits: 1, amount: 199, currency: 'EGP', status: 'SUCCESS', provider: 'KASHIER', providerRef: 'TX-249893122', countryCode: 'EG', createdAt: daysAgo(3), paidAt: daysAgo(3) },
    { id: 'o5', orderNumber: 'RN-15003', userId: 'u3', packageName: 'Two dreams', credits: 2, amount: 35, currency: 'USD', status: 'SUCCESS', provider: 'KASHIER', providerRef: 'TX-249893555', countryCode: 'DE', createdAt: daysAgo(5), paidAt: daysAgo(5) },
  ] as MockOrder[],

  dreams: [
    {
      id: 'd1', userId: 'u1', gender: 'FEMALE', status: 'DRAFT', createdAt: daysAgo(0, 1), submittedAt: null, interpretedAt: null, expectedBy: null,
      text: 'رأيت نفسي أطير فوق مدينة نورانية، والقمر كبير وقريب، وشعرت بطمأنينة عظيمة، ثم نزلت على سطح بيت أعرفه من الطفولة.',
      messages: [], interpretation: null, testimonial: null, credit: null,
    },
    {
      id: 'd5', userId: 'u1', gender: 'FEMALE', status: 'DRAFT', createdAt: daysAgo(1), submittedAt: null, interpretedAt: null, expectedBy: null,
      text: 'رأيت أنني أمشي على شاطئ هادئ، ثم وجدت خاتماً ذهبياً بين الرمال، وحين لبسته سمعت أذاناً بعيداً.',
      messages: [], interpretation: null, testimonial: null, credit: null,
    },
    {
      id: 'd3', userId: 'u1', gender: 'FEMALE', status: 'AWAITING_USER_REPLY', createdAt: daysAgo(2), submittedAt: daysAgo(1, 5), interpretedAt: null, expectedBy: daysAhead(2),
      text: 'رأيت بيتاً قديماً فيه باب مفتوح ينتهي إلى حديقة خضراء مليئة بالورود البيضاء، وجلست تحت شجرة كبيرة وأنا أبكي من الفرح.',
      messages: [
        { id: 'm1', senderRole: 'INTERPRETER', body: 'بارك الله فيكِ. هل البيت القديم هو بيت أهلك، أم بيت لا تعرفينه؟ وهل كان الباب مفتوحاً من الداخل أم من الخارج؟', createdAt: daysAgo(0, 6), readAt: null },
      ],
      interpretation: null, testimonial: null, credit: { ledgerEntryId: 'l3', orderId: 'o1' },
    },
    {
      id: 'd4', userId: 'u1', gender: 'FEMALE', status: 'IN_REVIEW', createdAt: daysAgo(1), submittedAt: daysAgo(0, 20), interpretedAt: null, expectedBy: daysAhead(2),
      text: 'رأيت أنني أحمل مصحفاً صغيراً في يدي وأصعد درجاً طويلاً من الرخام الأبيض، وكلما صعدت درجة خفّ وزني.',
      messages: [], interpretation: null, testimonial: null, credit: { ledgerEntryId: 'l4', orderId: 'o2' },
    },
    {
      id: 'd2', userId: 'u1', gender: 'FEMALE', status: 'INTERPRETED', createdAt: daysAgo(9), submittedAt: daysAgo(8), interpretedAt: daysAgo(7), expectedBy: daysAgo(6),
      text: 'رأيت أنني أقرأ القرآن في مسجد كبير مضاء، وصوتي جميل والناس يستمعون، ثم رأيت شيخاً كبيراً يبتسم لي ويشير إلى الباب.',
      messages: [],
      interpretation: {
        text: 'أختي الكريمة، رؤياكِ مبشّرة بإذن الله.\n\nالمسجد الكبير المضاء يدل على الهداية وسعة الدين في قلبك، وحسن الصوت بالقرآن دليل على القبول وحسن الذكر بين الناس. أما الشيخ المبتسم فهو رضا أهل العلم أو والدٍ أو معلّم، وإشارته إلى الباب فتحٌ قريب في أمر كنتِ تنتظرينه.\n\nأوصيكِ بالمحافظة على وردك من القرآن، وبالصدقة شكراً لله. والله أعلم.',
        interpretedAt: daysAgo(7),
      },
      testimonial: null, credit: { ledgerEntryId: 'l2', orderId: 'o1' },
    },
    {
      id: 'd6', userId: 'u1', gender: 'FEMALE', status: 'INTERPRETED', createdAt: daysAgo(30), submittedAt: daysAgo(29), interpretedAt: daysAgo(27), expectedBy: daysAgo(27),
      text: 'رأيت مطراً خفيفاً ينزل على بيتنا فقط دون بيوت الجيران، والأرض تخضرّ بسرعة.',
      messages: [],
      interpretation: { text: 'المطر الخاص ببيتكم رزق ورحمة تخصكم، واخضرار الأرض سرعة الأثر والبركة. والله أعلم.', interpretedAt: daysAgo(27) },
      testimonial: { rating: 5, comment: 'تحقق والحمد لله بعد أسبوعين.', approved: true }, credit: { ledgerEntryId: 'l2', orderId: 'o1' },
    },
    // other users' dreams for the admin queue
    { id: 'd7', userId: 'u2', gender: 'MALE', status: 'IN_REVIEW', createdAt: daysAgo(4), submittedAt: daysAgo(3, 4), interpretedAt: null, expectedBy: daysAgo(0, 4), text: 'رأيت أنني أركب فرساً أبيض في صحراء واسعة ثم ظهرت واحة.', messages: [], interpretation: null, testimonial: null, credit: { ledgerEntryId: 'x', orderId: 'x' } },
    { id: 'd8', userId: 'u3', gender: 'FEMALE', status: 'IN_REVIEW', createdAt: daysAgo(1), submittedAt: daysAgo(0, 9), interpretedAt: null, expectedBy: daysAhead(2), text: 'I saw myself planting an olive tree in my grandmother\'s garden and it grew instantly.', messages: [], interpretation: null, testimonial: null, credit: { ledgerEntryId: 'x', orderId: 'o5' } },
    { id: 'd9', userId: 'u4', gender: 'FEMALE', status: 'IN_REVIEW', createdAt: daysAgo(2), submittedAt: daysAgo(2), interpretedAt: null, expectedBy: daysAhead(1), userEditedAt: daysAgo(0, 3), text: 'رأيت أمي المتوفاة تعطيني رغيف خبز ساخن وتقول: كُلي ولا تخافي.\nثم أخذتني من يدي إلى بيت جدي القديم وفتحت لي الباب.', messages: [], interpretation: null, testimonial: null, credit: { ledgerEntryId: 'x', orderId: 'o4' } },
    {
      id: 'd10', userId: 'u4', gender: 'FEMALE', status: 'AWAITING_USER_REPLY', createdAt: daysAgo(6), submittedAt: daysAgo(5), interpretedAt: null, expectedBy: daysAgo(2),
      text: 'رأيت أنني أفتح نافذة بيتنا فيدخل منها نور أبيض كثير، ثم أسمع صوت أبي يناديني باسمي.',
      messages: [{ id: 'm2', senderRole: 'INTERPRETER', body: 'هل والدك على قيد الحياة؟ وهل كان النور في الليل أم في النهار؟', createdAt: daysAgo(4), readAt: null }],
      interpretation: null, testimonial: null, credit: { ledgerEntryId: 'x', orderId: 'o4' },
    },
    // started by the interpreter ("Start interpreting"): final text, expected within 3 days
    {
      id: 'd15', userId: 'u1', gender: 'FEMALE', status: 'IN_PROGRESS', createdAt: daysAgo(3), submittedAt: daysAgo(2, 6), interpretedAt: null, expectedBy: new Date(Date.now() - 5 * 36e5 + 3 * 864e5).toISOString(),
      startedAt: daysAgo(0, 5), expectedDays: 3,
      text: 'رأيت أنني أسقي شجرة ليمون صغيرة أمام بيتنا، فأثمرت في الحال ثماراً كثيرة صفراء،\nوكان أبي يقطف منها ويوزّع على الجيران وهو يضحك.',
      messages: [], interpretation: null, testimonial: null, credit: { ledgerEntryId: 'l4', orderId: 'o2' },
    },
    { id: 'd16', userId: 'u3', gender: 'FEMALE', status: 'IN_PROGRESS', createdAt: daysAgo(3), submittedAt: daysAgo(2), interpretedAt: null, expectedBy: daysAhead(4), startedAt: daysAgo(1), expectedDays: 5, text: 'I dreamt of a wide river with clear water. I crossed it on stones that appeared under my feet one by one.', messages: [], interpretation: null, testimonial: null, credit: { ledgerEntryId: 'x', orderId: 'o5' } },
    // drafts attached to InstaPay transfers (see orderMeta below): not plain drafts any more
    { id: 'd13', userId: 'u1', gender: 'FEMALE', status: 'DRAFT', createdAt: daysAgo(0, 7), submittedAt: null, interpretedAt: null, expectedBy: null, text: 'رأيت أنني في سوق قديم أشتري قماشاً أبيض، والبائع يرفض أن يأخذ الثمن ويقول: هذا لكِ منذ زمن.', messages: [], interpretation: null, testimonial: null, credit: null },
    { id: 'd14', userId: 'u1', gender: 'FEMALE', status: 'DRAFT', createdAt: daysAgo(4), submittedAt: null, interpretedAt: null, expectedBy: null, text: 'رأيت قطة بيضاء تدخل غرفتي وتنام على سجادة الصلاة، ثم سمعت صوت مطر خفيف.', messages: [], interpretation: null, testimonial: null, credit: null },
    { id: 'd11', userId: 'u4', gender: 'FEMALE', status: 'DRAFT', createdAt: daysAgo(0, 2), submittedAt: null, interpretedAt: null, expectedBy: null, text: 'رأيت أنني أصعد جبلاً أخضر مع أختي الكبرى، وفي القمة وجدنا بئراً ماؤها بارد وحلو، فشربنا حتى ارتوينا.\n\nثم نزلنا من طريق آخر لم أره من قبل، وكان مضيئاً كأنه نهار.', messages: [], interpretation: null, testimonial: null, credit: null },
    { id: 'd12', userId: 'u4', gender: 'FEMALE', status: 'DRAFT', createdAt: daysAgo(0, 2), submittedAt: null, interpretedAt: null, expectedBy: null, text: 'رأيت جدتي رحمها الله تمشّط شعري وتقرأ آية الكرسي بصوت هادئ.', messages: [], interpretation: null, testimonial: null, credit: null },
    { id: 'd17', userId: 'u5', gender: 'MALE', status: 'DRAFT', createdAt: daysAgo(1, 3), submittedAt: null, interpretedAt: null, expectedBy: null, text: 'رأيت أنني أبني بيتاً من الحجر الأبيض على شاطئ البحر، وكلما وضعت حجراً جاء طائر أبيض ووقف عليه.', messages: [], interpretation: null, testimonial: null, credit: null },
  ] as MockDream[],

  notifications: [
    { id: 'n1', userId: 'u1', type: 'INTERPRETER_QUESTION', title: 'استفسار من المعبّرة', body: 'أرسلت المعبّرة سؤالاً عن رؤياك «البيت القديم». ردّك يساعد على تفسير أدق.', link: '/me/dreams/d3', createdAt: daysAgo(0, 6), readAt: null },
    { id: 'n2', userId: 'u1', type: 'DREAM_RECEIVED', title: 'وصلت رؤياك', body: 'وصلت رؤيا «المصحف والدرج». الرد خلال يومين إلى ثلاثة أيام إن شاء الله.', link: '/me/dreams/d4', createdAt: daysAgo(0, 20), readAt: null },
    { id: 'n3', userId: 'u1', type: 'PAYMENT_SUCCESS', title: 'تم الدفع', body: 'أُضيفت رؤيتان إلى رصيدك.', link: '/me/payments', createdAt: daysAgo(2), readAt: daysAgo(1) },
    { id: 'n4', userId: 'u1', type: 'INTERPRETATION_READY', title: 'تم تفسير رؤياك', body: 'تفسير رؤيا «المسجد المضاء» جاهز الآن.', link: '/me/dreams/d2', createdAt: daysAgo(7), readAt: daysAgo(6) },
    { id: 'n5', userId: 'admin', type: 'DREAM_SUBMITTED', title: 'رؤية جديدة', body: 'قدّمت أم محمد رؤيا جديدة.', link: '/admin/dreams/d4', createdAt: daysAgo(0, 20), readAt: null },
    { id: 'n7', userId: 'admin', type: 'DREAM_EDITED', title: 'عدّل صاحب الرؤيا نصّها', body: 'عدّلت منى عبد الله نص رؤيا قبل أن تبدئي تعبيرها.', link: '/admin/dreams/d9', createdAt: daysAgo(0, 3), readAt: null },
    { id: 'n6', userId: 'admin', type: 'DREAM_SUBMITTED', title: 'رؤية جديدة', body: 'Sara Ahmed قدّمت رؤيا جديدة.', link: '/admin/dreams/d8', createdAt: daysAgo(0, 9), readAt: null },
  ] as (T.NotificationDto & { userId: string })[],

  visits: [
    { userId: 'u1', countryCode: 'SA', count: 42 }, { userId: 'u2', countryCode: 'SA', count: 11 },
    { userId: 'u3', countryCode: 'DE', count: 9 }, { userId: 'u4', countryCode: 'EG', count: 27 },
    { userId: 'anon', countryCode: 'EG', count: 1840 }, { userId: 'anon', countryCode: 'SA', count: 1210 },
    { userId: 'anon', countryCode: 'AE', count: 340 }, { userId: 'anon', countryCode: 'MA', count: 220 },
    { userId: 'anon', countryCode: 'DE', count: 160 }, { userId: 'anon', countryCode: 'US', count: 95 },
    { userId: 'anon', countryCode: 'KW', count: 130 }, { userId: 'anon', countryCode: 'JO', count: 88 },
  ],

  userNotes: {} as Record<string, { notes: string; tags: string[] }>,

  /** POST /public/track hits (the mock traffic report adds them to today's numbers). */
  pageViews: [] as { path: string; referrer: string | null; sessionId: string; visitorId: string | null; device: T.DeviceType; countryCode: string; at: string }[],

  /** app_settings as the admin settings API returns it (string values; BOOL = 'true' / 'false'). */
  settings: {} as T.SettingsMap,
}

/** Toggleable e-mail events — setting key `mail.event.<template>` (default true). */
export const MAIL_EVENTS = [
  'welcome', 'payment-failed', 'dream-cancelled', 'credits-adjusted', 'testimonial-approved', 'account-deleted', 'new-user',
  'testimonial-received', 'dream-submitted', 'dream-received', 'interpreter-question', 'user-replied', 'interpretation-ready',
  'payment-receipt', 'payment-suspicious', 'reply-reminder', 'testimonial-request', 'interpreter-digest', 'youtube-new-video',
  'support-auto-reply', 'new-sign-in', 'passkey-added', 'support-in-progress', 'support-closed',
] as const

/** Every e-mail template (GET /admin/mail/templates); the sign-in and code e-mails have no on/off switch. */
export const MAIL_TEMPLATES = ['magic-link', 'email-code', ...MAIL_EVENTS] as const

/** The e-mail theme registry (backend classpath mail/themes.json). Image paths are on the site. */
export const MAIL_THEMES: Omit<T.MailThemeDto, 'headerImageUrl' | 'footerImageUrl' | 'usedByDefault'>[] = [
  { key: 'crescent-night', nameAr: 'ليلة الهلال', nameEn: 'Crescent night', pageBg: '#0A1128', cardBg: '#F4EFE6', accent: '#D4AF37' },
  { key: 'rose-dawn', nameAr: 'فجر وردي', nameEn: 'Rose dawn', pageBg: '#3E3352', cardBg: '#FBF4F2', accent: '#B76E79' },
  { key: 'sea-breeze', nameAr: 'نسيم البحر', nameEn: 'Sea breeze', pageBg: '#0A2A33', cardBg: '#F1F8F6', accent: '#2E8C83' },
  { key: 'lavender-night', nameAr: 'ليل الخزامى', nameEn: 'Lavender night', pageBg: '#1E1739', cardBg: '#F6F2FB', accent: '#8C6CC8' },
  { key: 'desert-dusk', nameAr: 'غروب الصحراء', nameEn: 'Desert dusk', pageBg: '#2E2333', cardBg: '#FBF5EC', accent: '#B5713F' },
  { key: 'emerald-night', nameAr: 'ليلة الزمرد', nameEn: 'Emerald night', pageBg: '#0A221B', cardBg: '#F2F6F1', accent: '#B8963F' },
  { key: 'winter-sky', nameAr: 'سماء الشتاء', nameEn: 'Winter sky', pageBg: '#222C3E', cardBg: '#F3F6F9', accent: '#5F7FA8' },
  { key: 'calm-morning', nameAr: 'صباح هادئ', nameEn: 'Calm morning', pageBg: '#E6EEF5', cardBg: '#FFFFFF', accent: '#B8862B' },
]

db.settings = {
  ...Object.fromEntries(MAIL_EVENTS.map((e) => [`mail.event.${e}`, 'true'])),
  ...Object.fromEntries(MAIL_TEMPLATES.map((e) => [`mail.theme.${e}`, ''])),
  'brand.legal_name': 'فاطمه عبدالوهاب',
  'brand.tax_registration_no': '114-683-768',
  'brand.support_phone': '+201096452764',
  'payments.instapay.enabled': 'true',
  'payments.instapay.address': 'vcbow1694@instapay',
  'payments.instapay.link': 'https://ipn.eg/S/vcbow1694/instapay/3F76CH',
  'payments.instapay.recipient_name': '',
  'payments.instapay.expire_hours': '24',
  'orders.number_prefix': 'RN',
  'brand.support_hours.ar': 'من ٢ إلى ٦ مساءً بتوقيت مصر',
  'brand.support_hours.en': '2 PM to 6 PM, Egypt time',
  'mail.theme.default': 'crescent-night',
  'mail.assets_base_url': '',
  'mail.auto_reply_cooldown_hours': '24',
  'brand.support_email': 'support@saadatu-aldarein.com',
  'dreams.reply_reminder_hours': '48',
  'interpreter.digest_hour': '9',
  'schedule.time_zone': 'Africa/Cairo',
}

export const helpers = { now, daysAgo, daysAhead }

/** Readable order numbers like the server's: prefix (setting orders.number_prefix) + a sequence from 15001. */
export const nextOrderNumber = () => {
  const max = Math.max(15000, ...db.orders.map((o) => Number(/(\d+)$/.exec(o.orderNumber ?? '')?.[1] ?? 0)))
  return `${db.settings['orders.number_prefix'] || 'RN'}-${max + 1}`
}

// ---------- orders: InstaPay transfers waiting / rejected, and a history for the tabs and the pager ----------
{
  const seed = (o: Omit<MockOrder, 'orderNumber'>, m?: Partial<OrderMeta>) => {
    db.orders.push(o as MockOrder)
    if (o.provider === 'INSTAPAY') orderMeta.set(o.id, { dreamIds: [], reference: null, senderName: null, submittedAt: null, reviewedAt: null, reviewNote: null, expiresAt: daysAhead(1), ...m })
  }
  const base = { failureReason: null, discount: 0, couponCode: null, countrySource: 'CF_IPCOUNTRY', settledAmountEgp: null } as const
  seed({ ...base, id: 'o-ip1', userId: 'u4', packageName: 'تفسير ثلاث رؤى', credits: 3, amount: 450, currency: 'EGP', status: 'PENDING_REVIEW', provider: 'INSTAPAY', providerRef: null, countryCode: 'EG', createdAt: daysAgo(0, 2), paidAt: null, discount: 49, couponCode: 'BARAKA10' },
    { dreamIds: ['d11', 'd12'], reference: '202311580274', senderName: 'MONA ABDALLAH', submittedAt: daysAgo(0, 1) })
  seed({ ...base, id: 'o-ip2', userId: 'u1', packageName: 'تفسير رؤيتين', credits: 2, amount: 349, currency: 'EGP', status: 'PENDING_REVIEW', provider: 'INSTAPAY', providerRef: null, countryCode: 'EG', createdAt: daysAgo(0, 8), paidAt: null },
    { dreamIds: ['d13'], reference: '202311588812', senderName: 'UMM MOHAMED', submittedAt: daysAgo(0, 7) })
  seed({ ...base, id: 'o-ip3', userId: 'u1', packageName: 'تفسير رؤيا واحدة', credits: 1, amount: 199, currency: 'EGP', status: 'FAILED', failureReason: 'TRANSFER_REJECTED', provider: 'INSTAPAY', providerRef: null, countryCode: 'EG', createdAt: daysAgo(4), paidAt: null },
    { dreamIds: ['d14'], reference: '202311000017', senderName: 'UMM MOHAMED', submittedAt: daysAgo(4), reviewedAt: daysAgo(3), reviewedByName: 'المعبرة فاطمة', reviewNote: 'لم يصل تحويل بهذا الرقم المرجعي، راجعي الرقم في تطبيق إنستاباي.' })
  seed({ ...base, id: 'o-ip4', userId: 'u5', packageName: 'تفسير رؤيا واحدة', credits: 1, amount: 199, currency: 'EGP', status: 'PENDING_REVIEW', provider: 'INSTAPAY', providerRef: null, countryCode: 'EG', createdAt: daysAgo(1, 4), paidAt: null },
    { dreamIds: ['d17'], reference: '202311475530', senderName: 'YOUSSEF HASSAN', submittedAt: daysAgo(1, 3) })
  // history: Egyptian InstaPay payments, Gulf / Europe / Jordan card payments (amounts in the buyer's currency)
  const users = ['u4', 'u5', 'u2', 'u3', 'u6']
  const plans = [
    { name: 'تفسير رؤيا واحدة', credits: 1, EGP: 199, SAR: 49, USD: 15 },
    { name: 'تفسير رؤيتين', credits: 2, EGP: 349, SAR: 89, USD: 27 },
    { name: 'تفسير ثلاث رؤى', credits: 3, EGP: 499, SAR: 129, USD: 39 },
  ]
  const statuses: T.OrderStatus[] = ['SUCCESS', 'SUCCESS', 'SUCCESS', 'FAILED', 'SUCCESS', 'EXPIRED', 'SUCCESS', 'REFUNDED']
  for (let i = 0; i < 34; i++) {
    const userId = users[i % users.length]
    const u = db.users.find((x) => x.id === userId)!
    const cc = u.countryCode
    const currency: T.Currency = cc === 'EG' ? 'EGP' : cc === 'SA' ? 'SAR' : 'USD'
    const plan = plans[i % plans.length]
    const status = statuses[i % statuses.length]
    const at = daysAgo(6 + i * 2, i % 7)
    const paid = status === 'SUCCESS' || status === 'REFUNDED'
    const amount = plan[currency]
    seed({ ...base, id: `o-h${i + 1}`, userId, packageName: plan.name, credits: plan.credits, amount, currency, status, provider: cc === 'EG' ? 'INSTAPAY' : 'MOR', providerRef: paid ? (cc === 'EG' ? `INSTAPAY-REF-2023${100000 + i * 731}` : `MOR-${70000 + i * 37}`) : null, countryCode: cc, createdAt: at, paidAt: paid ? at : null, settledAmountEgp: paid ? toEgp(amount, currency) : null },
      cc === 'EG' && paid ? { reference: `2023${100000 + i * 731}`, senderName: u.name, submittedAt: at, reviewedAt: at, reviewedByName: 'المعبرة فاطمة' } : undefined)
  }
  for (const o of db.orders) if ((o.status === 'SUCCESS' || o.status === 'REFUNDED') && o.settledAmountEgp == null) o.settledAmountEgp = toEgp(o.amount, o.currency)
  // readable numbers for the seeded orders, oldest first, after the fixed ones
  for (const o of [...db.orders].filter((x) => !x.orderNumber).sort((a, b) => a.createdAt.localeCompare(b.createdAt))) o.orderNumber = nextOrderNumber()
}

// ---------- pricing resolution (same order as the spec) ----------
export function resolvePrice(countryCode: string, packageId: string): { price: number; currency: T.Currency; scope: T.PriceScope } {
  const c = db.countries.find((x) => x.code === countryCode)
  const rules = db.priceRules.filter((r) => r.packageId === packageId)
  const by = (scope: T.PriceScope, scopeId: string | null) => rules.find((r) => r.scope === scope && r.scopeId === scopeId)
  const hit =
    (c && by('COUNTRY', c.code)) ||
    (c?.groupId && by('GROUP', c.groupId)) ||
    (c && by('CONTINENT', c.continent)) ||
    by('GLOBAL', null)
  if (!hit) return { price: 0, currency: 'USD', scope: 'GLOBAL' }
  return { price: hit.price, currency: hit.currency, scope: hit.scope }
}

export function applyPromotion(packageId: string, price: number, countryCode?: string): { price: number; original: number | null; promo: T.PackageDto['promotion'] } {
  const c = db.countries.find((x) => x.code === countryCode)
  const inScope = (x: T.PromotionDto) => x.scope === 'GLOBAL' || (x.scope === 'COUNTRY' && x.scopeId === countryCode) || (x.scope === 'GROUP' && x.scopeId === c?.groupId) || (x.scope === 'CONTINENT' && x.scopeId === c?.continent)
  const p = db.promotions.find((x) => x.active && inScope(x) && x.packageIds.includes(packageId) && new Date(x.endsAt) > new Date() && new Date(x.startsAt) <= new Date())
  if (!p) return { price, original: null, promo: null }
  const discounted = p.type === 'PERCENT' ? Math.round(price * (1 - p.value / 100)) : p.type === 'FIXED' ? Math.max(0, price - p.value) : price
  return { price: discounted, original: price, promo: { label: p.name, endsAt: p.endsAt } }
}

export function balanceOf(userId: string) {
  return db.ledger.filter((l) => l.userId === userId).reduce((a, l) => a + l.delta, 0)
}

export function excerpt(text: string) {
  return text.length > 110 ? text.slice(0, 110) + '…' : text
}

/**
 * The live order a DRAFT is attached to (contract §2): its id is in the intent of one of the user's orders that is
 * PENDING_REVIEW, or FAILED with TRANSFER_REJECTED. The latest such order wins.
 */
export function attachedOrder(d: MockDream): MockOrder | null {
  if (d.status !== 'DRAFT') return null
  return db.orders
    .filter((o) => o.userId === d.userId && (o.status === 'PENDING_REVIEW' || (o.status === 'FAILED' && o.failureReason === 'TRANSFER_REJECTED')) && orderMeta.get(o.id)?.dreamIds.includes(d.id))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0] ?? null
}
/** DRAFT, or IN_REVIEW / AWAITING_USER_REPLY before the interpreter pressed "Start interpreting". */
export const isEditable = (d: MockDream) => d.status === 'DRAFT' || ((d.status === 'IN_REVIEW' || d.status === 'AWAITING_USER_REPLY') && !d.startedAt)

export function toSummary(d: MockDream): T.DreamSummary {
  const o = attachedOrder(d)
  const m = o ? orderMeta.get(o.id) : undefined
  return {
    id: d.id, excerpt: excerpt(d.text), status: d.status, createdAt: d.createdAt, submittedAt: d.submittedAt,
    interpretedAt: d.interpretedAt, expectedBy: d.expectedBy,
    unreadMessages: d.messages.filter((m) => m.senderRole === 'INTERPRETER' && !m.readAt).length,
    startedAt: d.startedAt ?? null, expectedDays: d.expectedDays ?? null, editable: isEditable(d),
    payment: o ? { orderId: o.id, status: o.status === 'PENDING_REVIEW' ? 'PENDING_REVIEW' : 'REJECTED', submittedAt: m?.submittedAt ?? null, reviewNote: m?.reviewNote ?? null } : null,
  }
}

export function toDetail(d: MockDream): T.DreamDetail {
  return { ...toSummary(d), text: d.text, gender: d.gender, interpretation: d.interpretation, messages: d.messages, testimonial: d.testimonial, credit: d.credit }
}

export const ytVideos: import('@/api/types').YoutubeVideoDto[] = [
  { id: 'yt1', title: 'رؤية الماء في المنام: بين الرزق والفتنة', url: 'https://youtube.com/@almoaberafatema', publishedAt: new Date(Date.now() - 2 * 864e5).toISOString(), thumbnailUrl: null },
  { id: 'yt2', title: 'كيف تفرّق بين الرؤيا والحُلم؟', url: 'https://youtube.com/@almoaberafatema', publishedAt: new Date(Date.now() - 9 * 864e5).toISOString(), thumbnailUrl: null },
  { id: 'yt3', title: 'أدب الرؤيا: متى تحكيها ولمن؟', url: 'https://youtube.com/@almoaberafatema', publishedAt: new Date(Date.now() - 20 * 864e5).toISOString(), thumbnailUrl: null },
]
export const ytSeen: Record<string, number> = {}
