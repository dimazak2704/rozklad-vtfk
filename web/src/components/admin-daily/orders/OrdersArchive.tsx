import { CalendarRange, Eye, FileDown, Search, Trash2, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { downloadOrderFile, useDeleteImportedOrder, useOrders } from '@/api/admin-daily';
import type { DocumentFormat, IsoDate, OrderSummaryDto, Uuid } from '@/api/types';
import { Button, DataTable, EmptyState, Field, IconButton, Input, notify, RowActions, type DataTableColumn } from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { formatInstant, formatNumericDate, isoWeekday } from '@/lib/date';
import { weekdayShort } from '@/lib/labels';
import { confirm } from '@/stores/confirm';
import { trackFinal } from '@/lib/undo/actions';

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
  // межі дат — рідкий фільтр: поля зʼявляються на вимогу, а не стоять трьома рядками на телефоні
  const [datesOpen, setDatesOpen] = useState(false);
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
      trackFinal(`Видалено імпортоване розпорядження №${row.number ?? '—'}`, 'Видалене розпорядження можна лише імпортувати знову');
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
        width: '9rem',
        rowHeader: true,
        // вид документа — лише коли це доповнення: «Розпорядження» в кожному рядку нічого не каже
        cell: (row) => (
          <span className="whitespace-nowrap">
            <span className="font-semibold text-secondary figures">№{row.number ?? '—'}</span>
            {row.kind === 'AMENDMENT' ? <span className="text-caption text-muted"> · доповнення</span> : null}
          </span>
        ),
      },
      {
        id: 'date',
        header: 'На дату',
        // дата — те, за чим шукають розпорядження: вона й найпомітніша в рядку
        cell: (row) => (
          <span className="whitespace-nowrap font-[650] text-primary figures">
            {weekdayShort(isoWeekday(row.date))}, {formatNumericDate(row.date)}
          </span>
        ),
      },
      { id: 'rows', header: 'Рядків', numeric: true, hideBelow: 'md', cell: (row) => row.itemCount },
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
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-end gap-2">
          {/* пошук по номеру чи прізвищу — коротке поле, а не смуга на всю ширину */}
          <Field label="Пошук" className="min-w-0 flex-1 basis-64 sm:max-w-[30rem]">
            <Input
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setPage(0);
              }}
              iconStart={<Search className="size-4" />}
              placeholder="Номер, група чи прізвище"
              endSlot={
                query ? (
                  <IconButton label="Очистити пошук" size="sm" icon={<X aria-hidden="true" className="size-4" />} onClick={() => setQuery('')} />
                ) : null
              }
            />
          </Field>
          {!datesOpen && !from && !to ? (
            <Button variant="ghost" onClick={() => setDatesOpen(true)} iconStart={<CalendarRange aria-hidden="true" className="size-4" />}>
              За датами
            </Button>
          ) : null}
        </div>
        {datesOpen || from || to ? (
          <div className="grid grid-cols-2 gap-3 sm:max-w-sm">
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
        ) : null}
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
