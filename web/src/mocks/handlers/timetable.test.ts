import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import { ApiError } from '@/api/errors';
import { previewMove, type LessonMutationResult, type TimetableConflictsDto } from '@/api/timetable';
import type { PublishVersionResponse, TemplateLessonDto, TimetableVersionDto, VersionDiffDto } from '@/api/types';
import { ACTIVE_SEMESTER, PUBLISHED_VERSION_ID, TEMPLATE_LESSONS } from '../fixtures';
import { analyzeLessons, DEMO_DRAFT_ID, diffLessons } from './timetable';

const versionsPath = '/admin/timetable/versions';

async function draftLessons(): Promise<TemplateLessonDto[]> {
  return api.get<TemplateLessonDto[]>(`${versionsPath}/${DEMO_DRAFT_ID}/lessons`);
}

function find(lessons: readonly TemplateLessonDto[], code: string, weekday: number, pair: number, discipline: string): TemplateLessonDto {
  const lesson = lessons.find((l) => l.group.code === code && l.weekday === weekday && l.pair === pair && l.discipline.shortName === discipline);
  if (!lesson) throw new Error(`немає ${code} ${discipline} ${weekday}/${pair}`);
  return lesson;
}

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Очікували помилку API');
}

afterEach(() => {
  vi.useRealTimers();
});

describe('правила конфліктів і метрики (DOMAIN §5)', () => {
  it('на даних з Excel: накладки аудиторій і двох викладачів, вікон у груп немає', () => {
    const { hard, metrics } = analyzeLessons(TEMPLATE_LESSONS);
    expect(hard.filter((c) => c.kind === 'ROOM')).toHaveLength(73);
    expect(hard.filter((c) => c.kind === 'TEACHER')).toHaveLength(2);
    expect(metrics).toMatchObject({ groupWindows: 0, teacherWindows: 180, brokenDays: 34, latePairs: 106, maxPairsPerDay: 4 });
    expect(metrics.perGroup).toHaveLength(56);
  });

  it('дві половинки різних дисциплін в одному слоті роблять дні 4КІ1 рівними', async () => {
    const broken4ki1 = (lessons: readonly TemplateLessonDto[]) =>
      analyzeLessons(lessons).soft.filter((c) => c.kind === 'BROKEN_DAY' && c.message.startsWith('4КІ1')).map((c) => c.message.replace(/\u00a0/g, ' ')); // у текстах — нерозривні пробіли
    expect(broken4ki1(TEMPLATE_LESSONS)).toEqual([
      '4КІ1: рваний день — середа: у чисельник 3 пари з 1-ї, у знаменник 2 пари з 2-ї',
      '4КІ1: рваний день — четвер: у чисельник 3 пари з 2-ї, у знаменник 2 пари з 3-ї',
    ]);
    expect(broken4ki1(await draftLessons())).toEqual([]);
  });

  it('потік (той самий викладач, дисципліна й аудиторія) — не накладка', () => {
    const [a] = TEMPLATE_LESSONS;
    if (!a) throw new Error('немає даних');
    const twin: TemplateLessonDto = { ...a, id: 'twin', group: { id: 'g2', code: '9ZZ', course: 4 }, streamId: 'stream-1' };
    const stream = analyzeLessons([{ ...a, streamId: 'stream-1' }, twin]).hard;
    const clash = analyzeLessons([a, { ...twin, streamId: null }]).hard;
    expect(stream.some((c) => c.kind === 'TEACHER')).toBe(false);
    expect(clash.map((c) => c.kind)).toEqual(expect.arrayContaining(['TEACHER', 'ROOM']));
  });

  it('порівняння впізнає ту саму пару в іншому слоті як переміщення', () => {
    const [a, b] = TEMPLATE_LESSONS;
    if (!a || !b) throw new Error('немає даних');
    const moved = { ...a, id: 'copy-a', pair: a.pair + 1 };
    const diff = diffLessons([a, b], [moved, { ...b, id: 'copy-b' }]);
    expect(diff.moved).toEqual([{ from: a, to: moved }]);
    expect(diff.added).toEqual([]);
    expect(diff.removed).toEqual([]);
  });
});

