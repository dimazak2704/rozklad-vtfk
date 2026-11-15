import { describe, expect, it } from 'vitest';
import { DEFAULT_WEIGHTS } from '@/api/generation';
import type { GenerationJobDto, MetricsDto, TimetableVersionDto } from '@/api/types';
import {
  baselineSentence,
  completionNotice,
  comparisonRows,
  ignoredWeights,
  formFromJob,
  groupComparison,
  hardAfter,
  hardBefore,
  jobScopeText,
  jobPrioritiesText,
  jobStatusTitle,
  outcomeDraftSentence,
  outcomeSentence,
  splitAnalysis,
  timeTicks,
  usableMetrics,
} from './model';

const metrics = (values: Partial<MetricsDto>): MetricsDto => ({
  groupWindows: 0,
  teacherWindows: 0,
  brokenDays: 0,
  latePairs: 0,
  maxPairsPerDay: 0,
  perGroup: [],
  ...values,
});

/** Задача в старому форматі: без scope, groups, weights, hardScore, outcome (genfix §5: має читатись). */
const job = (patch: Partial<GenerationJobDto>): GenerationJobDto => ({
  id: 'j',
  semesterId: 's',
  status: 'SOLVED',
  createdAt: '2026-11-11T10:00:00Z',
  startedAt: '2026-11-11T10:00:00Z',
  finishedAt: '2026-11-11T10:00:15Z',
  bestScore: '0hard/-18soft',
  progress: [
    { t: 0, hard: -3, soft: -40 },
    { t: 2, hard: 0, soft: -18 },
  ],
  resultVersionId: 'v2',
  baseVersionId: 'v1',
  timeLimitSeconds: 45,
  analysis: null,
  metricsBefore: null,
  metricsAfter: metrics({
    teacherWindows: 20,
    latePairs: 18,
    maxPairsPerDay: 4,
  }),
  error: null,
  requestedBy: null,
  ...patch,
});

describe('генератор: «Було / Стало»', () => {
  it('нульові метрики — це «не пораховано», а не ідеальний розклад', () => {
    expect(usableMetrics(metrics({}))).toBeNull();
    expect(usableMetrics(null)).toBeNull();
    expect(usableMetrics(metrics({ latePairs: 3 }))?.latePairs).toBe(3);
  });

  it('порожнє «Було» з задачі береться з базової версії', () => {
    const rows = comparisonRows(job({ metricsBefore: metrics({}) }), {
      metrics: metrics({ teacherWindows: 180, latePairs: 106, maxPairsPerDay: 4 }),
      hardConflicts: 0,
    });
    expect(rows.find((row) => row.key === 'latePairs')).toMatchObject({ label: '4-та пара зміни', before: 106, after: 18 });
    // B2: «Було» жорстких — з бази (0), а не з першої точки прогресу (-3)
    expect(rows.find((row) => row.key === 'hard')).toMatchObject({ before: 0, after: 0 });
  });

  it('«Було» жорстких — з metricsBefore.hardConflicts, потім з бази; перша точка прогресу — ніколи', () => {
    expect(hardBefore(job({ metricsBefore: metrics({ hardConflicts: 75, teacherWindows: 3 }) }), { metrics: null, hardConflicts: 2 })).toBe(75);
    expect(hardBefore(job({}), { metrics: null, hardConflicts: 2 })).toBe(2);
    expect(hardBefore(job({}), null)).toBeNull();
    // без базової версії (з навантаження) «Було» з чужої версії не підставляємо
    expect(hardBefore(job({ baseVersionId: null }), { metrics: null, hardConflicts: 2 })).toBeNull();
    expect(hardAfter(job({ metricsAfter: metrics({ hardConflicts: 1, maxPairsPerDay: 4 }) }))).toBe(1);
    expect(hardAfter(job({ hardScore: -4 }))).toBe(4);
  });

  it('без жодного «Було» показує лише «Стало», а не 0 → 18', () => {
    const rows = comparisonRows(job({ metricsBefore: metrics({}) }), null);
    expect(rows.every((row) => row.before === null)).toBe(true);
    expect(rows.find((row) => row.key === 'latePairs')?.after).toBe(18);
  });

  it('нові показники зʼявляються, лише коли сервер їх порахував', () => {
    expect(comparisonRows(job({})).map((row) => row.key)).not.toContain('teacherShiftWaits');
    const rows = comparisonRows(job({ metricsAfter: metrics({ maxPairsPerDay: 4, teacherShiftWaits: 12, studentOnePairDays: 0 }) }));
    expect(rows.find((row) => row.key === 'teacherShiftWaits')).toMatchObject({ label: 'Очікування між змінами', after: 12 });
    expect(rows.find((row) => row.key === 'studentOnePairDays')?.after).toBe(0);
  });

  it('вісь часу без повторів після округлення', () => {
    expect(timeTicks(15)).toEqual([0, 5, 10, 15]);
    expect(timeTicks(2)).toEqual([0, 1, 2]);
    const long = timeTicks(600);
    expect(new Set(long).size).toBe(long.length);
    expect(long.at(-1)).toBeLessThanOrEqual(600);
  });
});

