import { describe, expect, it } from 'vitest';
import { assignLines, disciplineLine, hashString, LINE_COUNT, LINE_HUES, NEUTRAL_LINE, oklchHue } from './discipline-color';

/** Відтінки, зарезервовані для станів занять і «живого» синього. */
const RESERVED_HUES = [27, 58, 70, 81, 150, 221, 226, 256];

describe('колір лінії дисципліни', () => {
  it('однаковий для того самого id на будь-якому екрані', () => {
    const ref = { id: '0b8f7a4e-3c1d-4d2b-9a55-1f2e3d4c5b6a', color: null };
    expect(disciplineLine(ref)).toBe(disciplineLine({ ...ref }));
    expect(disciplineLine(ref)).toBeGreaterThanOrEqual(0);
    expect(disciplineLine(ref)).toBeLessThan(LINE_COUNT);
  });

  it('жодна лінія не ближча за 30° до кольору стану чи «живого» синього', () => {
    for (const hue of LINE_HUES) {
      for (const reserved of RESERVED_HUES) {
        const raw = Math.abs(hue - reserved);
        expect(Math.min(raw, 360 - raw)).toBeGreaterThanOrEqual(30);
      }
    }
  });

  it('поважає колір з каталогу, підбираючи найближчу перевірену лінію', () => {
    // чистий синій (#1f6feb) ближчий до лінії 290°, червоний (#d73a49) — до 352°
    expect(disciplineLine({ id: 'x', color: '#1f6feb' })).toBe(2);
    expect(disciplineLine({ id: 'x', color: '#d73a49' })).toBe(4);
  });

  it('ігнорує сірий або зіпсований колір і бере хеш', () => {
    expect(oklchHue('#808080')).toBeNull();
    expect(oklchHue('nope')).toBeNull();
    expect(disciplineLine({ id: 'abc', color: '#808080' })).toBe(hashString('abc') % LINE_COUNT);
  });
});

describe('лінії на одному екрані', () => {
  it('п’ять різних дисциплін отримують п’ять різних ліній, шоста — нейтральна', () => {
    const ids = ['СТП', 'КМ', 'ДК', 'ФВ', 'Прогр.', 'ЕУ'];
    const lines = assignLines(ids.map((id) => ({ id, color: null })));
    const values = ids.map((id) => lines.get(id));
    expect(new Set(values.filter((v) => v !== NEUTRAL_LINE)).size).toBe(5);
    expect(values.filter((v) => v === NEUTRAL_LINE)).toHaveLength(1);
  });

  it('стабільна незалежно від порядку і повторів', () => {
    const a = assignLines([{ id: 'b', color: null }, { id: 'a', color: null }, { id: 'b', color: null }]);
    const b = assignLines([{ id: 'a', color: null }, { id: 'b', color: null }]);
    expect([...a.entries()].sort()).toEqual([...b.entries()].sort());
  });

  it('перші дві дисципліни розведені якнайдалі за відтінком', () => {
    const lines = assignLines([{ id: 'a', color: null }, { id: 'b', color: null }]);
    const hues = [lines.get('a'), lines.get('b')].map((i) => LINE_HUES[i ?? 0] ?? 0);
    const raw = Math.abs((hues[0] ?? 0) - (hues[1] ?? 0));
    expect(Math.min(raw, 360 - raw)).toBeGreaterThanOrEqual(100);
  });

  it('колір каталогу бере найближчу вільну лінію', () => {
    const lines = assignLines([
      { id: 'a', color: '#d73a49' },
      { id: 'b', color: '#c2185b' },
    ]);
    expect(lines.get('a')).not.toBe(lines.get('b'));
  });
});
