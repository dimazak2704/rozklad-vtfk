import { describe, expect, it } from 'vitest';
import { parityActionLabel, parityConfirmation } from './parity-confirmation';

const week = {
  weekNumber: 12,
  weekStart: '2026-11-16',
  weekEnd: '2026-11-20',
  parity: 'NUMERATOR',
  computedParity: 'NUMERATOR',
} as const;

describe('ручна парність тижня', () => {
  it('дія меню називає, що станеться', () => {
    expect(parityActionLabel(week)).toBe('Зробити знаменником…');
    expect(parityActionLabel({ parity: 'DENOMINATOR', computedParity: 'NUMERATOR' })).toBe('Повернути до чергування (чисельник)…');
  });

  it('підтвердження пояснює наслідок і як повернути', () => {
    const ask = parityConfirmation(week, '2026-11-10');
    expect(ask.title).toBe('Зробити 12-й тиждень знаменником?');
    expect(ask.description).toContain('за чергуванням — чисельник, а стане знаменником');
    expect(ask.description).toContain('Розклад усіх груп на цей тиждень перебудується');
    expect(ask.description).toContain('журналу дій');
    expect(ask.description).not.toContain('вже минули');
    expect(ask.confirmLabel).toBe('Зробити знаменником');
  });

  it('посеред тижня — минулі дні не зміняться; повернення до чергування — своїми словами', () => {
    const back = parityConfirmation({ ...week, parity: 'DENOMINATOR' }, '2026-11-18');
    expect(back.title).toBe('Повернути 12-й тиждень до чергування?');
    expect(back.description).toContain('знову буде чисельником');
    expect(back.description).toContain('Дні, що вже минули, не зміняться.');
    expect(back.confirmLabel).toBe('Повернути до чергування');
  });

  it('тиждень, що вже минув: розклад не перебудовується — так і пишемо', () => {
    const ask = parityConfirmation(week, '2026-11-25');
    expect(ask.description).toContain('Тиждень уже минув, тож розклад цих днів не зміниться');
    expect(ask.description).not.toContain('перебудується');
    expect(ask.description).not.toContain('вже минули');
  });
});
