/**
 * Журнал дій диспетчера в цій вкладці: що він додав, змінив чи видалив, і як це повернути.
 *
 * Кожен запис знає зворотну дію (`undo`) і, якщо можна, повтор (`redo`) — це виклики API. Стан —
 * звичайні дані (для панелі «Журнал дій» і тестів), а функції лежать окремо, поза станом.
 * Ctrl+Z скасовує останній запис поточної сторінки (`scope`); старіші й чужі — з панелі.
 * Незворотні дії (публікація розпорядження, надсилання в Telegram) теж записуються — зі статусом
 * `final`, щоб диспетчер бачив, що сталося, — але скасувати їх не можна.
 *
 * Записи (без функцій) живуть у sessionStorage вкладки: після перезавантаження журнал видно, але
 * скасувати звідти вже нічого не можна — записи стають «не можна скасувати» з поясненням. Журнал
 * належить одному користувачу (`owner`): інший вхід у тій самій вкладці починає порожній журнал.
 *
 * Сервер лишається джерелом правди: якщо запис уже змінив хтось інший (409) чи його вже немає,
 * журнал так і каже, а не перезаписує чужу правку.
 */
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { classifyUndoError } from './errors';

export type JournalStatus =
  /** Виконано, можна скасувати. */
  | 'done'
  /** Скасовуємо чи повторюємо просто зараз. */
  | 'pending'
  /** Скасовано; `canRedo` — чи можна повернути. */
  | 'undone'
  /** Скасувати не вдалося через збій (мережа, сервер) — можна спробувати ще раз. */
  | 'failed'
  /** Дані змінилися після дії (хтось інший, 409, 404) — скасовувати наосліп не будемо. */
  | 'stale'
  /** Незворотна дія або правило сервера (422) не дає її повернути. */
  | 'final';

export interface JournalEntry {
  id: string;
  /** Час дії, мс. */
  at: number;
  /** Речення про дію: «Додано аудиторію 305». */
  label: string;
  /** Сторінка (чи версія розкладу), до якої належить дія: Ctrl+Z скасовує лише її записи. */
  scope: string;
  status: JournalStatus;
  /** Чому не скасовано / чому не можна. */
  reason?: string;
  /** Наслідок поза системою: «Викладач отримав повідомлення в Telegram». */
  note?: string;
  canUndo: boolean;
  canRedo: boolean;
}

export interface JournalOps {
  undo?: () => Promise<unknown>;
  redo?: () => Promise<unknown>;
}

export interface RecordInput extends JournalOps {
  label: string;
  /** За замовчуванням — поточна сторінка (`activeScope`). */
  scope?: string;
  note?: string;
  /** Для незворотної дії: чому її не можна скасувати. */
  reason?: string;
}

export type UndoOutcome =
  | { kind: 'ok'; entry: JournalEntry }
  | { kind: 'error'; entry: JournalEntry; error: unknown }
  /** Нема що скасовувати (на цій сторінці). `elsewhere` — чи є що скасувати на інших. */
  | { kind: 'empty'; elsewhere: boolean }
  /** Попередня дія ще виконується. */
  | { kind: 'busy' };

export const JOURNAL_LIMIT = 50;

interface JournalState {
  /** Найновіші — першими. */
  entries: JournalEntry[];
  /** Що Ctrl+Shift+Z поверне на кожній сторінці: ідентифікатори скасованих, останній — нагорі. */
  redoStacks: Record<string, string[]>;
  /** Поточна сторінка: те, що задала сторінка (`useJournalScope`), інакше її шлях. */
  activeScope: string;
  pathScope: string;
  scopeOverride: string | null;
  busy: boolean;
  panelOpen: boolean;
  /** Чий це журнал (id користувача). */
  owner: string | null;
  setOwner: (owner: string | null) => void;
  setPathScope: (path: string) => void;
  setScopeOverride: (scope: string | null) => void;
  setPanelOpen: (open: boolean) => void;
  record: (input: RecordInput) => JournalEntry;
  undo: (id: string) => Promise<UndoOutcome>;
  redo: (id: string) => Promise<UndoOutcome>;
  undoLatest: (scope?: string) => Promise<UndoOutcome>;
  redoLatest: (scope?: string) => Promise<UndoOutcome>;
  clear: () => void;
}

const ops = new Map<string, JournalOps>();
let seq = 0;

function nextId(): string {
  seq += 1;
  return `j${Date.now().toString(36)}-${seq}`;
}

/** Останній запис сторінки, який Ctrl+Z скасує: виконаний або той, що не скасувався через збій. */
export function latestUndoable(entries: readonly JournalEntry[], scope: string): JournalEntry | undefined {
  return entries.find((entry) => entry.scope === scope && entry.canUndo && (entry.status === 'done' || entry.status === 'failed'));
}

/** Що поверне Ctrl+Shift+Z на сторінці: останній скасований, якщо після нього не було нових дій. */
export function latestRedoable(state: Pick<JournalState, 'entries' | 'redoStacks'>, scope: string): JournalEntry | undefined {
  const id = state.redoStacks[scope]?.at(-1);
  return id ? state.entries.find((entry) => entry.id === id && entry.status === 'undone' && entry.canRedo) : undefined;
}

export const RELOADED_REASON = 'Сторінку перезавантажили — звідси цю дію вже не скасувати';

