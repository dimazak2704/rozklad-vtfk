import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AffectedLesson, LessonOptions } from '@/api/admin-daily';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import type {
  AbsenceDto,
  BindingCodeDto,
  ChangeDto,
  DashboardDto,
  DeliveryDto,
  OrderDto,
  OrderSummaryDto,
  Page,
  SettingsDto,
} from '@/api/types';
import { resetMockStores } from '@/mocks/store';
import { groupNamed, lessonsOn, teacherNamed } from '../fixtures';

/** Понеділок 09.11.2026, 07:40 за Києвом — демо-ранок припадає саме на цей день. */
const NOW = new Date('2026-11-09T05:40:00Z');
const DAY = '2026-11-09';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  resetMockStores();
});
afterEach(() => vi.useRealTimers());

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Очікували помилку API');
}

async function absenceFor(shortName: string): Promise<AbsenceDto> {
  return api.post<AbsenceDto>('/admin/absences', {
    teacherId: teacherNamed(shortName).id,
    dateFrom: DAY,
    dateTo: DAY,
    pairs: null,
    reasonCategory: 'SICK_LEAVE',
  });
}

describe('демо-ранок', () => {
  it('панель рахує відсутніх, пари без рішення, очікування й відмови', async () => {
    const dashboard = await api.get<DashboardDto>('/admin/dashboard', { date: DAY });
    expect(dashboard).toMatchObject({ date: DAY, parity: 'NUMERATOR', absences: 3, uncoveredLessons: 4, pending: 2, confirmed: 2, declined: 1 });
    expect(dashboard.orderDraft?.itemCount).toBe(2);
    // рівно ті види й у тому порядку, що й у core: відмови, без рішення, прострочені, неопубліковані
    expect(dashboard.alerts.map((a) => a.kind)).toEqual(['DECLINED', 'UNCOVERED_LESSONS', 'PENDING_OVERDUE', 'ORDER_NOT_PUBLISHED']);
    expect(dashboard.alerts.map((a) => a.severity)).toEqual(['ERROR', 'WARN', 'WARN', 'INFO']);
    expect(dashboard.alerts[1]).toMatchObject({ message: '4 пари без рішення через відсутність викладачів', link: `/admin/changes?date=${DAY}` });
    expect(dashboard.aiSpendToday).toBeNull();
  });

  it('відсутність на кілька днів повертає зачеплені пари з датами й рішеннями', async () => {
    const absences = await api.get<AbsenceDto[]>('/admin/absences', { from: DAY, to: DAY });
    const sick = absences.find((a) => a.teacher.shortName === 'Крисак Л.В.');
    expect(sick).toMatchObject({ dateFrom: DAY, dateTo: '2026-11-11', pairs: null, source: 'BOT' });
    const affected = await api.get<AffectedLesson[]>(`/admin/absences/${sick?.id ?? ''}/affected`);
    expect(new Set(affected.map((a) => a.date))).toEqual(new Set(['2026-11-09', '2026-11-10', '2026-11-11']));
    const monday = affected.filter((a) => a.date === DAY).map((a) => [a.lesson.group.code, a.decision?.status ?? null]);
    // як у №35: підгрупова пара 1ТК1 — «Пари не буде», далі заміна, очікування, без рішення
    expect(monday).toEqual([
      ['1ТК1', 'CONFIRMED'],
      ['1КІ2', 'CONFIRMED'],
      ['1М', 'PENDING_CONFIRMATION'],
      ['3Ф', null],
    ]);
  });

  it('архів містить розпорядження №35 з фото, а пошук знаходить його за групою, викладачем і номером', async () => {
    for (const q of ['35', '№35', '4ki1', 'прокоп']) {
      const page = await api.get<Page<OrderSummaryDto>>('/admin/orders', { status: 'PUBLISHED', q });
      expect(page.items.map((o) => o.number), `запит «${q}»`).toContain(35);
    }
    const none = await api.get<Page<OrderSummaryDto>>('/admin/orders', { status: 'PUBLISHED', q: 'Шевченко' });
    expect(none.items).toHaveLength(0);
    const order = await api.get<OrderDto>(`/admin/orders/${(await api.get<Page<OrderSummaryDto>>('/admin/orders', { q: '35' })).items[0]?.id ?? ''}`);
    expect(order).toMatchObject({ number: 35, date: '2026-10-19', weekdayLabel: 'ПОНЕДІЛОК', parityLabel: 'ЗНАМЕННИК', status: 'PUBLISHED' });
    expect(order.items).toHaveLength(16);
    expect(order.items.map((i) => i.rowType)).toEqual(expect.arrayContaining(['BLOCK', 'ADDITIONAL', 'CANCELLATION', 'REPLACEMENT']));
    expect(order.notes).toHaveLength(5);
    expect(order.signatoryName).toBe('Микола ПОЛІЩУК');
  });
});

