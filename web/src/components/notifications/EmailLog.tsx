import { useState } from 'react';
import { useEmailDeliveries } from '@/api/email';
import type { EmailDeliveryDto, EmailDeliveryStatus } from '@/api/types';
import { Badge, Chip, DataTable, EmptyState, type DataTableColumn } from '@/components/ui';
import { formatInstant } from '@/lib/date';
import { emailLogKind, emailStatus } from './labels';

const PAGE_SIZE = 15;
const FILTERS: ReadonlyArray<{ value: EmailDeliveryStatus | null; label: string }> = [
  { value: null, label: 'Усі' },
  { value: 'SENT', label: 'Надіслано' },
  { value: 'QUEUED', label: 'У черзі' },
  { value: 'RETRYING', label: 'Повтор' },
  { value: 'FAILED', label: 'Не надіслано' },
];

/** Журнал листів: кому (адреса під маскою), про що, коли і чи дійшло до поштового сервера. */
export function EmailLog() {
  const [status, setStatus] = useState<EmailDeliveryStatus | null>(null);
  const [page, setPage] = useState(0);
  const deliveries = useEmailDeliveries({ status: status ?? undefined, page, size: PAGE_SIZE });
  const data = deliveries.data;

  const columns: Array<DataTableColumn<EmailDeliveryDto>> = [
    {
      id: 'at',
      header: 'Коли',
      width: '9.5rem',
      cell: (row) => <span className="figures whitespace-nowrap text-secondary">{formatInstant(row.sentAt ?? row.createdAt)}</span>,
    },
    {
      id: 'to',
      header: 'Кому',
      rowHeader: true,
      cell: (row) => (
        <span className="flex min-w-0 flex-col">
          <span className="truncate">{row.accountName ?? row.to}</span>
          {row.accountName ? <span className="text-meta font-normal text-muted">{row.to}</span> : null}
        </span>
      ),
    },
    {
      id: 'subject',
      header: 'Лист',
      hideBelow: 'md',
      cell: (row) => (
        <span className="flex min-w-0 flex-col">
          <span className="line-clamp-2">{row.subject}</span>
          <span className="text-meta font-normal text-muted">{emailLogKind(row.kind)}</span>
        </span>
      ),
    },
    {
      id: 'status',
      header: 'Стан',
      cell: (row) => {
        const s = emailStatus(row.status);
        return (
          <span className="flex min-w-0 flex-col items-start gap-0.5">
            <Badge size="sm" tone={s.tone}>
              {s.label}
            </Badge>
            {row.error ? <span className="text-meta font-normal text-danger">{row.error}</span> : null}
            {row.nextAttemptAt && (row.status === 'QUEUED' || row.status === 'RETRYING') ? (
              <span className="text-meta font-normal text-muted figures">Піде не раніше {formatInstant(row.nextAttemptAt)}</span>
            ) : null}
          </span>
        );
      },
    },
    { id: 'attempts', header: 'Спроб', numeric: true, hideBelow: 'sm', cell: (row) => row.attempts },
  ];

  return (
    <div className="flex flex-col gap-3">
      <div role="group" aria-label="Стан листа" className="flex flex-wrap gap-1.5">
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
        caption="Журнал листів"
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
            title={status ? 'Таких листів немає' : 'Листів ще не було'}
            description={status ? 'Оберіть інший стан.' : 'Тут зʼявляться листи викладачам про заміни й диспетчерам про генерацію та відмови.'}
          />
        }
        pagination={data && data.total > PAGE_SIZE ? { page, size: PAGE_SIZE, total: data.total, onPageChange: setPage, noun: 'листів' } : undefined}
      />
    </div>
  );
}
