import { describe, expect, it } from 'vitest';
import { teacherLoadSummary } from './summary';

const krentsin = { id: 't-k', shortName: 'Кренцін М.Д.', displayName: 'Кренцін' };
const other = { id: 't-o', shortName: 'Непийвода М.В.', displayName: 'Непийвода' };
const group = (id: string) => ({ id, code: id, course: 4 });

describe('підсумок навантаження викладача', () => {
  it('рядки одного викладача: години тижня й семестру, рядки й групи', () => {
    const items = ['4КІ1', '4КІ2', '4КІ3'].map((code) => ({
      teachers: [{ subgroup: 0, teacher: krentsin }],
      hoursPerWeek: 4,
      plannedHours: 72,
      group: group(code),
    }));
    expect(teacherLoadSummary(items)).toEqual({ teacher: krentsin, hours: 12, planned: 216, rows: 3, groups: 3 });
  });

  it('обидві підгрупи в одного викладача — удвічі; різні викладачі в рядках — підсумку немає', () => {
    const split = { teachers: [{ subgroup: 1, teacher: krentsin }, { subgroup: 2, teacher: krentsin }], hoursPerWeek: 2, plannedHours: 36, group: group('3КІ1') };
    expect(teacherLoadSummary([split])).toMatchObject({ hours: 4, planned: 72 });
    const mixed = { teachers: [{ subgroup: 0, teacher: other }], hoursPerWeek: 2, plannedHours: 36, group: group('2КІ1') };
    expect(teacherLoadSummary([split, mixed])).toBeNull();
    expect(teacherLoadSummary([])).toBeNull();
  });
});
