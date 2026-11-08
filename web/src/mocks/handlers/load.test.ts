import { describe, expect, it } from 'vitest';
import type { CalendarSemester } from '@/api/calendar';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import type { DerivedLoadResult, LoadItem } from '@/api/load';
import type { TimetableVersionDto } from '@/api/types';
import { DISCIPLINES, GROUPS, PUBLISHED_VERSION_ID, TEACHERS } from '../fixtures';

async function failure(request: Promise<unknown>): Promise<ApiError> {
  try {
    await request;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Запит мав завершитися помилкою');
}

const group = (code: string) => GROUPS.find((g) => g.code === code) as (typeof GROUPS)[number];
const discipline = (shortName: string) => DISCIPLINES.find((d) => d.shortName === shortName) as (typeof DISCIPLINES)[number];
const semester = async () => (await api.get<CalendarSemester[]>('/admin/semesters')).find((s) => s.active) as CalendarSemester;
const loadOf = async (code: string) => api.get<LoadItem[]>('/admin/load', { semesterId: (await semester()).id, groupId: group(code).id });

describe('GET /api/admin/load', () => {
  it('без семестру — 400', async () => {
    expect((await failure(api.get('/admin/load'))).fieldErrors()).toEqual({ semesterId: 'Обовʼязковий параметр' });
  });

  it('навантаження 4КІ1 виведене з розкладу: ЕУ 3 год → 1 + ½, план з програми — для НДЕКСМ', async () => {
    const items = await loadOf('4КІ1');
    const eu = items.find((i) => i.discipline.shortName === 'ЕУ');
    expect(eu).toMatchObject({ hoursPerWeek: 3, pattern: { whole: 1, half: 1 }, plannedHours: 54, source: 'TIMETABLE_DERIVED', lessonKind: 'MIXED' });
    expect(eu?.teachers.map((t) => t.teacher.shortName)).toEqual(['Алексєєнко']);
    expect(items.find((i) => i.discipline.shortName === 'НДЕКСМ')).toMatchObject({ hoursPerWeek: 2, plannedHours: 40, source: 'PROGRAM' });
    const english = items.filter((i) => i.discipline.shortName === 'Ін.мова');
    expect(english.find((i) => i.splitSubgroups)?.teachers.map((t) => [t.subgroup, t.teacher.shortName])).toEqual([
      [1, 'Прокопчук'],
      [2, 'Гарболінська'],
    ]);
  });
});

describe('POST/PUT /api/admin/load', () => {
  it('ділити на підгрупи можна лише поділену групу; дубль — 409', async () => {
    const unsplit = GROUPS.find((g) => (g.subgroups ?? []).length === 0) as (typeof GROUPS)[number];
    const { id: semesterId } = await semester();
    const error = await failure(
      api.post('/admin/load', { semesterId, groupId: unsplit.id, disciplineId: discipline('СТП').id, hoursPerWeek: 2, splitSubgroups: true }),
    );
    expect(error.fieldErrors().splitSubgroups).toBe(`Група ${unsplit.code} не поділена на підгрупи: спершу додайте підгрупи в каталозі.`);
    const duplicate = await failure(
      api.post('/admin/load', { semesterId, groupId: group('4КІ1').id, disciplineId: discipline('СТП').id, hoursPerWeek: 4, lessonKind: 'MIXED' }),
    );
    expect(duplicate.message).toBe('У групи 4КІ1 уже є таке навантаження з цієї дисципліни.');
  });

  it('години — з одним знаком після коми й до 40', async () => {
    const { id: semesterId } = await semester();
    const body = { semesterId, groupId: group('4КІ1').id, disciplineId: discipline('КБ').id, lessonKind: 'LAB' };
    expect((await failure(api.post('/admin/load', { ...body, hoursPerWeek: 1.25 }))).fieldErrors().hoursPerWeek).toBe(
      'Години — число з одним знаком після коми, наприклад 1.5',
    );
    expect((await failure(api.post('/admin/load', { ...body, hoursPerWeek: 41 }))).fieldErrors().hoursPerWeek).toBe('Не більше 40 годин на тиждень');
  });

  it('новий запис — ручний, план за замовчуванням = години × тижні; правка виведеного робить його ручним', async () => {
    const { id: semesterId } = await semester();
    const teacher = TEACHERS.find((t) => t.shortName === 'Непийвода') as (typeof TEACHERS)[number];
    const created = await api.post<LoadItem>('/admin/load', {
      semesterId,
      groupId: group('4КІ1').id,
      disciplineId: discipline('КБ').id,
      lessonKind: 'LAB',
      hoursPerWeek: 1.5,
      teachers: [{ subgroup: 0, teacherId: teacher.id }],
      controlForm: 'CREDIT',
    });
    expect(created).toMatchObject({ source: 'MANUAL', plannedHours: 27, pattern: { whole: 1, half: 0 }, controlForm: 'CREDIT' });
    const eu = (await loadOf('4КІ1')).find((i) => i.discipline.shortName === 'ЕУ') as LoadItem;
    const edited = await api.put<LoadItem>(`/admin/load/${eu.id}`, {
      semesterId,
      groupId: eu.group.id,
      disciplineId: eu.discipline.id,
      hoursPerWeek: 4,
      version: eu.version,
    });
    expect(edited).toMatchObject({ source: 'MANUAL', hoursPerWeek: 4, pattern: { whole: 2, half: 0 }, version: eu.version + 1 });
  });
});

describe('Вивести з розкладу', () => {
  it('є опублікована версія семестру', async () => {
    const versions = await api.get<TimetableVersionDto[]>('/admin/timetable/versions', { semesterId: (await semester()).id });
    expect(versions).toEqual([expect.objectContaining({ id: PUBLISHED_VERSION_ID, status: 'PUBLISHED' })]);
  });

  it('оновлює виведені записи й не чіпає внесені вручну чи з програми', async () => {
    const eu = (await loadOf('4КІ1')).find((i) => i.discipline.shortName === 'ЕУ') as LoadItem;
    await api.delete(`/admin/load/${eu.id}`);
    const result = await api.post<DerivedLoadResult>(`/admin/load/derive-from-version/${PUBLISHED_VERSION_ID}`);
    expect(result).toMatchObject({ versionId: PUBLISHED_VERSION_ID, created: 1, updated: 0, removed: 0 });
    expect(result.skipped.map((s) => `${s.group.code} ${s.discipline.shortName}`)).toEqual(
      expect.arrayContaining(['4КІ1 НДЕКСМ', '4КІ1 СТП', '4КІ1 ОПЦЗ']),
    );
    expect(result.skipped[0]?.reason).toBe('Навантаження вже внесено вручну або з плану — його не перезаписано.');
    expect((await failure(api.post('/admin/load/derive-from-version/невідома'))).status).toBe(404);
  });
});
