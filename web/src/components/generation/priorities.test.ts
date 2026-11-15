import { describe, expect, it } from 'vitest';
import { DEFAULT_WEIGHTS } from '@/api/generation';
import type { GenerationWeights } from '@/api/types';
import { changedPriorities, defaultPriority, PRIORITIES, PRIORITY_LABEL, PRIORITY_RULES, priorityOf, withPriority } from './priorities';

describe('пріоритети замість ваг', () => {
  it('кожна з 14 ваг належить рівно одному правилу', () => {
    const keys = PRIORITY_RULES.flatMap((rule) => Object.keys(rule.ladder));
    expect(new Set(keys).size).toBe(keys.length);
    expect([...keys].sort()).toEqual(Object.keys(DEFAULT_WEIGHTS).sort());
  });

  it('типові рівні дають рівно типові ваги core', () => {
    let weights = Object.fromEntries(Object.keys(DEFAULT_WEIGHTS).map((key) => [key, -1])) as unknown as GenerationWeights;
    for (const rule of PRIORITY_RULES) weights = withPriority(weights, rule, defaultPriority(rule));
    expect(weights).toEqual(DEFAULT_WEIGHTS);
    expect(changedPriorities({ ...DEFAULT_WEIGHTS })).toEqual([]);
  });

  it('рівні впорядковані: «Дуже важливо» > «Важливо» > «Бажано» > «Не враховувати» = 0', () => {
    for (const rule of PRIORITY_RULES) {
      const values = PRIORITIES.map((priority) => withPriority({ ...DEFAULT_WEIGHTS }, rule, priority));
      for (const key of Object.keys(rule.ladder) as Array<keyof GenerationWeights>) {
        const ladder = values.map((weights) => weights[key]);
        expect(ladder[3]).toBe(0);
        expect(ladder[0]).toBeGreaterThan(ladder[1] ?? 0);
        expect(ladder[1]).toBeGreaterThan(ladder[2] ?? 0);
        expect(ladder[2]).toBeGreaterThan(0);
      }
      // рівень, вибраний людиною, читається назад
      for (const [index, priority] of PRIORITIES.entries()) expect(priorityOf(rule, values[index] as GenerationWeights)).toBe(priority);
    }
  });

  it('жоден рівень не називається «Обовʼязково»: усі правила мʼякі (genfix repair)', () => {
    expect(Object.values(PRIORITY_LABEL)).not.toContain('Обовʼязково');
    // «Спортзал — як у базовій версії» — мʼяке правило з власною вагою, яку можна вимкнути
    const rule = PRIORITY_RULES.find((candidate) => candidate.id === 'specialRooms');
    expect(rule).toBeDefined();
    expect(withPriority({ ...DEFAULT_WEIGHTS }, rule as (typeof PRIORITY_RULES)[number], 'off').specialRooms).toBe(0);
  });

  it('ваги поза рівнями (стара задача з повзунком) — «вага N», а не вигаданий рівень', () => {
    const changed = changedPriorities({ ...DEFAULT_WEIGHTS, windows: 50 });
    expect(changed).toEqual([{ rule: expect.objectContaining({ id: 'groupWindows' }), text: 'вага 50' }]);
  });
});
