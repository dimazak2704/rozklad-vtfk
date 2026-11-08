import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { ApiError } from '@/api/errors';
import type { AbsenceDto, BindingCodeDto, ChangeDto, MeDto, PreferenceDto, ScheduleDto } from '@/api/types';
import { ACTIVE_SEMESTER, teacherNamed } from '../fixtures';

const failure = (promise: Promise<unknown>) => promise.then(() => null, (e: unknown) => e as ApiError);

function at(instant: string): void {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(instant));
}

afterEach(() => {
  vi.useRealTimers();
});

const krentsin = teacherNamed('Кренцін');

describe('GET /api/me', () => {
  it('демо-обліковий запис повʼязаний з викладачем Кренціним', async () => {
    expect(await api.get<MeDto>('/me')).toMatchObject({ username: 'krentsin', roles: ['TEACHER'], teacher: { id: krentsin.id } });
  });
});

describe('запити на заміну', () => {
  it('є запити, що чекають відповіді, на найближчі навчальні дні', async () => {
    at('2026-11-09T08:00:00Z');
    const changes = await api.get<ChangeDto[]>('/me/changes');
    const pending = changes.filter((c) => c.status === 'PENDING_CONFIRMATION');
    expect(pending.length).toBeGreaterThanOrEqual(2);
    expect(pending.every((c) => c.new.teacher?.id === krentsin.id && c.date > '2026-11-09')).toBe(true);
    expect(pending.every((c) => c.confirmDeadline)).toBe(true);
    // сьогоднішня заміна вже в опублікованому розпорядженні
    expect(changes.find((c) => c.date === '2026-11-09')?.status).toBe('APPLIED');
    expect((await api.get<ChangeDto[]>('/me/changes', { status: 'PENDING_CONFIRMATION' })).length).toBe(pending.length);
  });

  it('підтвердження: статус змінюється, повторна відповідь — 409, заміна видна в розкладі групи', async () => {
    at('2026-11-09T13:00:00Z'); // 15:00 — розпорядження на завтра опубліковане
    const [request] = (await api.get<ChangeDto[]>('/me/changes', { status: 'PENDING_CONFIRMATION' })).filter((c) => c.date === '2026-11-10');
    expect(request).toBeDefined();
    const confirmed = await api.post<ChangeDto>(`/me/changes/${request?.id}/confirm`, { comment: 'Буду' });
    expect(confirmed.status).toBe('APPLIED');
    expect(confirmed.history.map((h) => h.to)).toEqual(['DRAFT', 'PENDING_CONFIRMATION', 'CONFIRMED', 'APPLIED']);
    expect((await failure(api.post(`/me/changes/${request?.id}/decline`, {})))?.status).toBe(409);

    const day = (
      await api.get<ScheduleDto>(`/public/schedule/group/${request?.group.id}`, { from: '2026-11-10', to: '2026-11-10' }, { auth: false })
    ).days[0];
    expect(day?.lessons.find((l) => l.pair === request?.pairFrom && l.subgroup === request.subgroup)).toMatchObject({
      kind: 'SUBSTITUTED',
      teacher: { id: krentsin.id },
    });
  });

  it('відмова прибирає заміну з публічного розкладу; чужий запит — 404', async () => {
    at('2026-11-09T13:00:00Z');
    const pending = await api.get<ChangeDto[]>('/me/changes', { status: 'PENDING_CONFIRMATION' });
    const target = pending[pending.length - 1];
    const declined = await api.post<ChangeDto>(`/me/changes/${target?.id}/decline`, { comment: 'Маю пару в іншій групі' });
    expect(declined).toMatchObject({ status: 'DECLINED' });
    expect(declined.history.at(-1)).toMatchObject({ actor: 'krentsin', to: 'DECLINED', comment: 'Маю пару в іншій групі' });
    expect((await failure(api.post('/me/changes/00000000-0000-4000-8000-000000000000/confirm', {})))?.status).toBe(404);
  });
});

