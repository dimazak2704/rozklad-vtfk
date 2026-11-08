/**
 * Правки чернетки з історією: перенесення, додавання, зміна, видалення — з оптимістичним показом,
 * тостами (із «Скасувати») і стеком Ctrl+Z / Ctrl+Shift+Z. Помилки сервера не ховаємо: кеш
 * повертається як був, а тост пояснює причину словами сервера.
 */
import { useRef, useState } from 'react';
import { createStore, useStore } from 'zustand';
import { isApiError } from '@/api/errors';
import { useCreateLesson, useDeleteLesson, useMoveLesson, useUpdateLesson, type TimetableConflict } from '@/api/timetable';
import type { MoveLessonRequest, PublicRoomDto, RoomRef, TemplateLessonDto, TemplateLessonRequest, Uuid } from '@/api/types';
import { notify } from '@/components/ui';
import {
  EMPTY_HISTORY,
  historyFor,
  historyReducer,
  slotOf,
  toLessonRequest,
  type HistoryAction,
  type HistoryEntry,
  type HistoryState,
} from './model/history';
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

const toRoomRef = (room: PublicRoomDto | RoomRef): RoomRef => ({ id: room.id, name: room.name, type: room.type });

export function useLessonEditing(versionId: Uuid) {
  const move = useMoveLesson(versionId);
  const create = useCreateLesson(versionId);
  const update = useUpdateLesson(versionId);
  const remove = useDeleteLesson(versionId);
  // історію читаємо зі сховища в момент виклику: «Скасувати» в тості спрацює і після нових правок
  const [store] = useState(() => createStore<HistoryState>()(() => EMPTY_HISTORY));
  const history = historyFor(
    useStore(store, (state) => state),
    versionId,
  );
  const dispatch = (action: HistoryAction) => store.setState((state) => historyReducer(state, action));
  const current = () => historyFor(store.getState(), versionId);
  const busy = useRef(false);

  const record = (entry: HistoryEntry) => dispatch({ type: 'push', versionId, entry });

  // ── дії, що потрапляють в історію

  async function moveLesson(lesson: TemplateLessonDto, target: MoveLessonRequest, room: PublicRoomDto | null): Promise<boolean> {
    const toRoom = room ? toRoomRef(room) : (lesson.room ?? null);
    try {
      const result = await move.mutateAsync({ lessonId: lesson.id, target, room: room ? toRoomRef(room) : undefined });
      const label = `${lessonShort(lesson)}: ${slotShort(lesson)} → ${slotShort(target)}`;
      record({ kind: 'move', lessonId: lesson.id, from: slotOf(lesson), to: target, fromRoom: lesson.room ?? null, toRoom, label });
      notify.undo(`Пару перенесено: ${label}`, { description: warningsDescription(result.conflicts), onUndo: () => void undo() });
      return true;
    } catch (error) {
      notify.error(error, 'Пару не перенесено', { description: conflictDescription(error) ?? (isApiError(error) ? error.message : undefined) });
      return false;
    }
  }

  async function createLesson(body: TemplateLessonRequest): Promise<TemplateLessonDto | null> {
    try {
      const result = await create.mutateAsync(body);
      record({ kind: 'create', lesson: result.lesson, label: `додано ${lessonShort(result.lesson)}` });
      notify.undo(`Пару додано: ${lessonShort(result.lesson)}, ${slotShort(result.lesson)}`, {
        description: warningsDescription(result.conflicts),
        onUndo: () => void undo(),
      });
      return result.lesson;
    } catch (error) {
      notify.error(error, 'Пару не додано');
      throw error;
    }
  }

  async function updateLesson(before: TemplateLessonDto, body: TemplateLessonRequest): Promise<TemplateLessonDto | null> {
    try {
      const result = await update.mutateAsync({ lessonId: before.id, body });
      record({ kind: 'update', before, after: result.lesson, label: `змінено ${lessonShort(result.lesson)}` });
      notify.undo(`Пару змінено: ${lessonShort(result.lesson)}, ${slotShort(result.lesson)}`, {
        description: warningsDescription(result.conflicts),
        onUndo: () => void undo(),
      });
      return result.lesson;
    } catch (error) {
      notify.error(error, 'Зміни не збережено');
      throw error;
    }
  }

  async function deleteLesson(lesson: TemplateLessonDto): Promise<boolean> {
    try {
      await remove.mutateAsync(lesson.id);
      record({ kind: 'delete', lesson, label: `видалено ${lessonShort(lesson)}` });
      notify.undo(`Пару видалено: ${lessonShort(lesson)}, ${slotShort(lesson)}`, { onUndo: () => void undo() });
      return true;
    } catch (error) {
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

  // ── скасування й повтор

  /** Виконує зворотну (або повторну) дію через API; відтворене заняття отримує новий id — оновлюємо історію. */
  async function replay(entry: HistoryEntry, direction: 'undo' | 'redo'): Promise<void> {
    const back = direction === 'undo';
    switch (entry.kind) {
      case 'move': {
        const room = back ? entry.fromRoom : entry.toRoom;
        await move.mutateAsync({ lessonId: entry.lessonId, target: back ? entry.from : entry.to, room: room ?? undefined });
        return;
      }
      case 'update':
        await update.mutateAsync({ lessonId: entry.before.id, body: toLessonRequest(back ? entry.before : entry.after) });
        return;
      case 'create':
      case 'delete': {
        const recreate = (entry.kind === 'delete') === back;
        if (!recreate) {
          await remove.mutateAsync(entry.lesson.id);
          return;
        }
        const result = await create.mutateAsync(toLessonRequest(entry.lesson));
        dispatch({ type: 'remap', from: entry.lesson.id, to: result.lesson });
        return;
      }
    }
  }

  async function undo(): Promise<void> {
    const entry = current().undo.at(-1);
    if (!entry || busy.current) return;
    busy.current = true;
    try {
      await replay(entry, 'undo');
      dispatch({ type: 'undone' });
      notify.info(`Скасовано: ${entry.label}`);
    } catch (error) {
      dispatch({ type: 'discard', from: 'undo' });
      notify.error(error, 'Не вдалося скасувати', { description: conflictDescription(error) ?? entry.label });
    } finally {
      busy.current = false;
    }
  }

  async function redo(): Promise<void> {
    const entry = current().redo.at(-1);
    if (!entry || busy.current) return;
    busy.current = true;
    try {
      await replay(entry, 'redo');
      dispatch({ type: 'redone' });
      notify.info(`Повторено: ${entry.label}`);
    } catch (error) {
      dispatch({ type: 'discard', from: 'redo' });
      notify.error(error, 'Не вдалося повторити', { description: conflictDescription(error) ?? entry.label });
    } finally {
      busy.current = false;
    }
  }

  return {
    moveLesson,
    createLesson,
    updateLesson,
    deleteLesson,
    reportRejected,
    undo,
    redo,
    canUndo: history.undo.length > 0,
    canRedo: history.redo.length > 0,
    undoLabel: history.undo.at(-1)?.label ?? null,
    redoLabel: history.redo.at(-1)?.label ?? null,
    pending: move.isPending || create.isPending || update.isPending || remove.isPending,
  };
}