describe('стан задачі (B6)', () => {
  it('SOLVED з жорсткими порушеннями — «Готово з конфліктами», а не «Готово»', () => {
    expect(jobStatusTitle(job({}))).toBe('Готово');
    expect(jobStatusTitle(job({ bestScore: '-1hard/-2089soft' }))).toBe('Готово з конфліктами');
    expect(jobStatusTitle(job({ hardScore: -1 }))).toBe('Готово з конфліктами');
    expect(jobStatusTitle(job({ status: 'RUNNING', bestScore: '-3hard/-40soft' }))).toBe('Розвʼязується');
  });

  it('сповіщення про завершення: з конфліктами — попередження з кількістю, а не зелене «завершено»', () => {
    expect(completionNotice(job({}))).toMatchObject({ level: 'success', title: 'Генерацію завершено' });
    expect(completionNotice(job({ hardScore: -21 }))).toEqual({
      level: 'warning',
      title: 'Генерацію завершено з конфліктами',
      description: 'Чернетку збережено, але в ній 21 жорсткий конфлікт: виправте їх перед публікацією.',
    });
    // стара задача без hardScore: бал береться з рядка bestScore
    expect(completionNotice(job({ bestScore: '-3hard/-40soft' }))?.description).toContain('3 жорсткі конфлікти');
    expect(completionNotice(job({ status: 'FAILED' }))).toBeNull();
    expect(completionNotice(job({ status: 'CANCELLED' }))).toBeNull();
  });
});

