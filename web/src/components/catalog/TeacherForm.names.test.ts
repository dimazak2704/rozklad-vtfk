import { describe, expect, it } from 'vitest';
import { displayNameFollow, followName, shortNameFollow, surnameMismatch } from './TeacherForm.names';

const krentsin = { lastName: 'Кренцін', firstName: 'Михайло', middleName: 'Дмитрович' };

describe('коротке й публічне імʼя за ПІБ', () => {
  it('складене з ПІБ — складається заново, зокрема з новими ініціалами', () => {
    expect(shortNameFollow('Кренцін М.Д.', krentsin)).toBe('derived');
    expect(followName('Кренцін М.Д.', 'derived', krentsin, { ...krentsin, firstName: 'Олег' })).toBe('Кренцін О.Д.');
  });

  it('починається з прізвища — міняється лише прізвище', () => {
    expect(shortNameFollow('Кренцін', krentsin)).toBe('surname');
    expect(shortNameFollow('ХарченкоГ.', { lastName: 'Харченко', firstName: '', middleName: '' })).toBe('surname');
    expect(followName('Кренцін М. Д.', 'surname', krentsin, { ...krentsin, lastName: 'Кренцин' })).toBe('Кренцин М. Д.');
    expect(displayNameFollow('Поліщук Л.', 'Поліщук')).toBe('surname');
  });

  it('інше написання, частина довшого прізвища чи порожнє поле — не чіпаємо', () => {
    expect(shortNameFollow('Микола К.', krentsin)).toBeNull();
    expect(shortNameFollow('Кренцін', { ...krentsin, lastName: ' ' })).toBeNull();
    expect(displayNameFollow('Кренціна', 'Кренцін')).toBeNull();
    expect(shortNameFollow('', krentsin)).toBeNull();
    expect(displayNameFollow('  ', 'Кренцін')).toBeNull();
    expect(followName('Микола К.', null, krentsin, { ...krentsin, lastName: 'Кренцин' })).toBe('Микола К.');
  });

  it('прізвище стерли — значення лишається, доки не введуть нове', () => {
    expect(followName('Кренцін', 'surname', krentsin, { ...krentsin, lastName: ' ' })).toBe('Кренцін');
    expect(followName('Кренцін', 'surname', { ...krentsin, lastName: 'Інше' }, { ...krentsin, lastName: 'Кренцин' })).toBe('Кренцін');
  });

  it('попереджає, коли прізвище в короткому імені інше', () => {
    expect(surnameMismatch('Кренцін М.Д.', 'Кренцин')).toBe(true);
    expect(surnameMismatch('КРЕНЦІН М.Д.', 'Кренцін')).toBe(false);
    expect(surnameMismatch('', 'Кренцін')).toBe(false);
    expect(surnameMismatch('Кренцін', '')).toBe(false);
  });
});