describe('відсутності', () => {
  it('перевіряє поля як core: викладач, дати, пари', async () => {
    const error = await failure(api.post('/admin/absences', { dateFrom: DAY, dateTo: '2026-11-06', pairs: [0, 9], reasonCategory: 'SICK_LEAVE' }));
    expect(error.status).toBe(400);
    expect(Object.keys(error.fieldErrors()).sort()).toEqual(['dateTo', 'pairs', 'teacherId']);
  });

  it('не дозволяє другу відсутність того самого викладача на ті самі дати', async () => {
    const error = await failure(absenceFor('Крисак Л.В.'));
    expect(error.status).toBe(409);
    expect(error.message).toContain('Крисак Л.В.');
  });

  it('скасування відсутності скасовує й невнесені рішення за нею', async () => {
    const absences = await api.get<AbsenceDto[]>('/admin/absences', { from: DAY, to: DAY });
    const trip = absences.find((a) => a.reasonCategory === 'BUSINESS_TRIP');
    await api.delete(`/admin/absences/${trip?.id ?? ''}`);
    const changes = await api.get<ChangeDto[]>('/admin/changes', { date: DAY });
    const ofTrip = changes.filter((c) => c.absenceId === trip?.id);
    expect(ofTrip.length).toBeGreaterThan(0);
    expect(ofTrip.every((c) => c.status === 'CANCELLED')).toBe(true);
    const left = await api.get<AbsenceDto[]>('/admin/absences', { from: DAY, to: DAY });
    expect(left.map((a) => a.id)).not.toContain(trip?.id);
  });
});

