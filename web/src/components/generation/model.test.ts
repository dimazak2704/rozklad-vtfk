import { describe, expect, it } from 'vitest';
import type { GenerationJobDto, MetricsDto } from '@/api/types';
import { comparisonRows, timeTicks, usableMetrics } from './model';

const metrics = (values: Partial<MetricsDto>): MetricsDto => ({
  groupWindows: 0,
  teacherWindows: 0,
  brokenDays: 0,
  latePairs: 0,
  maxPairsPerDay: 0,
  perGroup: [],
  ...values,
});

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
    const rows = comparisonRows(job({ metricsBefore: metrics({}) }), metrics({ teacherWindows: 180, latePairs: 106, maxPairsPerDay: 4 }));
    expect(rows.find((row) => row.key === 'latePairs')).toMatchObject({
      before: 106,
      after: 18,
    });
    expect(rows.find((row) => row.key === 'hard')).toMatchObject({
      before: 3,
      after: 0,
    });
  });

  it('без жодного «Було» показує лише «Стало», а не 0 → 18', () => {
    const rows = comparisonRows(job({ metricsBefore: metrics({}) }), null);
    expect(rows.every((row) => row.before === null)).toBe(true);
    expect(rows.find((row) => row.key === 'latePairs')?.after).toBe(18);
  });

  it('вісь часу без повторів після округлення', () => {
    expect(timeTicks(15)).toEqual([0, 5, 10, 15]);
    expect(timeTicks(2)).toEqual([0, 1, 2]);
    const long = timeTicks(600);
    expect(new Set(long).size).toBe(long.length);
    expect(long.at(-1)).toBeLessThanOrEqual(600);
  });
});
