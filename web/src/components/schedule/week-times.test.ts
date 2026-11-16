import { describe, expect, it } from 'vitest';
import type { BellSlotDto } from '@/api/types';
import { BELLS_FRI, BELLS_MON_THU, DEMO_WEEK } from '@/pages/dev/demo-data';
import { weekTimes } from './week-times';

const WEEK_BELLS = new Map<number, readonly BellSlotDto[]>([
  [1, BELLS_MON_THU],
  [2, BELLS_MON_THU],
  [3, BELLS_MON_THU],
  [4, BELLS_MON_THU],
  [5, BELLS_FRI],
]);

describe('weekTimes', () => {
  it('колонка часу — час більшості днів, а пʼятниця зі своїми дзвінками — окремо', () => {
    const { rows, byDay, exceptions } = weekTimes(DEMO_WEEK.days, WEEK_BELLS);
    expect(rows.get(4)).toEqual({ startsAt: '12:10', endsAt: '13:10' });
    // пʼятниця: 4 пара о 12:20, а не о 12:10 (V1_201)
    expect(byDay[4]?.get(4)).toEqual({ startsAt: '12:20', endsAt: '13:20' });
    expect(byDay[0]?.get(4)).toEqual({ startsAt: '12:10', endsAt: '13:10' });
    expect(exceptions.get(4)).toEqual([{ weekday: 5, time: { startsAt: '12:20', endsAt: '13:20' } }]);
    expect(exceptions.get(6)).toEqual([{ weekday: 5, time: { startsAt: '14:50', endsAt: '15:50' } }]);
    // однаковий час — без підпису; пар 7–8 у пʼятницю немає — це не «інший час»
    expect(exceptions.has(1)).toBe(false);
    expect(exceptions.has(7)).toBe(false);
  });

  it('за рівності днів у колонці час дня, що раніше в тижні', () => {
    const days = [DEMO_WEEK.days[0]!, DEMO_WEEK.days[4]!];
    const { rows, exceptions } = weekTimes(days, WEEK_BELLS);
    expect(rows.get(4)).toEqual({ startsAt: '12:10', endsAt: '13:10' });
    expect(exceptions.get(4)?.map((item) => item.weekday)).toEqual([5]);
  });

  it('ненавчальний день не впливає на час рядка й не дає підпису', () => {
    const days = DEMO_WEEK.days.map((day) => (day.weekday === 5 ? { ...day, studyDay: false } : day));
    expect(weekTimes(days, WEEK_BELLS).exceptions.size).toBe(0);
  });

  it('без дзвінків — як раніше: час рядка з занять, у кожному дні той самий, без підписів', () => {
    const { rows, byDay, exceptions } = weekTimes(DEMO_WEEK.days, null);
    expect(rows.get(1)).toEqual({ startsAt: '08:30', endsAt: '09:30' });
    expect(byDay[4]?.get(1)).toEqual(rows.get(1));
    expect(exceptions.size).toBe(0);
  });
});
