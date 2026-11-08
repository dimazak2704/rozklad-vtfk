import { Eye, FileDown, Search, Trash2, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { downloadOrderFile, useDeleteImportedOrder, useOrders } from '@/api/admin-daily';
import type { DocumentFormat, IsoDate, OrderSummaryDto, Uuid } from '@/api/types';
import { DataTable, EmptyState, Field, IconButton, Input, notify, Plate, RowActions, type DataTableColumn } from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { formatInstant, formatNumericDate, formatShortWeekdayDate } from '@/lib/date';
import { confirm } from '@/stores/confirm';

const PAGE_SIZE = 10;

interface OrdersArchiveProps {
  semester: { startsOn: IsoDate; endsOn: IsoDate } | null;
  selectedId: Uuid | null;
  onOpen: (order: OrderSummaryDto) => void;
}

/** Архів опублікованих розпоряджень: пошук за номером, групою, викладачем чи дисципліною і за датами. */
export function OrdersArchive({ semester, selectedId, onOpen }: OrdersArchiveProps) {
  const [query, setQuery] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const q = useDebouncedValue(query.trim(), 250);
  const orders = useOrders({
    status: 'PUBLISHED',
    q: q || undefined,
    from: from || undefined,
    to: to || undefined,
    page,
    size: PAGE_SIZE,
    sort: 'date,desc',
  });
  const filtered = Boolean(q || from || to);
  const removeImported = useDeleteImportedOrder();

  /** Імпортоване з помилкою в номері чи даті розпорядження видаляють і імпортують знову. */
  const deleteImported = async (row: OrderSummaryDto) => {
    const ok = await confirm({
      title: 'Видалити імпортоване розпорядження?',
      description:
        'Це лише текст паперового розпорядження: змін і пар за ним немає. Після видалення його номер більше не впливає на нумерацію, а правильне розпорядження можна імпортувати знову.',
      objectName: `№${row.number ?? '—'} на ${formatNumericDate(row.date)}`,
      confirmLabel: 'Видалити',
      cancelLabel: 'Залишити',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await removeImported.mutateAsync(row.id);
      notify.success(`Імпортоване розпорядження №${row.number ?? '—'} видалено`);
    } catch (error) {
      notify.error(error, 'Не вдалося видалити розпорядження');
    }
  };

  const download = async (row: OrderSummaryDto, format: DocumentFormat) => {
    try {
      await downloadOrderFile(row, format);
    } catch (error) {
      notify.error(error, 'Не вдалося завантажити файл');
    }
  };

  const columns = useMemo<Array<DataTableColumn<OrderSummaryDto>>>(
    () => [
      {
        id: 'number',
        header: '№',
        width: '5.5rem',
        rowHeader: true,
        cell: (row) => <Plate size="sm">№{row.number ?? '—'}</Plate>,
      },
      { id: 'date', header: 'На дату', cell: (row) => <span className="figures">{formatShortWeekdayDate(row.date)}.{row.date.slice(0, 4)}</span> },
      {
        id: 'kind',
        header: 'Документ',
        hideBelow: 'sm',
        cell: (row) => (row.kind === 'AMENDMENT' ? 'Доповнення' : 'Розпорядження'),
      },
      { id: 'rows', header: 'Рядків', numeric: true, cell: (row) => row.itemCount },
      {
        id: 'published',
        header: 'Опубліковано',
        hideBelow: 'md',
        cell: (row) => (row.publishedAt ? <span className="figures text-secondary">{formatInstant(row.publishedAt)}</span> : '—'),
      },
    ],
    [],
  );

  return (
    <div className="flex flex-col gap-3">
      <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_10rem_10rem]">
        <Field label="Пошук" hint="Номер («35»), група («4ki1»), прізвище чи дисципліна">
          <Input
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setPage(0);
            }}
            iconStart={<Search className="size-4" />}
            placeholder="Напр.: Прокопчук"
            endSlot={
              query ? (
                <IconButton label="Очистити пошук" size="sm" icon={<X aria-hidden="true" className="size-4" />} onClick={() => setQuery('')} />
              ) : null
            }
          />
        </Field>
        <Field label="Від">
          <Input type="date" value={from} min={semester?.startsOn} max={to || semester?.endsOn} onChange={(event) => {
            setFrom(event.target.value);
            setPage(0);
          }} className="figures" />
        </Field>
        <Field label="До">
          <Input type="date" value={to} min={from || semester?.startsOn} max={semester?.endsOn} onChange={(event) => {
            setTo(event.target.value);
            setPage(0);
          }} className="figures" />
        </Field>
      </div>
      <DataTable
        caption="Опубліковані розпорядження"
        columns={columns}
        rows={orders.data?.items}
        getRowId={(row) => row.id}
        loading={orders.isPending || orders.isPlaceholderData}
        error={orders.error ?? undefined}
        onRetry={() => void orders.refetch()}
        onRowClick={onOpen}
        selectedRowId={selectedId}
        skeletonRows={4}
        rowActions={(row) => (
          <RowActions
            rowLabel={`розпорядження №${row.number ?? '—'}`}
            actions={[
              { label: 'Переглянути', icon: <Eye aria-hidden="true" />, onSelect: () => onOpen(row) },
              // імпортоване паперове розпорядження — лише текст: файлів у сервісі документів немає
              ...(row.imported
                ? [
                    {
                      label: 'Видалити імпортоване',
                      icon: <Trash2 aria-hidden="true" />,
                      onSelect: () => void deleteImported(row),
                      separatorBefore: true,
                      tone: 'danger' as const,
                    },
                  ]
                : [
                    { label: 'Завантажити DOCX', icon: <FileDown aria-hidden="true" />, onSelect: () => void download(row, 'docx'), separatorBefore: true },
                    { label: 'Завантажити PDF', icon: <FileDown aria-hidden="true" />, onSelect: () => void download(row, 'pdf') },
                    { label: 'Завантажити PNG', icon: <FileDown aria-hidden="true" />, onSelect: () => void download(row, 'png') },
                  ]),
            ]}
          />
        )}
        empty={
          <EmptyState
            size="sm"
            title={filtered ? 'Нічого не знайдено' : 'Опублікованих розпоряджень ще немає'}
            description={
              filtered
                ? 'Спробуйте інше написання: «4ki1», «кренц» або номер без «№».'
                : 'Щойно опублікуєте перше розпорядження семестру, воно зʼявиться тут.'
            }
          />
        }
        pagination={
          orders.data && orders.data.total > PAGE_SIZE
            ? { page, size: PAGE_SIZE, total: orders.data.total, onPageChange: setPage, noun: 'розпоряджень' }
            : undefined
        }
        density="compact"
      />
    </div>
  );
}
