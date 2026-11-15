import { describe, expect, it } from 'vitest';
import { DEFAULT_WEIGHTS } from '@/api/generation';
import type { GenerationWeights } from '@/api/types';
import {
  changedPriorities,
  defaultPriority,
  FIXED_RULES,
  PRIORITIES,
  PRIORITY_LABEL,
  PRIORITY_RULES,
  priorityOf,
  withFixedRules,
  withPriority,
} from './priorities';

describe('пріоритети замість ваг', () => {
  it('кожна з 14 ваг належить рівно одному пріоритету або одному правилу «Діє завжди»', () => {
    const keys = [...PRIORITY_RULES.flatMap((rule) => Object.keys(rule.ladder)), ...FIXED_RULES.flatMap((rule) => rule.weights)];
    expect(new Set(keys).size).toBe(keys.length);
    expect([...keys].sort()).toEqual(Object.keys(DEFAULT_WEIGHTS).sort());
  });

  it('диспетчер бачить лише компроміси: підгрупи, спортзал і дні з однією парою — правила, а не пріоритети', () => {
    expect(PRIORITY_RULES.map((rule) => rule.label)).toEqual([
      'Вікна груп',
      'Рвані дні',
      'Дисципліна двічі на день',
      '4-та пара зміни',
      'Вікна викладачів',
      'Дні викладачів',
      'Побажання викладачів',
      'Аудиторія викладача',
      'Не переставляти без потреби',
    ]);
    const ruleWeights = PRIORITY_RULES.flatMap((rule) => Object.keys(rule.ladder));
    for (const key of ['subgroupsTogether', 'specialRooms', 'studentOnePairDay']) expect(ruleWeights).not.toContain(key);
    expect(FIXED_RULES.map((rule) => rule.text).join(' ')).toMatch(/Підгрупи однієї дисципліни займаються одночасно/);
    // одне речення на правило, без статистики й ваг
    for (const rule of PRIORITY_RULES) {
      expect(rule.hint.replace(/\.$/, ''), rule.label).not.toContain('. ');
      expect(rule.hint, rule.label).not.toMatch(/вага|\d+ з \d+|%/);
    }
  });

  it('типові рівні дають рівно типові ваги core', () => {
    let weights = withFixedRules(Object.fromEntries(Object.keys(DEFAULT_WEIGHTS).map((key) => [key, -1])) as unknown as GenerationWeights);
    for (const rule of PRIORITY_RULES) weights = withPriority(weights, rule, defaultPriority(rule));
    expect(weights).toEqual(DEFAULT_WEIGHTS);
    expect(changedPriorities({ ...DEFAULT_WEIGHTS })).toEqual([]);
  });

  it('4-та пара зміни типово вимкнена: коледж вважає її звичайною (власник, 15.11.2026)', () => {
    const rule = PRIORITY_RULES.find((candidate) => candidate.id === 'latePairs');
    expect(rule).toBeDefined();
    expect(defaultPriority(rule as (typeof PRIORITY_RULES)[number])).toBe('off');
    expect(DEFAULT_WEIGHTS.latePairs).toBe(0);
    // правило лишається: його можна ввімкнути
    expect(withPriority({ ...DEFAULT_WEIGHTS }, rule as (typeof PRIORITY_RULES)[number], 'important').latePairs).toBe(9);
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

  it('жоден рівень не називається «Обовʼязково»: усі пріоритети мʼякі', () => {
    expect(Object.values(PRIORITY_LABEL)).not.toContain('Обовʼязково');
  });

  it('ваги поза рівнями (стара задача з повзунком) — «вага N», а не вигаданий рівень', () => {
    const changed = changedPriorities({ ...DEFAULT_WEIGHTS, windows: 50 });
    expect(changed).toEqual([{ rule: expect.objectContaining({ id: 'groupWindows' }), text: 'вага 50' }]);
  });

  it('стара задача з нетиповою вагою правила «Діє завжди» показує її чесно, а форма повертає типову', () => {
    const old = { ...DEFAULT_WEIGHTS, subgroupsTogether: 10, specialRooms: 0 };
    expect(changedPriorities(old)).toEqual([
      { rule: expect.objectContaining({ label: 'Підгрупи разом' }), text: 'вага 10' },
      { rule: expect.objectContaining({ label: 'Фізкультура в спортзалі' }), text: 'вага 0' },
    ]);
    expect(withFixedRules(old)).toEqual(DEFAULT_WEIGHTS);
  });
});
