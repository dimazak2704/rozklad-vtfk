import { useMemo, useState } from 'react';
import { useOrders } from '@/api/admin-daily';
import { useDeliveries } from '@/api/telegram';
import type { DeliveryDto, DeliveryStatus } from '@/api/types';
import { Badge, Chip, DataTable, EmptyState, type DataTableColumn } from '@/components/ui';
import { formatInstant } from '@/lib/date';
import { BINDING_TYPE_LABEL, deliveryChat, deliveryKind, DELIVERY_STATUS } from './labels';

const PAGE_SIZE = 12;
const FILTERS: ReadonlyArray<{ value: DeliveryStatus | null; label: string }> = [
  { value: null, label: 'Усі' },
  { value: 'SENT', label: 'Надіслано' },
  { value: 'QUEUED', label: 'У черзі' },
  { value: 'RETRYING', label: 'Повтор' },
  { value: 'FAILED', label: 'Не доставлено' },
];

/** Журнал розсилки: що, кому й коли надіслав бот, скільки спроб і чому не вдалося. */
export function DeliveriesTable() {
  const [status, setStatus] = useState<DeliveryStatus | null>(null);
  const [page, setPage] = useState(0);
  // сторінки рахує сервер: журнал може бути довгим, тож не тягнемо його весь
  const deliveries = useDeliveries({ status: status ?? undefined, page, size: PAGE_SIZE });
  const orders = useOrders({ status: 'PUBLISHED', size: 100 });
  const numberOf = useMemo(() => new Map((orders.data?.items ?? []).map((o) => [o.id, o.number])), [orders.data]);
  const data = deliveries.data;

  const columns: Array<DataTableColumn<DeliveryDto>> = [
    {
      id: 'at',
      header: 'Коли',
      width: '9.5rem',
      cell: (row) => <span className="figures text-secondary">{formatInstant(row.sentAt ?? row.createdAt)}</span>,
    },
    {
      id: 'chat',
      header: 'Чат',
      rowHeader: true,
      cell: (row) => (
        <span className="flex min-w-0 flex-col">
          <span className="truncate">{deliveryChat(row)}</span>
          <span className="text-meta font-normal text-muted">{row.bindingType ? BINDING_TYPE_LABEL[row.bindingType] : 'Без привʼязки'}</span>
        </span>
      ),
    },
    {
      id: 'what',
      header: 'Що',
      hideBelow: 'md',
      cell: (row) => deliveryKind(row, row.orderId ? (numberOf.get(row.orderId) ?? null) : null),
    },
    {
      id: 'status',
      header: 'Стан',
      cell: (row) => (
        <span className="flex min-w-0 flex-col items-start gap-0.5">
          <Badge size="sm" tone={DELIVERY_STATUS[row.status].tone}>
            {DELIVERY_STATUS[row.status].label}
          </Badge>
          {row.error ? <span className="text-meta font-normal text-danger">{row.error}</span> : null}
          {row.nextAttemptAt ? (
            <span className="text-meta font-normal text-muted figures">Наступна спроба {formatInstant(row.nextAttemptAt)}</span>
          ) : null}
        </span>
      ),
    },
    { id: 'attempts', header: 'Спроб', numeric: true, hideBelow: 'sm', cell: (row) => row.attempts },
  ];

  return (
    <div className="flex flex-col gap-3">
      <div role="group" aria-label="Стан доставки" className="flex flex-wrap gap-1.5">
        {FILTERS.map((filter) => (
          <Chip
            key={filter.label}
            selected={status === filter.value}
            onToggle={() => {
              setStatus(filter.value);
              setPage(0);
            }}
          >
            {filter.label}
          </Chip>
        ))}
      </div>
      <DataTable
        caption="Журнал розсилки Telegram"
        columns={columns}
        rows={data?.items}
        getRowId={(row) => row.id}
        loading={deliveries.isPending}
        error={deliveries.error ?? undefined}
        onRetry={() => void deliveries.refetch()}
        density="compact"
        empty={
          <EmptyState
            size="sm"
            title={status ? 'Таких доставок немає' : 'Розсилок ще не було'}
            description={status ? 'Оберіть інший стан.' : 'Після публікації розпорядження тут зʼявляться повідомлення в чати груп.'}
          />
        }
        pagination={data && data.total > PAGE_SIZE ? { page, size: PAGE_SIZE, total: data.total, onPageChange: setPage, noun: 'записів' } : undefined}
      />
    </div>
  );
}
