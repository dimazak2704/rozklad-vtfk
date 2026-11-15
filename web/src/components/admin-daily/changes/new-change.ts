/**
 * «Нова зміна» без відсутності: додаткова пара (відпрацювання), інша аудиторія, інший викладач на пару, вичитана
 * дисципліна. Чисті функції: що можна обрати, які поля обовʼязкові й яке тіло піде в POST /admin/changes.
 */
import type { LoadItem } from '@/api/load';
import type { ChangeDto, ChangeKind, CreateChangeRequest, IsoDate, LessonView, ReasonCode, Uuid } from '@/api/types';
import { formatShortWeekdayDate } from '@/lib/date';

/** Текст причини, якої розпорядження не друкує: додаткова пара й заміна аудиторії йдуть окремими розділами. */
const ADDITIONAL_REASON = 'Додаткова пара';
const ROOM_REASON = 'Заміна аудиторії';

export type NewChangeMode = 'additional' | 'room' | 'teacher' | 'finished';

export const NEW_CHANGE_MODES: ReadonlyArray<{
  value: NewChangeMode;
  label: string;
  hint: string;
}> = [
  {
    value: 'additional',
    label: 'Додаткова пара',
    hint: 'Відпрацювання чи пара понад розклад — у розпорядженні «ДОДАТКОВО».',
  },
  {
    value: 'room',
    label: 'Інша аудиторія',
    hint: 'Пара лишається, міняється лише аудиторія — розділ «Заміна аудиторій».',
  },
  {
    value: 'teacher',
    label: 'Інший викладач',
    hint: 'Пару групи веде інший викладач: свою дисципліну («Зв.зам.») чи цю саму.',
  },
  {
    value: 'finished',
    label: 'Вичитано',
    hint: 'Години дисципліни вичитано — пари не буде, у розпорядженні «Вичит.».',
  },
];

/**
 * Що вже обрано у формі: з адреси «Заміни» з «Обліку годин» (`?group=…&discipline=…&suggest=…`) — вид, група й
 * дисципліна; із зміни, від якої викладач відмовився (`prefillFromDeclined`), — ще дата, пари й заняття.
 */
export interface NewChangePrefill {
  mode: NewChangeMode;
  groupId: Uuid | null;
  disciplineId: Uuid | null;
  date?: IsoDate;
  pairs?: readonly number[];
  lessonId?: Uuid | null;
  roomId?: Uuid | null;
  /** «Облік годин»: скільки пар бракує дисципліні (форма нагадує, що їх можна розкласти на кілька днів). */
  missingLessons?: number | null;
  /** Зміна з відмовою, яку нова заміняє: після створення нової її прибираємо. */
  replaces?: { changeId: Uuid; teacherId: Uuid | null } | null;
}

/**
 * Друга спроба для зміни без відсутності, від якої викладач відмовився: та сама група, дата й пари (додаткова
 * пара — ще дисципліна й аудиторія), обрати треба лише іншого викладача. Інші види викладача не мають — null.
 */
export function prefillFromDeclined(change: ChangeDto): NewChangePrefill | null {
  if (change.status !== 'DECLINED') return null;
  const replaces = { changeId: change.id, teacherId: change.new.teacher?.id ?? null };
  if (change.kind === 'ADDITIONAL') {
    const pairs = Array.from({ length: change.pairTo - change.pairFrom + 1 }, (_, i) => change.pairFrom + i);
    return {
      mode: 'additional',
      groupId: change.group.id,
      disciplineId: change.new.discipline?.id ?? null,
      date: change.date,
      pairs,
      roomId: change.new.room?.id ?? null,
      replaces,
    };
  }
  if ((change.kind === 'REPLACE_TEACHER' || change.kind === 'REPLACE_DISCIPLINE') && change.originalLessonId) {
    return { mode: 'teacher', groupId: change.group.id, disciplineId: null, date: change.date, lessonId: change.originalLessonId, replaces };
  }
  return null;
}

export function prefillFromParams(params: URLSearchParams): NewChangePrefill | null {
  const suggest = params.get('suggest');
  if (suggest !== 'ADDITIONAL' && suggest !== 'DISCIPLINE_FINISHED') return null;
  const lessons = Number(params.get('lessons'));
  return {
    mode: suggest === 'ADDITIONAL' ? 'additional' : 'finished',
    groupId: params.get('group'),
    disciplineId: params.get('discipline'),
    missingLessons: suggest === 'ADDITIONAL' && Number.isInteger(lessons) && lessons > 0 ? lessons : null,
  };
}

