import { describe, expect, it } from 'vitest';
import type { LoadItem } from '@/api/load';
import type { ChangeDto } from '@/api/types';
import {
  defaultTeacherDiscipline,
  lessonSelectable,
  loadTeachers,
  newChangeLabel,
  newChangeRequest,
  pairRange,
  prefillFromDeclined,
  prefillFromParams,
  standaloneChanges,
  standaloneHeading,
  teacherChangeKind,
  teacherDisciplines,
  validateNewChange,
  type NewChangeValues,
} from './new-change';

const NB = ' ';
const STP = {
  id: 'd-stp',
  shortName: 'СТП',
  name: 'Сучасні технології проєктування',
  color: null,
};
const DK = {
  id: 'd-dk',
  shortName: 'ДК',
  name: 'Ділові комунікації',
  color: null,
};
const LESSON = { id: 'l-1', pair: 3, subgroup: 0, discipline: STP };

const load = [
  {
    group: { id: 'g1', code: '4КІ1' },
    discipline: DK,
    teachers: [
      {
        subgroup: 0,
        teacher: {
          id: 't-dk',
          shortName: 'Коваль О.П.',
          displayName: 'Коваль',
        },
      },
    ],
  },
  {
    group: { id: 'g1', code: '4КІ1' },
    discipline: {
      id: 'd-eng',
      shortName: 'Ін.мова',
      name: 'Іноземна мова',
      color: null,
    },
    teachers: [
      {
        subgroup: 2,
        teacher: {
          id: 't-b',
          shortName: 'Гарболінська',
          displayName: 'Гарболінська',
        },
      },
      {
        subgroup: 1,
        teacher: {
          id: 't-a',
          shortName: 'Прокопчук',
          displayName: 'Прокопчук',
        },
      },
    ],
  },
  {
    group: { id: 'g1', code: '4КІ1' },
    discipline: STP,
    teachers: [
      {
        subgroup: 0,
        teacher: {
          id: 't-dk',
          shortName: 'Коваль О.П.',
          displayName: 'Коваль',
        },
      },
    ],
  },
  {
    group: { id: 'g2', code: '4КІ2' },
    discipline: DK,
    teachers: [
      {
        subgroup: 0,
        teacher: { id: 't-x', shortName: 'Інший', displayName: 'Інший' },
      },
    ],
  },
] as unknown as LoadItem[];

const base: NewChangeValues = {
  mode: 'additional',
  groupId: 'g1',
  date: '2026-11-20',
  pairs: [3, 4],
  subgroup: 0,
  disciplineId: DK.id,
  teacherId: 't-dk',
  roomId: null,
  lesson: null,
  reasonCode: 'OTHER',
  reasonText: ' Відпрацювання ',
};

describe('посилання з «Обліку годин»', () => {
  it('«Додати пари» відкриває додаткову пару для групи й дисципліни, «Вичит.» — вичитану', () => {
    expect(prefillFromParams(new URLSearchParams('group=g1&discipline=d-dk&suggest=ADDITIONAL'))).toEqual({
      mode: 'additional',
      groupId: 'g1',
      disciplineId: 'd-dk',
      missingLessons: null,
    });
    // «Додати 2 пари»: скільки бракує — у формі; сміття в адресі не рахуємо
    expect(prefillFromParams(new URLSearchParams('group=g1&discipline=d-dk&suggest=ADDITIONAL&lessons=2'))?.missingLessons).toBe(2);
    expect(prefillFromParams(new URLSearchParams('group=g1&discipline=d-dk&suggest=ADDITIONAL&lessons=abc'))?.missingLessons).toBeNull();
    expect(prefillFromParams(new URLSearchParams('group=g1&suggest=DISCIPLINE_FINISHED&lessons=2'))?.missingLessons).toBeNull();
    expect(prefillFromParams(new URLSearchParams('group=g1&discipline=d-dk&suggest=DISCIPLINE_FINISHED&date=2026-11-24'))?.mode).toBe('finished');
  });

  it('без пропозиції (звичайні «Заміни», вибір пари) — нічого не відкриваємо', () => {
    expect(prefillFromParams(new URLSearchParams('date=2026-11-20&lesson=l-1'))).toBeNull();
    expect(prefillFromParams(new URLSearchParams('suggest=SOMETHING'))).toBeNull();
  });
});

