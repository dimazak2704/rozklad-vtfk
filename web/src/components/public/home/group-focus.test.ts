import { describe, expect, it } from 'vitest';
import type { BellSlotDto, DayDto, LessonView } from '@/api/types';
import { pairStatus } from '@/lib/bells';
import { timeToMinutes } from '@/lib/date';
import { groupFocusPlan } from './group-focus';

// дзвінки пн–чт («2 зміни, 60 хв»)
const BELLS: BellSlotDto[] = [
  { pair: 1, startsAt: '08:30', endsAt: '09:30' },
  { pair: 2, startsAt: '09:40', endsAt: '10:40' },
  { pair: 3, startsAt: '11:00', endsAt: '12:00' },
  { pair: 4, startsAt: '12:10', endsAt: '13:10' },
  { pair: 5, startsAt: '13:30', endsAt: '14:30' },
  { pair: 6, startsAt: '14:40', endsAt: '15:40' },
];

function lesson(pair: number, discipline: string, overrides: Partial<LessonView> = {}): LessonView {
  const slot = BELLS.find((b) => b.pair === pair);
  if (!slot) throw new Error(`немає дзвінка для ${pair} пари`);
  return {
    id: `${pair}-${discipline}-${overrides.subgroup ?? 0}`,
    pair,
    startsAt: slot.startsAt,
    endsAt: slot.endsAt,
    discipline: { id: discipline, shortName: discipline, name: `${discipline} повна`, color: null },
    teacher: null,
    room: null,
    group: { id: 'g', code: '2КІ1', course: 2 },
    subgroup: 0,
    kind: 'REGULAR',
    online: false,
    original: null,
    reasonText: null,
    changeId: null,
    ...overrides,
  };
}

const day = (date: string, lessons: LessonView[]): DayDto =>
  ({ date, weekday: 4, weekdayLabel: 'ЧЕТВЕР', parity: 'NUMERATOR', studyDay: true, note: null, lessons });

const at = (time: string) => pairStatus(BELLS, timeToMinutes(time));
const TODAY = '2026-11-12';
const TOMORROW = '2026-11-13';

describe('groupFocusPlan — що героєві сказати про свою групу', () => {
  it('пара групи на поточному дзвінку — «зараз»', () => {
    const plan = groupFocusPlan({ status: at('11:22'), groupToday: day(TODAY, [lesson(3, 'КМ')]), groupNextDay: undefined, today: TODAY, nextDay: TOMORROW });
    expect(plan?.when).toBe('зараз');
    expect(plan?.lessons.map((l) => l.pair)).toEqual([3]);
  });

  it('у перерві — пара на наступному дзвінку, «далі»', () => {
    const plan = groupFocusPlan({ status: at('12:05'), groupToday: day(TODAY, [lesson(4, 'КМ')]), groupNextDay: undefined, today: TODAY, nextDay: TOMORROW });
    expect(plan?.when).toBe('далі');
  });

  it('на цьому дзвінку в групи пари немає — її наступне заняття сьогодні з часом', () => {
    const groupToday = day(TODAY, [lesson(5, 'Укр.літ'), lesson(6, 'Фіз')]);
    const plan = groupFocusPlan({ status: at('11:22'), groupToday, groupNextDay: undefined, today: TODAY, nextDay: TOMORROW });
    expect(plan?.when).toBe('далі о 13:30');
    expect(plan?.lessons.map((l) => l.discipline.shortName)).toEqual(['Укр.літ']);
  });

  it('скасована пара не рахується наступною', () => {
    const groupToday = day(TODAY, [lesson(4, 'КМ', { kind: 'CANCELLED' }), lesson(5, 'Укр.літ')]);
    const plan = groupFocusPlan({ status: at('11:22'), groupToday, groupNextDay: undefined, today: TODAY, nextDay: TOMORROW });
    expect(plan?.when).toBe('далі о 13:30');
  });

  it('у групи на сьогодні все — перша пара наступного навчального дня', () => {
    const groupToday = day(TODAY, [lesson(1, 'КМ'), lesson(2, 'КМ')]);
    const groupNextDay = day(TOMORROW, [lesson(3, 'ЕУ'), lesson(2, 'Фіз', { subgroup: 2 }), lesson(2, 'Фіз', { subgroup: 1 })]);
    const plan = groupFocusPlan({ status: at('11:22'), groupToday, groupNextDay, today: TODAY, nextDay: TOMORROW });
    expect(plan?.when).toBe('завтра о 09:40');
    // підгрупи — по порядку
    expect(plan?.lessons.map((l) => l.subgroup)).toEqual([1, 2]);
  });

  it('наступний навчальний день не завтра — днем тижня', () => {
    const groupNextDay = day('2026-11-16', [lesson(1, 'ЕУ')]);
    const plan = groupFocusPlan({ status: at('11:22'), groupToday: day(TODAY, []), groupNextDay, today: TODAY, nextDay: '2026-11-16' });
    expect(plan?.when).toBe('у понеділок о 08:30');
  });

  it('після пар, без даних групи чи без наступного дня — нічого', () => {
    expect(groupFocusPlan({ status: at('16:00'), groupToday: day(TODAY, [lesson(1, 'КМ')]), groupNextDay: day(TOMORROW, [lesson(1, 'КМ')]), today: TODAY, nextDay: TOMORROW })).toBeNull();
    expect(groupFocusPlan({ status: at('11:22'), groupToday: undefined, groupNextDay: undefined, today: TODAY, nextDay: TOMORROW })).toBeNull();
    expect(groupFocusPlan({ status: at('11:22'), groupToday: day(TODAY, []), groupNextDay: undefined, today: TODAY, nextDay: null })).toBeNull();
  });
});
