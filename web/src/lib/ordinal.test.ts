import { describe, expect, it } from 'vitest';
import { pairOrdinal, pairOrdinalGenitive, pairOrdinalLabel, weekOrdinal } from './ordinal';

describe('порядкові числівники', () => {
  it('пари: 1-ша … 8-ма', () => {
    expect([1, 2, 3, 4, 5, 6, 7, 8].map(pairOrdinal)).toEqual(['1-ша', '2-га', '3-тя', '4-та', '5-та', '6-та', '7-ма', '8-ма']);
    expect(pairOrdinal(11)).toBe('11-та');
    expect(pairOrdinalLabel(2)).toBe('2-га пара');
  });

  it('родовий відмінок і тиждень', () => {
    expect(`з ${pairOrdinalGenitive(4)} пари`).toBe('з 4-ї пари');
    expect(weekOrdinal(10)).toBe('10-й тиждень');
  });
});
