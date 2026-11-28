import type { ComponentType } from 'react';
import type { DayPickerProps } from 'react-day-picker';

export type DayPickerComponent = ComponentType<DayPickerProps>;

let pending: Promise<DayPickerComponent> | null = null;
/** Завантажений календар: наступні відкриття малюють його одразу, без кільця. */
let loaded: DayPickerComponent | null = null;

/** Календар, якщо його код уже завантажено. */
export function loadedDayPicker(): DayPickerComponent | null {
  return loaded;
}

/** Один запит на всі календарі сторінки; невдалий (немає мережі) не запамʼятовується — можна спробувати ще. */
export function loadDayPicker(): Promise<DayPickerComponent> {
  pending ??= import('./DayPickerUk').then(
    (module) => {
      loaded = module.default;
      return module.default;
    },
    (error: unknown) => {
      pending = null;
      throw error;
    },
  );
  return pending;
}

/**
 * Починає вантажити код календаря заздалегідь — коли людина наводить курсор чи фокус на кнопку, що його
 * відкриває: поки спливне вікно відкривається, календар уже готовий.
 */
export function preloadDayPicker(): void {
  loadDayPicker().catch(() => undefined);
}
