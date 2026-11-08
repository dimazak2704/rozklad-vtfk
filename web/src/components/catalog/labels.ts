import type { Employment, RoomType } from '@/api/types';
import type { SelectOption } from '@/components/ui';
import { capitalize, ROOM_TYPE_LABEL } from '@/lib/labels';

export const ROOM_TYPES: readonly RoomType[] = ['CLASSROOM', 'COMPUTER_LAB', 'LAB', 'GYM', 'SPORTS_GROUND', 'OTHER'];

export const ROOM_TYPE_OPTIONS: ReadonlyArray<SelectOption<RoomType>> = ROOM_TYPES.map((type) => ({
  value: type,
  label: capitalize(ROOM_TYPE_LABEL[type]),
}));

export const EMPLOYMENT_LABEL: Record<Employment, string> = {
  STAFF: 'Штатний',
  PART_TIME: 'Сумісник',
};

export const EMPLOYMENT_OPTIONS: ReadonlyArray<SelectOption<Employment>> = [
  { value: 'STAFF', label: 'Штатний', description: 'Основне місце роботи — коледж' },
  { value: 'PART_TIME', label: 'Сумісник', description: 'Працює за сумісництвом' },
];

/** Значення фільтра «стан»: усі / активні / неактивні. */
export type ActiveFilter = 'all' | 'active' | 'inactive';

export const ACTIVE_FILTER_OPTIONS: ReadonlyArray<{ value: ActiveFilter; label: string }> = [
  { value: 'all', label: 'Усі' },
  { value: 'active', label: 'Активні' },
  { value: 'inactive', label: 'Неактивні' },
];

/** `?active=` → параметр запиту `active=true|false`. */
export function activeParam(value: string | undefined): boolean | undefined {
  if (value === 'active') return true;
  if (value === 'inactive') return false;
  return undefined;
}

export function toActiveFilter(value: string | undefined): ActiveFilter {
  return value === 'active' || value === 'inactive' ? value : 'all';
}

/** Рядків на сторінці довідника: 25 — сторінка вміщується на екрані ноутбука без довгого гортання. */
export const CATALOG_PAGE_SIZE = 25;
