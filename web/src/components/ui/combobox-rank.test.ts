import { describe, expect, it } from 'vitest';
import { rankOptions } from './combobox-rank';

const opt = (label: string, keywords?: string[]) => ({ label, keywords });

describe('rankOptions', () => {
  it('returns the list unchanged for a blank query', () => {
    const options = [opt('Б'), opt('А')];
    expect(rankOptions(options, '  ')).toBe(options);
  });

  it('drops typo matches when a stronger match exists', () => {
    const labels = rankOptions([opt('1КІ1'), opt('4КІ1'), opt('2КІ1')], '4КІ1').map((o) => o.label);
    expect(labels).toEqual(['4КІ1']);
  });

  it('falls back to typo matches when nothing stronger matches', () => {
    const labels = rankOptions([opt('Кренцін'), opt('Непийвода')], 'кренцин').map((o) => o.label);
    expect(labels).toEqual(['Кренцін']);
  });

  it('never offers typo matches for a query with a digit', () => {
    expect(rankOptions([opt('4КІ1'), opt('4КІ2')], '4КІ3')).toEqual([]);
  });

  it('sorts by score and keeps the original order for ties', () => {
    const labels = rankOptions([opt('4КІ12'), opt('4КІ2'), opt('4КІ1'), opt('4КІ11')], '4КІ1').map((o) => o.label);
    expect(labels).toEqual(['4КІ1', '4КІ12', '4КІ11']);
  });

  it('matches by keywords too', () => {
    const labels = rankOptions([opt('Кренцін М. Д.', ['Микола']), opt('Інший')], 'микола').map((o) => o.label);
    expect(labels).toEqual(['Кренцін М. Д.']);
  });
});
