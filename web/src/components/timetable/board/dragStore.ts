/**
 * Стан перетягування для дошки: яке заняття несемо, над якою ціллю, попередня оцінка всіх цілей
 * і відповіді сервера (`move?dryRun=true`). Окреме сховище з селекторами — щоб кожна ціль
 * перемальовувалась лише тоді, коли змінилось саме її значення, а не вся дошка.
 */
import { createStore, useStore, type StoreApi } from 'zustand';
import type { MovePreview } from '@/api/timetable';
import type { TemplateLessonDto, Uuid } from '@/api/types';

/** Що відомо про ціль: попередня оцінка в браузері або відповідь сервера. */
export type TargetCheck =
  | { source: 'estimate'; status: 'free' | 'busy'; messages: string[] }
  | { source: 'server'; status: 'loading' }
  | { source: 'server'; status: 'error'; message: string }
  | ({ source: 'server' } & MovePreview);

export interface DragState {
  active: TemplateLessonDto | null;
  /** Учасники потоку переносяться разом — їх теж показуємо «в дорозі». */
  activeIds: ReadonlySet<Uuid>;
  /** Сутності, в межах яких можна кидати (свої сітки; в аудиторіях — усі). */
  overId: string | null;
  checks: ReadonlyMap<string, TargetCheck>;
  /** Заняття, що щойно приземлилось (коротка підсвітка). */
  landedId: Uuid | null;
}

export type DragStore = StoreApi<DragState>;

const EMPTY_IDS: ReadonlySet<Uuid> = new Set();

export function createDragStore(): DragStore {
  return createStore<DragState>()(() => ({
    active: null,
    activeIds: EMPTY_IDS,
    overId: null,
    checks: new Map(),
    landedId: null,
  }));
}

export function setCheck(store: DragStore, targetId: string, check: TargetCheck): void {
  store.setState((state) => {
    const checks = new Map(state.checks);
    checks.set(targetId, check);
    return { checks };
  });
}

export function useDragState<T>(store: DragStore, selector: (state: DragState) => T): T {
  return useStore(store, selector);
}

