import { describe, expect, it } from 'vitest';
import { ambiguousGridNames, formatTeacherName, namePartsFromFull, teacherSurname } from './teacher-name';

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

describe('довідник імен', () => {
  const ALEKS = { id: 'a1', shortName: 'Алексєєнко', displayName: 'Алексєєнко' };
  const names = new Map([['a1', namePartsFromFull('Алексєєнко Олександра Анатоліївна', 'Олександра')]]);

  it('частини імені з публічного «Прізвище Імʼя По батькові»', () => {
    expect(namePartsFromFull('Алексєєнко Олександра Анатоліївна', 'Олександра')).toEqual({
      fullName: 'Алексєєнко Олександра Анатоліївна',
      firstName: 'Олександра',
      lastName: 'Алексєєнко',
      middleName: 'Анатоліївна',
    });
    // без по батькові
    expect(namePartsFromFull('Коваль Олена', 'Олена')).toMatchObject({ lastName: 'Коваль', middleName: null });
    // налаштування вимкнене — нічого не вгадуємо
    expect(namePartsFromFull(null, null)).toEqual({ fullName: null, firstName: null });
    // імʼя не знайшлося в повному — лише готові поля
    expect(namePartsFromFull('Коваль Олена Петрівна', 'Оксана')).toEqual({ fullName: 'Коваль Олена Петрівна', firstName: 'Оксана' });
  });

  it('коротка форма без ініціалів — ініціали з довідника', () => {
    expect(formatTeacherName(ALEKS, 'grid', { names })).toBe(`Алексєєнко О.${NB}А.`);
    expect(formatTeacherName(ALEKS, 'full', { names })).toBe('Алексєєнко Олександра Анатоліївна');
    // довідника немає (або імені в ньому немає) — коротка форма як є
    expect(formatTeacherName(ALEKS, 'grid')).toBe('Алексєєнко');
    expect(formatTeacherName({ ...ALEKS, id: 'other' }, 'grid', { names })).toBe('Алексєєнко');
  });

  it('ініціали короткої форми мають перевагу над довідником', () => {
    const krentsin = { id: 'k', shortName: 'Кренцін М.Д.' };
    const directory = new Map([['k', namePartsFromFull('Кренцін Михайло Дмитрович', 'Михайло')]]);
    expect(formatTeacherName(krentsin, 'grid', { names: directory })).toBe(`Кренцін М.${NB}Д.`);
    // двобуквений ініціал однофамільця лишається, навіть коли імʼя відоме
    expect(formatTeacherName({ shortName: 'Овчар Ін.М.', firstName: 'Інна', middleName: 'Миколаївна' }, 'grid')).toBe(`Овчар Ін.${NB}М.`);
  });

  it('неповні ініціали короткої форми доповнює довідник', () => {
    const kolesnyk = { id: 'kl', shortName: 'Колесник А.' };
    const directory = new Map([['kl', namePartsFromFull('Колесник Анатолій Іванович', 'Анатолій')]]);
    expect(formatTeacherName(kolesnyk, 'grid', { names: directory })).toBe(`Колесник А.${NB}І.`);
    expect(formatTeacherName(kolesnyk, 'grid')).toBe('Колесник А.');
  });

  it('однофамільців рахує вже з ініціалами з довідника', () => {
    const other = { id: 'a2', shortName: 'Алексєєнко' };
    expect(ambiguousGridNames([ALEKS, other], names).size).toBe(0);
    expect([...ambiguousGridNames([ALEKS, other])]).toEqual(['Алексєєнко']);
  });
});
