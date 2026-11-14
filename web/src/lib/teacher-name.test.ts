import { describe, expect, it } from 'vitest';
import { ambiguousGridNames, formatTeacherName, teacherSurname } from './teacher-name';

const KRENTSIN = { id: 't1', shortName: 'Кренцін М.Д.', displayName: 'Кренцін' };
const OVCHAR_INNA = { id: 't2', shortName: 'Овчар Ін.М.', displayName: 'Овчар' };
const OVCHAR_IVAN = { id: 't3', shortName: 'Овчар Ів.М.', displayName: 'Овчар' };
const NB = ' ';

describe('formatTeacherName', () => {
  it('у сітці — завжди прізвище з ініціалами, а не голе прізвище з каталогу', () => {
    expect(formatTeacherName(KRENTSIN, 'grid')).toBe(`Кренцін М.${NB}Д.`);
    expect(formatTeacherName({ shortName: 'ХарченкоГ.' }, 'grid')).toBe('Харченко Г.');
    expect(formatTeacherName({ shortName: 'Крисак Л.' }, 'grid')).toBe('Крисак Л.');
    // імʼя, якщо воно є в DTO, не робить сітку довшою
    expect(formatTeacherName({ ...KRENTSIN, firstName: 'Михайло', middleName: 'Дмитрович' }, 'grid')).toBe(`Кренцін М.${NB}Д.`);
  });

  it('у сітці однофамільців розрізняють ініціали короткої форми', () => {
    expect(formatTeacherName(OVCHAR_INNA, 'grid')).toBe(`Овчар Ін.${NB}М.`);
    expect(formatTeacherName(OVCHAR_IVAN, 'grid')).toBe(`Овчар Ів.${NB}М.`);
    expect(ambiguousGridNames([OVCHAR_INNA, KRENTSIN, OVCHAR_IVAN, OVCHAR_INNA, null]).size).toBe(0);
  });

  it('без ініціалів у короткій формі — ініціали з імені й по батькові', () => {
    expect(formatTeacherName({ shortName: 'Коваль', firstName: 'Олена', middleName: 'петрівна' }, 'grid')).toBe(`Коваль О.${NB}П.`);
    expect(formatTeacherName({ shortName: 'Коваль', firstName: 'Олена' }, 'grid')).toBe('Коваль О.');
    expect(formatTeacherName({ shortName: 'Коваль' }, 'grid')).toBe('Коваль');
  });

  it('двоє з однаковим підписом — імʼя повністю, коли воно відоме', () => {
    const olena = { id: 'a', shortName: 'Коваль О.П.', firstName: 'Олена', middleName: 'Петрівна' };
    const oksana = { id: 'b', shortName: 'Коваль О.П.', firstName: 'Оксана' };
    const ambiguous = ambiguousGridNames([olena, oksana, KRENTSIN]);
    expect([...ambiguous]).toEqual([`Коваль О.${NB}П.`]);
    expect(formatTeacherName(olena, 'grid', { ambiguous })).toBe('Коваль Олена П.');
    expect(formatTeacherName(oksana, 'grid', { ambiguous })).toBe('Коваль Оксана');
    expect(formatTeacherName(KRENTSIN, 'grid', { ambiguous })).toBe(`Кренцін М.${NB}Д.`);
    // імені немає — лишаємо ініціали
    expect(formatTeacherName({ shortName: 'Коваль О.П.' }, 'grid', { ambiguous })).toBe(`Коваль О.${NB}П.`);
  });

  it('той самий викладач кілька разів — не однофамілець', () => {
    expect(ambiguousGridNames([KRENTSIN, KRENTSIN]).size).toBe(0);
  });

  it('повна форма — «Прізвище Імʼя По батькові», коли імʼя відоме', () => {
    expect(formatTeacherName({ ...KRENTSIN, fullName: ' Кренцін  Михайло Дмитрович ' }, 'full')).toBe('Кренцін Михайло Дмитрович');
    expect(formatTeacherName({ ...KRENTSIN, lastName: 'Кренцін', firstName: 'Михайло', middleName: 'Дмитрович' }, 'full')).toBe(
      'Кренцін Михайло Дмитрович',
    );
    // без по батькові — прізвище й імʼя; прізвище з короткої форми, якщо в DTO його немає
    expect(formatTeacherName({ ...OVCHAR_INNA, firstName: 'Інна' }, 'full')).toBe('Овчар Інна');
  });

  it('без імені (налаштування вимкнене) повна форма — як у сітці', () => {
    expect(formatTeacherName({ ...KRENTSIN, fullName: null }, 'full')).toBe(`Кренцін М.${NB}Д.`);
    expect(formatTeacherName(OVCHAR_IVAN, 'full')).toBe(`Овчар Ів.${NB}М.`);
    expect(formatTeacherName({ shortName: 'ХарченкоГ.' }, 'full')).toBe('Харченко Г.');
    expect(formatTeacherName({ shortName: 'Коваль' }, 'full')).toBe('Коваль');
    // порожнє імʼя — як невідоме
    expect(formatTeacherName({ ...KRENTSIN, firstName: '  ', fullName: ' ' }, 'full')).toBe(`Кренцін М.${NB}Д.`);
  });

  it('для документів — коротке імʼя як у паперах', () => {
    expect(formatTeacherName({ ...KRENTSIN, firstName: 'Михайло' }, 'formal')).toBe('Кренцін М.Д.');
  });

  it('прізвище — з каталогу, а без нього — з короткої форми', () => {
    expect(teacherSurname({ shortName: 'Кренцін М.Д.', lastName: 'Кренцін' })).toBe('Кренцін');
    expect(teacherSurname({ shortName: 'Овчар Ін.М.' })).toBe('Овчар');
  });
});