describe('версії розкладу', () => {
  it('список: чернетка спершу, з лічильниками конфліктів і метриками', async () => {
    const versions = await api.get<TimetableVersionDto[]>(versionsPath, { semesterId: ACTIVE_SEMESTER.id });
    expect(versions.map((v) => [v.id, v.status])).toEqual([
      [DEMO_DRAFT_ID, 'DRAFT'],
      [PUBLISHED_VERSION_ID, 'PUBLISHED'],
    ]);
    expect(versions[1]).toMatchObject({ lessonCount: 1077, hardConflicts: 75, effectiveFrom: '2026-09-01' });
    expect(versions[0]?.metrics?.brokenDays).toBe(32);
  });

  it('копія версії — нова чернетка з тими самими парами під новими id', async () => {
    const created = await api.post<TimetableVersionDto>(versionsPath, {
      semesterId: ACTIVE_SEMESTER.id,
      name: 'Копія для перевірки',
      copyFromVersionId: PUBLISHED_VERSION_ID,
    });
    expect(created).toMatchObject({ status: 'DRAFT', source: 'COPY', lessonCount: 1077 });
    const lessons = await api.get<TemplateLessonDto[]>(`${versionsPath}/${created.id}/lessons`);
    expect(lessons.some((l) => TEMPLATE_LESSONS.some((t) => t.id === l.id))).toBe(false);
  });

  it('порожня назва і дубль назви — 400 з помилкою поля', async () => {
    const empty = await failure(api.post(versionsPath, { semesterId: ACTIVE_SEMESTER.id, name: '  ' }));
    expect(empty.status).toBe(400);
    expect(empty.fieldErrors()).toMatchObject({ name: 'Вкажіть назву версії' });
    const duplicate = await failure(api.post(versionsPath, { semesterId: ACTIVE_SEMESTER.id, name: 'Чернетка: рівні дні 4КІ1' }));
    expect(duplicate.fieldErrors()).toMatchObject({ name: 'Така назва вже є в цьому семестрі' });
  });

  it('порівняння чернетки з опублікованою: одна переміщена половинка ЕУ', async () => {
    const diff = await api.get<VersionDiffDto>(`${versionsPath}/${PUBLISHED_VERSION_ID}/diff/${DEMO_DRAFT_ID}`);
    expect(diff.added).toEqual([]);
    expect(diff.removed).toEqual([]);
    expect(diff.moved).toHaveLength(1);
    expect(diff.moved[0]?.from).toMatchObject({ weekday: 4, pair: 2, parity: 'NUMERATOR', discipline: { shortName: 'ЕУ' } });
    expect(diff.moved[0]?.to).toMatchObject({ weekday: 3, pair: 1, parity: 'DENOMINATOR' });
  });
});

describe('переміщення пари', () => {
  it('dryRun у вільний слот — 200 без збереження', async () => {
    const lessons = await draftLessons();
    const stp = find(lessons, '4КІ3', 2, 4, 'СТП');
    const preview = await previewMove(DEMO_DRAFT_ID, stp.id, { weekday: 3, pair: 4, parity: 'BOTH' });
    expect(preview.status).toBe('ok');
    expect(find(await draftLessons(), '4КІ3', 2, 4, 'СТП').id).toBe(stp.id);
  });

  it('слот, де викладач уже веде іншу групу, — 409 з поясненням', async () => {
    const stp = find(await draftLessons(), '4КІ3', 2, 4, 'СТП');
    const preview = await previewMove(DEMO_DRAFT_ID, stp.id, { weekday: 1, pair: 4, parity: 'BOTH' });
    expect(preview.status).toBe('blocked');
    expect(preview.conflicts).toEqual([
      expect.objectContaining({
        kind: 'TEACHER',
        severity: 'HARD',
        message: 'Кренцін веде одночасно 4КІ2 — СТП і 4КІ3 — СТП — понеділок, 4 пара',
      }),
    ]);
    const error = await failure(api.post(`${versionsPath}/${DEMO_DRAFT_ID}/lessons/${stp.id}/move`, { weekday: 1, pair: 4, parity: 'BOTH' }));
    expect(error.status).toBe(409);
    expect(error.type).toBe('urn:rozklad:problem:schedule-conflict');
  });

  it('переміщення зберігається, а нові мʼякі попередження повертаються', async () => {
    const stp = find(await draftLessons(), '4КІ3', 2, 4, 'СТП');
    const result = await api.post<LessonMutationResult>(`${versionsPath}/${DEMO_DRAFT_ID}/lessons/${stp.id}/move`, {
      weekday: 3,
      pair: 4,
      parity: 'BOTH',
    });
    expect(result.lesson).toMatchObject({ id: stp.id, weekday: 3, pair: 4 });
    expect(result.conflicts.every((c) => c.severity === 'SOFT')).toBe(true);
    expect(find(await draftLessons(), '4КІ3', 3, 4, 'СТП').id).toBe(stp.id);
  });

  it('некоректний слот — 400 з полями', async () => {
    const stp = find(await draftLessons(), '4КІ3', 2, 4, 'СТП');
    const error = await failure(api.post(`${versionsPath}/${DEMO_DRAFT_ID}/lessons/${stp.id}/move`, { weekday: 6, pair: 9, parity: 'X' }));
    expect(error.status).toBe(400);
    expect(Object.keys(error.fieldErrors()).sort()).toEqual(['pair', 'parity', 'weekday']);
  });

  it('опубліковану версію не змінюють — 422', async () => {
    const [lesson] = TEMPLATE_LESSONS;
    const error = await failure(api.post(`${versionsPath}/${PUBLISHED_VERSION_ID}/lessons/${lesson?.id}/move`, { weekday: 1, pair: 1, parity: 'BOTH' }));
    expect(error.status).toBe(422);
    expect(error.message).toContain('лише чернетку');
  });
});

