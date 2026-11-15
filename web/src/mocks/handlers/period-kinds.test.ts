import { describe, expect, it } from 'vitest';
import type { CalendarPeriod } from '@/api/calendar';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import type { CoreCalendarImportResult } from '@/api/import';
import type { PeriodKindDto, PeriodKindImpactDto, PeriodKindRequest } from '@/api/types';
import { periodKindRequest } from '@/api/period-kinds';
import { academicCalendarDraft } from './calendar-import';
import { ONLINE_BLOCKER, ROOM_BLOCKER } from './period-kinds';

async function failure(request: Promise<unknown>): Promise<ApiError> {
  try {
    await request;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Запит мав завершитися помилкою');
}

const kinds = () => api.get<PeriodKindDto[]>('/admin/period-kinds');
const kind = async (code: string) => (await kinds()).find((k) => k.code === code) as PeriodKindDto;

describe('довідник видів періодів', () => {
  it('15 вбудованих видів за порядком; І, Д, А знімають пари, Р — лише позначка; НС — «група не на парах»', async () => {
    const list = await kinds();
    expect(list.map((k) => k.code)).toEqual(['К', 'С', 'БЗ', 'НП', 'ВП', 'ПП', 'Т', 'ЗУ', 'НС', 'І', 'Д', 'А', 'ФН', 'Р', 'ВКР']);
    expect(list.every((k) => k.builtin && k.active)).toBe(true);
    const effect = Object.fromEntries(list.map((k) => [k.code, k.effect]));
    expect(effect).toMatchObject({ К: 'DAY_OFF', С: 'DAY_OFF', БЗ: 'DAY_OFF', НС: 'SUSPEND', І: 'SUSPEND', Д: 'SUSPEND', А: 'SUSPEND', ФН: 'ONLINE', Р: 'INFO' });
    expect(await kind('ЗУ')).toMatchObject({ aliases: ['З'], name: 'Збори' });
    expect(await kind('НП')).toMatchObject({ name: 'Практика', usage: 1 });
    expect(await kind('НС')).toMatchObject({ usage: 1, noteTemplate: '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]' });
  });

  it('новий вид: 201, колір великими літерами, порядок — у кінці; код і псевдоніми не повторюються між видами', async () => {
    const body: PeriodKindRequest = { code: 'ОП', name: 'Оборонна підготовка', effect: 'SUSPEND', color: '#33cc99', aliases: ['ОБП'] };
    const created = await api.post<PeriodKindDto>('/admin/period-kinds', body);
    expect(created).toMatchObject({ code: 'ОП', color: '#33CC99', builtin: false, usage: 0, position: 160 });
    expect((await failure(api.post('/admin/period-kinds', { ...body, code: 'нс', aliases: [] }))).message).toBe('Код «нс» уже має вид «Національний спротив».');
    expect((await failure(api.post('/admin/period-kinds', { ...body, code: 'ЗЗ', aliases: ['з'] }))).message).toBe('Код «з» уже має вид «Збори».');
  });

  it('перевірка полів: код без пробілів, ефект BLOCK не приймається, колір #RRGGBB, вбудованому код не міняють', async () => {
    const error = await failure(api.post('/admin/period-kinds', { code: 'Н С', name: '', effect: 'BLOCK', color: 'red' }));
    expect(error.fieldErrors()).toEqual({
      code: 'Код — від 1 до 10 літер чи цифр, без пробілів.',
      name: 'Назва не може бути порожньою.',
      effect: 'Оберіть ефект: вихідний, група не на парах, форма навчання або лише позначка.',
      color: 'Колір має вигляд #RRGGBB.',
    });
    const ns = await kind('НС');
    expect((await failure(api.put(`/admin/period-kinds/${ns.id}`, { ...periodKindRequest(ns), code: 'ОНС' }))).fieldErrors()).toEqual({
      code: 'Код вбудованого виду не змінюється.',
    });
  });

  it('видалення: вбудований — 409, використаний — 409 з кількістю, новий без періодів — 204', async () => {
    expect((await failure(api.delete(`/admin/period-kinds/${(await kind('НС')).id}`))).message).toBe('Вбудований вид не видаляють: його можна вимкнути.');
    const own = await api.post<PeriodKindDto>('/admin/period-kinds', { code: 'ХХ', name: 'Новий вид', effect: 'SUSPEND' });
    const semester = (await api.get<Array<{ id: string; active: boolean }>>('/admin/semesters')).find((s) => s.active) as { id: string };
    await api.post('/admin/calendar-periods', { semesterId: semester.id, kindId: own.id, title: 'ХХ', startsOn: '2026-12-01', endsOn: '2026-12-02', scope: 'ALL' });
    expect((await failure(api.delete(`/admin/period-kinds/${own.id}`))).message).toBe(
      'Вид «ХХ» мають 1 період календаря: спершу видаліть їх або змініть їм вид.',
    );
    const unused = await api.post<PeriodKindDto>('/admin/period-kinds', { code: 'ЙЙ', name: 'Порожній', effect: 'INFO' });
    await api.delete(`/admin/period-kinds/${unused.id}`);
    expect((await kinds()).some((k) => k.id === unused.id)).toBe(false);
  });

  it('зміна ефекту: dryRun показує дати, групи й пари й нічого не зберігає; потім збереження змінює періоди', async () => {
    const practice = await kind('НП');
    const body = { ...periodKindRequest(practice), effect: 'INFO' as const };
    const impact = await api.put<PeriodKindImpactDto>(`/admin/period-kinds/${practice.id}`, body, { query: { dryRun: true } });
    expect(impact).toMatchObject({ effectChanged: true, periods: 1, canApply: true, blockers: [], conflicts: [], groups: [{ code: '2КІ3' }] });
    expect(impact.dates[0]).toBe('2026-10-19');
    expect(impact.dates).toHaveLength(10);
    expect(impact.kind.effect).toBe('INFO');
    expect((await kind('НП')).effect).toBe('SUSPEND');

    const saved = await api.put<PeriodKindDto>(`/admin/period-kinds/${practice.id}`, body);
    expect(saved).toMatchObject({ effect: 'INFO', version: 1 });
    const periods = await api.get<CalendarPeriod[]>('/admin/calendar-periods');
    expect(periods.find((p) => p.kindId === practice.id)).toMatchObject({ periodKind: { effect: 'INFO' }, kind: 'PRACTICE' });
  });

  it('ефект «онлайн» не вмикають і не вимикають у виду з періодами; без SUSPEND не лишають аудиторію', async () => {
    const fn = await kind('ФН');
    const online = await api.put<PeriodKindImpactDto>(`/admin/period-kinds/${fn.id}`, { ...periodKindRequest(fn), effect: 'INFO' }, { query: { dryRun: true } });
    expect(online).toMatchObject({ canApply: false, blockers: [ONLINE_BLOCKER] });
    expect((await failure(api.put(`/admin/period-kinds/${fn.id}`, { ...periodKindRequest(fn), effect: 'INFO' }))).message).toBe(ONLINE_BLOCKER);
    const ns = await kind('НС');
    const room = await api.put<PeriodKindImpactDto>(`/admin/period-kinds/${ns.id}`, { ...periodKindRequest(ns), effect: 'DAY_OFF' }, { query: { dryRun: true } });
    expect(room).toMatchObject({ canApply: false, blockers: [ROOM_BLOCKER] });
  });

  it('стара версія — 409 stale-data', async () => {
    const ns = await kind('НС');
    await api.put(`/admin/period-kinds/${ns.id}`, { ...periodKindRequest(ns), name: 'НС' });
    const error = await failure(api.put(`/admin/period-kinds/${ns.id}`, { ...periodKindRequest(ns), name: 'Ще раз' }));
    expect(error.isStaleData).toBe(true);
  });
});

describe('імпорт графіка в календар', () => {
  it('чернетка: НС 4КІ1 без змін, канікули обрізано до кінця семестру, НС у січні поза семестрами, невідомий код — помилка', () => {
    const { report, draft } = academicCalendarDraft(null);
    expect(draft.check).toMatchObject({ created: 3, updated: 0, unchanged: 1, deleted: 0 });
    expect(report.filter((r) => r.severity === 'ERROR')).toMatchObject([
      { type: 'PERIOD_KIND', raw: 'Ф', message: 'Невідомий код «Ф» у груп 3ЕА2: додайте вид у «Видах періодів» або пропустіть код.' },
    ]);
    expect(report.filter((r) => r.severity === 'WARN').map((r) => r.message)).toEqual([
      'К усі групи 28.12–08.01 поза семестрами системи: імпортується лише 28.12–30.12.',
      'НС 4ЕА1 11.01–22.01 поза семестрами системи: імпортується лише 19.01–22.01.',
    ]);
    expect(draft.periods.find((p) => p.groupCodes?.[0] === '4КІ2')).toMatchObject({
      roomName: '401',
      orderNote: '4КІ2 – Основи національного спротиву з 30.11.26р. по 11.12.26 р., ауд. 401',
    });
    expect(academicCalendarDraft({ skipCodes: ['Ф'] }).report.some((r) => r.severity === 'ERROR')).toBe(false);
  });

  it('POST /admin/import/calendar-periods: dryRun нічого не пише, справжній — створює й не дублює при повторі', async () => {
    const { draft } = academicCalendarDraft({ skipCodes: ['Ф'] });
    const body = { academicYear: draft.academicYear, periods: draft.periods.map(({ cells: _cells, ...p }) => p), groupSemesters: draft.groupSemesters };
    const before = (await api.get<CalendarPeriod[]>('/admin/calendar-periods')).length;
    const dry = await api.post<CoreCalendarImportResult>('/admin/import/calendar-periods', body, { query: { dryRun: true } });
    expect(dry).toMatchObject({ dryRun: true, created: 3, unchanged: 1 });
    expect(await api.get<CalendarPeriod[]>('/admin/calendar-periods')).toHaveLength(before);
    const real = await api.post<CoreCalendarImportResult>('/admin/import/calendar-periods', body);
    expect(real).toMatchObject({ dryRun: false, created: 3, groupSemesters: { created: 2 } });
    expect(await api.get<CalendarPeriod[]>('/admin/calendar-periods')).toHaveLength(before + 3);
    const again = await api.post<CoreCalendarImportResult>('/admin/import/calendar-periods', body);
    expect(again).toMatchObject({ created: 0, updated: 0, unchanged: 4, deleted: 0, groupSemesters: { unchanged: 2 } });
    const error = await failure(api.post('/admin/import/calendar-periods', { ...body, periods: [{ ...body.periods[0], kindCode: 'Ф' }] }));
    expect(error.fieldErrors()).toEqual({ 'periods[0].kindCode': 'Невідомий вид «Ф».' });
  });
});
