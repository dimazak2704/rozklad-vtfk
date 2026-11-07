import { describe, expect, it } from 'vitest';
import { disciplineLine, hashString, LINE_COUNT, oklchHue } from './discipline-color';

describe('колір лінії дисципліни', () => {
  it('однаковий для того самого id на будь-якому екрані', () => {
    const ref = { id: '0b8f7a4e-3c1d-4d2b-9a55-1f2e3d4c5b6a', color: null };
    expect(disciplineLine(ref)).toBe(disciplineLine({ ...ref }));
    expect(disciplineLine(ref)).toBeGreaterThanOrEqual(0);
    expect(disciplineLine(ref)).toBeLessThan(LINE_COUNT);
  });

  it('розкидає різні дисципліни по різних лініях', () => {
    const lines = new Set(
      ['СТП', 'КМ', 'ДК', 'ФВ', 'Прогр.', 'ЕУ', 'ОПЦЗ', 'Укр.мов.', 'Ін.мова', 'СП', 'КБ', 'НДЕКС'].map((id) =>
        disciplineLine({ id, color: null }),
      ),
    );
    expect(lines.size).toBeGreaterThanOrEqual(7);
  });

  it('поважає колір з каталогу, підбираючи найближчу перевірену лінію', () => {
    // чистий синій (#1f6feb) ближчий до лінії 245°, червоний (#d73a49) — до 12°
    expect(disciplineLine({ id: 'x', color: '#1f6feb' })).toBe(8);
    expect(disciplineLine({ id: 'x', color: '#d73a49' })).toBe(0);
  });

  it('ігнорує сірий або зіпсований колір і бере хеш', () => {
    expect(oklchHue('#808080')).toBeNull();
    expect(oklchHue('nope')).toBeNull();
    expect(disciplineLine({ id: 'abc', color: '#808080' })).toBe(hashString('abc') % LINE_COUNT);
  });
});