describe('редагування чернетки', () => {
  it('нова пара зберігається навіть з конфліктом і повертає його', async () => {
    const lessons = await draftLessons();
    const stp = find(lessons, '4КІ3', 2, 4, 'СТП');
    const result = await api.post<LessonMutationResult>(`${versionsPath}/${DEMO_DRAFT_ID}/lessons`, {
      weekday: 1,
      pair: 4,
      parity: 'BOTH',
      groupId: stp.group.id,
      subgroup: 0,
      disciplineId: stp.discipline.id,
      teacherId: stp.teacher?.id,
      roomId: null,
      lessonKind: 'PRACTICE',
    });
    expect(result.lesson).toMatchObject({ weekday: 1, pair: 4, lessonKind: 'PRACTICE' });
    expect(result.conflicts.map((c) => c.kind)).toContain('TEACHER');
    const conflicts = await api.get<TimetableConflictsDto>(`${versionsPath}/${DEMO_DRAFT_ID}/conflicts`);
    expect(conflicts.hard.some((c) => c.lessonIds.includes(result.lesson.id))).toBe(true);
  });

  it('невідома група й дисципліна — 400 біля полів; видалення невідомої — 404', async () => {
    const error = await failure(
      api.post(`${versionsPath}/${DEMO_DRAFT_ID}/lessons`, { weekday: 1, pair: 1, parity: 'BOTH', groupId: 'x', subgroup: 0, disciplineId: 'y', lessonKind: 'MIXED' }),
    );
    expect(error.fieldErrors()).toMatchObject({ groupId: 'Оберіть групу', disciplineId: 'Оберіть дисципліну' });
    const missing = await failure(api.delete(`${versionsPath}/${DEMO_DRAFT_ID}/lessons/nope`));
    expect(missing.status).toBe(404);
  });

  it('видалена пара зникає з чернетки', async () => {
    const stp = find(await draftLessons(), '4КІ3', 2, 4, 'СТП');
    await api.delete(`${versionsPath}/${DEMO_DRAFT_ID}/lessons/${stp.id}`);
    expect((await draftLessons()).some((l) => l.id === stp.id)).toBe(false);
  });
});

describe('публікація', () => {
  it('жорсткі конфлікти не пускають — 409 зі списком', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-08T10:00:00Z'));
    const error = await failure(api.post(`${versionsPath}/${DEMO_DRAFT_ID}/publish`, { effectiveFrom: '2026-11-09' }));
    expect(error.status).toBe(409);
    expect(error.conflicts).toHaveLength(75);
  });

  it('дата в минулому чи поза семестром — 400', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-08T10:00:00Z'));
    const past = await failure(api.post(`${versionsPath}/${DEMO_DRAFT_ID}/publish`, { effectiveFrom: '2026-11-02' }));
    expect(past.fieldErrors()).toMatchObject({ effectiveFrom: 'Розклад не можна ввести заднім числом' });
    const outside = await failure(api.post(`${versionsPath}/${DEMO_DRAFT_ID}/publish`, { effectiveFrom: '2027-02-01' }));
    expect(outside.status).toBe(400);
  });

  it('версія без конфліктів публікується, попередня йде в архів', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-08T10:00:00Z'));
    const created = await api.post<TimetableVersionDto>(versionsPath, { semesterId: ACTIVE_SEMESTER.id, name: 'Порожня' });
    const result = await api.post<PublishVersionResponse>(`${versionsPath}/${created.id}/publish`, { effectiveFrom: '2026-11-09' });
    expect(result.materializedLessons).toBe(0);
    const versions = await api.get<TimetableVersionDto[]>(versionsPath, { semesterId: ACTIVE_SEMESTER.id });
    expect(versions.find((v) => v.id === created.id)?.status).toBe('PUBLISHED');
    expect(versions.find((v) => v.id === PUBLISHED_VERSION_ID)?.status).toBe('ARCHIVED');
  });
});