/** Пари підряд: [3, 4] → {3, 4}; [3] → {3, 3}; [2, 4] чи [] → null (додаткові пари — лише суцільним блоком). */
export function pairRange(pairs: readonly number[]): { from: number; to: number } | null {
  if (pairs.length === 0) return null;
  const sorted = [...new Set(pairs)].sort((a, b) => a - b);
  const from = sorted[0] as number;
  const to = sorted[sorted.length - 1] as number;
  return to - from + 1 === sorted.length ? { from, to } : null;
}

/** Хто веде дисципліну групи за навантаженням: уся група (0) чи кожна підгрупа окремо. */
export function loadTeachers(load: readonly LoadItem[] | undefined, groupId: Uuid | null, disciplineId: Uuid | null): LoadItem['teachers'] {
  if (!load || !groupId || !disciplineId) return [];
  const item = load.find((entry) => entry.group.id === groupId && entry.discipline.id === disciplineId);
  return item ? [...item.teachers].sort((a, b) => a.subgroup - b.subgroup) : [];
}

/** Дисципліни групи, які веде викладач (за навантаженням) — для «Зв.зам.»: замінник веде свою дисципліну. */
export function teacherDisciplines(load: readonly LoadItem[] | undefined, groupId: Uuid | null, teacherId: Uuid | null): LoadItem['discipline'][] {
  if (!load || !groupId || !teacherId) return [];
  return load
    .filter((entry) => entry.group.id === groupId && entry.teachers.some((t) => t.teacher.id === teacherId))
    .map((entry) => entry.discipline);
}

/**
 * Дисципліна «Іншого викладача» за замовчуванням: та сама, що й у пари, якщо замінник її веде (звичайна заміна);
 * інакше — його власна дисципліна групи («Зв.зам.»); без навантаження — дисципліна пари.
 */
export function defaultTeacherDiscipline(
  own: readonly LoadItem['discipline'][],
  lesson: Pick<LessonView, 'discipline'> | null,
): Uuid | null {
  if (lesson && own.some((d) => d.id === lesson.discipline.id)) return lesson.discipline.id;
  return own[0]?.id ?? lesson?.discipline.id ?? null;
}

/**
 * Пару можна змінити, лише якщо вона йде за розкладом і ще без рішення: інакше core відповість, що заняття
 * вже змінено чи скасовано. Додаткову пару теж можна перенести в іншу аудиторію.
 */
export function lessonSelectable(lesson: Pick<LessonView, 'kind' | 'changeId'>, mode: NewChangeMode): boolean {
  if (mode === 'room') return lesson.changeId === null && (lesson.kind === 'REGULAR' || lesson.kind === 'ADDITIONAL');
  return lesson.changeId === null && lesson.kind === 'REGULAR';
}

export interface NewChangeValues {
  mode: NewChangeMode;
  groupId: Uuid | null;
  date: IsoDate;
  /** Додаткова пара: номери пар (підряд). */
  pairs: readonly number[];
  subgroup: number;
  disciplineId: Uuid | null;
  teacherId: Uuid | null;
  roomId: Uuid | null;
  /** Інша аудиторія, інший викладач, вичитано: пара групи цього дня. */
  lesson: Pick<LessonView, 'id' | 'pair' | 'subgroup' | 'discipline'> | null;
  reasonCode: ReasonCode;
  reasonText: string;
}

export type NewChangeErrors = Partial<
  Record<'groupId' | 'date' | 'pairs' | 'disciplineId' | 'teacherId' | 'roomId' | 'lesson' | 'reasonText', string>
>;

export function validateNewChange(values: NewChangeValues): NewChangeErrors {
  const errors: NewChangeErrors = {};
  if (!values.groupId) errors.groupId = 'Оберіть групу';
  if (!values.date) errors.date = 'Вкажіть дату';
  if (values.mode === 'additional') {
    if (values.pairs.length === 0) errors.pairs = 'Оберіть пару';
    else if (!pairRange(values.pairs)) errors.pairs = 'Додаткові пари мають іти підряд';
    if (!values.disciplineId) errors.disciplineId = 'Оберіть дисципліну';
    if (!values.teacherId) errors.teacherId = 'Оберіть викладача';
    return errors;
  }
  if (!values.lesson) errors.lesson = 'Оберіть пару групи';
  if (values.mode === 'room' && !values.roomId) errors.roomId = 'Оберіть аудиторію';
  if (values.mode === 'teacher') {
    if (!values.teacherId) errors.teacherId = 'Оберіть викладача';
    if (values.reasonCode === 'OTHER' && !values.reasonText.trim()) errors.reasonText = 'Коротко вкажіть причину — вона піде в розпорядження';
  }
  return errors;
}

