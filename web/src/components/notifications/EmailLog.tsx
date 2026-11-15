import { useState } from 'react';
import { useEmailDeliveries } from '@/api/email';
import type { EmailDeliveryDto, EmailDeliveryStatus, EmailKind } from '@/api/types';
import { Badge, Chip, DataTable, EmptyState, Select, type DataTableColumn, type SelectOption } from '@/components/ui';
import { formatInstant } from '@/lib/date';
import { EMAIL_KIND_TEXT, emailLogKind, emailStatus } from './labels';

const PAGE_SIZE = 15;
const FILTERS: ReadonlyArray<{ value: EmailDeliveryStatus | null; label: string }> = [
  { value: null, label: 'Усі' },
  { value: 'SENT', label: 'Надіслано' },
  { value: 'QUEUED', label: 'У черзі' },
  { value: 'RETRYING', label: 'Повтор' },
  { value: 'FAILED', label: 'Не надіслано' },
];

type KindFilter = EmailKind | 'TEST' | 'ALL';
const KIND_FILTERS: ReadonlyArray<SelectOption<KindFilter>> = [
  { value: 'ALL', label: 'Усі види' },
  ...(Object.keys(EMAIL_KIND_TEXT) as EmailKind[]).map((kind) => ({ value: kind, label: EMAIL_KIND_TEXT[kind].label })),
  { value: 'TEST', label: emailLogKind('TEST') },
];

/** Журнал листів: кому (адреса під маскою), про що, коли і чи дійшло до поштового сервера. */
export function EmailLog() {
  const [status, setStatus] = useState<EmailDeliveryStatus | null>(null);
  const [kind, setKind] = useState<KindFilter>('ALL');
  const [page, setPage] = useState(0);
  const deliveries = useEmailDeliveries({
    status: status ?? undefined,
    kind: kind === 'ALL' ? undefined : kind,
    page,
    size: PAGE_SIZE,
  });
  const filtered = status !== null || kind !== 'ALL';
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
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
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
        <Select<KindFilter>
          aria-label="Вид листа"
          size="sm"
          value={kind}
          onValueChange={(next) => {
            setKind(next);
            setPage(0);
          }}
          options={KIND_FILTERS}
          className="w-full sm:w-64"
        />
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
            title={filtered ? 'Таких листів немає' : 'Листів ще не було'}
            description={
              filtered ? 'Оберіть інший стан або вид листа.' : 'Тут зʼявляться листи викладачам про заміни й диспетчерам про генерацію та відмови.'
            }
          />
        }
        pagination={data && data.total > PAGE_SIZE ? { page, size: PAGE_SIZE, total: data.total, onPageChange: setPage, noun: 'листів' } : undefined}
      />
    </div>
  );
}
