// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { bellSlots } from '../../fixtures';
import { confirmDeadline, lessonOver } from './factory';

/** Понеділок 09.11.2026 (UTC+2 у Києві). Дзвінки — з активного профілю мок-даних. */
const DATE = '2026-11-09';
const [first, second] = bellSlots(1);
if (!first || !second) throw new Error('Мок-дані: у понеділок мають бути дзвінки на 1 і 2 пару');

/** Момент «сьогодні о HH:MM за Києвом» плюс секунди. */
function kyiv(time: string, seconds = 0): Date {
  return new Date(`${DATE}T${time}:${String(seconds).padStart(2, '0')}+02:00`);
}

const pair = (n: number) => ({ date: DATE, pairFrom: n, pairTo: n });

describe('вікно підтвердження в моку — як ConfirmationWindow у core', () => {
  it('строк — не пізніше за початок пари', () => {
    const now = kyiv(first.startsAt);
    const before = new Date(now.getTime() - 30 * 60_000);
    // до 1 пари 30 хв, тайм-аут 120 — строк на початку пари
    expect(confirmDeadline(pair(1), before, 120)).toBe(now.toISOString());
    // тайм-аут коротший за час до пари — строк за тайм-аутом
    expect(confirmDeadline(pair(1), before, 10)).toBe(new Date(before.getTime() + 10 * 60_000).toISOString());
  });

  it('пара вже йде — строк до її кінця, якщо він раніший за тайм-аут', () => {
    const during = new Date(kyiv(first.startsAt).getTime() + 5 * 60_000);
    expect(confirmDeadline(pair(1), during, 600)).toBe(kyiv(first.endsAt).toISOString());
    expect(confirmDeadline(pair(1), during, 5)).toBe(new Date(during.getTime() + 5 * 60_000).toISOString());
  });

  it('пара минула з її кінцем; давніший день — теж; без дзвінка сьогоднішня — ні', () => {
    expect(lessonOver(pair(1), kyiv(first.endsAt, 0))).toBe(true);
    expect(lessonOver(pair(1), new Date(kyiv(first.endsAt).getTime() - 1_000))).toBe(false);
    expect(lessonOver(pair(2), kyiv(first.endsAt))).toBe(false);
    expect(lessonOver({ date: '2026-11-06', pairFrom: 8, pairTo: 8 }, kyiv('07:00'))).toBe(true);
    expect(lessonOver({ date: '2026-11-10', pairFrom: 1, pairTo: 1 }, kyiv('23:00'))).toBe(false);
    expect(lessonOver({ date: DATE, pairFrom: 99, pairTo: 99 }, kyiv('23:00'))).toBe(false);
  });
});