describe('аналіз обмежень (B7)', () => {
  it('бонус — у «Що вдалося»; порушення поза вибором — окремим числом', () => {
    const split = splitAnalysis([
      { constraint: 'Рвані дні', matchCount: 10, score: '0hard/-100soft', bonus: false, scopeMatchCount: 3 },
      { constraint: 'Вікна груп', matchCount: 4, score: '0hard/-40soft', scopeMatchCount: 0 },
      { constraint: 'Половинки', matchCount: 12, score: '0hard/120soft', bonus: true, scopeMatchCount: 2 },
      { constraint: 'Аудиторія зайнята', matchCount: 1, score: '-1hard/0soft' },
    ]);
    expect(split.remaining).toEqual([
      { key: 'Рвані дні', constraint: 'Рвані дні', count: 3, hard: false },
      { key: 'Аудиторія зайнята', constraint: 'Аудиторія зайнята', count: 1, hard: true },
    ]);
    expect(split.achieved.map((row) => row.constraint)).toEqual(['Половинки']);
    expect(split.clean).toEqual(['Вікна груп']);
    expect(split.outsideScope).toBe(7 + 4);
  });

  it('правило «Не враховувати» — не «Що лишилось», а довідка; без ваг (стара задача) — як раніше', () => {
    const items = [
      { constraint: '4-та пара зміни', constraintId: 'Late pair', matchCount: 59, score: '0hard/0soft', scopeMatchCount: 59 },
      { constraint: 'Вікна груп', constraintId: 'Group windows', matchCount: 2, score: '0hard/-20soft', scopeMatchCount: 2 },
    ];
    const ignored = ignoredWeights(job({ weights: { ...DEFAULT_WEIGHTS, latePairs: 0 } }));
    const split = splitAnalysis(items, ignored);
    expect(split.remaining.map((row) => row.constraint)).toEqual(['Вікна груп']);
    expect(split.ignored).toEqual([{ key: 'Late pair', constraint: '4-та пара зміни', count: 59, hard: false }]);
    expect(splitAnalysis(items, ignoredWeights(job({}))).remaining).toHaveLength(2);
  });

  it('«Було і стало»: показник правила, яке не враховувалось, позначено', () => {
    const rows = comparisonRows(
      job({ weights: { ...DEFAULT_WEIGHTS, latePairs: 0 }, metricsBefore: metrics({ latePairs: 291, groupWindows: 3, maxPairsPerDay: 4 }), metricsAfter: metrics({ latePairs: 300, groupWindows: 1, maxPairsPerDay: 4 }) }),
    );
    expect(rows.find((row) => row.key === 'latePairs')).toMatchObject({ before: 291, after: 300, ignored: true });
    expect(rows.find((row) => row.key === 'groupWindows')?.ignored).toBe(false);
  });

  it('стара задача без bonus: бонус упізнається за додатним балом', () => {
    const split = splitAnalysis([{ constraint: 'Чисельник і знаменник в одному слоті (бонус)', matchCount: 5, score: '0hard/50soft' }]);
    expect(split.remaining).toEqual([]);
    expect(split.achieved).toHaveLength(1);
  });
});

describe('параметри і наслідки задачі (B8)', () => {
  const g = (code: string, course = 4) => ({ id: code, code, course });

  it('«Переставлено N з M пар» і аудиторії окремо', () => {
    expect(outcomeSentence({ moved: 34, roomChanged: 0, total: 53, perGroup: [] })).toBe('Переставлено 34 з 53\u00a0пар.');
    expect(outcomeSentence({ moved: 1, roomChanged: 5, total: 21, perGroup: [] })).toBe('Переставлено 1 з 21\u00a0пари, ще 5 — в іншій аудиторії.');
    expect(outcomeSentence(null)).toBeNull();
    expect(outcomeSentence({ moved: 0, roomChanged: 0, total: 0, perGroup: [] })).toBeNull();
  });

  it('аудиторію, якої не було, рахує окремо від іншої аудиторії — як порівняння версій', () => {
    expect(outcomeSentence({ moved: 2, roomChanged: 0, roomAssigned: 1, total: 35, perGroup: [] })).toBe(
      'Переставлено 2 з 35 пар, ще 1 отримала аудиторію, якої не було.',
    );
    expect(outcomeSentence({ moved: 5, roomChanged: 3, roomAssigned: 44, total: 400, perGroup: [] })).toBe(
      'Переставлено 5 з 400 пар, ще 3 — в іншій аудиторії, 44 отримали аудиторію, якої не було.',
    );
  });

  it('пояснює, чому в чернетці пар більше, ніж «з M»', () => {
    expect(outcomeDraftSentence({ moved: 128, roomChanged: 30, total: 1074, perGroup: [], kept: 2, added: 1 })).toBe(
      'Ще 3 пари чернетки: 2 — закріплені чи поза вибором, стоять, як у базовій; 1 нова пара з навантаження — у базовій її не було.',
    );
    expect(outcomeDraftSentence({ moved: 2, roomChanged: 0, total: 35, perGroup: [], kept: 1042, added: 0 })).toBe(
      'Ще 1042 пари чернетки: 1042 — закріплені чи поза вибором, стоять, як у базовій.',
    );
    // стара задача без нових полів — нічого не вигадуємо
    expect(outcomeDraftSentence({ moved: 2, roomChanged: 0, total: 35, perGroup: [] })).toBeNull();
  });

  it('область задачі словами', () => {
    expect(jobScopeText({ scope: { groupIds: [], courses: [] }, groups: [] })).toBe('Усі групи');
    expect(jobScopeText({ scope: { groupIds: [], courses: [4] }, groups: [g('4КІ1'), g('4КІ2')] })).toBe('4 курс · 2\u00a0групи');
    expect(jobScopeText({ scope: { groupIds: ['4КІ1', '4КІ3'], courses: [] }, groups: [g('4КІ1'), g('4КІ3')] })).toBe('4КІ1, 4КІ3');
    expect(jobScopeText({})).toBeNull();
  });

  it('форма з задачі: область, база, ліміт і ваги; старі пʼять ваг доповнюються як у core', () => {
    const form = formFromJob(
      job({ scope: { groupIds: ['a'], courses: [] }, weights: { windows: 20, brokenDays: 10, latePairs: 3, preferences: 5, homeRoom: 1 } }),
    );
    expect(form).toMatchObject({ scope: 'groups', groupIds: ['a'], baseVersionId: 'v1', timeLimitSeconds: 45 });
    expect(form.weights).toEqual({ ...DEFAULT_WEIGHTS, windows: 20, latePairs: 3, teacherWindows: 20 });
  });

  it('таблиця по групах: було → стало і переставлені пари', () => {
    const row = (code: string, latePairs: number, onePairDays?: number) => ({
      group: g(code),
      windows: 0,
      brokenDays: 1,
      latePairs,
      maxPairsPerDay: 4,
      ...(onePairDays === undefined ? {} : { onePairDays }),
    });
    const rows = groupComparison(
      job({
        groups: [g('4КІ1')],
        metricsBefore: metrics({ maxPairsPerDay: 4, perGroup: [row('4КІ1', 4), row('4КІ2', 5)] }),
        metricsAfter: metrics({ maxPairsPerDay: 4, perGroup: [row('4КІ1', 2, 0), row('4КІ2', 5, 0)] }),
        outcome: { moved: 3, roomChanged: 0, total: 9, perGroup: [{ group: g('4КІ1'), moved: 3, total: 9 }] },
      }),
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]?.cells.find((cell) => cell.key === 'latePairs')).toEqual({ key: 'latePairs', before: 4, after: 2 });
    // старі метрики без onePairDays: «Було» невідоме, а не 0
    expect(rows[0]?.cells.find((cell) => cell.key === 'onePairDays')).toEqual({ key: 'onePairDays', before: null, after: 0 });
    expect(rows[0]?.moved).toEqual({ moved: 3, total: 9 });
  });
});

