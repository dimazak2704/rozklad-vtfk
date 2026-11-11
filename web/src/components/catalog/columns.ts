import type { DataTableColumn } from '@/components/ui';

/**
 * Колонки, що нічого не кажуть, ховаємо: коли в усіх рядках одне й те саме значення або порожньо
 * («Штатний» у кожному рядку, «—» скрізь). `values` — за якими колонками перевіряти й як брати значення.
 * Порожній список рядків — нічого не ховаємо (ще вантажиться або порожній стан).
 */
export function informativeColumns<T>(
  columns: ReadonlyArray<DataTableColumn<T>>,
  rows: readonly T[] | undefined,
  values: Readonly<Record<string, (row: T) => unknown>>,
): Array<DataTableColumn<T>> {
  if (!rows || rows.length < 2) return [...columns];
  return columns.filter((column) => {
    const pick = values[column.id];
    if (!pick) return true;
    return new Set(rows.map((row) => pick(row) ?? null)).size > 1;
  });
}
