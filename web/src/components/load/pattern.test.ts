import { describe, expect, it } from 'vitest';
import { formatHours, loadPattern, pairsPerWeek, patternDescription, patternLabel } from './pattern';

describe('loadPattern (DOMAIN §4)', () => {
  it.each([
    [3, { whole: 1, half: 1 }, '1 + ½'],
    [4, { whole: 2, half: 0 }, '2'],
    [2, { whole: 1, half: 0 }, '1'],
    [1, { whole: 0, half: 1 }, '½'],
    [5, { whole: 2, half: 1 }, '2 + ½'],
  ])('%s год на тиждень → %o (%s)', (hours, pattern, label) => {
    expect(loadPattern(hours)).toEqual(pattern);
    expect(patternLabel(pattern)).toBe(label);
  });

  it('залишок від ¾ дає ще одну цілу пару, менше ¼ — нічого', () => {
    expect(loadPattern(3.5)).toEqual({ whole: 2, half: 0 }); // 1,75 пари
    expect(loadPattern(0.4)).toEqual({ whole: 0, half: 0 }); // 0,2 пари
    expect(loadPattern(0.5)).toEqual({ whole: 0, half: 1 }); // рівно ¼ — половинка
  });

  it('рахує й для інших налаштувань годин на пару', () => {
    expect(loadPattern(3, 3)).toEqual({ whole: 1, half: 0 });
    expect(() => loadPattern(2, 0)).toThrow(RangeError);
  });

  it('нуль і некоректні години — порожній шаблон', () => {
    expect(loadPattern(0)).toEqual({ whole: 0, half: 0 });
    expect(loadPattern(Number.NaN)).toEqual({ whole: 0, half: 0 });
    expect(patternLabel({ whole: 0, half: 0 })).toBe('0');
  });
});

describe('опис шаблону', () => {
  it('пояснює людською мовою', () => {
    expect(patternDescription({ whole: 1, half: 1 })).toBe(
      '1 пара щотижня і ще 1 пара через тиждень (лише чисельник або лише знаменник).',
    );
    expect(patternDescription({ whole: 2, half: 0 })).toBe('2 пари щотижня.');
    expect(patternDescription({ whole: 0, half: 1 })).toBe('Ще 1 пара через тиждень (лише чисельник або лише знаменник).');
  });

  it('пари в середньому й години з комою', () => {
    expect(pairsPerWeek({ whole: 1, half: 1 })).toBe(1.5);
    expect(formatHours(1.5)).toBe('1,5');
    expect(formatHours(4)).toBe('4');
  });
});
