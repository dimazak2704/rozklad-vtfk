import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, apiRequest } from '@/api/client';
import { ApiError } from '@/api/errors';
import type { ImportJob, TimetableDraft } from '@/api/import';
import type { AiUsageDto, AliasDto, ImportMapping, Page } from '@/api/types';
import { DISCIPLINES, GROUPS, SEMESTERS, TEACHERS } from '../fixtures';
import { resetMockStores } from '../store';

async function failure(request: Promise<unknown>): Promise<ApiError> {
  try {
    await request;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Запит мав завершитися помилкою');
}

const jobs = async () => (await api.get<Page<ImportJob>>('/import/jobs')).items;
const job = (id: string) => api.get<ImportJob>(`/import/jobs/${id}`);
/** Повне завдання розкладу (у списку importer звіт порожній, а чернетки немає). */
const timetableJob = async () => job(((await jobs()).find((j) => j.kind === 'TIMETABLE_XLSX') as ImportJob).id);
const teacherId = (shortName: string) => (TEACHERS.find((t) => t.shortName === shortName) as { id: string }).id;
const disciplineId = (shortName: string) => (DISCIPLINES.find((d) => d.shortName === shortName) as { id: string }).id;

/** Рішення для всіх рядків таблиці, що чекають: одруківки — псевдоніми, обрізане «Вс.» — нова дисципліна. */
const DECISIONS: ImportMapping[] = [
  { type: 'TEACHER', raw: 'Граболінська', targetId: teacherId('Гарболінська'), addAlias: true },
  { type: 'TEACHER', raw: 'Герасменко', targetId: teacherId('Герасименко'), addAlias: true },
  { type: 'DISCIPLINE', raw: 'Інфом.', targetId: disciplineId('Інф.'), addAlias: true },
  { type: 'DISCIPLINE', raw: 'Вс.спц.', targetId: disciplineId('Вст.спц.'), addAlias: false },
  { type: 'DISCIPLINE', raw: 'Вс.', createNew: {}, addAlias: false },
];

afterEach(() => {
  vi.useRealTimers();
});

describe('завдання імпорту', () => {
  it('список: сторінка, нові зверху, різні стани й витрати ШІ; без звіту й чернетки', async () => {
    const page = await api.get<Page<ImportJob>>('/import/jobs', { size: 2 });
    expect(page).toMatchObject({ page: 0, size: 2, total: 4 });
    expect(page.items).toHaveLength(2);
    const list = await jobs();
    expect(list.map((j) => [j.fileName, j.status])).toEqual([
      ['розпорядження-35.jpg', 'READY'],
      ['Розклад 2025-2026.xlsx', 'NEEDS_REVIEW'],
      ['Програма ЕУІТП.docx', 'READY'],
      ['навчальний-план-КІ.pdf', 'FAILED'],
    ]);
    expect(list[0]?.ai).toMatchObject({ model: 'claude-sonnet-5-5' });
    expect(list[3]?.error).toMatch(/^Денний бюджет ШІ \(\$2\.00\) вичерпано/);
    // як ImportJobDto у списку: порожній звіт, без draft/options/mappings/result; null-полів немає зовсім
    expect(list.every((j) => j.report.length === 0 && !('draft' in j) && !('options' in j) && !('result' in j))).toBe(true);
    expect(list[1]).not.toHaveProperty('error');
    expect(list[1]).toMatchObject({ fileType: 'XLSX', canApply: false });
    expect(list[0]).toMatchObject({ canApply: true });
  });

  it('звіт таблиці містить справжні одруківки й чекає 5 рішень', async () => {
    const timetable = await timetableJob();
    expect(timetable.summary.conflicts).toBe(5);
    const raws = timetable.report.map((r) => r.raw);
    expect(raws).toEqual(expect.arrayContaining(['Гарболінська', 'Граболінська', 'Герасменко', 'ІКІ1']));
    expect(timetable.report.find((r) => r.raw === 'ІКІ1')).toMatchObject({ severity: 'WARN', matched: { label: '1КІ1' } });
    const typo = timetable.report.find((r) => r.raw === 'Граболінська');
    expect(typo).toMatchObject({ severity: 'WARN', suggestions: [{ label: 'Гарболінська', score: 0.94 }] });
    // NON_NULL: збігу немає — поля matched теж немає
    expect(typo).not.toHaveProperty('matched');
    const draft = timetable.draft as TimetableDraft;
    expect(draft.groups).toHaveLength(GROUPS.length);
    expect(draft.groups.find((g) => g.code === '1КІ1')?.raw).toBe('ІКІ1');
    expect(draft.lessons.some((l) => l.teacher === 'Граболінська')).toBe(true);
  });

  it('рішення перенормалізують звіт; псевдонім для групи не дозволено', async () => {
    const timetable = await timetableJob();
    const invalid = await failure(
      api.put(`/import/jobs/${timetable.id}/mappings`, { mappings: [{ type: 'GROUP', raw: 'ІКІ1', targetId: GROUPS[0]?.id, addAlias: true }] }),
    );
    expect(invalid.fieldErrors()).toEqual({ 'mappings[0].addAlias': 'Псевдоніми зберігаються лише для викладачів і дисциплін.' });
    const partial = await api.put<ImportJob>(`/import/jobs/${timetable.id}/mappings`, { mappings: DECISIONS.slice(0, 2) });
    expect(partial).toMatchObject({ status: 'NEEDS_REVIEW', summary: { conflicts: 3 } });
    const ready = await api.put<ImportJob>(`/import/jobs/${timetable.id}/mappings`, { mappings: DECISIONS.slice(2) });
    expect(ready).toMatchObject({ status: 'READY', summary: { conflicts: 0 } });
    expect(ready.mappings).toHaveLength(5);
    expect(ready.report.find((r) => r.raw === 'Граболінська')).toMatchObject({ severity: 'OK', matched: { label: 'Гарболінська' } });
  });

  it('застосування: APPLYING → APPLIED за опитуваннями; написання стають псевдонімами, «Вс.» — новою дисципліною', async () => {
    const timetable = await timetableJob();
    const blocked = await failure(api.post(`/import/jobs/${timetable.id}/apply`));
    expect(blocked).toMatchObject({ status: 422, code: 'IMPORT_NEEDS_REVIEW' });
    await api.put(`/import/jobs/${timetable.id}/mappings`, { mappings: DECISIONS });
    const started = await api.post<ImportJob>(`/import/jobs/${timetable.id}/apply`);
    expect(started.status).toBe('APPLYING');
    expect((await job(timetable.id)).status).toBe('APPLYING');
    const applied = await job(timetable.id);
    expect(applied.status).toBe('APPLIED');
    // результат — відповіді core за кроками: каталог (новий запис + псевдоніми) і чернетка версії
    expect(applied.result?.catalog).toMatchObject({ created: 1, updated: 3 });
    expect(applied.result?.timetable).toMatchObject({ name: `Імпорт з «${timetable.fileName}»` });
    expect(applied.result?.timetable?.lessonCount).toBeGreaterThan(0);
    const aliases = await api.get<AliasDto[]>(`/admin/teachers/${teacherId('Гарболінська')}/aliases`);
    expect(aliases.map((a) => a.alias)).toContain('Граболінська');
    // повторне застосування ідемпотентне (202 з тим самим завданням), як у importer
    expect((await api.post<ImportJob>(`/import/jobs/${timetable.id}/apply`)).status).toBe('APPLIED');
  });
});

/**
 * multipart/form-data вручну: у jsdom FormData не серіалізується для fetch з Node (у браузері — так),
 * тому тіло складаємо самі. Назви файлів — латиницею, щоб не залежати від кодування заголовків частин.
 */
function multipart(fields: Record<string, string>, fileName: string): { body: string; headers: Record<string, string> } {
  const boundary = '----rozklad-test-boundary';
  const CRLF = '\r\n';
  const part = (headers: string[], content: string) => [`--${boundary}`, ...headers, '', content].join(CRLF) + CRLF;
  const body =
    Object.entries(fields)
      .map(([name, value]) => part([`Content-Disposition: form-data; name="${name}"`], value))
      .join('') +
    part([`Content-Disposition: form-data; name="file"; filename="${fileName}"`, 'Content-Type: application/octet-stream'], 'test') +
    `--${boundary}--${CRLF}`;
  return { body, headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` } };
}

describe('завантаження файлу', () => {
  // undici розбирає multipart у File з Node, а jsdom підміняє глобальний File своїм — повертаємо рідний
  // (модуль Node підтягуємо динамічно: у tsconfig застосунку немає типів node)
  beforeEach(async () => {
    const nodeBuffer = 'node:buffer';
    const { File: NodeFile } = (await import(/* @vite-ignore */ nodeBuffer)) as { File: typeof File };
    vi.stubGlobal('File', NodeFile);
  });

  const upload = (fileName: string, kind: string, extra: Record<string, string> = {}) => {
    const { body, headers } = multipart({ kind, ...extra }, fileName);
    return apiRequest<ImportJob>('/import/jobs', { method: 'POST', body, headers });
  };

  it('розклад: UPLOADED → PARSING → NEEDS_REVIEW з тим самим звітом', async () => {
    const created = await upload('timetable-new.xlsx', 'TIMETABLE_XLSX');
    expect(created.status).toBe('UPLOADED');
    expect((await job(created.id)).status).toBe('PARSING');
    expect((await job(created.id)).status).toBe('PARSING');
    const parsed = await job(created.id);
    expect(parsed).toMatchObject({ status: 'NEEDS_REVIEW', summary: { conflicts: 5 } });
    expect(parsed).not.toHaveProperty('ai');
  });

  it('завантажений розклад проходить увесь шлях до APPLIED', async () => {
    const created = await upload('timetable-flow.xlsx', 'TIMETABLE_XLSX', {
      semesterId: SEMESTERS.find((s) => s.active)?.id ?? '',
      options: JSON.stringify({ versionName: 'Розклад з листопада' }),
    });
    let current = created;
    for (let i = 0; i < 5 && current.status !== 'NEEDS_REVIEW'; i += 1) current = await job(created.id);
    expect(current.status).toBe('NEEDS_REVIEW');
    const decisions = DECISIONS.map((m) => (m.raw === 'Вс.спц.' ? { ...m, addAlias: true } : m));
    expect((await api.put<ImportJob>(`/import/jobs/${created.id}/mappings`, { mappings: decisions })).status).toBe('READY');
    expect((await api.post<ImportJob>(`/import/jobs/${created.id}/apply`)).status).toBe('APPLYING');
    for (let i = 0; i < 5 && current.status !== 'APPLIED'; i += 1) current = await job(created.id);
    expect(current).toMatchObject({
      status: 'APPLIED',
      result: { catalog: { created: 1, updated: 4 }, timetable: { name: 'Розклад з листопада' } },
    });
  });

  it('формат не для цього виду — 415; параметри — лише ключі importer; план без семестру — 400; програма без груп — 422 при застосуванні', async () => {
    const wrong = await failure(upload('rozklad.jpg', 'TIMETABLE_XLSX'));
    expect(wrong).toMatchObject({ status: 415, message: 'Для розкладу потрібна таблиця Excel (XLSX). Файл «rozklad.jpg» має інший формат.' });
    // старий ключ клієнта `groupIds` importer відхиляє
    const unknownKey = await failure(upload('program.docx', 'DISCIPLINE_PROGRAM', { options: JSON.stringify({ groupIds: ['x'] }) }));
    expect(Object.keys(unknownKey.fieldErrors())).toEqual(['options.groupIds']);
    const noSemester = await failure(upload('plan.pdf', 'CURRICULUM', { options: JSON.stringify({ groupCodes: ['4КІ1'] }) }));
    expect(Object.keys(noSemester.fieldErrors())).toEqual(['options.semesterNumber']);
    const program = await upload('program.docx', 'DISCIPLINE_PROGRAM', { semesterId: SEMESTERS.find((s) => s.active)?.id ?? '' });
    let current = program;
    for (let i = 0; i < 5 && current.status !== 'READY'; i += 1) current = await job(program.id);
    const noGroups = await failure(api.post(`/import/jobs/${program.id}/apply`));
    expect(noGroups).toMatchObject({ status: 422, code: 'GROUPS_REQUIRED' });
  });

  it('фото розпорядження розпізнає модель зору й рахує витрати дня', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-08T10:00:00Z'));
    resetMockStores();
    const before = await api.get<AiUsageDto>('/import/ai/usage');
    expect(before).toMatchObject({ today: { calls: 1 }, dailyBudgetUsd: 2, enabled: true, model: { text: 'claude-haiku-5-5', vision: 'claude-sonnet-5-5' } });
    expect(before.remainingUsd).toBeCloseTo(2 - before.today.costUsd, 4);
    const created = await upload('order-36.jpg', 'ORDER_DOCUMENT');
    for (let i = 0; i < 3; i += 1) await job(created.id);
    const parsed = await job(created.id);
    expect(parsed).toMatchObject({ status: 'READY', ai: { model: 'claude-sonnet-5-5', inputTokens: 3850, outputTokens: 1240 } });
    const after = await api.get<AiUsageDto>('/import/ai/usage');
    expect(after.today.calls).toBe(2);
    expect(after.today.costUsd).toBeCloseTo(before.today.costUsd * 2, 3);
  });
});
