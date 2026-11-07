import { describe, expect, it } from 'vitest';
import {
  canonicalGroupCode,
  compactKey,
  editDistance,
  fromEnglishLayout,
  fuzzyFilter,
  groupCodeVariants,
  matchScore,
  normalizeText,
} from './search';

describe('canonicalGroupCode', () => {
  it.each([
    ['4ki1', '4КІ1'],
    ['4КІ1', '4КІ1'],
    ['4кі1', '4КІ1'],
    ['ІКІ1', '1КІ1'],
    ['1ТК 1', '1ТК1'],
    ['4КИ1', '4КІ1'],
    ['3 ф', '3Ф'],
  ])('%s → %s', (raw, expected) => {
    expect(canonicalGroupCode(raw)).toBe(expected);
  });

  it('повертає null для тексту, що не схожий на код групи', () => {
    expect(canonicalGroupCode('кренцін')).toBeNull();
    expect(canonicalGroupCode('6')).toBeNull();
    expect(canonicalGroupCode('')).toBeNull();
  });
});

describe('groupCodeVariants', () => {
  it('дає і візуальний, і фонетичний варіант для латиниці', () => {
    expect(groupCodeVariants('2ob')).toEqual(expect.arrayContaining(['2ОБ']));
    expect(groupCodeVariants('4ki1')).toEqual(['4КІ1']);
  });
});

describe('normalizeText', () => {
  it('зводить регістр, апострофи та латинські двійники в кириличному слові', () => {
    expect(normalizeText('П’ятниця')).toBe('пятниця');
    expect(normalizeText("Компʼютерні мережі")).toBe('компютерні мережі');
    // «o» та «p» латинські всередині кириличного слова
    expect(normalizeText('Кренцiн Пpокопчук')).toBe('кренцін прокопчук');
  });

  it('не чіпає латинські слова', () => {
    expect(normalizeText('Python')).toBe('python');
  });

  it('стискає пробіли', () => {
    expect(normalizeText('  Укр.   мова ')).toBe('укр. мова');
  });
});

describe('compactKey', () => {
  it('прибирає крапки й пробіли для абревіатур', () => {
    expect(compactKey('Укр.мова.')).toBe(compactKey('Укр. мова'));
    expect(compactKey('Вс.Іст.')).toBe(compactKey('Вс.іст.'));
  });
});

describe('fromEnglishLayout', () => {
  it('виправляє незмінену розкладку клавіатури', () => {
    expect(fromEnglishLayout('rhtywsy')).toBe('кренцін');
  });
});

describe('editDistance', () => {
  it('рахує вставки, заміни та перестановки', () => {
    expect(editDistance('кренцін', 'кренцін')).toBe(0);
    expect(editDistance('граболінська', 'гарболінська')).toBe(1);
    expect(editDistance('герасменко', 'герасименко')).toBe(1);
    expect(editDistance('abc', 'xyz', 1)).toBe(2);
  });
});

describe('matchScore і fuzzyFilter', () => {
  const items = [
    { label: '4КІ1', type: 'group' },
    { label: '4КІ2', type: 'group' },
    { label: 'Кренцін М.Д.', type: 'teacher' },
    { label: 'Непийвода М.В.', type: 'teacher' },
    { label: 'ауд. 6', type: 'room' },
    { label: 'ауд. 16', type: 'room' },
    { label: 'ауд. 306', type: 'room' },
  ];
  const texts = (item: (typeof items)[number]) => [item.label];

  it('латинський код групи знаходить кириличну групу першою', () => {
    expect(fuzzyFilter(items, '4ki1', texts)[0]?.label).toBe('4КІ1');
  });

  it('початок прізвища знаходить викладача', () => {
    expect(fuzzyFilter(items, 'кренц', texts).map((i) => i.label)).toEqual(['Кренцін М.Д.']);
  });

  it('номер аудиторії збігається лише з цілим числом', () => {
    expect(fuzzyFilter(items, '6', texts).map((i) => i.label)).toEqual(['ауд. 6']);
  });

  it('пробачає одну одруківку в довгому слові', () => {
    expect(matchScore('непиввода', 'Непийвода М.В.')).toBeGreaterThanOrEqual(40);
  });

  it('знаходить прізвище, набране в англійській розкладці', () => {
    expect(fuzzyFilter(items, 'rhtywsy', texts)[0]?.label).toBe('Кренцін М.Д.');
  });

  it('латиниця, набрана фонетично, знаходить групу', () => {
    const groups = [{ label: '2ОД' }, { label: '2ОБ' }];
    expect(fuzzyFilter(groups, '2ob', (g) => [g.label])[0]?.label).toBe('2ОБ');
  });

  it('порожній запит повертає список без змін', () => {
    expect(fuzzyFilter(items, '  ', texts)).toHaveLength(items.length);
  });
});
