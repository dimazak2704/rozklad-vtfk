import { describe, expect, it } from 'vitest';
import { countLabel, PAIR_FORMS, PAIR_FORMS_ACC, PAIR_FORMS_GEN } from './labels';

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
