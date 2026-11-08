import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import { appendProgressPoint, DEFAULT_WEIGHTS, parseScore } from '@/api/generation';
import type { GenerationJobCreated, GenerationJobDto, TemplateLessonDto, TimetableVersionDto } from '@/api/types';
import { ACTIVE_SEMESTER, GROUPS, PUBLISHED_VERSION_ID, TEMPLATE_LESSONS } from '../fixtures';
import { solveTimetable } from './generation';
import { analyzeLessons } from './timetable';

const jobsPath = '/admin/generation/jobs';

const softOf = (lessons: readonly TemplateLessonDto[]) => {
  const m = analyzeLessons(lessons).metrics;
  const w = DEFAULT_WEIGHTS;
  return -(w.windows * (m.groupWindows + m.teacherWindows) + w.brokenDays * m.brokenDays + w.latePairs * m.latePairs);
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
  const start = (body: object = {}) =>
    api.post<GenerationJobCreated>(jobsPath, { semesterId: ACTIVE_SEMESTER.id, scope: {}, baseVersionId: PUBLISHED_VERSION_ID, timeLimitSeconds: 10, ...body });

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
    expect(done.metricsAfter?.teacherWindows).toBeLessThan(180);
    expect(done.analysis?.find((a) => a.constraint === 'Аудиторія зайнята')).toMatchObject({ matchCount: 0 });
    const versions = await api.get<TimetableVersionDto[]>('/admin/timetable/versions', { semesterId: ACTIVE_SEMESTER.id });
    expect(versions.find((v) => v.id === done.resultVersionId)).toMatchObject({ status: 'DRAFT', source: 'GENERATED', hardConflicts: 0 });
  });

  it('другий запуск, поки перший іде, — 409; скасування зупиняє задачу', async () => {
    const { jobId } = await start();
    const second = await failure(start());
    expect(second.status).toBe(409);
    await api.post(`${jobsPath}/${jobId}/cancel`);
    const cancelled = await api.get<GenerationJobDto>(`${jobsPath}/${jobId}`);
    expect(cancelled.status).toBe('CANCELLED');
    expect(cancelled.resultVersionId).toBeNull();
    const again = await failure(api.post(`${jobsPath}/${jobId}/cancel`));
    expect(again.status).toBe(409);
  });

  it('параметри перевіряються як на сервері — 400 з полями', async () => {
    const error = await failure(start({ timeLimitSeconds: 5, scope: { courses: [7] }, weights: { ...DEFAULT_WEIGHTS, windows: -1 } }));
    expect(error.status).toBe(400);
    expect(error.fieldErrors()).toMatchObject({
      timeLimitSeconds: 'Ліміт часу — від 10 до 600 секунд',
      'scope.courses': 'Курс — від 1 до 4',
      'weights.windows': 'Вага — від 0 до 100',
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
