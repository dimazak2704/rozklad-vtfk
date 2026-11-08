import { ArrowDown, ArrowUp } from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { sortRows, type SortState } from '@/lib/sort';
import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';
import { Pagination } from './Pagination';
import { Skeleton } from './Skeleton';

export interface DataTableColumn<T> {
  id: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  /** Значення для сортування на клієнті. Без нього колонка не сортується (якщо не `sortable` для сервера). */
  sortValue?: (row: T) => string | number | boolean | null | undefined;
  /** Колонка сортується (для серверного сортування без sortValue). */
  sortable?: boolean;
  align?: 'start' | 'center' | 'end';
  /** Числа: вирівнювання праворуч і табличні цифри. */
  numeric?: boolean;
  /** Ширина колонки: `8rem`, `20%`. */
  width?: string;
  /** Ховати на вузьких екранах. */
  hideBelow?: 'sm' | 'md' | 'lg';
  /** Клітинка — заголовок рядка (th scope="row"). Зазвичай перша колонка з назвою. */
  rowHeader?: boolean;
  className?: string;
}

export interface DataTablePagination {
  page: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  noun?: string;
}

export interface DataTableProps<T> {
  columns: ReadonlyArray<DataTableColumn<T>>;
  rows: readonly T[] | undefined;
  getRowId: (row: T) => string;
  /** Підпис таблиці для читача екрана (обовʼязковий). */
  caption: string;
  showCaption?: boolean;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  /** Порожній стан: що робити, коли записів немає. */
  empty?: ReactNode;
  /** Кероване сортування; разом з `manualSorting` — сортує сервер. */
  sort?: SortState | null;
  onSortChange?: (sort: SortState | null) => void;
  defaultSort?: SortState | null;
  manualSorting?: boolean;
  /** Дії рядка (зазвичай <RowActions/>), окрема колонка в кінці. */
  rowActions?: (row: T) => ReactNode;
  onRowClick?: (row: T) => void;
  selectedRowId?: string | null;
  rowClassName?: (row: T) => string | undefined;
  pagination?: DataTablePagination;
  density?: 'compact' | 'comfortable';
  /** Обмежити висоту — тоді шапка прилипає всередині таблиці. */
  maxHeight?: string;
  skeletonRows?: number;
  className?: string;
}

const ALIGN = { start: 'text-start', center: 'text-center', end: 'text-end' } as const;
const HIDE = { sm: 'hidden sm:table-cell', md: 'hidden md:table-cell', lg: 'hidden lg:table-cell' } as const;

/** Перша й остання клітинки — з відступом панелі (16 px), щоб таблиця стояла врівень із заголовком панелі. */
const EDGE = 'first:ps-4 last:pe-4';

/**
 * Таблиця даних: сортування (клієнт або сервер), липка шапка, стани завантаження/помилки/порожнечі,
 * пагінація і дії рядка. Перша колонка з назвою має бути `rowHeader` і містити посилання на деталі.
 * Ставиться в `<Panel flush>` без обгорток: шапка таблиці на всю ширину, клітинки врівень із заголовком панелі.
 */