describe('мої відсутності', () => {
  it('список: майбутня й минула', async () => {
    at('2026-11-09T08:00:00Z');
    const list = await api.get<AbsenceDto[]>('/me/absences');
    expect(list).toHaveLength(2);
    const [future, past] = list;
    expect((future?.dateFrom ?? '') > '2026-11-09').toBe(true);
    expect((past?.dateTo ?? '9999') < '2026-11-09').toBe(true);
  });

  it('створення: перевірка полів (400), минулої дати (422), перетину (409)', async () => {
    at('2026-11-09T08:00:00Z');
    const badRange = await failure(api.post('/me/absences', { dateFrom: '2026-11-12', dateTo: '2026-11-11', reasonCategory: 'SICK_LEAVE' }));
    expect(badRange?.fieldErrors()).toEqual({ dateTo: 'Не раніше за дату початку' });
    const noPairs = await failure(api.post('/me/absences', { dateFrom: '2026-11-12', dateTo: '2026-11-12', pairs: [], reasonCategory: 'OTHER' }));
    expect(Object.keys(noPairs?.fieldErrors() ?? {})).toEqual(['pairs']);
    expect((await failure(api.post('/me/absences', { dateFrom: '2026-11-06', dateTo: '2026-11-06', reasonCategory: 'OTHER' })))?.status).toBe(422);

    const created = await api.post<AbsenceDto>('/me/absences', { dateFrom: '2026-11-12', dateTo: '2026-11-13', pairs: [4, 2], reasonCategory: 'BUSINESS_TRIP', comment: ' Конференція ' });
    expect(created).toMatchObject({ pairs: [2, 4], source: 'WEB', status: 'ACTIVE', comment: 'Конференція', teacher: { id: krentsin.id } });
    expect((await failure(api.post('/me/absences', { dateFrom: '2026-11-13', dateTo: '2026-11-13', pairs: [4], reasonCategory: 'OTHER' })))?.status).toBe(409);
  });

  it('видалення: лише майбутня', async () => {
    at('2026-11-09T08:00:00Z');
    const [future, past] = await api.get<AbsenceDto[]>('/me/absences');
    expect((await failure(api.delete(`/me/absences/${past?.id}`)))?.status).toBe(422);
    await api.delete(`/me/absences/${future?.id}`);
    expect(await api.get<AbsenceDto[]>('/me/absences')).toHaveLength(1);
    expect((await failure(api.delete(`/me/absences/${future?.id}`)))?.status).toBe(404);
  });
});

describe('побажання', () => {
  const query = { query: { semesterId: ACTIVE_SEMESTER.id } };

  it('зберігає повний список і перевіряє дні та пари', async () => {
    expect((await api.get<PreferenceDto[]>('/me/preferences', { semesterId: ACTIVE_SEMESTER.id })).length).toBeGreaterThan(0);
    const saved = await api.put<PreferenceDto[]>('/me/preferences', [{ weekday: 3, pair: 7, kind: 'UNAVAILABLE' }, { weekday: 1, pair: 1, kind: 'UNWANTED' }], query);
    expect(saved).toEqual([
      { weekday: 1, pair: 1, kind: 'UNWANTED', note: null },
      { weekday: 3, pair: 7, kind: 'UNAVAILABLE', note: null },
    ]);
    expect(await api.get<PreferenceDto[]>('/me/preferences', { semesterId: ACTIVE_SEMESTER.id })).toEqual(saved);
    expect((await failure(api.put('/me/preferences', [{ weekday: 5, pair: 7, kind: 'UNWANTED' }], query)))?.status).toBe(400);
    expect((await failure(api.put('/me/preferences', [{ weekday: 2, pair: 1, kind: 'UNWANTED' }, { weekday: 2, pair: 1, kind: 'UNAVAILABLE' }], query)))?.status).toBe(400);
    expect((await failure(api.get('/me/preferences')))?.status).toBe(400);
  });
});

describe('код привʼязки Telegram', () => {
  // один мок на POST /notify/bindings/codes (область Telegram); що викладач просить код лише для себе, перевіряє сервер
  it('викладач отримує глибоке посилання на бота з кодом', async () => {
    const code = await api.post<BindingCodeDto>('/notify/bindings/codes', { type: 'TEACHER', teacherId: krentsin.id });
    expect(code.deepLink).toBe(`https://t.me/scheduler_vtfk_bot?start=${code.code}`);
    expect(code.code).not.toBe('');
  });
});