describe('додаткова пара', () => {
  it('пари — лише суцільним блоком', () => {
    expect(pairRange([4, 3])).toEqual({ from: 3, to: 4 });
    expect(pairRange([5])).toEqual({ from: 5, to: 5 });
    expect(pairRange([2, 4])).toBeNull();
    expect(pairRange([])).toBeNull();
  });

  it('викладач — з навантаження групи (по підгрупах — за порядком)', () => {
    expect(loadTeachers(load, 'g1', DK.id).map((t) => t.teacher.id)).toEqual(['t-dk']);
    expect(loadTeachers(load, 'g1', 'd-eng').map((t) => t.subgroup)).toEqual([1, 2]);
    expect(loadTeachers(load, 'g2', 'd-eng')).toEqual([]);
    expect(loadTeachers(undefined, 'g1', DK.id)).toEqual([]);
  });

  it('тіло запиту: ADDITIONAL без пари-оригіналу, діапазон пар, причина «інше» з приміткою', () => {
    expect(newChangeRequest(base)).toEqual({
      kind: 'ADDITIONAL',
      date: '2026-11-20',
      pairFrom: 3,
      pairTo: 4,
      groupId: 'g1',
      subgroup: 0,
      newDisciplineId: DK.id,
      newTeacherId: 't-dk',
      newRoomId: null,
      reasonCode: 'OTHER',
      reasonText: 'Відпрацювання',
      absenceId: null,
    });
  });

  it('без примітки — причина «Додаткова пара», а не порожнє «Інше»', () => {
    expect(newChangeRequest({ ...base, reasonText: '  ' })).toMatchObject({
      reasonCode: 'OTHER',
      reasonText: 'Додаткова пара',
    });
  });

  it('незаповнене — помилки полів і жодного запиту', () => {
    const empty = {
      ...base,
      groupId: null,
      pairs: [1, 3],
      disciplineId: null,
      teacherId: null,
    };
    expect(validateNewChange(empty)).toEqual({
      groupId: 'Оберіть групу',
      pairs: 'Додаткові пари мають іти підряд',
      disciplineId: 'Оберіть дисципліну',
      teacherId: 'Оберіть викладача',
    });
    expect(newChangeRequest(empty)).toBeNull();
    expect(validateNewChange({ ...base, pairs: [] }).pairs).toBe('Оберіть пару');
  });
});

describe('зміна наявної пари', () => {
  it('аудиторія: ROOM_CHANGE на цю пару, лише з новою аудиторією', () => {
    const room = {
      ...base,
      mode: 'room' as const,
      lesson: LESSON,
      roomId: 'r-14',
    };
    expect(newChangeRequest(room)).toEqual({
      kind: 'ROOM_CHANGE',
      date: '2026-11-20',
      pairFrom: 3,
      groupId: 'g1',
      subgroup: 0,
      originalLessonId: 'l-1',
      newRoomId: 'r-14',
      reasonCode: 'OTHER',
      reasonText: 'Заміна аудиторії',
      absenceId: null,
    });
    expect(validateNewChange({ ...room, roomId: null })).toEqual({
      roomId: 'Оберіть аудиторію',
    });
    expect(validateNewChange({ ...room, lesson: null })).toEqual({
      lesson: 'Оберіть пару групи',
    });
  });

  it('інший викладач: своя дисципліна — REPLACE_DISCIPLINE («Зв.зам.»), та сама — REPLACE_TEACHER', () => {
    const teacher = {
      ...base,
      mode: 'teacher' as const,
      lesson: LESSON,
      teacherId: 't-dk',
      disciplineId: DK.id,
      reasonCode: 'MUTUAL' as const,
    };
    expect(teacherChangeKind(teacher)).toBe('REPLACE_DISCIPLINE');
    expect(newChangeRequest(teacher)).toMatchObject({
      kind: 'REPLACE_DISCIPLINE',
      newTeacherId: 't-dk',
      newDisciplineId: DK.id,
      reasonCode: 'MUTUAL',
      reasonText: null,
    });
    const same = {
      ...teacher,
      disciplineId: STP.id,
      reasonCode: 'ABSENT' as const,
    };
    expect(newChangeRequest(same)).toMatchObject({
      kind: 'REPLACE_TEACHER',
      newDisciplineId: null,
      originalLessonId: 'l-1',
    });
    expect(validateNewChange({ ...same, reasonCode: 'OTHER', reasonText: ' ' }).reasonText).toBeDefined();
    expect(validateNewChange({ ...same, teacherId: null }).teacherId).toBe('Оберіть викладача');
  });

  it('дисципліни замінника — ті, що він веде в цій групі', () => {
    expect(teacherDisciplines(load, 'g1', 't-dk').map((d) => d.id)).toEqual([DK.id, STP.id]);
    expect(teacherDisciplines(load, 'g2', 't-dk')).toEqual([]);
  });

  it('вичитано: DISCIPLINE_FINISHED з причиною «Вичит.» і без заміни', () => {
    expect(newChangeRequest({ ...base, mode: 'finished', lesson: LESSON })).toMatchObject({
      kind: 'DISCIPLINE_FINISHED',
      originalLessonId: 'l-1',
      reasonCode: 'FINISHED',
    });
  });

  it('обрати можна пару за розкладом без рішення; аудиторію — і додатковій', () => {
    expect(lessonSelectable({ kind: 'REGULAR', changeId: null }, 'teacher')).toBe(true);
    expect(lessonSelectable({ kind: 'SUBSTITUTED', changeId: 'c' }, 'teacher')).toBe(false);
    expect(lessonSelectable({ kind: 'ADDITIONAL', changeId: null }, 'room')).toBe(true);
    expect(lessonSelectable({ kind: 'ADDITIONAL', changeId: null }, 'finished')).toBe(false);
    expect(lessonSelectable({ kind: 'REGULAR', changeId: 'c' }, 'room')).toBe(false);
  });
});

