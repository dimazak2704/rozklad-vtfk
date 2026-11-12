/**
 * Журнал дій для сторінок: записати дію з тостом «Скасувати», скасувати / повернути з тостом про результат.
 *
 * `track({ label: 'Додано аудиторію 305', undo, redo })` — тост на 8 с з «Скасувати»; Ctrl+Z на цій
 * сторінці робить те саме. Після скасування тост «Скасовано: додано аудиторію 305» з «Повернути».
 * Незворотну дію (публікацію) записуйте через `trackFinal` — вона буде в журналі з замком.
 */
import type { QueryKey } from '@tanstack/react-query';
import { useEffect } from 'react';
import { queryClient } from '@/api/queryClient';
import { notify } from '@/components/ui/toast';
import { useJournal, type JournalEntry, type RecordInput, type UndoOutcome } from './journal';

export interface TrackInput extends RecordInput {
  /** Які запити оновити, якщо скасування не вдалося (щоб диспетчер побачив свіжий стан). */
  invalidate?: readonly QueryKey[];
  /** Не показувати тост (сторінка показує власний). */
  silent?: boolean;
  /** Друга строка тосту дії — наприклад, попередження сервера. */
  description?: string;
}

const invalidations = new Map<string, readonly QueryKey[]>();

/** «Додано аудиторію 305» → «додано аудиторію 305» (але «4КІ1 …» і «ЕУ …» лишаються як є). */
export function lowerFirst(text: string): string {
  const [first = '', second = ''] = text;
  if (second && second !== second.toLowerCase()) return text;
  return first.toLowerCase() + text.slice(1);
}

/** Записує дію в журнал; якщо її можна скасувати — показує тост «Скасувати» (8 с). */
export function track({ invalidate, silent, description, ...input }: TrackInput): JournalEntry {
  const entry = useJournal.getState().record(input);
  if (invalidate) invalidations.set(entry.id, invalidate);
  if (!silent && entry.canUndo) {
    notify.undo(entry.label, {
      id: entry.id,
      description: [description, entry.note].filter(Boolean).join(' ') || undefined,
      onUndo: () => void undoEntry(entry.id),
    });
  }
  return entry;
}

/** Незворотна дія: лише запис у журналі («не можна скасувати»), без тосту — тост показує сама дія. */
export function trackFinal(label: string, reason?: string, note?: string): JournalEntry {
  return useJournal.getState().record({ label, reason, note });
}

function refresh(entry: JournalEntry): void {
  for (const key of invalidations.get(entry.id) ?? []) void queryClient.invalidateQueries({ queryKey: key });
}

function report(outcome: UndoOutcome, direction: 'undo' | 'redo'): UndoOutcome {
  switch (outcome.kind) {
    case 'ok': {
      const { entry } = outcome;
      if (direction === 'redo') {
        notify.undo(`Повернуто: ${lowerFirst(entry.label)}`, { id: entry.id, onUndo: () => void undoEntry(entry.id) });
      } else if (entry.canRedo) {
        notify.undo(`Скасовано: ${lowerFirst(entry.label)}`, {
          id: entry.id,
          undoLabel: 'Повернути',
          onUndo: () => void redoEntry(entry.id),
        });
      } else {
        notify.info(`Скасовано: ${lowerFirst(entry.label)}`, { id: entry.id });
      }
      return outcome;
    }
    case 'error': {
      const { entry } = outcome;
      refresh(entry);
      const title = direction === 'undo' ? `Не скасовано: ${lowerFirst(entry.label)}` : `Не повернуто: ${lowerFirst(entry.label)}`;
      if (entry.status === 'failed' || (direction === 'redo' && entry.status === 'undone')) {
        notify.error(title, undefined, { id: entry.id, description: entry.reason });
      } else {
        notify.warning(title, { id: entry.id, description: entry.reason });
      }
      return outcome;
    }
    case 'empty':
      if (direction === 'undo') {
        if (outcome.elsewhere) {
          notify.undo('На цій сторінці скасовувати нічого', {
            undoLabel: 'Журнал дій',
            duration: 4000,
            onUndo: () => useJournal.getState().setPanelOpen(true),
          });
        } else {
          notify.info('Скасовувати нічого', { duration: 3000 });
        }
      } else {
        notify.info('Повертати нічого', { duration: 3000 });
      }
      return outcome;
    case 'busy':
      return outcome;
  }
}

export async function undoEntry(id: string): Promise<UndoOutcome> {
  return report(await useJournal.getState().undo(id), 'undo');
}

export async function redoEntry(id: string): Promise<UndoOutcome> {
  return report(await useJournal.getState().redo(id), 'redo');
}

/** Ctrl+Z: остання дія поточної сторінки. */
export async function undoLatest(scope?: string): Promise<UndoOutcome> {
  return report(await useJournal.getState().undoLatest(scope), 'undo');
}

/** Ctrl+Shift+Z / Ctrl+Y: остання скасована дія поточної сторінки. */
export async function redoLatest(scope?: string): Promise<UndoOutcome> {
  return report(await useJournal.getState().redoLatest(scope), 'redo');
}

/**
 * Сторінка, до якої належать нові записи журналу й на яку діє Ctrl+Z. За замовчуванням — шлях
 * сторінки (так ставить `JournalHost`); редактор розкладу уточнює її версією.
 */
export function useJournalScope(scope: string | null | undefined): void {
  useEffect(() => {
    if (!scope) return;
    useJournal.getState().setScopeOverride(scope);
    return () => {
      if (useJournal.getState().scopeOverride === scope) useJournal.getState().setScopeOverride(null);
    };
  }, [scope]);
}
