/**
 * Історія правок чернетки для Ctrl+Z / Ctrl+Shift+Z. Кожен запис знає, як себе скасувати й повторити
 * через API; стан — чистий редʼюсер (легко тестувати), виконання — у хуку useLessonHistory.
 */
import type { MoveLessonRequest, RoomRef, TemplateLessonDto, TemplateLessonRequest, Uuid } from '@/api/types';

export type HistoryEntry =
  | {
      kind: 'move';
      lessonId: Uuid;
      from: MoveLessonRequest;
      to: MoveLessonRequest;
      /** Аудиторії до й після — щоб скасування одразу показало стару аудиторію. */
      fromRoom: RoomRef | null;
      toRoom: RoomRef | null;
      label: string;
    }
  | { kind: 'create'; lesson: TemplateLessonDto; label: string }
  | { kind: 'delete'; lesson: TemplateLessonDto; label: string }
  | { kind: 'update'; before: TemplateLessonDto; after: TemplateLessonDto; label: string };

export interface HistoryState {
  versionId: Uuid | null;
  undo: HistoryEntry[];
  redo: HistoryEntry[];
}

export type HistoryAction =
  /** Інша версія — інша історія. */
  | { type: 'reset'; versionId: Uuid | null }
  /** Нова правка: стає останньою, гілка «повторити» зникає. Правка іншої версії починає нову історію. */
  | { type: 'push'; versionId: Uuid; entry: HistoryEntry }
  /** Скасування вдалося: запис переходить у «повторити». */
  | { type: 'undone' }
  /** Повтор вдався: запис повертається в «скасувати». */
  | { type: 'redone' }
  /** Скасування чи повтор не вдалися (слот уже зайнятий): запис прибираємо, щоб не застрягти. */
  | { type: 'discard'; from: 'undo' | 'redo' }
  /** Видалене заняття відновлено з новим id — оновлюємо посилання в усіх записах. */
  | { type: 'remap'; from: Uuid; to: TemplateLessonDto };

export const HISTORY_LIMIT = 50;

export const EMPTY_HISTORY: HistoryState = { versionId: null, undo: [], redo: [] };

function remapEntry(entry: HistoryEntry, from: Uuid, to: TemplateLessonDto): HistoryEntry {
  switch (entry.kind) {
    case 'move':
      return entry.lessonId === from ? { ...entry, lessonId: to.id } : entry;
    case 'create':
    case 'delete':
      return entry.lesson.id === from ? { ...entry, lesson: { ...entry.lesson, id: to.id } } : entry;
    case 'update':
      return entry.before.id === from
        ? { ...entry, before: { ...entry.before, id: to.id }, after: { ...entry.after, id: to.id } }
        : entry;
  }
}

export function historyReducer(state: HistoryState, action: HistoryAction): HistoryState {
  switch (action.type) {
    case 'reset':
      return state.versionId === action.versionId ? state : { versionId: action.versionId, undo: [], redo: [] };
    case 'push': {
      const undo = state.versionId === action.versionId ? state.undo : [];
      return { versionId: action.versionId, undo: [...undo, action.entry].slice(-HISTORY_LIMIT), redo: [] };
    }
    case 'undone': {
      const entry = state.undo.at(-1);
      return entry ? { ...state, undo: state.undo.slice(0, -1), redo: [...state.redo, entry] } : state;
    }
    case 'redone': {
      const entry = state.redo.at(-1);
      return entry ? { ...state, redo: state.redo.slice(0, -1), undo: [...state.undo, entry] } : state;
    }
    case 'discard':
      return action.from === 'undo' ? { ...state, undo: state.undo.slice(0, -1) } : { ...state, redo: state.redo.slice(0, -1) };
    case 'remap':
      return {
        ...state,
        undo: state.undo.map((entry) => remapEntry(entry, action.from, action.to)),
        redo: state.redo.map((entry) => remapEntry(entry, action.from, action.to)),
      };
  }
}

/** Історія саме цієї версії (історія іншої версії для неї порожня). */
export function historyFor(state: HistoryState, versionId: Uuid | null): HistoryState {
  return state.versionId === versionId ? state : { versionId, undo: [], redo: [] };
}

/** Слот заняття як тіло переміщення (з аудиторією, щоб скасування повернуло й її). */
export function slotOf(lesson: TemplateLessonDto): MoveLessonRequest {
  const slot: MoveLessonRequest = { weekday: lesson.weekday, pair: lesson.pair, parity: lesson.parity };
  if (lesson.room) slot.roomId = lesson.room.id;
  return slot;
}

/** Заняття → тіло POST/PUT (для відновлення видаленого й повернення правки). */
export function toLessonRequest(lesson: TemplateLessonDto): TemplateLessonRequest {
  return {
    weekday: lesson.weekday,
    pair: lesson.pair,
    parity: lesson.parity,
    groupId: lesson.group.id,
    subgroup: lesson.subgroup,
    disciplineId: lesson.discipline.id,
    teacherId: lesson.teacher?.id ?? null,
    roomId: lesson.room?.id ?? null,
    lessonKind: lesson.lessonKind,
    loadItemId: lesson.loadItemId ?? null,
    pinned: lesson.pinned,
    online: lesson.online,
    streamId: lesson.streamId ?? null,
    version: lesson.version,
  };
}
