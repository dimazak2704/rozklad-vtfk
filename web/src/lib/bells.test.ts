import { describe, expect, it } from 'vitest';
import type { BellProfileDto, BellSlotDto } from '@/api/types';
import { bellsByWeekday, describePairStatus, pairStatus } from './bells';

// Профіль «2 зміни, 60 хв», понеділок–четвер (DOMAIN §2)
const MON_THU: BellSlotDto[] = [
  { pair: 1, startsAt: '08:30', endsAt: '09:30' },
  { pair: 2, startsAt: '09:40', endsAt: '10:40' },
  { pair: 3, startsAt: '11:00', endsAt: '12:00' },
  { pair: 4, startsAt: '12:10', endsAt: '13:10' },
];
const at = (hh: number, mm: number) => hh * 60 + mm;

describe('pairStatus', () => {
  it('до першої пари', () => {
    expect(pairStatus(MON_THU, at(8, 0))).toMatchObject({ kind: 'before-day', minutesUntil: 30 });
  });

  it('під час пари рахує прогрес і залишок', () => {
    const status = pairStatus(MON_THU, at(10, 0));
    expect(status.kind).toBe('in-pair');
    if (status.kind !== 'in-pair') return;
    expect(status.current.pair).toBe(2);
    expect(status.minutesLeft).toBe(40);
    expect(status.progress).toBeCloseTo(1 / 3, 5);
    expect(status.next?.pair).toBe(3);
  });

  it('на перерві показує наступну пару', () => {
    const status = pairStatus(MON_THU, at(10, 50));
    expect(status).toMatchObject({ kind: 'break', minutesLeft: 10 });
    if (status.kind === 'break') expect(status.next.startsAt).toBe('11:00');
  });

  it('межі: початок пари вже «пара», кінець — вже перерва', () => {
    expect(pairStatus(MON_THU, at(8, 30)).kind).toBe('in-pair');
    expect(pairStatus(MON_THU, at(9, 30)).kind).toBe('break');
  });

  it('після останньої пари', () => {
    expect(pairStatus(MON_THU, at(14, 0)).kind).toBe('after-day');
  });

  it('враховує лише свої пари', () => {
    expect(pairStatus(MON_THU, at(11, 30), new Set([1, 2])).kind).toBe('after-day');
  });

  it('без дзвінків пар немає', () => {
    expect(pairStatus([], at(10, 0)).kind).toBe('no-classes');
  });
});

describe('describePairStatus', () => {
  it('формує український текст', () => {
    expect(describePairStatus(pairStatus(MON_THU, at(10, 0)))).toBe('Зараз 2 пара, до кінця 40\u00a0хв');
    expect(describePairStatus(pairStatus(MON_THU, at(10, 50)))).toBe('Перерва до 11:00, далі 3 пара');
  });
});

describe('bellsByWeekday', () => {
  const profile = (id: string, slots: BellProfileDto['slots']): BellProfileDto => ({
    id,
    name: id,
    active: true,
    defaultProfile: false,
    slots,
    version: 0,
  });
  const profiles = [
    profile('other', [{ weekday: 1, pair: 4, startsAt: '11:40', endsAt: '12:30' }]),
    profile('main', [
      { weekday: 5, pair: 10, startsAt: '18:00', endsAt: '19:00' },
      { weekday: 5, pair: 4, startsAt: '12:20', endsAt: '13:20' },
      { weekday: 1, pair: 4, startsAt: '12:10', endsAt: '13:10' },
      { weekday: 5, pair: 2, startsAt: '09:40', endsAt: '10:40' },
    ]),
  ];

  it('групує слоти профілю, що діє, за днями тижня; пари — за номером, не за рядком (10 після 4)', () => {
    const byDay = bellsByWeekday('main', profiles);
    expect(byDay?.get(1)).toEqual([{ pair: 4, startsAt: '12:10', endsAt: '13:10' }]);
    expect(byDay?.get(5)?.map((slot) => slot.pair)).toEqual([2, 4, 10]);
    expect(byDay?.get(5)?.[1]).toEqual({ pair: 4, startsAt: '12:20', endsAt: '13:20' });
  });

  it('профілю немає серед переданих або вони ще вантажаться — null', () => {
    expect(bellsByWeekday('missing', profiles)).toBeNull();
    expect(bellsByWeekday(undefined, profiles)).toBeNull();
    expect(bellsByWeekday('main', undefined)).toBeNull();
  });
});
