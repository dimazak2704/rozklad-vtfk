import { describe, expect, it } from 'vitest';
import { nextDayForward } from './next-day';

const TOMORROW = { isTomorrow: true, weekday: 'пʼятницю' };
const MONDAY = { isTomorrow: false, weekday: 'понеділок' };

describe('nextDayForward', () => {
  it('пари без заміни потребують уваги, а неопубліковане розпорядження дописується до того ж речення', () => {
    expect(nextDayForward({ uncoveredLessons: 3, absences: 2, orderDraft: null }, TOMORROW)).toEqual({
      text: 'Завтра 3 пари без заміни.',
      needsAttention: true,
    });
    expect(nextDayForward({ uncoveredLessons: 1, absences: 1, orderDraft: { id: 'd', itemCount: 4 } }, MONDAY)).toEqual({
      text: 'На понеділок 1 пара без заміни, розпорядження ще не опубліковане.',
      needsAttention: true,
    });
  });

  it('усе вирішено: уваги потребує лише неопубліковане розпорядження', () => {
    expect(nextDayForward({ uncoveredLessons: 0, absences: 2, orderDraft: { id: 'd', itemCount: 12 } }, TOMORROW)).toEqual({
      text: 'На завтра усі пари вже вирішено, розпорядження ще не опубліковане.',
      needsAttention: true,
    });
    expect(nextDayForward({ uncoveredLessons: 0, absences: 2, orderDraft: { id: 'd', itemCount: 0 } }, TOMORROW).needsAttention).toBe(false);
  });

  it('без відсутностей, але з неопублікованим розпорядженням (додаткова пара, аудиторія) — потребує уваги', () => {
    expect(nextDayForward({ uncoveredLessons: 0, absences: 0, orderDraft: { id: 'd', itemCount: 2 } }, TOMORROW)).toEqual({
      text: 'На завтра розпорядження ще не опубліковане.',
      needsAttention: true,
    });
  });

  it('без відсутностей — спокійне речення, що не потребує уваги', () => {
    expect(nextDayForward({ uncoveredLessons: 0, absences: 0, orderDraft: null }, MONDAY)).toEqual({
      text: 'На понеділок відсутностей поки немає.',
      needsAttention: false,
    });
  });
});