describe('пріоритети задачі (genfix repair)', () => {
  it('стара задача з пʼятьма вагами — її власні ваги, а не «типові» правила, яких тоді не було', () => {
    const weights = { ...DEFAULT_WEIGHTS, windows: 10, latePairs: 3, teacherWindows: 10 };
    expect(jobPrioritiesText(job({ weights, legacyWeights: true }), [])).toBe(
      'стара версія генератора, лише 5 правил: вікна 10, рвані дні 10, пізні пари 3, побажання 5, аудиторія 1',
    );
    expect(jobPrioritiesText(job({ weights: { ...DEFAULT_WEIGHTS }, legacyWeights: false }), [])).toBe('типові');
    expect(jobPrioritiesText(job({ weights }), [{ rule: { label: 'Вікна груп' }, text: 'Важливо' }])).toBe('Вікна груп — важливо');
  });
});

describe('стан базової версії', () => {
  const version = (hardConflicts: number, values: Partial<MetricsDto>) => ({ hardConflicts, metrics: metrics(values) }) as unknown as TimetableVersionDto;

  it('одне число, що вирішує, — конфлікти; решта метрик не перелічується', () => {
    expect(baselineSentence(version(75, { teacherWindows: 180, brokenDays: 34 }))).toBe('75\u00a0жорстких конфліктів.');
    expect(baselineSentence(version(0, { teacherWindows: 3 }))).toBe('Конфліктів немає, лишились вікна чи рвані дні.');
    expect(baselineSentence(version(0, {}))).toBe('Конфліктів і вікон немає.');
    // 4-та пара зміни — не вада
    expect(baselineSentence(version(0, { latePairs: 291 }))).toBe('Конфліктів і вікон немає.');
  });
});
