export interface SortState {
  columnId: string;
  direction: 'asc' | 'desc';
}

/** Українське порівняння рядків із «природними» числами: 1КІ1 < 1КІ2 < 1КІ10, «Є» після «Е». */
export const ukCollator = new Intl.Collator('uk', { numeric: true, sensitivity: 'base' });

type SortValue = string | number | boolean | null | undefined;

/** Стабільне сортування; порожні значення завжди в кінці. */
export function sortRows<T>(rows: readonly T[], getValue: (row: T) => SortValue, direction: 'asc' | 'desc'): T[] {
  const factor = direction === 'asc' ? 1 : -1;
  return rows
    .map((row, index) => ({ row, index, value: getValue(row) }))
    .sort((a, b) => {
      const aEmpty = a.value === null || a.value === undefined || a.value === '';
      const bEmpty = b.value === null || b.value === undefined || b.value === '';
      if (aEmpty || bEmpty) return aEmpty === bEmpty ? a.index - b.index : aEmpty ? 1 : -1;
      let result: number;
      if (typeof a.value === 'number' && typeof b.value === 'number') result = a.value - b.value;
      else if (typeof a.value === 'boolean' && typeof b.value === 'boolean') result = Number(a.value) - Number(b.value);
      else result = ukCollator.compare(String(a.value), String(b.value));
      return result === 0 ? a.index - b.index : result * factor;
    })
    .map((entry) => entry.row);
}

/** `name,asc` для параметра `sort` у запитах Page<T>. */
export function toSortParam(sort: SortState | null | undefined): string | undefined {
  return sort ? `${sort.columnId},${sort.direction}` : undefined;
}