describe('варіанти й зміни', () => {
  async function firstLessonOf(shortName: string): Promise<{ absence: AbsenceDto; lesson: AffectedLesson }> {
    const absence = await absenceFor(shortName);
    const affected = await api.get<AffectedLesson[]>(`/admin/absences/${absence.id}/affected`);
    const lesson = affected[0];
    if (!lesson) throw new Error(`У ${shortName} немає пар ${DAY}`);
    return { absence, lesson };
  }

  it('кандидати: без відсутнього й зайнятих, найкращі — ті, хто веде дисципліну', async () => {
    const { lesson } = await firstLessonOf('Погосян');
    const result = await api.get<LessonOptions>(`/admin/lessons/${lesson.lesson.id}/options`);
    // як core: відповідь без дати заняття; дата є лише у варіантів відпрацювання
    expect(result).not.toHaveProperty('date');
    expect(result.options.filter((o) => o.kind !== 'ADDITIONAL').every((o) => o.date === null && o.pair === null)).toBe(true);
    const teachers = result.options.filter((o) => o.kind === 'REPLACE_TEACHER');
    expect(teachers.length).toBeGreaterThan(0);
    expect(teachers.length).toBeLessThanOrEqual(10);
    const ids = teachers.map((o) => o.teacher?.id);
    expect(ids).not.toContain(teacherNamed('Погосян').id);
    // Крисак Л.В. на лікарняному — серед кандидатів її немає
    expect(ids).not.toContain(teacherNamed('Крисак Л.В.').id);
    const scores = teachers.map((o) => o.score);
    expect(scores).toEqual([...scores].sort((a, b) => b - a));
    expect(result.options.some((o) => o.kind === 'CANCEL')).toBe(true);
  });

  it('вибір кандидата в режимі підтвердження чекає відповіді; друге рішення на ту саму пару — 409', async () => {
    const { absence, lesson } = await firstLessonOf('Погосян');
    const { options } = await api.get<LessonOptions>(`/admin/lessons/${lesson.lesson.id}/options`);
    const best = options.find((o) => o.kind === 'REPLACE_TEACHER');
    const body = {
      kind: 'REPLACE_TEACHER',
      date: DAY,
      pairFrom: lesson.lesson.pair,
      groupId: lesson.lesson.group.id,
      subgroup: lesson.lesson.subgroup,
      originalLessonId: lesson.lesson.id,
      newTeacherId: best?.teacher?.id,
      reasonCode: 'ABSENT',
      absenceId: absence.id,
    };
    const change = await api.post<ChangeDto>('/admin/changes', body);
    expect(change.status).toBe('PENDING_CONFIRMATION');
    // типовий строк core — 120 хв (налаштування confirmTimeoutMinutes)
    expect(Date.parse(change.confirmDeadline ?? '') - NOW.getTime()).toBe(120 * 60_000);
    // адитивні поля core: pairTo = pairFrom для однієї пари, версія для оптимістичного блокування
    expect(change).toMatchObject({ pairTo: lesson.lesson.pair, swapFromPair: null, printedReason: 'Відп.', version: 0, approvalMode: 'CONFIRM' });
    expect(change.history.at(-1)?.to).toBe('PENDING_CONFIRMATION');
    const again = await failure(api.post('/admin/changes', body));
    expect(again.status).toBe(409);
  });

  it('зайнятий чи відсутній кандидат — 409 з conflicts[] (TEACHER / UNAVAILABLE)', async () => {
    const { lesson } = await firstLessonOf('Погосян');
    const body = {
      kind: 'REPLACE_TEACHER',
      date: DAY,
      pairFrom: lesson.lesson.pair,
      groupId: lesson.lesson.group.id,
      originalLessonId: lesson.lesson.id,
      reasonCode: 'ABSENT',
    };
    // викладач, у якого саме цієї пари власне заняття в іншій групі
    const busy = lessonsOn(DAY).find((l) => l.pair === lesson.lesson.pair && l.teacher && l.teacher.id !== lesson.lesson.teacher?.id)?.teacher;
    const taken = await failure(api.post('/admin/changes', { ...body, newTeacherId: busy?.id }));
    expect(taken.status).toBe(409);
    expect(taken.conflicts[0]).toMatchObject({ kind: 'TEACHER', severity: 'HARD', pair: lesson.lesson.pair, date: DAY });
    expect(taken.conflicts[0]?.lessonIds).toHaveLength(1);
    const absent = await failure(api.post('/admin/changes', { ...body, newTeacherId: teacherNamed('Крисак Л.В.').id }));
    expect(absent.conflicts[0]).toMatchObject({ kind: 'UNAVAILABLE', severity: 'HARD' });
  });

  it('у режимі «Автоматично» заміна одразу підтверджена', async () => {
    const settings = await api.get<SettingsDto>('/admin/settings');
    await api.put('/admin/settings', { ...settings, approvalMode: 'AUTO' });
    const { lesson } = await firstLessonOf('Погосян');
    const { options } = await api.get<LessonOptions>(`/admin/lessons/${lesson.lesson.id}/options`);
    const change = await api.post<ChangeDto>('/admin/changes', {
      kind: 'REPLACE_TEACHER',
      date: DAY,
      pairFrom: lesson.lesson.pair,
      groupId: lesson.lesson.group.id,
      originalLessonId: lesson.lesson.id,
      newTeacherId: options.find((o) => o.kind === 'REPLACE_TEACHER')?.teacher?.id,
      reasonCode: 'ABSENT',
    });
    expect(change.status).toBe('CONFIRMED');
    expect(change.confirmDeadline).toBeNull();
  });

  it('відповідь з Telegram: чужий викладач — 403, повторна — 409, нагадування лише для очікування', async () => {
    const changes = await api.get<ChangeDto[]>('/admin/changes', { date: DAY, status: 'PENDING_CONFIRMATION' });
    const pending = changes[0];
    if (!pending?.new.teacher) throw new Error('У демо-ранку має бути заміна в очікуванні');
    const wrong = await failure(api.post(`/internal/changes/${pending.id}/reply`, { teacherId: teacherNamed('Кренцін').id, decision: 'CONFIRM' }));
    expect(wrong.status).toBe(403);
    const reminded = await api.post<ChangeDto>(`/admin/changes/${pending.id}/request-confirmation`);
    expect(reminded.history.at(-1)?.comment).toBe('Запит надіслано повторно');
    const confirmed = await api.post<ChangeDto>(`/internal/changes/${pending.id}/reply`, { teacherId: pending.new.teacher.id, decision: 'CONFIRM' });
    expect(confirmed).toMatchObject({ status: 'CONFIRMED', respondedAt: NOW.toISOString() });
    expect(confirmed.history.at(-1)?.actor).toBe(pending.new.teacher.shortName);
    const twice = await failure(api.post(`/internal/changes/${pending.id}/reply`, { teacherId: pending.new.teacher.id, decision: 'DECLINE' }));
    expect(twice.status).toBe(409);
    const remindConfirmed = await failure(api.post(`/admin/changes/${pending.id}/request-confirmation`));
    expect(remindConfirmed).toMatchObject({ status: 422, code: 'CHANGE_ILLEGAL_TRANSITION' });
  });

  it('відхилену заміну не підтвердити (422 CHANGE_DECLINED), але можна спитати ще раз; скасування повертає пару «без рішення»', async () => {
    const changes = await api.get<ChangeDto[]>('/admin/changes', { date: DAY, status: 'DECLINED' });
    const declined = changes[0];
    const refused = await failure(api.post(`/admin/changes/${declined?.id ?? ''}/confirm`));
    expect(refused).toMatchObject({ status: 422, code: 'CHANGE_DECLINED' });
    const asked = await api.post<ChangeDto>(`/admin/changes/${declined?.id ?? ''}/request-confirmation`);
    expect(asked.history.at(-1)).toMatchObject({ actor: 'admin', from: 'DECLINED', to: 'PENDING_CONFIRMATION' });
    await api.delete(`/admin/changes/${asked.id}`);
    // повторне скасування — без помилки, як у core
    await api.delete(`/admin/changes/${asked.id}`);
    const after = await api.get<ChangeDto[]>('/admin/changes', { date: DAY });
    expect(after.find((c) => c.id === asked.id)?.status).toBe('CANCELLED');
  });

  it('PUT зміни: лише причина й аудиторія з version; застаріла версія — 409 stale-data', async () => {
    const [change] = await api.get<ChangeDto[]>('/admin/changes', { date: DAY, status: 'CONFIRMED' });
    if (!change) throw new Error('У демо-ранку має бути підтверджена зміна');
    const updated = await api.put<ChangeDto>(`/admin/changes/${change.id}`, {
      newRoomId: change.new.room?.id ?? null,
      reasonCode: 'OTHER',
      reasonText: 'Конф.',
      version: change.version,
    });
    expect(updated).toMatchObject({ reasonCode: 'OTHER', reasonText: 'Конф.', printedReason: 'Конф.', version: change.version + 1 });
    const stale = await failure(
      api.put(`/admin/changes/${change.id}`, { newRoomId: null, reasonCode: 'ABSENT', version: change.version }),
    );
    expect(stale.status).toBe(409);
    expect(stale.isStaleData).toBe(true);
  });
});

