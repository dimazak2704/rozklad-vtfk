import { describe, expect, it } from 'vitest';
import type { LessonView } from '@/api/types';
import { daySlots, lessonCounterpart, pairRange, roomLabel, rowTimes } from './schedule';

function lesson(pair: number, overrides: Partial<LessonView> = {}): LessonView {
  return {
    id: `l-${pair}-${overrides.subgroup ?? 0}`,
    pair,
    startsAt: '08:30',
    endsAt: '09:30',
    discipline: { id: 'd1', shortName: 'СТП', name: 'Сучасні технології проєктування', color: null },
    teacher: { id: 't1', shortName: 'Кренцін М.Д.', displayName: 'Кренцін' },
    room: { id: 'r1', name: '301', type: 'COMPUTER_LAB' },
    group: { id: 'g1', code: '4КІ1', course: 4 },
    subgroup: 0,
    kind: 'REGULAR',
    online: false,
    original: null,
    reasonText: null,
    changeId: null,
    ...overrides,
  };
}

describe('daySlots', () => {
  it('позначає порожню пару між заняттями як вікно', () => {
    const slots = daySlots({ lessons: [lesson(1), lesson(3)] });
    expect(slots.map((s) => [s.pair, s.isWindow])).toEqual([
      [1, false],
      [2, true],
      [3, false],
    ]);
  });

  it('бере час із дзвінків, навіть для вікна', () => {
    const slots = daySlots({ lessons: [lesson(1), lesson(3)] }, [
      { pair: 1, startsAt: '08:30', endsAt: '09:30' },
      { pair: 2, startsAt: '09:40', endsAt: '10:40' },
      { pair: 3, startsAt: '11:00', endsAt: '12:00' },
    ]);
    expect(slots[1]).toMatchObject({ startsAt: '09:40', endsAt: '10:40' });
  });

  it('ставить підгрупи по порядку', () => {
    const slots = daySlots({ lessons: [lesson(4, { subgroup: 2 }), lesson(4, { subgroup: 1 })] });
    expect(slots[0]?.lessons.map((l) => l.subgroup)).toEqual([1, 2]);
  });

  it('порожній день — без слотів', () => {
    expect(daySlots({ lessons: [] })).toEqual([]);
  });
});

describe('межі та час сітки тижня', () => {
  it('знаходить діапазон пар', () => {
    expect(pairRange([{ lessons: [lesson(2)] }, { lessons: [lesson(1), lesson(4)] }])).toEqual({ first: 1, last: 4 });
    expect(pairRange([{ lessons: [] }])).toBeNull();
  });

  it('бере найчастіший час пари (пʼятниця з іншим дзвінком не ламає рядок)', () => {
    const times = rowTimes([
      { lessons: [lesson(4, { startsAt: '12:10', endsAt: '13:10' })] },
      { lessons: [lesson(4, { startsAt: '12:10', endsAt: '13:10' })] },
      { lessons: [lesson(4, { startsAt: '12:20', endsAt: '13:20' })] },
    ]);
    expect(times.get(4)).toEqual({ startsAt: '12:10', endsAt: '13:10' });
  });
});

describe('підписи клітинки', () => {
  it('показує викладача для групи і групу для викладача', () => {
    expect(lessonCounterpart(lesson(1), 'group')).toBe('Кренцін');
    expect(lessonCounterpart(lesson(1), 'teacher')).toBe('4КІ1');
    expect(lessonCounterpart(lesson(1), 'room')).toBe('4КІ1 · Кренцін');
  });

  it('додає «ауд.» лише до номерів', () => {
    expect(roomLabel('301')).toBe('ауд.\u00a0301');
    expect(roomLabel('с.з.')).toBe('с.з.');
    expect(roomLabel(null)).toBeNull();
  });
});
