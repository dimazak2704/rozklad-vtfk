import { Search, Unlink, X } from 'lucide-react';
import { useCallback, useMemo, useState } from 'react';
import { useGroupDirectory, useTeacherDirectory } from '@/api/admin-daily';
import { useDeleteBinding } from '@/api/telegram';
import type { BindingType, ChatBindingDto } from '@/api/types';
import { Badge, Chip, DataTable, EmptyState, IconButton, Input, notify, Plate, RowActions, type DataTableColumn } from '@/components/ui';
import { formatNumericDate, todayInKyiv } from '@/lib/date';
import { fuzzyFilter } from '@/lib/search';
import { sortRows, type SortState } from '@/lib/sort';
import { confirm } from '@/stores/confirm';
import { BINDING_TYPE_LABEL } from './labels';

const PAGE_SIZE = 15;
const TYPES: readonly BindingType[] = ['GROUP_CHAT', 'TEACHER', 'ADMIN_CHAT', 'SUBSCRIBER'];
const TYPE_ORDER: Record<BindingType, number> = { ADMIN_CHAT: 0, GROUP_CHAT: 1, TEACHER: 2, SUBSCRIBER: 3 };

interface BindingsTableProps {
  bindings: readonly ChatBindingDto[] | undefined;
  loading: boolean;
  error: unknown;
  onRetry: () => void;
}

/** Привʼязані чати: фільтр за типом, пошук, відвʼязування з підтвердженням (назва чату в діалозі). */
export function BindingsTable({ bindings, loading, error, onRetry }: BindingsTableProps) {
  const groups = useGroupDirectory();
  const teachers = useTeacherDirectory();
  const remove = useDeleteBinding();
  const [type, setType] = useState<BindingType | null>(null);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);

  const [sort, setSort] = useState<SortState | null>(null);

  const groupCode = useMemo(() => new Map((groups.data ?? []).map((g) => [g.id, g.code])), [groups.data]);
  const teacherName = useMemo(() => new Map((teachers.data ?? []).map((t) => [t.id, t.shortName])), [teachers.data]);
  const target = useCallback(
    (row: ChatBindingDto): string | null =>
      row.groupId ? (groupCode.get(row.groupId) ?? null) : row.teacherId ? (teacherName.get(row.teacherId) ?? null) : null,
    [groupCode, teacherName],
  );

  // фільтр, пошук і сортування — до поділу на сторінки, щоб сортувалися всі чати, а не лише видимі
  const rows = useMemo(() => {
    const byType = (bindings ?? []).filter((b) => !type || b.type === type);
    const found = fuzzyFilter(byType, query, (b) => [b.title, target(b)]);
    // без пошуку й сортування: спершу диспетчерська, далі чати груп, потім викладачі — за абеткою
    if (!sort) {
      return query.trim() ? found : sortRows(sortRows(found, (row) => row.title, 'asc'), (row) => TYPE_ORDER[row.type], 'asc');
    }
    const value: Record<string, (row: ChatBindingDto) => string | null> = {
      title: (row) => row.title,
      type: (row) => BINDING_TYPE_LABEL[row.type],
      target,
      created: (row) => row.createdAt,
    };
    const get = value[sort.columnId];
    return get ? sortRows(found, get, sort.direction) : found;
  }, [bindings, type, query, target, sort]);
  const pageRows = rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

  const unlink = async (row: ChatBindingDto) => {
    const ok = await confirm({
      title: 'Відвʼязати чат від бота?',
      description: 'Чат перестане отримувати розпорядження й запити. Привʼязати знову можна новим кодом.',
      objectName: `${row.title} (${BINDING_TYPE_LABEL[row.type].toLocaleLowerCase('uk')})`,
      confirmLabel: 'Відвʼязати',
      cancelLabel: 'Залишити',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await remove.mutateAsync(row.id);
      notify.success(`Чат «${row.title}» відвʼязано`);
    } catch (cause) {
      notify.error(cause, 'Не вдалося відвʼязати чат');
    }
  };

  const columns: Array<DataTableColumn<ChatBindingDto>> = [
    {
      id: 'title',
      header: 'Чат',
      rowHeader: true,
      sortable: true,
      cell: (row) => (
        <span className="flex min-w-0 flex-col">
          {/* особистий чат викладача названий так само, як і він сам: не дублюємо імʼя в двох колонках */}
          <span className="truncate">{row.type === 'TEACHER' && row.title === target(row) ? 'Особистий чат' : row.title}</span>
          <span className="text-meta font-normal text-muted figures">{row.chatId}</span>
        </span>
      ),
    },
    {
      id: 'type',
      header: 'Тип',
      sortable: true,
      cell: (row) => <Badge size="sm">{BINDING_TYPE_LABEL[row.type]}</Badge>,
    },
    {
      id: 'target',
      header: 'Кому',
      hideBelow: 'sm',
      sortable: true,
      cell: (row) => {
        const value = target(row);
        if (!value) return <span className="text-muted">—</span>;
        return row.groupId ? <Plate size="sm">{value}</Plate> : value;
      },
    },
    {
      id: 'created',
      header: 'Привʼязано',
      hideBelow: 'md',
      sortable: true,
      cell: (row) => <span className="figures text-secondary">{formatNumericDate(todayInKyiv(new Date(row.createdAt)))}</span>,
    },
  ];

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <div role="group" aria-label="Тип чату" className="flex flex-wrap gap-1.5">
          <Chip selected={type === null} onToggle={() => { setType(null); setPage(0); }} count={bindings?.length ?? 0}>
            Усі
          </Chip>
          {TYPES.map((value) => {
            const count = bindings?.filter((b) => b.type === value).length ?? 0;
            if (count === 0 && value === 'SUBSCRIBER') return null;
            return (
              <Chip key={value} selected={type === value} onToggle={() => { setType(type === value ? null : value); setPage(0); }} count={count}>
                {BINDING_TYPE_LABEL[value]}
              </Chip>
            );
          })}
        </div>
        <Input
          aria-label="Пошук чату"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setPage(0);
          }}
          iconStart={<Search className="size-4" />}
          placeholder="Група чи прізвище"
          wrapperClassName="ms-auto w-full sm:w-64"
          endSlot={
            query ? <IconButton label="Очистити пошук" size="sm" icon={<X aria-hidden="true" className="size-4" />} onClick={() => setQuery('')} /> : null
          }
        />
      </div>
      <DataTable
        caption="Привʼязані чати Telegram"
        columns={columns}
        rows={bindings ? pageRows : undefined}
        getRowId={(row) => row.id}
        loading={loading}
        error={error ?? undefined}
        onRetry={onRetry}
        density="compact"
        sort={sort}
        onSortChange={(next) => {
          setSort(next);
          setPage(0);
        }}
        manualSorting
        rowActions={(row) => (
          <RowActions
            rowLabel={row.title}
            actions={[{ label: 'Відвʼязати від бота', icon: <Unlink aria-hidden="true" />, onSelect: () => void unlink(row), tone: 'danger' }]}
          />
        )}
        empty={
          <EmptyState
            size="sm"
            title={query || type ? 'Нічого не знайдено' : 'Жоден чат ще не привʼязано'}
            description={query || type ? 'Змініть фільтр чи пошук.' : 'Створіть код привʼязки й надішліть його куратору групи чи викладачу.'}
          />
        }
        pagination={rows.length > PAGE_SIZE ? { page, size: PAGE_SIZE, total: rows.length, onPageChange: setPage, noun: 'чатів' } : undefined}
      />
    </div>
  );
}