describe('розпорядження', () => {
  it('чернетка — підтверджені зміни дати за курсом, групою й парою; публікація дає №36 і вносить зміни', async () => {
    const draft = await api.get<OrderDto>('/admin/orders/draft', { date: DAY });
    expect(draft).toMatchObject({ status: 'DRAFT', number: null, kind: 'ORDER', weekdayLabel: 'ПОНЕДІЛОК', parityLabel: 'ЧИСЕЛЬНИК' });
    expect(draft.items.map((i) => [i.groupCode, i.rowType])).toEqual([
      ['1КІ2', 'REPLACEMENT'],
      ['1ТК1', 'CANCELLATION'],
    ]);
    // «(п.)» — лише там, де скасовано пару однієї підгрупи (як «Крисак Л. (п.)» у №35)
    expect(draft.items[1]?.fromTeacher).toBe('Крисак Л. (п.)');
    expect(draft.items[0]?.fromTeacher).toBe('Крисак Л.');

    const published = await api.post<OrderDto>(`/admin/orders/${draft.id}/publish`);
    expect(published).toMatchObject({ status: 'PUBLISHED', number: 36 });
    const changes = await api.get<ChangeDto[]>('/admin/changes', { date: DAY });
    const applied = changes.filter((c) => c.status === 'APPLIED');
    expect(applied).toHaveLength(2);
    expect(applied.every((c) => c.orderId === published.id)).toBe(true);

    const twice = await failure(api.post(`/admin/orders/${draft.id}/publish`));
    expect(twice).toMatchObject({ status: 422, code: 'ORDER_ALREADY_PUBLISHED' });
    expect(published.title).toBe('РОЗПОРЯДЖЕННЯ №36');
    const amendment = await api.get<OrderDto>('/admin/orders/draft', { date: DAY });
    expect(amendment).toMatchObject({ kind: 'AMENDMENT', parentId: published.id, items: [] });
    expect(amendment.title).toBe('ДОПОВНЕННЯ до розпорядження №36');
    const emptyPublish = await failure(api.post(`/admin/orders/${amendment.id}/publish`));
    expect(emptyPublish).toMatchObject({ status: 422, code: 'AMENDMENT_EMPTY' });
  });

  it('примітки чернетки зберігаються, а опублікованого — ні', async () => {
    const draft = await api.get<OrderDto>('/admin/orders/draft', { date: DAY });
    const saved = await api.put<OrderDto>(`/admin/orders/${draft.id}/notes`, { notes: ['  2КІ3 – практика  '], version: draft.version });
    expect(saved.notes).toEqual(['2КІ3 – практика']);
    // правка з застарілого вигляду чернетки не перезаписує збережену мовчки
    const stale = await failure(api.put(`/admin/orders/${draft.id}/notes`, { notes: ['інша'], version: draft.version }));
    expect(stale).toMatchObject({ status: 409, type: 'urn:rozklad:problem:stale-data' });
    const noVersion = await failure(api.put(`/admin/orders/${draft.id}/notes`, { notes: ['інша'] }));
    expect(noVersion.fieldErrors()).toHaveProperty('version');
    const blank = await failure(api.put(`/admin/orders/${draft.id}/notes`, { notes: ['ok', '  '], version: saved.version }));
    expect(blank.fieldErrors()).toHaveProperty('notes[1]');
    const archive = await api.get<Page<OrderSummaryDto>>('/admin/orders', { q: '35' });
    const error = await failure(api.put(`/admin/orders/${archive.items[0]?.id ?? ''}/notes`, { notes: [], version: 0 }));
    expect(error).toMatchObject({ status: 422, code: 'ORDER_ALREADY_PUBLISHED' });
    const tooLong = await failure(api.put(`/admin/orders/${draft.id}/notes`, { notes: ['х'.repeat(501)], version: saved.version }));
    expect(tooLong.fieldErrors()).toHaveProperty('notes[0]');
  });

  it('файли доступні лише для опублікованого: DOCX — справжній ZIP', async () => {
    const archive = await api.get<Page<OrderSummaryDto>>('/admin/orders', { q: '35' });
    const docx = await api.blob(`/documents/orders/${archive.items[0]?.id ?? ''}.docx`);
    const bytes = new Uint8Array(await docx.arrayBuffer());
    expect(String.fromCharCode(bytes[0] ?? 0, bytes[1] ?? 0)).toBe('PK');
    const pdf = await api.blob(`/documents/orders/${archive.items[0]?.id ?? ''}.pdf`);
    expect(new TextDecoder().decode((await pdf.arrayBuffer()).slice(0, 8))).toBe('%PDF-1.4');
    const draft = await api.get<OrderDto>('/admin/orders/draft', { date: DAY });
    const error = await failure(api.blob(`/documents/orders/${draft.id}.png`));
    expect(error.status).toBe(404);
  });

  it('після публікації бот розсилає розпорядження в привʼязані чати груп і диспетчерам', async () => {
    const draft = await api.get<OrderDto>('/admin/orders/draft', { date: DAY });
    const published = await api.post<OrderDto>(`/admin/orders/${draft.id}/publish`);
    // журнал — сторінка, як у notification
    const page = await api.get<Page<DeliveryDto>>('/notify/deliveries', { orderId: published.id });
    expect(page).toMatchObject({ page: 0, size: 20, total: 3 });
    expect(page.items.map((d) => d.chatTitle).sort()).toEqual(['1КІ2 — група', '1ТК1 — група', 'Диспетчерська ВТФК']);
    expect(page.items.every((d) => d.status === 'SENT' && d.nextAttemptAt === null)).toBe(true);
    expect(page.items.map((d) => d.purpose).sort()).toEqual(['ORDER_ADMIN', 'ORDER_GROUP', 'ORDER_GROUP']);
  });
});

