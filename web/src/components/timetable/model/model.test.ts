import { describe, expect, it } from 'vitest';
import type { Parity, PublicGroupDto, SemesterRef, ShiftWindowDto, TemplateLessonDto, TimetableVersionDto } from '@/api/types';
import {
  buildEntities,
  dropTargetId,
  filterEntities,
  layoutCell,
  moveRequestFor,
  pairRows,
  parseDropTargetId,
  searchEntities,
  targetParities,
  type BoardEntity,
} from './grid';
import { EMPTY_HISTORY, historyFor, historyReducer, HISTORY_LIMIT, slotOf, type HistoryEntry } from './history';
import { SlotIndex } from './occupancy';
import { defaultEffectiveFrom, pickVersion } from './versions';

const WINDOWS: ShiftWindowDto[] = [
  ...([1, 2, 3, 4, 5] as const).map((weekday) => ({ shift: 1 as const, weekday, firstPair: 1, lastPair: 4 })),
  ...([1, 2, 3, 4] as const).map((weekday) => ({ shift: 2 as const, weekday, firstPair: 5, lastPair: 8 })),
  { shift: 2, weekday: 5, firstPair: 4, lastPair: 6 },
];

let seq = 0;
type LessonInput = Omit<Partial<TemplateLessonDto>, 'teacher' | 'room'> & { code?: string; teacher?: string | null; room?: string | null };

function lesson(partial: LessonInput = {}): TemplateLessonDto {
  seq += 1;
  const code = partial.code ?? '4КІ1';
  const { teacher = 'Кренцін', room = '301', ...rest } = partial;
  return {
    id: `l${seq}`,
    weekday: 1,
    pair: 1,
    parity: 'BOTH',
    group: { id: `g-${code}`, code, course: Number(code[0]) },
    subgroup: 0,
    discipline: { id: 'd-stp', shortName: 'СТП', name: 'Сучасні технології проєктування', color: null },
    teacher: teacher ? { id: `t-${teacher}`, shortName: teacher, displayName: teacher } : null,
    room: room ? { id: `r-${room}`, name: room, type: room === 'с.з.' ? 'GYM' : 'CLASSROOM' } : null,
    lessonKind: 'MIXED',
    loadItemId: null,
    pinned: false,
    online: false,
    streamId: null,
    ...rest,
  };
}

const group = (code: string, shift: 1 | 2 = 1): PublicGroupDto => ({
  id: `g-${code}`,
  code,
  course: Number(code[0]),
  specialty: { letters: 'КІ', name: 'Компʼютерна інженерія' },
  department: 'Компʼютерне відділення',
  shift,
});

describe('розкладка клітинки', () => {
  it('уся група займає всю ширину, підгрупи — свої доріжки', () => {
    const layout = layoutCell([lesson({ subgroup: 2 }), lesson({ subgroup: 1 })], 'group', 2);
    expect(layout.lanes).toBe(2);
    expect(layout.items.map((i) => [i.lesson.subgroup, i.laneStart, i.laneSpan, i.rowSpan])).toEqual([
      [1, 1, 1, 2],
      [2, 2, 1, 2],
    ]);
  });

  it('чисельник зверху, знаменник знизу, ціла пара на обидва рядки', () => {
    const items = layoutCell([lesson({ parity: 'DENOMINATOR' }), lesson({ parity: 'NUMERATOR' })], 'group', 1).items;
    expect(items.map((i) => [i.lesson.parity, i.rowStart, i.rowSpan])).toEqual([
      ['NUMERATOR', 1, 1],
      ['DENOMINATOR', 2, 1],
    ]);
  });

  it('накладка отримує окрему доріжку, а не ховається під іншою парою', () => {
    const layout = layoutCell([lesson(), lesson()], 'group', 1);
    expect(layout.lanes).toBe(2);
    expect(layout.items.map((i) => i.laneStart)).toEqual([1, 2]);
  });

  it('у викладача половинки різних тижнів стають в одну доріжку', () => {
    const layout = layoutCell([lesson({ parity: 'NUMERATOR' }), lesson({ parity: 'DENOMINATOR', code: '4КІ2' })], 'teacher');
    expect(layout.lanes).toBe(1);
  });
});

