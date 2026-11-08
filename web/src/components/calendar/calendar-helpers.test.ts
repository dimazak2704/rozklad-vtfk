import { describe, expect, it } from 'vitest';
import type { BellProfileSlotDto } from '@/api/types';
import { BELL_PROFILES } from '@/mocks/fixtures';
import { breakAfter, gridFromSlots, hasErrors, nextSlot, slotsFromGrid, validateDay } from './bells';
import { composeOrderNote, orderDate } from './notes';
import { periodAudience } from './periods';

describe('примітка періоду для розпорядження', () => {
  it('практика групи — як у розпорядженні №35', () => {
    expect(
      composeOrderNote({
        kind: 'PRACTICE',
        title: 'Електрорадіомонтажна практика',
        startsOn: '2026-10-19',
        endsOn: '2026-10-30',
        scope: 'GROUPS',
        courses: [],
        groupCodes: ['2КІ3'],
        studyMode: null,
      }),
    ).toBe('2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.');
  });

  it('дистанційне навчання кількох груп', () => {
    expect(
      composeOrderNote({
        kind: 'STUDY_MODE',
        title: 'Дистанційне навчання',
        startsOn: '2026-09-01',
        endsOn: '2026-10-31',
        scope: 'GROUPS',
        courses: [],
        groupCodes: ['4КІ1', '4КІ2', '4КІ3'],
        studyMode: 'ONLINE',
      }),
    ).toBe('Групи 4КІ1, 4КІ2, 4КІ3 з 01.09.26р. по 31.10.26 навчаються – дистанційно.');
  });

  it('не складає неповну примітку', () => {
    const base = { kind: 'PRACTICE' as const, title: 'Практика', scope: 'GROUPS' as const, courses: [], studyMode: null };
    expect(composeOrderNote({ ...base, startsOn: '2026-10-19', endsOn: '2026-10-30', groupCodes: [] })).toBe('');
    expect(composeOrderNote({ ...base, startsOn: '', endsOn: '2026-10-30', groupCodes: ['2КІ3'] })).toBe('');
    expect(
      composeOrderNote({ ...base, kind: 'STUDY_MODE', startsOn: '2026-09-01', endsOn: '2026-10-31', groupCodes: ['4КІ1'], studyMode: null }),
    ).toBe('');
  });

  it('канікули для всіх і дата з двома цифрами року', () => {
    expect(orderDate('2026-01-05')).toBe('05.01.26');
    expect(
      composeOrderNote({
        kind: 'HOLIDAY',
        title: 'Осінні канікули',
        startsOn: '2026-10-26',
        endsOn: '2026-10-30',
        scope: 'ALL',
        courses: [],
        groupCodes: [],
        studyMode: null,
      }),
    ).toBe('Усі групи: осінні канікули з 26.10.26р. по 30.10.26 р.');
  });

  it('кого стосується період', () => {
    expect(periodAudience({ scope: 'COURSES', courses: [3], groups: [] })).toBe('3 курс');
    expect(periodAudience({ scope: 'COURSES', courses: [4, 3], groups: [] })).toBe('3, 4 курси');
    const groups = ['1КІ1', '1КІ2', '1КІ3', '1ТК1', '1ТК2'].map((code, i) => ({ id: String(i), code, course: 1 }));
    expect(periodAudience({ scope: 'GROUPS', courses: null, groups })).toBe('1КІ1, 1КІ2, 1КІ3 та ще 2');
    expect(periodAudience({ scope: 'ALL', courses: null, groups: [] })).toBe('Усі групи');
  });
});

describe('редактор дзвінків', () => {
  const active = BELL_PROFILES[0] as { slots: BellProfileSlotDto[] };

  it('бачить однакові пн–чт і коротку пʼятницю профілю «2 зміни, 60 хв»', () => {
    const grid = gridFromSlots(active.slots);
    expect(grid.sameMonThu).toBe(true);
    expect(grid.days[1]).toHaveLength(8);
    expect(grid.days[5]).toHaveLength(6);
    expect(grid.days[5][3]).toEqual({ pair: 4, startsAt: '12:20', endsAt: '13:20' });
  });

  it('у режимі «пн–чт однакові» копіює понеділок на вт–чт і нумерує пари підряд', () => {
    const grid = gridFromSlots(active.slots);
    const edited = { ...grid, days: { ...grid.days, 1: grid.days[1].map((s, i) => (i === 0 ? { ...s, startsAt: '08:00' } : s)) } };
    const slots = slotsFromGrid(edited);
    expect(slots.filter((s) => s.weekday === 4 && s.pair === 1)[0]?.startsAt).toBe('08:00');
    expect(slots.filter((s) => s.weekday === 5 && s.pair === 1)[0]?.startsAt).toBe('08:30');
    expect(slots).toHaveLength(8 * 4 + 6);
  });

  it('знаходить помилки часу: кінець раніше початку, накладання на попередню пару, порожні поля', () => {
    const errors = validateDay([
      { pair: 1, startsAt: '08:30', endsAt: '09:30' },
      { pair: 2, startsAt: '09:20', endsAt: '10:20' },
      { pair: 3, startsAt: '11:00', endsAt: '10:00' },
      { pair: 4, startsAt: '', endsAt: '12:00' },
    ]);
    expect(errors.get(0)).toBeUndefined();
    expect(errors.get(1)).toBe('Починається раніше, ніж закінчується 1 пара');
    expect(errors.get(2)).toBe('Пара закінчується раніше, ніж починається');
    expect(errors.get(3)).toBe('Вкажіть початок і кінець пари');
    expect(hasErrors({ sameMonThu: true, days: { 1: [], 2: [], 3: [], 4: [], 5: [{ pair: 1, startsAt: '08:30', endsAt: '09:30' }] } })).toBe(true);
  });

  it('рахує перерви й пропонує наступну пару з тією самою тривалістю', () => {
    const day = [
      { pair: 1, startsAt: '08:30', endsAt: '09:30' },
      { pair: 2, startsAt: '09:40', endsAt: '10:40' },
    ];
    expect(breakAfter(day, 0)).toBe(10);
    expect(breakAfter(day, 1)).toBeNull();
    expect(nextSlot(day)).toEqual({ pair: 3, startsAt: '10:50', endsAt: '11:50' });
    expect(nextSlot([])).toEqual({ pair: 1, startsAt: '08:30', endsAt: '09:30' });
  });
});