export function DataTable<T>({
  columns,
  rows,
  getRowId,
  caption,
  showCaption = false,
  loading = false,
  error,
  onRetry,
  empty,
  sort: controlledSort,
  onSortChange,
  defaultSort = null,
  manualSorting = false,
  rowActions,
  onRowClick,
  selectedRowId,
  rowClassName,
  pagination,
  density = 'comfortable',
  maxHeight,
  skeletonRows = 6,
  className,
}: DataTableProps<T>) {
  const [innerSort, setInnerSort] = useState<SortState | null>(defaultSort);
  const sort = controlledSort !== undefined ? controlledSort : innerSort;

  const sortedRows = useMemo(() => {
    if (!rows) return [];
    if (manualSorting || !sort) return rows;
    const column = columns.find((c) => c.id === sort.columnId);
    return column?.sortValue ? sortRows(rows, column.sortValue, sort.direction) : rows;
  }, [rows, sort, manualSorting, columns]);

  const changeSort = (columnId: string) => {
    let next: SortState | null;
    if (!sort || sort.columnId !== columnId) next = { columnId, direction: 'asc' };
    else if (sort.direction === 'asc') next = { columnId, direction: 'desc' };
    else next = null;
    if (controlledSort === undefined) setInnerSort(next);
    onSortChange?.(next);
  };

  // рядок 44 px для щоденних черг, 36 px для довідників
  const cellPad = cn(density === 'compact' ? 'h-9 px-3 py-1.5' : 'h-11 px-3 py-2', EDGE);
  const hasActions = Boolean(rowActions);
  const colCount = columns.length + (hasActions ? 1 : 0);
  const showSkeleton = loading && (!rows || rows.length === 0);
  const showError = !loading && error !== undefined && error !== null;
  const showEmpty = !loading && !showError && rows !== undefined && rows.length === 0;

  return (
    <div className={cn('flex flex-col', className)}>
      <div
        className={cn('relative w-full overflow-x-auto', maxHeight && 'overflow-y-auto overscroll-contain')}
        style={maxHeight ? { maxHeight } : undefined}
      >
        <table
          className="w-full border-separate border-spacing-0 text-ui [&>tbody>tr:last-child>*]:border-b-0"
          aria-busy={loading || undefined}
        >
          <caption className={cn(showCaption ? 'px-4 pb-2 text-start text-caption text-muted' : 'sr-only')}>{caption}</caption>
          <thead>
            <tr>
              {columns.map((column) => {
                const canSort = Boolean(column.sortValue) || Boolean(column.sortable);
                const active = sort?.columnId === column.id;
                const ariaSort = active ? (sort?.direction === 'asc' ? 'ascending' : 'descending') : undefined;
                return (
                  <th
                    key={column.id}
                    scope="col"
                    aria-sort={canSort ? (ariaSort ?? 'none') : undefined}
                    style={column.width ? { width: column.width } : column.numeric ? { width: '5.5rem' } : undefined}
                    className={cn(
                      'group/th sticky top-0 z-10 h-9 border-b border-separator bg-sunken px-3 py-0 text-meta font-semibold whitespace-nowrap text-secondary',
                      EDGE,
                      ALIGN[column.numeric ? 'end' : (column.align ?? 'start')],
                      column.hideBelow && HIDE[column.hideBelow],
                    )}
                  >
                    {canSort ? (
                      <button
                        type="button"
                        onClick={() => changeSort(column.id)}
                        className={cn(
                          'group/sort -mx-1 inline-flex items-center gap-1 rounded-plate px-1 hover:text-primary',
                          'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-focus',
                          active && 'text-primary',
                          column.numeric && 'flex-row-reverse',
                        )}
                      >
                        {column.header}
                        {/* місце під стрілку зарезервоване (16 px), щоб підпис не зсувався; неактивна — лише при наведенні */}
                        <span className="inline-flex size-4 items-center justify-center" aria-hidden="true">
                          {active && sort?.direction === 'desc' ? (
                            <ArrowDown className="size-3.5" />
                          ) : (
                            <ArrowUp
                              className={cn(
                                'size-3.5',
                                !active && 'opacity-0 transition-opacity duration-100 group-hover/th:opacity-60 group-focus-visible/sort:opacity-60',
                              )}
                            />
                          )}
                        </span>
                      </button>
                    ) : (
                      column.header
                    )}
                  </th>
                );
              })}
              {hasActions ? (
                <th scope="col" className="sticky top-0 z-10 h-9 w-12 border-b border-separator bg-sunken px-2 last:pe-3">
                  <span className="sr-only">Дії</span>
                </th>
              ) : null}
            </tr>
          </thead>
          <tbody>
            {showSkeleton
              ? Array.from({ length: skeletonRows }, (_, rowIndex) => (
                  <tr key={`skeleton-${rowIndex}`}>
                    {columns.map((column, colIndex) => (
                      <td
                        key={column.id}
                        className={cn(cellPad, 'border-b border-separator', column.hideBelow && HIDE[column.hideBelow])}
                      >
                        <Skeleton
                          className={cn('h-4', colIndex === 0 ? 'w-3/4' : (rowIndex + colIndex) % 3 === 0 ? 'w-1/2' : 'w-2/3', column.numeric && 'ms-auto w-10')}
                        />
                      </td>
                    ))}
                    {hasActions ? <td className={cn(cellPad, 'border-b border-separator')} /> : null}
                  </tr>
                ))
              : null}
            {showError ? (
              <tr>
                <td colSpan={colCount} className="border-b border-separator px-4">
                  <ErrorState error={error} onRetry={onRetry} size="sm" />
                </td>
              </tr>
            ) : null}
            {showEmpty ? (
              <tr>
                <td colSpan={colCount} className="border-b border-separator px-4">
                  {empty ?? <EmptyState title="Записів немає" description="Змініть фільтри або додайте перший запис." size="sm" />}
                </td>
              </tr>
            ) : null}
            {!showSkeleton && !showError
              ? sortedRows.map((row) => {
                  const id = getRowId(row);
                  const selected = selectedRowId === id;
                  return (
                    <tr
                      key={id}
                      data-selected={selected || undefined}
                      onClick={onRowClick ? () => onRowClick(row) : undefined}
                      className={cn(
                        'group transition-[background-color] duration-100',
                        onRowClick && 'cursor-pointer',
                        selected ? 'bg-selected' : 'hover:bg-hover',
                        'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                        loading && 'text-muted',
                        rowClassName?.(row),
                      )}
                    >
                      {columns.map((column) => {
                        const Cell = column.rowHeader ? 'th' : 'td';
                        return (
                          <Cell
                            key={column.id}
                            scope={column.rowHeader ? 'row' : undefined}
                            className={cn(
                              cellPad,
                              'border-b border-separator align-middle',
                              'font-normal text-primary',
                              ALIGN[column.numeric ? 'end' : (column.align ?? 'start')],
                              column.numeric && 'figures',
                              column.hideBelow && HIDE[column.hideBelow],
                              column.className,
                            )}
                          >
                            {column.cell(row)}
                          </Cell>
                        );
                      })}
                      {hasActions ? (
                        <td
                          className="border-b border-separator px-2 py-1 text-end last:pe-3"
                          onClick={(event) => event.stopPropagation()}
                        >
                          {rowActions?.(row)}
                        </td>
                      ) : null}
                    </tr>
                  );
                })
              : null}
          </tbody>
        </table>
      </div>
      {pagination && !showError ? (
        <div className="border-t border-separator px-4 py-3">
          <Pagination {...pagination} />
        </div>
      ) : null}
    </div>
  );
}