describe('рядки пар і сутності', () => {
  it('група першої зміни — пари 1–4, другої — 4–8 (пʼятниця з 4-ї)', () => {
    const [first] = buildEntities('group', [], { groups: [group('4КІ1')], teachers: [], rooms: [] });
    const [second] = buildEntities('group', [], { groups: [group('2КІ1', 2)], teachers: [], rooms: [] });
    expect(pairRows(first as BoardEntity, WINDOWS)).toEqual([1, 2, 3, 4]);
    expect(pairRows(second as BoardEntity, WINDOWS)).toEqual([4, 5, 6, 7, 8]);
  });

  it('пара поза вікном теж отримує рядок, щоб конфлікт було видно', () => {
    const [entity] = buildEntities('group', [lesson({ pair: 6 })], { groups: [group('4КІ1')], teachers: [], rooms: [] });
    expect(pairRows(entity as BoardEntity, WINDOWS)).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it('викладачі й аудиторії — лише ті, що мають пари; курс фільтрує через їхні групи', () => {
    const lessons = [lesson({ code: '4КІ1', teacher: 'Кренцін' }), lesson({ code: '2КІ1', teacher: 'Стець', room: '14' })];
    const catalog = { groups: [group('4КІ1'), group('2КІ1', 2)], teachers: [], rooms: [] };
    const teachers = buildEntities('teacher', lessons, catalog);
    expect(teachers.map((t) => t.label)).toEqual(['Кренцін', 'Стець']);
    const fourth = filterEntities(teachers, { courses: [4], department: null, query: '', conflictsOnly: false }, new Set());
    expect(fourth.map((t) => t.label)).toEqual(['Кренцін']);
  });

  it('пошук: точний код групи показує лише її, частина коду — усі схожі', () => {
    const entities = buildEntities('group', [], { groups: ['1КІ3', '4КІ1', '4КІ3'].map((code) => group(code)), teachers: [], rooms: [] });
    expect(searchEntities(entities, '4КІ3').map((e) => e.label)).toEqual(['4КІ3']);
    expect(searchEntities(entities, '4ki').map((e) => e.label)).toEqual(['4КІ1', '4КІ3']);
  });

  it('«лише з конфліктами» лишає сутності з жорстко конфліктними парами', () => {
    const conflicted = lesson({ code: '4КІ3' });
    const entities = buildEntities('group', [conflicted, lesson({ code: '4КІ1' })], {
      groups: [group('4КІ1'), group('4КІ3')],
      teachers: [],
      rooms: [],
    });
    const result = filterEntities(entities, { courses: [], department: null, query: '', conflictsOnly: true }, new Set([conflicted.id]));
    expect(result.map((e) => e.label)).toEqual(['4КІ3']);
  });
});

describe('цілі перетягування', () => {
  it('ідентифікатор цілі читається назад без втрат', () => {
    const target = { view: 'room' as const, entityId: 'r-301', weekday: 3 as const, pair: 4, parity: 'DENOMINATOR' as Parity };
    expect(parseDropTargetId(dropTargetId(target))).toEqual(target);
    expect(parseDropTargetId('something-else')).toBeNull();
  });

  it('ціла пара лишається цілою, половинка — половинкою', () => {
    expect(targetParities('BOTH')).toEqual(['BOTH']);
    expect(targetParities('NUMERATOR')).toEqual(['NUMERATOR', 'DENOMINATOR']);
  });

  it('кидок в інший рядок аудиторій змінює й аудиторію', () => {
    const l = lesson({ room: '301' });
    expect(moveRequestFor(l, { view: 'room', entityId: 'r-14', weekday: 2, pair: 3, parity: 'BOTH' })).toEqual({
      weekday: 2,
      pair: 3,
      parity: 'BOTH',
      roomId: 'r-14',
    });
    expect(moveRequestFor(l, { view: 'group', entityId: l.group.id, weekday: 2, pair: 3, parity: 'BOTH' })).not.toHaveProperty('roomId');
  });
});

describe('попередня перевірка слота', () => {
  const busy = lesson({ code: '4КІ2', weekday: 1, pair: 4, teacher: 'Кренцін', room: '301' });
  const index = new SlotIndex([busy, lesson({ code: '4КІ2', weekday: 2, pair: 1, parity: 'NUMERATOR', teacher: 'Стець', room: '14' })]);
  const base = { groupId: 'g-4КІ3', subgroup: 0, shift: 1 as const, roomType: 'CLASSROOM' as const };

  it('бачить зайнятого викладача й аудиторію', () => {
    const issues = index.check({ ...base, weekday: 1, pair: 4, parity: 'BOTH', teacherId: 't-Кренцін', roomId: 'r-301' }, WINDOWS);
    expect(issues.map((i) => i.kind)).toEqual(['TEACHER', 'ROOM']);
    expect(issues[0]?.message).toContain('Кренцін у цей час веде 4КІ2');
  });

  it('чисельник і знаменник не заважають одне одному', () => {
    const issues = index.check({ ...base, weekday: 2, pair: 1, parity: 'DENOMINATOR', teacherId: 't-Стець', roomId: 'r-14' }, WINDOWS);
    expect(issues).toEqual([]);
  });

  it('власне місце пари не вважається зайнятим, а пара поза зміною — конфлікт', () => {
    const own = index.check(
      { ...base, groupId: 'g-4КІ2', weekday: 1, pair: 4, parity: 'BOTH', teacherId: 't-Кренцін', roomId: 'r-301', ignoreIds: new Set([busy.id]) },
      WINDOWS,
    );
    expect(own).toEqual([]);
    const late = index.check({ ...base, weekday: 1, pair: 6, parity: 'BOTH' }, WINDOWS);
    expect(late.map((i) => i.kind)).toEqual(['SHIFT']);
  });

  it('спортзал приймає кілька груп — його не позначаємо', () => {
    const gym = new SlotIndex([lesson({ room: 'с.з.', weekday: 1, pair: 2 })]);
    const issues = gym.check({ ...base, weekday: 1, pair: 2, parity: 'BOTH', roomId: 'r-с.з.', roomType: 'GYM', teacherId: null }, WINDOWS);
    expect(issues).toEqual([]);
  });
});

describe('історія правок', () => {
  const move = (n: number): Extract<HistoryEntry, { kind: 'move' }> => ({
    kind: 'move',
    lessonId: `l${n}`,
    from: { weekday: 1, pair: 1, parity: 'BOTH' },
    to: { weekday: 1, pair: 2, parity: 'BOTH' },
    fromRoom: null,
    toRoom: null,
    label: `#${n}`,
  });

  it('скасування переносить запис у «повторити», нова правка її очищає', () => {
    let state = historyReducer(EMPTY_HISTORY, { type: 'push', versionId: 'v1', entry: move(1) });
    state = historyReducer(state, { type: 'push', versionId: 'v1', entry: move(2) });
    state = historyReducer(state, { type: 'undone' });
    expect(state.undo.map((e) => e.label)).toEqual(['#1']);
    expect(state.redo.map((e) => e.label)).toEqual(['#2']);
    state = historyReducer(state, { type: 'push', versionId: 'v1', entry: move(3) });
    expect(state.redo).toEqual([]);
  });

  it('історія тримає не більше 50 правок і належить одній версії', () => {
    let state = EMPTY_HISTORY;
    for (let i = 0; i < HISTORY_LIMIT + 5; i += 1) state = historyReducer(state, { type: 'push', versionId: 'v1', entry: move(i) });
    expect(state.undo).toHaveLength(HISTORY_LIMIT);
    expect(historyFor(state, 'v2').undo).toEqual([]);
    state = historyReducer(state, { type: 'push', versionId: 'v2', entry: move(99) });
    expect(state.undo.map((e) => e.label)).toEqual(['#99']);
  });

  it('відновлене заняття з новим id оновлює посилання в записах', () => {
    const deleted = lesson();
    let state = historyReducer(EMPTY_HISTORY, { type: 'push', versionId: 'v1', entry: { ...move(1), lessonId: deleted.id } });
    state = historyReducer(state, { type: 'push', versionId: 'v1', entry: { kind: 'delete', lesson: deleted, label: 'del' } });
    state = historyReducer(state, { type: 'remap', from: deleted.id, to: { ...deleted, id: 'new-id' } });
    expect(state.undo[0]).toMatchObject({ lessonId: 'new-id' });
    expect(state.undo[1]).toMatchObject({ lesson: { id: 'new-id' } });
  });

  it('слот пари для скасування містить аудиторію', () => {
    expect(slotOf(lesson({ weekday: 3, pair: 2, parity: 'NUMERATOR', room: '14' }))).toEqual({
      weekday: 3,
      pair: 2,
      parity: 'NUMERATOR',
      roomId: 'r-14',
    });
  });
});

describe('версії', () => {
  const version = (id: string, status: TimetableVersionDto['status']): TimetableVersionDto => ({
    id,
    name: id,
    status,
    source: 'COPY',
    effectiveFrom: null,
    publishedAt: null,
    lessonCount: 0,
    hardConflicts: 0,
    softWarnings: 0,
    metrics: null,
  });

  it('за замовчуванням — версія з адреси, інакше чернетка, інакше опублікована', () => {
    const list = [version('pub', 'PUBLISHED'), version('draft', 'DRAFT')];
    expect(pickVersion(list, 'pub')?.id).toBe('pub');
    expect(pickVersion(list, 'missing')?.id).toBe('draft');
    expect(pickVersion([version('pub', 'PUBLISHED')], null)?.id).toBe('pub');
    expect(pickVersion([], null)).toBeNull();
  });

  it('публікувати пропонуємо з найближчого понеділка в межах семестру', () => {
    const semester: SemesterRef = { id: 's', name: 'I', startsOn: '2026-09-01', endsOn: '2026-12-30' };
    expect(defaultEffectiveFrom('2026-11-08', semester)).toBe('2026-11-09');
    expect(defaultEffectiveFrom('2026-11-09', semester)).toBe('2026-11-16');
    expect(defaultEffectiveFrom('2026-08-20', semester)).toBe('2026-09-01');
    expect(defaultEffectiveFrom('2026-12-29', semester)).toBe('2026-12-29');
  });
});