/** Після перезавантаження функцій скасування вже немає: записи лишаються для читання. */
export function restoreEntries(entries: readonly JournalEntry[]): JournalEntry[] {
  return entries.map((entry) => {
    const open = entry.canUndo && (entry.status === 'done' || entry.status === 'failed' || entry.status === 'pending');
    return open
      ? { ...entry, status: 'final', canUndo: false, canRedo: false, reason: RELOADED_REASON }
      : { ...entry, canUndo: false, canRedo: false };
  });
}

export const useJournal = create<JournalState>()(
  persist(
    (set, get) => {
  const patch = (id: string, changes: Partial<JournalEntry>) =>
    set((state) => ({ entries: state.entries.map((entry) => (entry.id === id ? { ...entry, ...changes } : entry)) }));

  const run = async (id: string, direction: 'undo' | 'redo'): Promise<UndoOutcome> => {
    const entry = get().entries.find((e) => e.id === id);
    const fn = ops.get(id)?.[direction];
    if (!entry || !fn) return { kind: 'empty', elsewhere: false };
    if (direction === 'undo' && !(entry.status === 'done' || entry.status === 'failed')) return { kind: 'empty', elsewhere: false };
    if (direction === 'redo' && entry.status !== 'undone') return { kind: 'empty', elsewhere: false };
    if (get().busy) return { kind: 'busy' };
    set({ busy: true });
    const previous = entry.status;
    patch(id, { status: 'pending', reason: undefined });
    try {
      await fn();
      set((state) => {
        const stack = (state.redoStacks[entry.scope] ?? []).filter((x) => x !== id);
        return {
          redoStacks: { ...state.redoStacks, [entry.scope]: direction === 'undo' ? [...stack, id] : stack },
        };
      });
      patch(id, { status: direction === 'undo' ? 'undone' : 'done' });
      return { kind: 'ok', entry: get().entries.find((e) => e.id === id) ?? entry };
    } catch (error) {
      const verdict = classifyUndoError(error);
      // невдалий повтор лишає дію скасованою: стан на сервері не змінився
      if (direction === 'redo' && verdict.status === 'failed') patch(id, { status: previous, reason: verdict.reason });
      else patch(id, { status: verdict.status, reason: verdict.reason });
      if (verdict.status !== 'failed') {
        set((state) => ({
          redoStacks: { ...state.redoStacks, [entry.scope]: (state.redoStacks[entry.scope] ?? []).filter((x) => x !== id) },
        }));
      }
      return { kind: 'error', entry: get().entries.find((e) => e.id === id) ?? entry, error };
    } finally {
      set({ busy: false });
    }
  };

  return {
    entries: [],
    redoStacks: {},
    owner: null,
    activeScope: '',
    pathScope: '',
    scopeOverride: null,
    busy: false,
    panelOpen: false,
    setPathScope: (pathScope) => set((state) => ({ pathScope, activeScope: state.scopeOverride ?? pathScope })),
    setScopeOverride: (scopeOverride) => set((state) => ({ scopeOverride, activeScope: scopeOverride ?? state.pathScope })),
    setPanelOpen: (panelOpen) => set({ panelOpen }),

    record: ({ label, scope, note, reason, undo, redo }) => {
      const entry: JournalEntry = {
        id: nextId(),
        at: Date.now(),
        label,
        scope: scope ?? get().activeScope,
        status: undo ? 'done' : 'final',
        reason: undo ? undefined : (reason ?? 'Цю дію не можна скасувати'),
        note,
        canUndo: Boolean(undo),
        canRedo: Boolean(undo && redo),
      };
      ops.set(entry.id, { undo, redo });
      set((state) => {
        const entries = [entry, ...state.entries];
        for (const dropped of entries.slice(JOURNAL_LIMIT)) ops.delete(dropped.id);
        // нова дія на сторінці обриває гілку «повернути» — як у будь-якому редакторі
        return { entries: entries.slice(0, JOURNAL_LIMIT), redoStacks: { ...state.redoStacks, [entry.scope]: [] } };
      });
      return entry;
    },

    undo: (id) => run(id, 'undo'),
    redo: (id) => run(id, 'redo'),

    undoLatest: (scope = get().activeScope) => {
      const entry = latestUndoable(get().entries, scope);
      if (!entry) {
        const elsewhere = get().entries.some((e) => e.canUndo && (e.status === 'done' || e.status === 'failed'));
        return Promise.resolve({ kind: 'empty', elsewhere });
      }
      return run(entry.id, 'undo');
    },

    redoLatest: (scope = get().activeScope) => {
      const entry = latestRedoable(get(), scope);
      return entry ? run(entry.id, 'redo') : Promise.resolve({ kind: 'empty', elsewhere: false });
    },

    setOwner: (owner) => {
      if (get().owner !== owner) get().clear();
      set({ owner });
    },

    clear: () => {
      ops.clear();
      set({ entries: [], redoStacks: {}, busy: false });
    },
  };
    },
    {
      name: 'rozklad.journal',
      version: 1,
      storage: createJSONStorage(() => sessionStorage),
      partialize: (state) => ({ entries: state.entries, owner: state.owner }),
      merge: (persisted, current) => {
        const saved = persisted as Partial<Pick<JournalState, 'entries' | 'owner'>> | undefined;
        return { ...current, owner: saved?.owner ?? null, entries: restoreEntries(saved?.entries ?? []) };
      },
    },
  ),
);
