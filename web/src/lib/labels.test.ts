import { describe, expect, it } from 'vitest';
import { countLabel, durationLabel, lessonKindShown, PAIR_FORMS, PAIR_FORMS_ACC, PAIR_FORMS_GEN } from './labels';

const nbsp = (text: string) => text.replace(/ /g, ' ');

describe('лічильні іменники у потрібному відмінку', () => {
  it('називний: «1 пара», «2 пари», «5 пар»', () => {
    expect([1, 2, 5].map((n) => countLabel(n, PAIR_FORMS))).toEqual(['1 пара', '2 пари', '5 пар'].map(nbsp));
  });

  it('після дієслова (знахідний): «Зачепить 1 пару», «… 22 пари», «… 11 пар»', () => {
    expect([1, 22, 11, 0].map((n) => countLabel(n, PAIR_FORMS_ACC))).toEqual(['1 пару', '22 пари', '11 пар', '0 пар'].map(nbsp));
  });

  it('після прийменника (родовий): «для 1 пари», «з 2 пар», «з 21 пари»', () => {
    expect([1, 2, 21, 5].map((n) => countLabel(n, PAIR_FORMS_GEN))).toEqual(['1 пари', '2 пар', '21 пари', '5 пар'].map(nbsp));
  });
});

describe('durationLabel', () => {
  // нерозривний пробіл — між числом і одиницею, звичайний — між частинами
  const n = (text: string) => text.replace(/(\d) /g, '$1\u00a0');

  it('хвилини, години, а від доби — дні й години без хвилин', () => {
    expect([0, 23, 60, 65, 23 * 60 + 59].map(durationLabel)).toEqual(['0 хв', '23 хв', '1 год', '1 год 5 хв', '23 год 59 хв'].map(n));
    // «прострочено 150 год 53 хв» на стенді: тепер «6 дн 6 год»
    expect([24 * 60, 24 * 60 + 59, 150 * 60 + 53, 49 * 60].map(durationLabel)).toEqual(['1 дн', '1 дн', '6 дн 6 год', '2 дн 1 год'].map(n));
  });
});

describe('вид заняття в картці пари', () => {
  it('лекцію, практичне, лабораторну й семінар називає', () => {
    expect((['LECTURE', 'PRACTICE', 'LAB', 'SEMINAR'] as const).map(lessonKindShown)).toEqual(['лекція', 'практичне', 'лабораторна', 'семінар']);
  });

  it('змішаний чи невідомий вид не показує', () => {
    expect(lessonKindShown('MIXED')).toBeNull();
    expect(lessonKindShown(null)).toBeNull();
    expect(lessonKindShown(undefined)).toBeNull();
  });
});
