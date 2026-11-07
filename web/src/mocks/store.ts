/**
 * Стан мок-API в памʼяті: кожна область тримає свої сховища (заміни, розпорядження, каталог…).
 * Стан живе до перезавантаження сторінки; у тестах скидається після кожного тесту (src/test/setup.ts).
 */
import type { Uuid } from '@/api/types';
import { notFound } from './kit';

const resetters = new Set<() => void>();

/** Повертає всі сховища до початкових даних. */
export function resetMockStores(): void {
  for (const reset of resetters) reset();
}

export interface MockStore<T> {
  get: () => T;
  set: (next: T) => void;
  /** Нове значення з поточного; повертає його. */
  update: (recipe: (current: T) => T) => T;
  reset: () => void;
}

/** Сховище одного значення (налаштування, лічильник номерів розпоряджень тощо). */
export function createMockStore<T>(seed: () => T): MockStore<T> {
  let state = seed();
  const store: MockStore<T> = {
    get: () => state,
    set: (next) => {
      state = next;
    },
    update: (recipe) => {
      state = recipe(state);
      return state;
    },
    reset: () => {
      state = seed();
    },
  };
  resetters.add(store.reset);
  return store;
}

export interface MockCollection<T extends { id: Uuid }> {
  all: () => T[];
  find: (id: Uuid) => T | undefined;
  /** Як `find`, але відсутній запис — 404 ProblemDetail, кинутий як відповідь MSW. */
  get: (id: Uuid) => T;
  insert: (item: T) => T;
  /** Змінює запис (404, якщо його немає); повертає нову версію. */
  update: (id: Uuid, recipe: (current: T) => T) => T;
  /** Видаляє запис (404, якщо його немає); повертає видалений. */
  remove: (id: Uuid) => T;
  reset: () => void;
}

/**
 * Колекція записів з id. `label` — назва в знахідному відмінку для повідомлень: «Групу …», «Заміну …».
 */
export function createMockCollection<T extends { id: Uuid }>(label: string, seed: () => readonly T[]): MockCollection<T> {
  const store = createMockStore<Map<Uuid, T>>(() => new Map(seed().map((item) => [item.id, item])));
  const missing = (id: Uuid) => notFound(`${label} з id ${id} не знайдено.`);
  const get = (id: Uuid): T => {
    const item = store.get().get(id);
    if (item === undefined) throw missing(id);
    return item;
  };
  return {
    all: () => [...store.get().values()],
    find: (id) => store.get().get(id),
    get,
    insert: (item) => {
      store.get().set(item.id, item);
      return item;
    },
    update: (id, recipe) => {
      const next = recipe(get(id));
      store.get().set(id, next);
      return next;
    },
    remove: (id) => {
      const item = get(id);
      store.get().delete(id);
      return item;
    },
    reset: store.reset,
  };
}
