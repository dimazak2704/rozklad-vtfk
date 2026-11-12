/**
 * Правки чернетки: перенесення, додавання, зміна, видалення — з оптимістичним показом і записом у
 * спільний журнал дій (тост «Скасувати», Ctrl+Z / Ctrl+Shift+Z, панель «Журнал дій»). Журнал
 * розділяє версії розкладу: Ctrl+Z у версії скасовує лише її правки. Помилки сервера не ховаємо:
 * кеш повертається як був, а тост пояснює причину словами сервера.
 */
import { useQueryClient } from '@tanstack/react-query';
import { useLayoutEffect, useRef, useState } from 'react';
import { isApiError, STALE_DATA_MESSAGE } from '@/api/errors';
import { qk } from '@/api/queryKeys';
import {
  isLessonOutdated,
  useCreateLesson,
  useDeleteLesson,
  useMoveLesson,
  useUpdateLesson,
  type TimetableConflict,
} from '@/api/timetable';
import type { MoveLessonRequest, PublicRoomDto, RoomRef, TemplateLessonDto, TemplateLessonRequest, Uuid } from '@/api/types';
import { notify } from '@/components/ui';
import { lowerFirst, redoLatest, track, undoLatest, useJournalScope } from '@/lib/undo/actions';
import { JournalBlockedError, STALE_REASON } from '@/lib/undo/errors';
import { sameRequest } from '@/lib/undo/records';
import { latestRedoable, latestUndoable, useJournal } from '@/lib/undo/journal';
import { LessonLedger, slotOf, toLessonRequest } from './model/history';
import { lessonShort, slotShort } from './model/text';

/** Перший конфлікт сервера — найкращий опис, чому дію не виконано. */
function conflictDescription(error: unknown): string | undefined {
  if (!isApiError(error) || error.conflicts.length === 0) return undefined;
  const [first] = error.conflicts;
  const rest = error.conflicts.length - 1;
  return rest > 0 ? `${first?.message ?? ''} (і ще ${rest})` : first?.message;
}

function warningsDescription(conflicts: readonly TimetableConflict[]): string | undefined {
  const soft = conflicts.filter((c) => c.severity === 'SOFT');
  const hard = conflicts.filter((c) => c.severity === 'HARD');
  if (hard.length > 0) return `Конфлікт: ${hard[0]?.message ?? ''}${hard.length > 1 ? ` (і ще ${hard.length - 1})` : ''}`;
  if (soft.length > 0) return `Попередження: ${soft[0]?.message ?? ''}${soft.length > 1 ? ` (і ще ${soft.length - 1})` : ''}`;
  return undefined;
}

/**
 * Повтор після 409 з відкритого діалогу: форма ще тримає старі значення. Беремо свіжу пару й кладемо
 * на неї лише ті поля, які диспетчер справді змінив у формі, — чужа правка інших полів лишається.
 */
function rebaseRequest(snapshot: TemplateLessonDto, fresh: TemplateLessonDto, body: TemplateLessonRequest): TemplateLessonRequest {
  if (fresh === snapshot) return body;
  const was = toLessonRequest(snapshot);
  const out: Record<string, unknown> = { ...toLessonRequest(fresh) };
  for (const [key, value] of Object.entries(body)) {
    if (key !== 'version' && !sameRequest(value, was[key as keyof TemplateLessonRequest])) out[key] = value;
  }
  return out as unknown as TemplateLessonRequest;
}

const toRoomRef = (room: PublicRoomDto | RoomRef): RoomRef => ({ id: room.id, name: room.name, type: room.type });

/** Сторінка журналу для версії розкладу: Ctrl+Z діє лише на правки цієї версії. */
export const timetableScope = (versionId: Uuid) => `timetable:${versionId}`;

export interface LessonEditingOptions {
  /** Скасування видалення повернуло пару (з новим id): сторінка може повернути на неї фокус. */
  onRestored?: (lesson: TemplateLessonDto) => void;
}

