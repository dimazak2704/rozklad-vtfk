import { describe, expect, it } from 'vitest';
import type { GroupTheoryDto } from '@/api/types';
import { graphDiffers, graphMismatchCount, graphWarning, offPeriodTitle, planHint, shortRange, theoryLine } from './theory';

const ns = {
  id: 'p-ns',
  kind: { code: 'НС', name: 'Національний спротив', color: null, effect: 'SUSPEND' as const },
  title: 'Основи національного спротиву',
  startsOn: '2026-11-30',
  endsOn: '2026-12-11',
};
const winter = {
  id: 'p-k',
  kind: { code: 'К', name: 'Канікули', color: '#FFFFCC', effect: 'DAY_OFF' as const },
  title: 'Канікули',
  startsOn: '2026-12-28',
  endsOn: '2026-12-30',
};
const ki2: GroupTheoryDto = {
  group: { id: 'g-4ki2', code: '4КІ2', course: 4 },
  semesterWeeks: 18,
  theoryWeeks: 15,
  numeratorWeeks: 8,
  denominatorWeeks: 7,
  studyDays: 74,
  graphTheoryWeeks: 20,
  offPeriods: [ns, winter],
};

describe('тижні теорії групи', () => {
  it('рядок групи: тижні теорії з тижнів семестру і що знімає з пар', () => {
    expect(theoryLine(ki2)).toBe('4КІ2: 15\u00a0тижнів теорії з 18 — НС 30.11–11.12, К 28.12–30.12');
    expect(theoryLine({ ...ki2, theoryWeeks: 1, offPeriods: [] })).toBe('4КІ2: 1\u00a0тиждень теорії з 18');
  });

  it('один день — одна дата; назва виду не повторюється, коли збігається з назвою періоду', () => {
    expect(shortRange('2026-12-28', '2026-12-28')).toBe('28.12');
    expect(offPeriodTitle(ns)).toBe('Національний спротив: Основи національного спротиву, 30.11–11.12');
    expect(offPeriodTitle(winter)).toBe('Канікули, 28.12–30.12');
  });

  it('графік — лише звірка: попередження, коли числа різні або графіка немає', () => {
    expect(graphDiffers(ki2)).toBe(true);
    expect(graphWarning(ki2)).toContain('20\u00a0тижнів теорії');
    expect(graphWarning({ ...ki2, graphTheoryWeeks: 15 })).toBeNull();
    expect(graphWarning({ ...ki2, graphTheoryWeeks: null })).toBeNull();
    expect(graphMismatchCount(13)).toBe('13\u00a0груп розходяться з графіком');
    expect(graphMismatchCount(2)).toBe('2\u00a0групи розходяться з графіком');
  });

  it('підказка до плану каже, звідки число', () => {
    expect(planHint({ planSource: 'AUTO', hoursPerWeek: 3 }, ki2)).toBe(
      'План рахує система: 3 год на тиждень лише за тижні теорії групи (15\u00a0тижнів теорії з 18), за парами розкладу.',
    );
    expect(planHint({ planSource: 'FIXED', hoursPerWeek: 2 })).toContain('задано числом');
    expect(planHint({ planSource: 'MIXED', hoursPerWeek: 4 })).toMatch(/^Частину плану задано числом/);
  });
});
