import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, apiRequest } from '@/api/client';
import { ApiError } from '@/api/errors';
import type { ImportJob, TimetableDraft } from '@/api/import';
import type { AiUsageDto, AliasDto, ImportMapping } from '@/api/types';
import { DISCIPLINES, GROUPS, TEACHERS } from '../fixtures';
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

const jobs = () => api.get<ImportJob[]>('/import/jobs');
const job = (id: string) => api.get<ImportJob>(`/import/jobs/${id}`);
const timetableJob = async () => (await jobs()).find((j) => j.kind === 'TIMETABLE_XLSX') as ImportJob;
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
  it('список: нові зверху, різні стани й витрати ШІ', async () => {
    const list = await jobs();
    expect(list.map((j) => [j.fileName, j.status])).toEqual([
      ['розпорядження-35.jpg', 'READY'],
      ['Розклад 2025-2026.xlsx', 'NEEDS_REVIEW'],
      ['Програма ЕУІТП.docx', 'READY'],
      ['навчальний-план-КІ.pdf', 'FAILED'],
    ]);
    expect(list[0]?.ai).toMatchObject({ model: 'claude-sonnet-5-5' });
    expect(list[3]?.error).toMatch(/^Денний бюджет ШІ \(\$2\.00\) вичерпано/);
  });

  it('звіт таблиці містить справжні одруківки й чекає 5 рішень', async () => {
    const timetable = await timetableJob();
    expect(timetable.summary.conflicts).toBe(5);
    const raws = timetable.report.map((r) => r.raw);
    expect(raws).toEqual(expect.arrayContaining(['Гарболінська', 'Граболінська', 'Герасменко', 'ІКІ1']));
    expect(timetable.report.find((r) => r.raw === 'ІКІ1')).toMatchObject({ severity: 'WARN', matched: { label: '1КІ1' } });
    expect(timetable.report.find((r) => r.raw === 'Граболінська')).toMatchObject({
      severity: 'WARN',
      matched: null,
      suggestions: [{ label: 'Гарболінська', score: 0.94 }],
    });
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
    expect((await failure(api.post(`/import/jobs/${timetable.id}/apply`))).message).toBe('Спершу вирішіть рядки, що чекають рішення: 5.');
    await api.put(`/import/jobs/${timetable.id}/mappings`, { mappings: DECISIONS });
    const started = await api.post<ImportJob>(`/import/jobs/${timetable.id}/apply`);
    expect(started.status).toBe('APPLYING');
    expect((await job(timetable.id)).status).toBe('APPLYING');
    const applied = await job(timetable.id);
    expect(applied.status).toBe('APPLIED');
    expect(applied.result).toMatchObject({ created: 1, aliases: 3 });
    expect(applied.result?.message).toMatch(/^Створено чернетку версії розкладу/);
    const aliases = await api.get<AliasDto[]>(`/admin/teachers/${teacherId('Гарболінська')}/aliases`);
    expect(aliases.map((a) => a.alias)).toContain('Граболінська');
    expect((await failure(api.post(`/import/jobs/${timetable.id}/apply`))).message).toBe('Цей імпорт уже застосовано.');
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
    expect(parsed).toMatchObject({ status: 'NEEDS_REVIEW', summary: { conflicts: 5 }, ai: null });
  });

  it('завантажений розклад проходить увесь шлях до APPLIED', async () => {
    const created = await upload('timetable-flow.xlsx', 'TIMETABLE_XLSX');
    let current = created;
    for (let i = 0; i < 5 && current.status !== 'NEEDS_REVIEW'; i += 1) current = await job(created.id);
    expect(current.status).toBe('NEEDS_REVIEW');
    const decisions = DECISIONS.map((m) => (m.raw === 'Вс.спц.' ? { ...m, addAlias: true } : m));
    expect((await api.put<ImportJob>(`/import/jobs/${created.id}/mappings`, { mappings: decisions })).status).toBe('READY');
    expect((await api.post<ImportJob>(`/import/jobs/${created.id}/apply`)).status).toBe('APPLYING');
    for (let i = 0; i < 5 && current.status !== 'APPLIED'; i += 1) current = await job(created.id);
    expect(current).toMatchObject({ status: 'APPLIED', result: { created: 1, aliases: 4 } });
  });

  it('формат не для цього виду — 415, програма без груп — 400', async () => {
    const wrong = await failure(upload('rozklad.jpg', 'TIMETABLE_XLSX'));
    expect(wrong).toMatchObject({ status: 415, message: 'Для розкладу потрібна таблиця Excel (XLSX). Файл «rozklad.jpg» має інший формат.' });
    const noGroups = await failure(upload('program.docx', 'DISCIPLINE_PROGRAM'));
    expect(noGroups.fieldErrors()).toEqual({ 'options.groupIds': 'Вкажіть групи, яким читається дисципліна за цим документом.' });
  });

  it('фото розпорядження розпізнає модель зору й рахує витрати дня', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-08T10:00:00Z'));
    resetMockStores();
    const before = await api.get<AiUsageDto>('/import/ai/usage');
    expect(before).toMatchObject({ today: { calls: 1 }, dailyBudgetUsd: 2, model: { text: 'claude-haiku-5-5', vision: 'claude-sonnet-5-5' } });
    const created = await upload('order-36.jpg', 'ORDER_DOCUMENT');
    for (let i = 0; i < 3; i += 1) await job(created.id);
    const parsed = await job(created.id);
    expect(parsed).toMatchObject({ status: 'READY', ai: { model: 'claude-sonnet-5-5', inputTokens: 3850, outputTokens: 1240 } });
    const after = await api.get<AiUsageDto>('/import/ai/usage');
    expect(after.today.calls).toBe(2);
    expect(after.today.costUsd).toBeCloseTo(before.today.costUsd * 2, 3);
  });
});
