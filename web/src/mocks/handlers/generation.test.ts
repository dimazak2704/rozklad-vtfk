import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import { appendProgressPoint, DEFAULT_WEIGHTS, parseScore } from '@/api/generation';
import type { GenerationJobCreated, GenerationJobDto, TemplateLessonDto, TimetableVersionDto } from '@/api/types';
import { ACTIVE_SEMESTER, GROUPS, PUBLISHED_VERSION_ID, TEMPLATE_LESSONS } from '../fixtures';
import { loadProblems, solveTimetable } from './generation';
import { analyzeLessons } from './timetable';

const jobsPath = '/admin/generation/jobs';

const softOf = (lessons: readonly TemplateLessonDto[]) => {
  const m = analyzeLessons(lessons).metrics;
  const w = DEFAULT_WEIGHTS;
  return -(w.windows * m.groupWindows + w.teacherWindows * m.teacherWindows + w.brokenDays * m.brokenDays + w.latePairs * m.latePairs);
};

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Очікували помилку API');
}

/** Чекає, доки розвʼязок порахується поза запитом (setTimeout 0). */
const solved = () => new Promise((resolve) => setTimeout(resolve, 50));

afterEach(() => {
  vi.useRealTimers();
});

describe('розвʼязувач моку', () => {
  const result = solveTimetable({ lessons: TEMPLATE_LESSONS, scopeGroupIds: null, weights: { ...DEFAULT_WEIGHTS } });

  it('прибирає всі жорсткі конфлікти даних з Excel і зменшує штраф', () => {
    expect(result.trajectory[0]).toEqual({ hard: -75, soft: softOf(TEMPLATE_LESSONS) });
    expect(result.trajectory.at(-1)?.hard).toBe(0);
    expect(analyzeLessons(result.lessons).hard).toHaveLength(0);
    expect(result.trajectory.at(-1)?.soft).toBeGreaterThan(softOf(TEMPLATE_LESSONS));
  });

  it('оцінка, яку він веде, збігається з метриками результату', () => {
    expect(result.trajectory.at(-1)?.soft).toBe(softOf(result.lessons));
  });

  it('пари поза областю й закріплені не рухає', () => {
    const fourth = new Set(GROUPS.filter((g) => g.course === 4).map((g) => g.id));
    const pinnedLesson = TEMPLATE_LESSONS.find((l) => fourth.has(l.group.id)) as TemplateLessonDto;
    const lessons = TEMPLATE_LESSONS.map((l) => (l.id === pinnedLesson.id ? { ...l, pinned: true } : l));
    const scoped = solveTimetable({ lessons, scopeGroupIds: fourth, weights: { ...DEFAULT_WEIGHTS } });
    const byId = new Map(lessons.map((l) => [l.id, l]));
    for (const lesson of scoped.lessons) {
      if (fourth.has(lesson.group.id) && lesson.id !== pinnedLesson.id) continue;
      expect(lesson).toEqual(byId.get(lesson.id));
    }
  });
});

