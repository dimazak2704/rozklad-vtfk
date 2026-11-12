import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useSearchParams } from 'react-router';
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

/** Затримка запису пошуку в адресу: поле не чекає на перехід роутера. */
export const SEARCH_URL_DELAY_MS = 250;

/** Позначка в `location.state`: цей запис адреси зробила сама таблиця. */
const OWN_WRITE = 'rozkladTableWrite';

function isOwnWrite(state: unknown): boolean {
  return typeof state === 'object' && state !== null && OWN_WRITE in state;
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
 *
 * Текст пошуку живе в локальному стані й потрапляє в адресу із затримкою: переходи роутера йдуть
 * як transition, і поле, кероване прямо з адреси, на мить отримувало старе значення — наступна
 * літера лягала на нього й попередні губилися. Зміну адреси ззовні (посилання, «назад») поле переймає.
 */
export function useTableUrlState(defaultSort: SortState | null, filterKeys: readonly string[] = []): TableUrlState {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const urlQ = params.get('q') ?? '';
  const [q, setQ] = useState(urlQ);
  const [seenKey, setSeenKey] = useState(location.key);
  if (location.key !== seenKey) {
    setSeenKey(location.key);
    if (!isOwnWrite(location.state) && urlQ !== q) setQ(urlQ);
  }

  const rawPage = Number(params.get('page') ?? 0);
  const page = Number.isInteger(rawPage) && rawPage > 0 ? rawPage : 0;
  const sort = parseSort(params.get('sort')) ?? defaultSort;
  const filtered = q.trim() !== '' || filterKeys.some((key) => params.get(key));

  /** Пошук, ще не записаний в адресу (undefined — нічого не чекає). */
  const pendingQ = useRef<string | undefined>(undefined);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const write = useCallback(
    (patch: Record<string, ParamValue>) => {
      clearTimeout(timer.current);
      const withSearch = pendingQ.current !== undefined && !('q' in patch) ? { ...patch, q: pendingQ.current } : patch;
      pendingQ.current = undefined;
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          for (const [key, value] of Object.entries(withSearch)) {
            if (value === null || value === undefined || value === '') next.delete(key);
            else next.set(key, String(value));
          }
          if (!('page' in withSearch)) next.delete('page');
          return next;
        },
        { replace: true, state: { [OWN_WRITE]: true } },
      );
    },
    [setParams],
  );

  const set = useCallback(
    (patch: Record<string, ParamValue>) => {
      if (!('q' in patch)) {
        write(patch);
        return;
      }
      const nextQ = patch.q == null ? '' : String(patch.q);
      setQ(nextQ);
      const rest = Object.fromEntries(Object.entries(patch).filter(([key]) => key !== 'q'));
      if (Object.keys(rest).length > 0 || nextQ === '') {
        // разом з іншими фільтрами чи очищення — одразу
        write({ ...rest, q: nextQ });
        return;
      }
      pendingQ.current = nextQ;
      clearTimeout(timer.current);
      timer.current = setTimeout(() => write({ q: nextQ }), SEARCH_URL_DELAY_MS);
    },
    [write],
  );

  useEffect(() => () => clearTimeout(timer.current), []);

  const setPage = useCallback((next: number) => set({ page: next > 0 ? next : null }), [set]);
  const setSort = useCallback(
    (next: SortState | null) => set({ sort: next ? `${next.columnId},${next.direction}` : null }),
    [set],
  );
  const reset = useCallback(() => set(Object.fromEntries(['q', ...filterKeys].map((key) => [key, null]))), [set, filterKeys]);

  return { q, page, sort, get: (name) => params.get(name) ?? undefined, set, setPage, setSort, filtered, reset };
}
