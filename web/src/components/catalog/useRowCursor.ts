import { useState } from 'react';
import { useHotkeys } from '@/hooks/useHotkeys';

interface RowCursorOptions<T> {
  rows: readonly T[] | undefined;
  getId: (row: T) => string;
  /** Enter чи E на виділеному рядку. */
  onOpen: (row: T) => void;
  /** Delete на виділеному рядку (наприклад, діалог видалення). */
  onDelete?: (row: T) => void;
  /** Розділ довідки. */
  group: string;
  enabled?: boolean;
}

/** Enter на кнопці, посиланні чи полі — їхній власний; рядок відкриваємо, коли фокус ніде або на самому рядку. */
function focusIsFree(): boolean {
  const active = document.activeElement;
  return !active || active === document.body || active.id === 'main' || active.matches('#main [data-selected]');
}

/**
 * Фокус — на виділений рядок (roving focus): видно кільце фокуса, а читач екрана називає рядок,
 * тож Enter чи Delete не діють наосліп.
 */
function focusSelected(): void {
  requestAnimationFrame(() => {
    const rows = document.querySelectorAll<HTMLElement>('#main [data-selected]');
    // таблиця й список на телефоні рендеряться обидва; беремо видимий
    const visible = [...rows].find((row) => row.offsetParent !== null);
    if (!visible) return;
    visible.focus({ preventScroll: true });
    visible.scrollIntoView({ block: 'nearest' });
  });
}

/**
 * Курсор рядків для таблиць панелі: J/K — наступний і попередній рядок (підсвічується, як вибраний),
 * Enter або E — відкрити, Delete — видалити, Esc — зняти виділення. Без курсора Enter і Esc
 * лишаються браузеру. `selectedRowId` передайте в `DataTable`.
 */
export function useRowCursor<T>({ rows, getId, onOpen, onDelete, group, enabled = true }: RowCursorOptions<T>) {
  const [cursor, setCursor] = useState<string | null>(null);
  const list = rows ?? [];
  const index = cursor === null ? -1 : list.findIndex((row) => getId(row) === cursor);
  const current = index >= 0 ? list[index] : undefined;

  const move = (delta: 1 | -1) => {
    if (list.length === 0) return;
    const next = index < 0 ? (delta > 0 ? 0 : list.length - 1) : Math.min(list.length - 1, Math.max(0, index + delta));
    const row = list[next];
    if (!row) return;
    setCursor(getId(row));
    focusSelected();
  };

  useHotkeys([
    { keys: 'j', handler: () => move(1), description: 'Наступний рядок', group, enabled },
    { keys: 'k', handler: () => move(-1), description: 'Попередній рядок', group, enabled },
    {
      keys: 'enter',
      handler: () => {
        if (!current || !focusIsFree()) return false;
        onOpen(current);
        return true;
      },
      description: 'Відкрити виділений рядок',
      helpKeys: 'enter,e',
      group,
      enabled,
    },
    {
      keys: 'e',
      handler: () => {
        if (!current) return false;
        onOpen(current);
        return true;
      },
      enabled,
    },
    {
      keys: 'delete',
      handler: () => {
        if (!current || !onDelete) return false;
        onDelete(current);
        return true;
      },
      description: onDelete ? 'Видалити виділений рядок' : undefined,
      group,
      enabled,
    },
    {
      keys: 'escape',
      handler: () => {
        if (!current) return false;
        setCursor(null);
        // фокус був на рядку — лишаємо його на сторінці, а не на <body>
        if (document.activeElement?.matches('#main [data-selected]')) document.getElementById('main')?.focus({ preventScroll: true });
        return true;
      },
      description: 'Зняти виділення рядка',
      group,
      enabled,
    },
  ]);

  return { selectedRowId: current ? cursor : null, setCursor };
}
