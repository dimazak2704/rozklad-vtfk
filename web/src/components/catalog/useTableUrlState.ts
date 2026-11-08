import { useCallback } from 'react';
import { useSearchParams } from 'react-router';
import type { SortState } from '@/lib/sort';

type ParamValue = string | number | boolean | null | undefined;

export interface TableUrlState {
  /** Текст пошуку як у полі (без затримки); для запиту беріть useDebouncedValue(q). */
  q: string;
  /** Сторінка з нуля. */
  page: number;
  sort: SortState | null;
  /** Значення фільтра з адреси. */
  get: (name: string) => string | undefined;
  /** Змінює параметри; будь-яка зміна, крім сторінки, повертає на першу сторінку. */
  set: (patch: Record<string, ParamValue>) => void;
  setPage: (page: number) => void;
  setSort: (sort: SortState | null) => void;
  /** Чи є активний пошук або фільтр (для порожнього стану «нічого не знайдено»). */
  filtered: boolean;
  reset: () => void;
}

function parseSort(raw: string | null): SortState | null {
  if (!raw) return null;
  const [columnId, direction] = raw.split(',');
  if (!columnId || (direction !== 'asc' && direction !== 'desc')) return null;
  return { columnId, direction };
}

/**
 * Стан таблиці в адресі (UI.md §3: посиланням можна поділитися): `?q=`, `?page=`, `?sort=поле,напрям`
 * і фільтри. Історію не засмічуємо — заміна запису, а не новий крок «назад».
 */
export function useTableUrlState(defaultSort: SortState | null, filterKeys: readonly string[] = []): TableUrlState {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const rawPage = Number(params.get('page') ?? 0);
  const page = Number.isInteger(rawPage) && rawPage > 0 ? rawPage : 0;
  const sort = parseSort(params.get('sort')) ?? defaultSort;
  const filtered = q.trim() !== '' || filterKeys.some((key) => params.get(key));

  const set = useCallback(
    (patch: Record<string, ParamValue>) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          for (const [key, value] of Object.entries(patch)) {
            if (value === null || value === undefined || value === '') next.delete(key);
            else next.set(key, String(value));
          }
          if (!('page' in patch)) next.delete('page');
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  const setPage = useCallback((next: number) => set({ page: next > 0 ? next : null }), [set]);
  const setSort = useCallback(
    (next: SortState | null) => set({ sort: next ? `${next.columnId},${next.direction}` : null }),
    [set],
  );
  const reset = useCallback(() => set(Object.fromEntries(['q', ...filterKeys].map((key) => [key, null]))), [set, filterKeys]);

  return { q, page, sort, get: (name) => params.get(name) ?? undefined, set, setPage, setSort, filtered, reset };
}
