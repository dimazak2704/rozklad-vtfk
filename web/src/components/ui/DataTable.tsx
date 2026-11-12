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
  /** Підпис значення в стосі на телефоні («Год: 4»), якщо `header` — не рядок. */
  mobileLabel?: string;
  className?: string;
}

/** Рядок списку на телефоні: назва, один рядок підпису, необовʼязковий стан праворуч. */
export interface DataTableMobileRow {
  /** «1ВЕ · БіЕ» — головне, що відрізняє рядок. */
  title: ReactNode;
  /** Один рядок без «Підпис:» — «30 з 54 год · не вистачить 4 год», «1 курс · 1 зміна». */
  caption?: ReactNode;
  /** Одне слово стану (`StatusText`) праворуч. */
  status?: ReactNode;
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
  /**
   * На вузьких екранах (< 768 px) таблиця стає списком: заголовок рядка — назвою, решта колонок —
   * рядками «Підпис: значення» під нею, дії — праворуч. Без горизонтальної прокрутки стиснутої таблиці.
   * `false` — лишити таблицю з прокруткою (сітки, де важливі стовпці).
   */
  stackOnMobile?: boolean;
  /**
   * Свій вигляд рядка на телефоні (< 768 px): назва, рядок підпису й стан — без стосу «Підпис: значення».
   * Решта полів — у шторці чи формі рядка (`onRowClick`), дії — у `⋯` праворуч.
   */
  mobileRow?: (row: T) => DataTableMobileRow;
  className?: string;
}

/** Класи стосу на телефоні: таблиця → список рядків. */
const STACK = {
  table: 'max-md:block',
  head: 'max-md:hidden',
  body: 'max-md:block',
  row: 'max-md:grid max-md:grid-cols-[minmax(0,1fr)_auto] max-md:gap-x-3 max-md:gap-y-0.5 max-md:border-b max-md:border-separator max-md:px-5 max-md:py-3 max-md:last:border-b-0',
  cell: 'max-md:col-start-1 max-md:h-auto max-md:border-0 max-md:p-0 max-md:first:ps-0 max-md:last:pe-0 max-md:text-start',
  title: 'max-md:block max-md:text-cell max-md:font-[650]',
  detail:
    'max-md:flex max-md:min-w-0 max-md:flex-wrap max-md:items-baseline max-md:gap-x-1 max-md:text-caption max-md:text-secondary max-md:before:me-1 max-md:before:text-muted max-md:before:content-[attr(data-label)_":"] max-md:[&:not([data-label])]:before:content-none',
  actions: 'max-md:col-start-2 max-md:row-span-6 max-md:row-start-1 max-md:block max-md:self-start max-md:border-0 max-md:p-0',
  wide: 'max-md:block max-md:border-0',
} as const;

const ALIGN = { start: 'text-start', center: 'text-center', end: 'text-end' } as const;
const HIDE = { sm: 'hidden sm:table-cell', md: 'hidden md:table-cell', lg: 'hidden lg:table-cell' } as const;
/** У стосі «sm» означає «сховати лише на найвужчих», а ширше клітинка — рядок списку, а не table-cell. */
const HIDE_STACKED = { sm: 'max-sm:hidden', md: 'hidden md:table-cell', lg: 'hidden lg:table-cell' } as const;

function labelOf<T>(column: DataTableColumn<T>): string | undefined {
  return column.mobileLabel ?? (typeof column.header === 'string' ? column.header : undefined);
}