/** Вид зміни «Інший викладач»: своя дисципліна замінника — REPLACE_DISCIPLINE, та сама — REPLACE_TEACHER. */
export function teacherChangeKind(values: Pick<NewChangeValues, 'disciplineId' | 'lesson'>): ChangeKind {
  return values.disciplineId && values.lesson && values.disciplineId !== values.lesson.discipline.id ? 'REPLACE_DISCIPLINE' : 'REPLACE_TEACHER';
}

/** Тіло POST /admin/changes (null — форма ще не заповнена; помилки дає `validateNewChange`). */
export function newChangeRequest(values: NewChangeValues): CreateChangeRequest | null {
  if (Object.keys(validateNewChange(values)).length > 0 || !values.groupId) return null;
  if (values.mode === 'additional') {
    const range = pairRange(values.pairs);
    if (!range) return null;
    return {
      kind: 'ADDITIONAL',
      date: values.date,
      pairFrom: range.from,
      pairTo: range.to,
      groupId: values.groupId,
      subgroup: values.subgroup,
      newDisciplineId: values.disciplineId,
      newTeacherId: values.teacherId,
      newRoomId: values.roomId,
      // додаткову пару розпорядження друкує без причини («ДОДАТКОВО»); код «інше» з коротким текстом лишається
      // в історії зміни (і без порожнього «Інше» там)
      reasonCode: 'OTHER',
      reasonText: values.reasonText.trim() || ADDITIONAL_REASON,
      absenceId: null,
    };
  }
  const lesson = values.lesson;
  if (!lesson) return null;
  const base = {
    date: values.date,
    pairFrom: lesson.pair,
    groupId: values.groupId,
    subgroup: lesson.subgroup,
    originalLessonId: lesson.id,
    absenceId: null,
  };
  switch (values.mode) {
    case 'room':
      return {
        ...base,
        kind: 'ROOM_CHANGE',
        newRoomId: values.roomId,
        reasonCode: 'OTHER',
        reasonText: ROOM_REASON,
      };
    case 'finished':
      return {
        ...base,
        kind: 'DISCIPLINE_FINISHED',
        reasonCode: 'FINISHED',
        reasonText: null,
      };
    case 'teacher': {
      const kind = teacherChangeKind(values);
      return {
        ...base,
        kind,
        newTeacherId: values.teacherId,
        newDisciplineId: kind === 'REPLACE_DISCIPLINE' ? values.disciplineId : null,
        newRoomId: values.roomId,
        reasonCode: values.reasonCode,
        reasonText: values.reasonCode === 'OTHER' ? values.reasonText.trim() || null : null,
      };
    }
  }
}

const NBSP = ' ';

/** «3 пара», «3–4 пари»: число не відривається від слова. */
function pairsWord(from: number, to: number): string {
  return from === to ? `${from}${NBSP}пара` : `${from}–${to}${NBSP}пари`;
}

/** Що піде в тост і журнал: «Додаткова пара — 4КІ1, 3–4 пари, пт 20.11». */
export function newChangeLabel(mode: NewChangeMode, groupCode: string, pairs: { from: number; to: number }, day: string): string {
  const head = NEW_CHANGE_MODES.find((m) => m.value === mode)?.label ?? 'Зміна';
  const pairText = pairsWord(pairs.from, pairs.to);
  return `${head} — ${groupCode}, ${pairText}, ${day}`;
}

/**
 * Зміни без відсутності, які ще діють, найраніші першими. Скасовані не показуємо, а з відмовою — показуємо: пари
 * немає, доки диспетчер не обере іншого викладача чи не прибере її (у відсутніх таку пару тримає список пар).
 */
export function standaloneChanges(changes: Iterable<ChangeDto>): ChangeDto[] {
  return [...changes]
    .filter((change) => change.absenceId === null && change.status !== 'CANCELLED')
    .sort((a, b) => a.date.localeCompare(b.date) || a.pairFrom - b.pairFrom || a.group.code.localeCompare(b.group.code, 'uk'));
}

/** «4КІ1 · 3–4 пари» (із датою на тижні) — пари не розриваються з числом. */
export function standaloneHeading(change: Pick<ChangeDto, 'group' | 'pairFrom' | 'pairTo' | 'date'>, showDate: boolean): string {
  const pairs = pairsWord(change.pairFrom, change.pairTo);
  return [showDate ? formatShortWeekdayDate(change.date) : null, change.group.code, pairs].filter(Boolean).join(' · ');
}
