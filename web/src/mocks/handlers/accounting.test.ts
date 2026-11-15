import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AccountingRow } from '@/api/accounting';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import type { TeacherStatsDto } from '@/api/types';
import { GROUPS } from '../fixtures';
import { resetMockStores } from '../store';

const group4ki1 = GROUPS.find((g) => g.code === '4КІ1') as (typeof GROUPS)[number];
const rowsOf = async (groupId?: string) => api.get<AccountingRow[]>('/admin/accounting', { groupId });
const row = (rows: AccountingRow[], shortName: string) => rows.find((r) => r.discipline.shortName === shortName) as AccountingRow;

beforeEach(() => {
  // неділя, 8 листопада 2026, полудень у Києві: пари до 6 листопада включно вже минули
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-08T10:00:00Z'));
  resetMockStores();
});

afterEach(() => {
  vi.useRealTimers();
});

// НС 4КІ1 16.11–27.11 (графік 2026/27) знімає два тижні пар групи: прогнози нижче це враховують
describe('GET /api/admin/accounting', () => {
  it('ОПЦЗ 4КІ1 вичитано 3 листопада: план 20 год досягнуто, 6 пар після цього (без тижнів НС) — зайві', async () => {
    const opcz = row(await rowsOf(group4ki1.id), 'ОПЦЗ');
    expect(opcz).toMatchObject({ plan: 20, fact: 20, remaining: 12, forecast: 32, status: 'FINISHED', forecastFinishDate: '2026-11-03' });
    expect(opcz.suggestion).toMatchObject({ kind: 'DISCIPLINE_FINISHED', fromDate: '2026-11-10', lessons: 6 });
    expect(opcz.suggestion?.text).toBe('Вичитано 3 листопада: зняти 6 пар після цієї дати');
    expect(opcz.suggestion?.lessonIds).toHaveLength(6);
  });

  it('СТП перевищить план: прогноз 62 год проти 48, вичитано буде 7 грудня (16–27.11 — НС)', async () => {
    const stp = row(await rowsOf(group4ki1.id), 'СТП');
    expect(stp).toMatchObject({ plan: 48, fact: 38, forecast: 62, status: 'WILL_OVERRUN', forecastFinishDate: '2026-12-07' });
    expect(stp.suggestion).toMatchObject({ kind: 'DISCIPLINE_FINISHED', fromDate: '2026-12-08', lessons: 7 });
  });

  it('НДЕКСМ не встигне: 15 пʼятниць (дві забрав НС) = 30 год проти 40 за програмою', async () => {
    const ndeksm = row(await rowsOf(group4ki1.id), 'НДЕКСМ');
    expect(ndeksm).toMatchObject({ plan: 40, forecast: 30, status: 'WILL_UNDERRUN', forecastFinishDate: null });
    expect(ndeksm.suggestion).toMatchObject({ kind: 'ADDITIONAL', text: 'Не вистачає 10 год: додати 5 пар', lessons: 5 });
  });
  it('практика 2КІ3 (19–30.10) знімає пари групи — прогноз менший за план', async () => {
    const group2ki3 = GROUPS.find((g) => g.code === '2КІ3') as (typeof GROUPS)[number];
    const rows = await rowsOf(group2ki3.id);
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.every((r) => r.forecast < r.plan)).toBe(true);
  });

  it('рядки відсортовано за курсом і групою; без групи — увесь семестр', async () => {
    const rows = await rowsOf();
    expect(rows[0]?.group.course).toBe(1);
    expect(rows.at(-1)?.group.course).toBe(4);
    expect(new Set(rows.map((r) => r.group.id)).size).toBe(GROUPS.length);
  });
});

describe('GET /api/admin/accounting/teachers', () => {
  it('жовтень — заміни з розпорядження №35', async () => {
    const stats = await api.get<TeacherStatsDto[]>('/admin/accounting/teachers', { month: '2026-10' });
    expect(stats.map((s) => [s.teacher.shortName, s.substitutionsCount, s.substitutionsHours, s.missedCount])).toEqual([
      ['Непийвода', 1, 2, 0],
      ['Прокопчук', 1, 2, 0],
      ['Кренцін', 0, 0, 1],
      ['Крисак Л.В.', 0, 0, 2],
    ]);
  });

  it('місяць без замін — порожній список, неправильний формат — 400', async () => {
    expect(await api.get<TeacherStatsDto[]>('/admin/accounting/teachers', { month: '2026-09' })).toEqual([]);
    const error = await api.get('/admin/accounting/teachers', { month: '10.2026' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).fieldErrors()).toEqual({ month: 'Формат РРРР-ММ' });
  });
});