/** Перша й остання клітинки — з відступом панелі (20 px), щоб таблиця стояла врівень із заголовком панелі. */
const EDGE = 'first:ps-5 last:pe-5';

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
  stackOnMobile = true,
  mobileRow,
  className,
}: DataTableProps<T>) {
  const stack = stackOnMobile ? STACK : null;
  const hide = stackOnMobile ? HIDE_STACKED : HIDE;
  // заголовок рядка в стосі: явний rowHeader або перша колонка
  const titleId = (columns.find((column) => column.rowHeader) ?? columns[0])?.id;
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
  // на телефоні з власним рядком — список замість таблиці (стани завантаження, помилки й порожнечі — спільні)
  const listMode = Boolean(mobileRow) && stackOnMobile && !showSkeleton && !showError && !showEmpty;

  return (
    <div className={cn('flex flex-col', className)}>
      {listMode && mobileRow ? (
        <ul className="md:hidden" aria-label={caption}>
          {sortedRows.map((row) => {
            const id = getRowId(row);
            const item = mobileRow(row);
            const body = (
              <>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="text-cell font-[650] text-primary">{item.title}</span>
                  {item.caption ? <span className="line-clamp-2 text-caption text-secondary">{item.caption}</span> : null}
                </span>
                {item.status ? <span className="shrink-0 text-caption">{item.status}</span> : null}
              </>
            );
            return (
              <li
                key={id}
                data-selected={selectedRowId === id || undefined}
                // курсор рядків (J/K) переносить сюди фокус: читач екрана чує, на якому рядку дія
                aria-current={selectedRowId === id || undefined}
                tabIndex={selectedRowId === id ? -1 : undefined}
                className={cn(
                  'flex items-center gap-2 border-b border-separator last:border-b-0',
                  rowActions ? 'pe-3' : 'pe-5',
                  selectedRowId === id && 'bg-selected',
                  rowClassName?.(row),
                )}
              >
                {onRowClick ? (
                  <button
                    type="button"
                    onClick={() => onRowClick(row)}
                    className={cn(
                      'flex min-h-14 min-w-0 flex-1 items-center gap-3 py-2.5 ps-5 text-start',
                      'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                    )}
                  >
                    {body}
                  </button>
                ) : (
                  <div className="flex min-h-14 min-w-0 flex-1 items-center gap-3 py-2.5 ps-5">{body}</div>
                )}
                {rowActions ? <div className="shrink-0">{rowActions(row)}</div> : null}
              </li>
            );
          })}
        </ul>
      ) : null}
      <div
        className={cn(
          'relative w-full overflow-x-auto',
          stackOnMobile && 'max-md:overflow-x-visible',
          listMode && 'max-md:hidden',
          maxHeight && 'overflow-y-auto overscroll-contain',
        )}
        style={maxHeight ? { maxHeight } : undefined}
      >
        <table
          className={cn('w-full border-separate border-spacing-0 text-ui [&>tbody>tr:last-child>*]:border-b-0', stack?.table)}
          aria-busy={loading || undefined}
        >
          <caption className={cn(showCaption ? 'px-4 pb-2 text-start text-caption text-muted' : 'sr-only')}>{caption}</caption>
          <thead className={stack?.head}>
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
                      // шапка прилипає лише в таблиці з власною прокруткою (maxHeight) — тоді їй потрібне тло
                      'group/th sticky top-0 z-10 h-10 border-b border-separator px-3 py-0 text-caption font-[650] whitespace-nowrap text-secondary',
                      maxHeight && 'bg-surface',
                      EDGE,
                      ALIGN[column.numeric ? 'end' : (column.align ?? 'start')],
                      column.hideBelow && hide[column.hideBelow],
                    )}
                  >
                    {canSort ? (
                      <button
                        type="button"
                        onClick={() => changeSort(column.id)}
                        className={cn(
                          'group/sort -mx-1 inline-flex items-center gap-1 rounded-tag px-1 hover:text-primary',
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
                <th scope="col" className={cn('sticky top-0 z-10 h-10 w-12 border-b border-separator px-2 last:pe-3', maxHeight && 'bg-surface')}>
                  <span className="sr-only">Дії</span>
                </th>
              ) : null}
            </tr>
          </thead>
          <tbody className={stack?.body}>
            {showSkeleton
              ? Array.from({ length: skeletonRows }, (_, rowIndex) => (
                  <tr key={`skeleton-${rowIndex}`} className={stack?.row}>
                    {columns.map((column, colIndex) => (
                      <td
                        key={column.id}
                        className={cn(cellPad, 'border-b border-separator', stack?.cell, column.hideBelow && hide[column.hideBelow])}
                      >
                        <Skeleton
                          className={cn('h-4', colIndex === 0 ? 'w-3/4' : (rowIndex + colIndex) % 3 === 0 ? 'w-1/2' : 'w-2/3', column.numeric && 'ms-auto w-10')}
                        />
                      </td>
                    ))}
                    {hasActions ? <td className={cn(cellPad, 'border-b border-separator', stack && 'max-md:hidden')} /> : null}
                  </tr>
                ))
              : null}
            {showError ? (
              <tr className={stack?.wide}>
                <td colSpan={colCount} className={cn('border-b border-separator px-5', stack?.wide)}>
                  <ErrorState error={error} onRetry={onRetry} size="sm" />
                </td>
              </tr>
            ) : null}
            {showEmpty ? (
              <tr className={stack?.wide}>
                <td colSpan={colCount} className={cn('border-b border-separator px-5', stack?.wide)}>
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
                      // курсор рядків (J/K) переносить сюди фокус: читач екрана чує, на якому рядку дія
                      aria-current={selected || undefined}
                      tabIndex={selected ? -1 : undefined}
                      onClick={onRowClick ? () => onRowClick(row) : undefined}
                      className={cn(
                        'group transition-[background-color] duration-100',
                        onRowClick && 'cursor-pointer',
                        selected ? 'bg-selected' : 'hover:bg-hover',
                        'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                        loading && 'text-muted',
                        stack?.row,
                        rowClassName?.(row),
                      )}
                    >
                      {columns.map((column) => {
                        const Cell = column.rowHeader ? 'th' : 'td';
                        const isTitle = column.id === titleId;
                        return (
                          <Cell
                            key={column.id}
                            scope={column.rowHeader ? 'row' : undefined}
                            data-label={stack && !isTitle ? labelOf(column) : undefined}
                            className={cn(
                              cellPad,
                              'border-b border-separator align-middle',
                              'font-normal text-primary',
                              ALIGN[column.numeric ? 'end' : (column.align ?? 'start')],
                              column.numeric && 'figures',
                              stack?.cell,
                              stack && (isTitle ? stack.title : stack.detail),
                              column.hideBelow && hide[column.hideBelow],
                              column.className,
                            )}
                          >
                            {column.cell(row)}
                          </Cell>
                        );
                      })}
                      {hasActions ? (
                        <td
                          className={cn('border-b border-separator px-2 py-1 text-end last:pe-3', stack?.actions)}
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
        <div className="border-t border-separator px-5 py-3">
          <Pagination {...pagination} />
        </div>
      ) : null}
    </div>
  );
}