describe('Telegram і налаштування', () => {
  it('код привʼязки чату групи вимагає групу й веде в Telegram через startgroup', async () => {
    const missing = await failure(api.post('/notify/bindings/codes', { type: 'GROUP_CHAT' }));
    expect(missing.fieldErrors()).toHaveProperty('groupId');
    const code = await api.post<BindingCodeDto>('/notify/bindings/codes', { type: 'GROUP_CHAT', groupId: groupNamed('4КІ1').id });
    // як у notification: 200, код «XXXX-XXXX», посилання — без дефіса
    expect(code.code).toMatch(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
    expect(code).toMatchObject({ type: 'GROUP_CHAT', label: '4КІ1', command: `/bind ${code.code}` });
    const raw = code.code.replace('-', '');
    expect(code.groupLink).toBe(`https://t.me/scheduler_vtfk_bot?startgroup=${raw}`);
    expect(code.deepLink).toBe(`https://t.me/scheduler_vtfk_bot?start=${raw}`);
    expect(Date.parse(code.expiresAt) - NOW.getTime()).toBe(24 * 60 * 60_000);
    // підписнику, як і чату групи, потрібна група
    const subscriber = await failure(api.post('/notify/bindings/codes', { type: 'SUBSCRIBER' }));
    expect(subscriber.fieldErrors()).toHaveProperty('groupId');
    const teacher = await api.post<BindingCodeDto>('/notify/bindings/codes', { type: 'TEACHER', teacherId: teacherNamed('Кренцін').id });
    expect(teacher).toMatchObject({ type: 'TEACHER', groupLink: null, command: null });
  });

  it('налаштування: межі значень перевіряються, коректні — зберігаються', async () => {
    const settings = await api.get<SettingsDto>('/admin/settings');
    // типові значення й межі — як у core: 120 хв, 5..1440, посада ≤ 200, імʼя ≤ 100
    expect(settings.confirmTimeoutMinutes).toBe(120);
    const invalid = await failure(api.put('/admin/settings', { ...settings, confirmTimeoutMinutes: 1441, signatoryName: ' ', hoursPerPair: 9 }));
    expect(Object.keys(invalid.fieldErrors()).sort()).toEqual(['confirmTimeoutMinutes', 'hoursPerPair', 'signatoryName']);
    const tooLong = await failure(api.put('/admin/settings', { ...settings, signatoryPosition: 'х'.repeat(201), signatoryName: 'х'.repeat(101) }));
    expect(Object.keys(tooLong.fieldErrors()).sort()).toEqual(['signatoryName', 'signatoryPosition']);
    const saved = await api.put<SettingsDto>('/admin/settings', { ...settings, confirmTimeoutMinutes: 1440, signatoryName: 'Микола ПОЛІЩУК ' });
    expect(saved).toMatchObject({ confirmTimeoutMinutes: 1440, signatoryName: 'Микола ПОЛІЩУК' });
    expect(await api.get<SettingsDto>('/admin/settings')).toEqual(saved);
  });
});