export function useLessonEditing(versionId: Uuid, options: LessonEditingOptions = {}) {
  const onRestored = useRef(options.onRestored);
  useLayoutEffect(() => {
    onRestored.current = options.onRestored;
  });
  const move = useMoveLesson(versionId);
  const create = useCreateLesson(versionId);
  const update = useUpdateLesson(versionId);
  const remove = useDeleteLesson(versionId);
  // облік id і версій для кожної версії розкладу (живе, поки живуть записи журналу)
  const [ledgers] = useState(() => new Map<Uuid, LessonLedger>());
  let ledger = ledgers.get(versionId);
  if (!ledger) {
    ledger = new LessonLedger();
    ledgers.set(versionId, ledger);
  }
  const book = ledger;
  const queryClient = useQueryClient();
  // пари, про чужу правку яких диспетчер уже знає (тост після 409): наступна дія — зі свіжою версією
  const [outdated] = useState(() => new Set<Uuid>());
  const scope = timetableScope(versionId);
  useJournalScope(scope);
  const lastUndo = useJournal((state) => latestUndoable(state.entries, scope));
  const lastRedo = useJournal((state) => latestRedoable(state, scope));
  const invalidate = [qk.admin.timetable.lessons(versionId, {})];

  /** Тіло з останньою відомою версією заняття — не з кешу (там могла бути вже чужа правка). */
  const versioned = <T extends { version?: number }>(lessonId: Uuid, body: T): T => ({ ...body, version: book.version(lessonId) });

  /**
   * Пара, з якою діє диспетчер. Діалог тримає знімок з моменту відкриття; якщо після 409 його вже
   * попередили й кеш перечитано, беремо свіжу пару з кешу — інакше повтор знову отримав би 409.
   */
  const current = (lesson: TemplateLessonDto): TemplateLessonDto => {
    if (!outdated.has(lesson.id)) return lesson;
    return queryClient.getQueryData<TemplateLessonDto[]>(qk.admin.timetable.lessons(versionId, {}))?.find((l) => l.id === lesson.id) ?? lesson;
  };

  /** Дія диспетчера над парою, яку тим часом змінив хтось інший: попередні записи журналу вже не наші. */
  const adopt = (lesson: TemplateLessonDto) => {
    const known = book.version(lesson.id);
    if (known !== undefined && lesson.version !== undefined && known !== lesson.version) book.fork(lesson.id);
  };

  /** Скасування й повтор діють, лише поки між нашими правками пари не втрутився хтось інший. */
  const guard = (lessonId: Uuid, op: () => Promise<unknown>) => {
    const epoch = book.epoch(lessonId);
    return async () => {
      if (book.epoch(lessonId) !== epoch) throw new JournalBlockedError(STALE_REASON, 'stale');
      await op();
    };
  };

  /** 409/404: пару вже змінили чи видалили — кеш перечитується (api/timetable), пояснюємо без паніки. */
  const reportOutdated = (lesson: TemplateLessonDto, error: unknown): boolean => {
    if (!isLessonOutdated(error)) return false;
    outdated.add(lesson.id);
    book.fork(lesson.id);
    if (isApiError(error) && error.isNotFound) {
      notify.warning('Цієї пари вже немає', { description: 'Її щойно видалив хтось інший. Розклад оновлено.' });
    } else {
      notify.warning('Пару щойно змінив хтось інший', { description: STALE_DATA_MESSAGE });
    }
    return true;
  };

  const recreate = async (lesson: TemplateLessonDto) => {
    const result = await create.mutateAsync({ ...toLessonRequest(lesson), version: undefined });
    book.remap(lesson.id, result.lesson);
    return result.lesson;
  };
  const removeCurrent = async (lessonId: Uuid) => {
    await remove.mutateAsync(book.resolve(lessonId));
  };

  // ── дії, що потрапляють у журнал

  async function moveLesson(picked: TemplateLessonDto, target: MoveLessonRequest, room: PublicRoomDto | null): Promise<boolean> {
    const lesson = current(picked);
    adopt(lesson);
    const toRoom = room ? toRoomRef(room) : (lesson.room ?? null);
    const fromRoom = lesson.room ?? null;
    const from = slotOf(lesson);
    try {
      // версія уроку, який диспетчер перетягнув: якщо його щойно змінив інший, сервер відповість 409
      const result = await move.mutateAsync({
        lessonId: lesson.id,
        target: { ...target, version: lesson.version },
        room: room ? toRoomRef(room) : undefined,
      });
      book.note(result.lesson);
      outdated.delete(lesson.id);
      const go = async (slot: MoveLessonRequest, slotRoom: RoomRef | null) => {
        const id = book.resolve(lesson.id);
        const moved = await move.mutateAsync({ lessonId: id, target: versioned(id, slot), room: slotRoom ?? undefined });
        book.note(moved.lesson);
      };
      track({
        label: `Перенесено пару ${lessonShort(lesson)}: ${slotShort(lesson)} → ${slotShort(target)}`,
        description: warningsDescription(result.conflicts),
        invalidate,
        undo: guard(lesson.id, () => go(from, fromRoom)),
        redo: guard(lesson.id, () => go(target, toRoom)),
      });
      return true;
    } catch (error) {
      if (reportOutdated(lesson, error)) return false;
      notify.error(error, 'Пару не перенесено', { description: conflictDescription(error) ?? (isApiError(error) ? error.message : undefined) });
      return false;
    }
  }

  async function createLesson(body: TemplateLessonRequest): Promise<TemplateLessonDto | null> {
    try {
      const result = await create.mutateAsync(body);
      const created = result.lesson;
      book.note(created);
      track({
        label: `Додано пару ${lessonShort(created)}, ${slotShort(created)}`,
        description: warningsDescription(result.conflicts),
        invalidate,
        undo: guard(created.id, () => removeCurrent(created.id)),
        redo: () => recreate(created),
      });
      return created;
    } catch (error) {
      notify.error(error, 'Пару не додано');
      throw error;
    }
  }

  async function updateLesson(snapshot: TemplateLessonDto, edited: TemplateLessonRequest): Promise<TemplateLessonDto | null> {
    const before = current(snapshot);
    const body = rebaseRequest(snapshot, before, edited);
    adopt(before);
    try {
      // версія уроку, з якого відкрили форму: чужа правка за цей час не перезапишеться мовчки
      const result = await update.mutateAsync({ lessonId: before.id, body: { ...body, version: before.version } });
      const after = result.lesson;
      book.note(after);
      outdated.delete(before.id);
      const put = async (lesson: TemplateLessonDto) => {
        const id = book.resolve(before.id);
        const saved = await update.mutateAsync({ lessonId: id, body: versioned(id, toLessonRequest(lesson)) });
        book.note(saved.lesson);
      };
      track({
        label: `Змінено пару ${lessonShort(after)}, ${slotShort(after)}`,
        description: warningsDescription(result.conflicts),
        invalidate,
        undo: guard(before.id, () => put(before)),
        redo: guard(before.id, () => put(after)),
      });
      return after;
    } catch (error) {
      if (!reportOutdated(before, error)) notify.error(error, 'Зміни не збережено');
      throw error;
    }
  }

  async function deleteLesson(lesson: TemplateLessonDto): Promise<boolean> {
    try {
      await remove.mutateAsync(lesson.id);
      track({
        label: `Видалено пару ${lessonShort(lesson)}, ${slotShort(lesson)}`,
        invalidate,
        undo: async () => {
          onRestored.current?.(await recreate(lesson));
        },
        redo: () => removeCurrent(lesson.id),
      });
      return true;
    } catch (error) {
      if (reportOutdated(lesson, error)) return false;
      notify.error(error, 'Пару не видалено');
      return false;
    }
  }

  /** Кидок на ціль, яку сервер уже назвав конфліктною: запиту немає, лише пояснення. */
  function reportRejected(conflicts: readonly TimetableConflict[]): void {
    const [first] = conflicts;
    notify.error('Пару не перенесено: у цьому слоті конфлікт', undefined, {
      description: first ? `${first.message}${conflicts.length > 1 ? ` (і ще ${conflicts.length - 1})` : ''}` : undefined,
    });
  }

  return {
    moveLesson,
    createLesson,
    updateLesson,
    deleteLesson,
    reportRejected,
    undo: () => undoLatest(scope),
    redo: () => redoLatest(scope),
    canUndo: lastUndo !== undefined,
    canRedo: lastRedo !== undefined,
    undoLabel: lastUndo ? lowerFirst(lastUndo.label) : null,
    redoLabel: lastRedo ? lowerFirst(lastRedo.label) : null,
    pending: move.isPending || create.isPending || update.isPending || remove.isPending,
  };
}