describe('задачі генерації', () => {
  // без ліміту часу: як вебзастосунок, задача зупиняється сама
  const start = (body: object = {}) =>
    api.post<GenerationJobCreated>(jobsPath, { semesterId: ACTIVE_SEMESTER.id, scope: {}, baseVersionId: PUBLISHED_VERSION_ID, ...body });
  const get = (jobId: string) => api.get<GenerationJobDto>(`${jobsPath}/${jobId}`);

  it('проходить шлях у черзі → розвʼязується → готово з «Було / Стало» і новою чернеткою', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const t0 = new Date('2026-11-08T10:00:00Z').getTime();
    vi.setSystemTime(t0);
    const { jobId } = await start();
    expect((await api.get<GenerationJobDto>(`${jobsPath}/${jobId}`)).status).toBe('QUEUED');

    await solved();
    vi.setSystemTime(t0 + 5_000);
    const running = await api.get<GenerationJobDto>(`${jobsPath}/${jobId}`);
    expect(running.status).toBe('RUNNING');
    expect(running.progress.length).toBeGreaterThan(1);
    expect(running.metricsBefore?.brokenDays).toBe(34);

    vi.setSystemTime(t0 + 15_000);
    const done = await api.get<GenerationJobDto>(`${jobsPath}/${jobId}`);
    expect(done).toMatchObject({ status: 'SOLVED', bestScore: expect.stringMatching(/^0hard\/-\d+soft$/) });
    // без ліміту: зупинилась сама після плато, точки прогресу — лише покращення
    expect(done).toMatchObject({ timeLimitSeconds: null, stopReason: 'NO_IMPROVEMENT', stopRequested: false });
    expect(done.progress.at(-1)?.t).toBeLessThan(12);
    expect(done.metricsAfter?.teacherWindows).toBeLessThan(180);
    expect(done.analysis?.find((a) => a.constraint === 'Аудиторія зайнята')).toMatchObject({ matchCount: 0 });
    const versions = await api.get<TimetableVersionDto[]>('/admin/timetable/versions', { semesterId: ACTIVE_SEMESTER.id });
    expect(versions.find((v) => v.id === done.resultVersionId)).toMatchObject({ status: 'DRAFT', source: 'GENERATED', hardConflicts: 0 });
  });

  it('другий запуск, поки перший іде, — 409; задачу в черзі скасовано без чернетки', async () => {
    const { jobId } = await start();
    const second = await failure(start());
    expect(second.status).toBe(409);
    await api.post(`${jobsPath}/${jobId}/cancel`);
    const cancelled = await get(jobId);
    expect(cancelled.status).toBe('CANCELLED');
    expect(cancelled.resultVersionId).toBeNull();
    // як core: повторне скасування — та сама задача
    expect((await api.post<GenerationJobDto>(`${jobsPath}/${jobId}/cancel`)).status).toBe('CANCELLED');
  });

  it('«Зупинити» задачу, що розвʼязується, — найкращий варіант стає чернеткою (STOPPED)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const t0 = new Date('2026-11-08T10:00:00Z').getTime();
    vi.setSystemTime(t0);
    const { jobId } = await start();
    await solved();
    vi.setSystemTime(t0 + 4_000);
    const stopping = await api.post<GenerationJobDto>(`${jobsPath}/${jobId}/cancel`);
    expect(stopping).toMatchObject({ status: 'RUNNING', stopRequested: true });

    vi.setSystemTime(t0 + 6_000);
    const stopped = await get(jobId);
    expect(stopped).toMatchObject({ status: 'SOLVED', stopReason: 'STOPPED', stopRequested: true });
    expect(stopped.resultVersionId).not.toBeNull();
    expect(stopped.progress.at(-1)?.t).toBeLessThanOrEqual(4);
    // зупинку не можна «повторити» в 409; семестр вільний для нового запуску
    expect((await api.post<GenerationJobDto>(`${jobsPath}/${jobId}/cancel`)).status).toBe('SOLVED');
    await expect(start()).resolves.toMatchObject({ jobId: expect.any(String) as unknown });
  });

  it('старий клієнт із лімітом часу: задача зупиняється на ліміті (TIME_LIMIT)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const t0 = new Date('2026-11-08T10:00:00Z').getTime();
    vi.setSystemTime(t0);
    const { jobId } = await start({ timeLimitSeconds: 10 });
    await solved();
    vi.setSystemTime(t0 + 15_000);
    expect(await get(jobId)).toMatchObject({ status: 'SOLVED', timeLimitSeconds: 10, stopReason: 'TIME_LIMIT' });
  });

  it('задача несе власні параметри: область, групи, усі 14 ваг, бали й скільки пар переставлено (genfix B8)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const t0 = new Date('2026-11-08T10:00:00Z').getTime();
    vi.setSystemTime(t0);
    // старий клієнт надсилає лише пʼять ваг: teacherWindows = windows, решта — типові, як у core
    const { jobId } = await start({ scope: { courses: [4] }, weights: { windows: 20, brokenDays: 10, latePairs: 3, preferences: 5, homeRoom: 1 } });
    await solved();
    vi.setSystemTime(t0 + 15_000);
    const done = await api.get<GenerationJobDto>(`${jobsPath}/${jobId}`);
    const fourth = GROUPS.filter((g) => g.course === 4);
    expect(done.scope).toEqual({ groupIds: [], courses: [4] });
    expect(done.groups?.map((g) => g.id).sort()).toEqual(fourth.map((g) => g.id).sort());
    expect(done.weights).toEqual({ ...DEFAULT_WEIGHTS, windows: 20, latePairs: 3, teacherWindows: 20 });
    // поза областю конфлікти (спортзал інших курсів) лишаються: hardScore чесно < 0 — «Готово з конфліктами»
    expect(done.hardScore).toBe(parseScore(done.bestScore)?.hard);
    expect(done.softScore).toBe(parseScore(done.bestScore)?.soft);
    expect(done.metricsBefore?.hardConflicts).toBe(75);
    expect(done.outcome?.total).toBeGreaterThan(0);
    expect(done.outcome?.perGroup).toHaveLength(fourth.length);
    expect(done.outcome?.moved).toBe(done.outcome?.perGroup.reduce((sum, row) => sum + row.moved, 0));
    // аналіз: бонус позначено, порушення поза областю не входять у scopeMatchCount
    const halves = done.analysis?.find((a) => a.constraintId === 'Halves paired');
    expect(halves).toMatchObject({ bonus: true });
    const broken = done.analysis?.find((a) => a.constraintId === 'Broken day');
    expect(broken?.scopeMatchCount).toBeLessThanOrEqual(broken?.matchCount ?? 0);
    // «Що лишилось» має рядки нових правил, які таблиця метрик теж показує
    for (const id of ['Subgroup alone', 'Group one pair day', 'Teacher shift wait', 'Teacher one pair day']) {
      expect(done.analysis?.find((a) => a.constraintId === id), id).toMatchObject({ bonus: false });
    }
    const lone = done.analysis?.find((a) => a.constraintId === 'Subgroup alone');
    expect(lone?.matchCount).toBe(done.metricsAfter?.loneSubgroupSlots);
  });

  it('навантаження, що не вміщується в зміну групи, — 422 GENERATION_LOAD_EXCEEDS_SLOTS зі списком (genfix B9)', () => {
    const group = GROUPS.find((g) => TEMPLATE_LESSONS.some((l) => l.group.id === g.id)) as (typeof GROUPS)[number];
    const sample = TEMPLATE_LESSONS.find((l) => l.group.id === group.id) as TemplateLessonDto;
    const extra = Array.from({ length: 25 }, (_, i) => ({ ...sample, id: `extra-${i}`, subgroup: 0, parity: 'BOTH' as const }));
    const problems = loadProblems([...TEMPLATE_LESSONS, ...extra]);
    // група з підгрупами — рядок на кожну підгрупу («4КІ1, підгрупа 1»), без підгруп — один
    expect(problems.length).toBeGreaterThan(0);
    for (const problem of problems) {
      expect(problem).toMatchObject({ kind: 'GROUP', id: group.id });
      expect(problem.label.startsWith(group.code)).toBe(true);
      expect(problem.requiredPairs).toBeGreaterThan(problem.availablePairs);
      expect(problem.message).toMatch(/^Група .+: .+ пари на тиждень, а в її зміні \d+ слотів\.$/);
    }
    expect(loadProblems(TEMPLATE_LESSONS)).toEqual([]);
  });

  it('параметри перевіряються як на сервері — 400 з полями', async () => {
    const error = await failure(start({ timeLimitSeconds: 5, scope: { courses: [7] }, weights: { ...DEFAULT_WEIGHTS, windows: -1 } }));
    expect(error.status).toBe(400);
    expect(error.fieldErrors()).toMatchObject({
      timeLimitSeconds: 'Ліміт часу — від 10 до 600 секунд',
      'scope.courses': 'Курс — від 1 до 4',
      'weights.windows': 'Вага — від 0 до 1000',
    });
  });
});

describe('оцінки й прогрес', () => {
  it('рядок Timefold розбирається, чужий формат — null', () => {
    expect(parseScore('0hard/-312soft')).toEqual({ hard: 0, soft: -312 });
    expect(parseScore('-3hard/0soft')).toEqual({ hard: -3, soft: 0 });
    expect(parseScore('0hard/0medium/-1soft')).toBeNull();
    expect(parseScore(null)).toBeNull();
  });

  it('точка з SSE, що прийшла вже з опитуванням, не дублюється', () => {
    const progress = [{ t: 0, hard: -5, soft: -100 }, { t: 2, hard: -1, soft: -90 }];
    expect(appendProgressPoint(progress, { t: 2, hard: -1, soft: -90 })).toHaveLength(2);
    expect(appendProgressPoint(progress, { t: 4, hard: 0, soft: -80 })).toHaveLength(3);
  });
});