describe('інші зміни на сторінці', () => {
  const change = (patch: Partial<ChangeDto>): ChangeDto =>
    ({
      id: 'c',
      absenceId: null,
      status: 'CONFIRMED',
      date: '2026-11-20',
      pairFrom: 3,
      pairTo: 3,
      group: { id: 'g1', code: '4КІ1' },
      ...patch,
    }) as ChangeDto;

  it('лише без відсутності й ще чинні, за датою й парою', () => {
    const list = standaloneChanges([
      change({ id: 'b', pairFrom: 5, pairTo: 5 }),
      change({ id: 'a', pairFrom: 1, pairTo: 2 }),
      change({ id: 'x', absenceId: 'abs' }),
      change({ id: 'y', status: 'CANCELLED' }),
    ]);
    expect(list.map((c) => c.id)).toEqual(['a', 'b']);
  });

  it('зміна з відмовою лишається в списку: пари немає, доки диспетчер не вирішить', () => {
    const list = standaloneChanges([change({ id: 'd', status: 'DECLINED' }), change({ id: 'y', status: 'CANCELLED' })]);
    expect(list.map((c) => c.id)).toEqual(['d']);
  });

  it('після відмови — та сама пара для іншого викладача', () => {
    const additional = change({
      id: 'add',
      kind: 'ADDITIONAL',
      status: 'DECLINED',
      pairFrom: 3,
      pairTo: 4,
      new: { discipline: DK, teacher: { id: 't-1', shortName: 'Стахова О.А.' }, room: { id: 'r-307', name: '307' } },
    } as Partial<ChangeDto>);
    expect(prefillFromDeclined(additional)).toEqual({
      mode: 'additional',
      groupId: 'g1',
      disciplineId: 'd-dk',
      date: '2026-11-20',
      pairs: [3, 4],
      roomId: 'r-307',
      replaces: { changeId: 'add', teacherId: 't-1' },
    });
    const manual = change({
      id: 'm',
      kind: 'REPLACE_TEACHER',
      status: 'DECLINED',
      originalLessonId: 'l-1',
      new: { discipline: null, teacher: { id: 't-2', shortName: 'Колесник А.' }, room: null },
    } as Partial<ChangeDto>);
    expect(prefillFromDeclined(manual)).toMatchObject({ mode: 'teacher', lessonId: 'l-1', date: '2026-11-20', replaces: { changeId: 'm', teacherId: 't-2' } });
    // без відмови чи без викладача — нічого переобирати
    expect(prefillFromDeclined({ ...additional, status: 'CONFIRMED' })).toBeNull();
    expect(prefillFromDeclined(change({ kind: 'ROOM_CHANGE', status: 'DECLINED', new: { discipline: null, teacher: null, room: null } }))).toBeNull();
  });

  it('заголовок: число не відривається від «пара»', () => {
    expect(standaloneHeading(change({ pairFrom: 3, pairTo: 4 }), false)).toBe(`4КІ1 · 3–4${NB}пари`);
    expect(standaloneHeading(change({}), true)).toMatch(new RegExp(`· 4КІ1 · 3${NB}пара$`));
    expect(newChangeLabel('additional', '4КІ1', { from: 3, to: 4 }, 'пт 20.11')).toBe(`Додаткова пара — 4КІ1, 3–4${NB}пари, пт 20.11`);
  });
});

describe('«Інший викладач»: дисципліна за замовчуванням', () => {
  it('та сама, що в пари, якщо замінник її веде; інакше — його своя («Зв.зам.»)', () => {
    const lesson = { discipline: DK } as never;
    expect(defaultTeacherDiscipline([STP, DK] as never, lesson)).toBe('d-dk');
    expect(defaultTeacherDiscipline([STP] as never, lesson)).toBe('d-stp');
    expect(defaultTeacherDiscipline([], lesson)).toBe('d-dk');
    expect(defaultTeacherDiscipline([], null)).toBeNull();
  });
});
