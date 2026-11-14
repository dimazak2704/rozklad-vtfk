import { describe, expect, it } from 'vitest';
import { ambiguousGridNames, formatTeacherName, teacherSurname } from './teacher-name';

const KRENTSIN = { id: 't1', shortName: 'Кренцін М.Д.', displayName: 'Кренцін' };
const OVCHAR_INNA = { id: 't2', shortName: 'Овчар Ін.М.', displayName: 'Овчар' };
const OVCHAR_IVAN = { id: 't3', shortName: 'Овчар Ів.М.', displayName: 'Овчар' };

describe('formatTeacherName', () => {
  it('у сітці — прізвище з каталогу', () => {
    expect(formatTeacherName(KRENTSIN, 'grid')).toBe('Кренцін');
    // без підпису каталогу — прізвище з короткої форми
    expect(formatTeacherName({ shortName: 'ХарченкоГ.' }, 'grid')).toBe('Харченко');
  });

  it('у сітці однофамільців пише з ініціалами', () => {
    const ambiguous = ambiguousGridNames([OVCHAR_INNA, KRENTSIN, OVCHAR_IVAN, OVCHAR_INNA, null]);
    expect([...ambiguous]).toEqual(['Овчар']);
    expect(formatTeacherName(OVCHAR_INNA, 'grid', { ambiguous })).toBe('Овчар Ін.М.');
    expect(formatTeacherName(OVCHAR_IVAN, 'grid', { ambiguous })).toBe('Овчар Ів.М.');
    expect(formatTeacherName(KRENTSIN, 'grid', { ambiguous })).toBe('Кренцін');
  });

  it('той самий викладач кілька разів — не однофамілець', () => {
    expect(ambiguousGridNames([KRENTSIN, KRENTSIN]).size).toBe(0);
  });

  it('повна форма — «Прізвище Імʼя» без по батькові, коли імʼя відоме', () => {
    expect(formatTeacherName({ ...KRENTSIN, lastName: 'Кренцін', firstName: 'Михайло' }, 'full')).toBe('Кренцін\u00a0Михайло');
    // прізвище з короткої форми, якщо в DTO його немає
    expect(formatTeacherName({ ...OVCHAR_INNA, firstName: 'Інна' }, 'full')).toBe('Овчар\u00a0Інна');
  });

  it('без імені повна форма — прізвище з ініціалами через пробіл', () => {
    expect(formatTeacherName(KRENTSIN, 'full')).toBe('Кренцін\u00a0М.\u00a0Д.');
    expect(formatTeacherName(OVCHAR_IVAN, 'full')).toBe('Овчар\u00a0Ів.\u00a0М.');
    expect(formatTeacherName({ shortName: 'Крисак Л.' }, 'full')).toBe('Крисак\u00a0Л.');
    expect(formatTeacherName({ shortName: 'ХарченкоГ.' }, 'full')).toBe('Харченко\u00a0Г.');
    expect(formatTeacherName({ shortName: 'Коваль' }, 'full')).toBe('Коваль');
    // порожнє імʼя — як невідоме
    expect(formatTeacherName({ ...KRENTSIN, firstName: '  ' }, 'full')).toBe('Кренцін\u00a0М.\u00a0Д.');
  });

  it('для документів — коротке імʼя як у паперах', () => {
    expect(formatTeacherName({ ...KRENTSIN, firstName: 'Михайло' }, 'formal')).toBe('Кренцін М.Д.');
  });

  it('прізвище — з каталогу, а без нього — з короткої форми', () => {
    expect(teacherSurname({ shortName: 'Кренцін М.Д.', lastName: 'Кренцін' })).toBe('Кренцін');
    expect(teacherSurname({ shortName: 'Овчар Ін.М.' })).toBe('Овчар');
  });
});
