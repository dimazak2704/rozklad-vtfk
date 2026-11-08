import { ArrowRight } from 'lucide-react';
import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useAccountingRows, type AccountingRow } from '@/api/accounting';
import { activeSemesterOf, useSemesters } from '@/api/calendar';
import { useCatalogOptions } from '@/api/catalog';
import { HoursBar } from '@/components/accounting/HoursBar';
import { changesLink, matchesFilter, needsAttention, STATUS_ORDER, STATUS_TONE, statusLabel, type StatusFilter } from '@/components/accounting/model';
import { TeacherStats } from '@/components/accounting/TeacherStats';
import {
  Badge,
  Chip,
  Combobox,
  DataTable,
  EmptyState,
  ErrorState,
  PageHeader,
  Plate,
  Select,
  Skeleton,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  buttonClass,
  type DataTableColumn,
} from '@/components/ui';
import { formatNumericDate, todayInKyiv } from '@/lib/date';
import { disciplineLine } from '@/lib/discipline-color';

const FILTERS: ReadonlyArray<{ value: StatusFilter; label: string }> = [
  { value: 'attention', label: 'Потребують уваги' },
  { value: 'WILL_OVERRUN', label: 'Перевищать' },
  { value: 'WILL_UNDERRUN', label: 'Не встигнуть' },
  { value: 'FINISHED', label: 'Вичитано' },
  { value: 'ON_TRACK', label: 'За планом' },
  { value: 'all', label: 'Усі' },
];
const FILTER_VALUES = new Set(FILTERS.map((f) => f.value));

/** Облік годин: план, факт і прогноз за групами з «вичитано» й діями; статистика замін викладачів. */
export default function AccountingPage() {
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'teachers' ? 'teachers' : 'groups';
  const currentMonth = todayInKyiv().slice(0, 7);
  const month = /^\d{4}-\d{2}$/.test(params.get('month') ?? '') ? (params.get('month') as string) : currentMonth;

  const set = (patch: Record<string, string | null>) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        for (const [key, value] of Object.entries(patch)) {
          if (value) next.set(key, value);
          else next.delete(key);
        }
        return next;
      },
      { replace: true },
    );

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Облік годин"
        description="Скільки годин уже вичитано, скільки ще заплановано і коли дисципліну буде вичитано. Пропозиції ведуть прямо в заміни."
      />
      <Tabs value={tab} onValueChange={(value) => set({ tab: value === 'groups' ? null : value })}>
        <TabsList aria-label="Розділи обліку">
          <TabsTrigger value="groups">За групами</TabsTrigger>
          <TabsTrigger value="teachers">Викладачі</TabsTrigger>
        </TabsList>
        <TabsContent value="groups">
          <GroupsAccounting params={params} set={set} />
        </TabsContent>
        <TabsContent value="teachers">
          <TeacherStats month={month} currentMonth={currentMonth} onMonthChange={(value) => set({ month: value === currentMonth ? null : value })} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function GroupsAccounting({ params, set }: { params: URLSearchParams; set: (patch: Record<string, string | null>) => void }) {
  const semesters = useSemesters();
  const semester = semesters.data?.find((s) => s.id === params.get('semester')) ?? activeSemesterOf(semesters.data);
  const groupId = params.get('group');
  const rawFilter = params.get('status') as StatusFilter | null;
  const filter: StatusFilter = rawFilter && FILTER_VALUES.has(rawFilter) ? rawFilter : groupId ? 'all' : 'attention';
  const rows = useAccountingRows(semester?.id, groupId);
  const groups = useCatalogOptions('groups');

  const counts = useMemo(() => {
    const out = new Map<StatusFilter, number>();
    for (const f of FILTERS) out.set(f.value, (rows.data ?? []).filter((row) => matchesFilter(row, f.value)).length);
    return out;
  }, [rows.data]);

  const visible = useMemo(
    () =>
      (rows.data ?? [])
        .filter((row) => matchesFilter(row, filter))
        .sort(
          (a, b) =>
            (groupId ? 0 : a.group.code.localeCompare(b.group.code, 'uk', { numeric: true })) ||
            STATUS_ORDER[a.status] - STATUS_ORDER[b.status] ||
            a.discipline.shortName.localeCompare(b.discipline.shortName, 'uk'),
        ),
    [rows.data, filter, groupId],
  );

  const columns = useMemo<Array<DataTableColumn<AccountingRow>>>(
    () => [
      ...(groupId
        ? []
        : [
            {
              id: 'group',
              header: 'Група',
              width: '6rem',
              cell: (row: AccountingRow) => <Plate size="sm">{row.group.code}</Plate>,
            } satisfies DataTableColumn<AccountingRow>,
          ]),
      {
        id: 'discipline',
        header: 'Дисципліна',
        rowHeader: true,
        width: '14rem',
        cell: (row) => (
          <span className="flex min-w-0 flex-col">
            <span className="flex items-center gap-2">
              <span data-line={disciplineLine(row.discipline)} aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
              <span className="font-condensed text-body font-bold">{row.discipline.shortName}</span>
            </span>
            {row.discipline.name !== row.discipline.shortName ? (
              <span className="line-clamp-1 text-caption font-normal text-muted" title={row.discipline.name}>
                {row.discipline.name}
              </span>
            ) : null}
          </span>
        ),
      },
      { id: 'bar', header: 'Години', cell: (row) => <HoursBar row={row} /> },
      {
        id: 'numbers',
        header: <span title="вичитано / план / прогноз">Факт / план / прогноз</span>,
        hideBelow: 'lg',
        width: '10rem',
        className: 'figures whitespace-nowrap',
        cell: (row) => (
          <span className="text-ui">
            <span className="font-semibold">{row.fact}</span>
            <span className="text-muted"> / {row.plan} / </span>
            <span className={row.status === 'WILL_OVERRUN' ? 'font-semibold text-change' : row.status === 'WILL_UNDERRUN' ? 'font-semibold text-cancel' : undefined}>
              {row.forecast}
            </span>
          </span>
        ),
      },
      {
        id: 'status',
        header: 'Стан',
        width: '17rem',
        cell: (row) => <StatusCell row={row} />,
      },
    ],
    [groupId],
  );

  if (semesters.isPending) return <Skeleton className="h-64 w-full" />;
  if (semesters.isError) return <ErrorState error={semesters.error} onRetry={() => void semesters.refetch()} />;
  if (!semester) {
    return <EmptyState title="Семестрів ще немає" description="Облік рахується в межах семестру — створіть його в календарі." />;
  }

  const group = groups.items?.find((g) => g.id === groupId);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-caption font-semibold text-secondary">
          Семестр
          <Select<string>
            size="sm"
            className="w-60"
            value={semester.id}
            onValueChange={(value) => set({ semester: value === activeSemesterOf(semesters.data)?.id ? null : value })}
            options={semesters.data.map((s) => ({ value: s.id, label: s.name, description: s.active ? 'Активний' : undefined }))}
          />
        </label>
        <label className="flex flex-col gap-1 text-caption font-semibold text-secondary">
          Група
          <Combobox
            size="sm"
            className="w-44"
            value={groupId}
            onValueChange={(value) => set({ group: value, status: null })}
            options={(groups.items ?? []).map((g) => ({ value: g.id, label: g.code, description: `${g.course} курс` }))}
            placeholder="Усі групи"
            searchPlaceholder="Код групи"
            loading={groups.isPending}
            clearable
          />
        </label>
      </div>
      <div role="group" aria-label="Фільтр за станом" className="flex flex-wrap gap-2">
        {FILTERS.map((f) => (
          <Chip key={f.value} selected={filter === f.value} onToggle={() => set({ status: f.value })} count={rows.data ? counts.get(f.value) : undefined}>
            {f.label}
          </Chip>
        ))}
      </div>
      <DataTable
        caption={group ? `Облік годин групи ${group.code}` : `Облік годин: ${semester.name}`}
        columns={columns}
        rows={rows.data ? visible : undefined}
        getRowId={(row) => `${row.group.id}-${row.discipline.id}`}
        loading={rows.isPending}
        error={rows.error}
        onRetry={() => void rows.refetch()}
        rowClassName={(row) => (needsAttention(row) ? undefined : 'text-secondary')}
        empty={
          rows.data && rows.data.length > 0 ? (
            <EmptyState
              size="sm"
              title={filter === 'attention' ? 'Усе за планом' : 'Таких дисциплін немає'}
              description={
                filter === 'attention'
                  ? 'Жодна дисципліна не перевищує план і не відстає. Перевірте пізніше: прогноз оновлюється після кожного розпорядження.'
                  : 'Оберіть інший стан або групу.'
              }
              action={
                <button type="button" className={buttonClass({ variant: 'secondary', size: 'sm' })} onClick={() => set({ status: 'all' })}>
                  Показати всі
                </button>
              }
            />
          ) : (
            <EmptyState
              size="sm"
              title="Плану годин ще немає"
              description="Облік рахується від навантаження: внесіть його або виведіть з розкладу."
              action={
                <Link to="/admin/load" className={buttonClass({ variant: 'primary', size: 'sm' })}>
                  До навантаження
                </Link>
              }
            />
          )
        }
      />
    </div>
  );
}

function StatusCell({ row }: { row: AccountingRow }) {
  const link = changesLink(row);
  return (
    <span className="flex flex-col items-start gap-1.5">
      <span className="flex flex-wrap items-center gap-2">
        <Badge tone={STATUS_TONE[row.status]} size="sm">
          {statusLabel(row)}
        </Badge>
        {row.forecastFinishDate && row.status !== 'WILL_UNDERRUN' ? (
          <span className="text-caption text-muted figures">{row.status === 'FINISHED' ? 'вичитано' : 'вичитає'} {formatNumericDate(row.forecastFinishDate)}</span>
        ) : null}
      </span>
      {row.suggestion ? (
        <span className="flex flex-col gap-1">
          <span className="text-caption text-secondary">{row.suggestion.text}</span>
          {link ? (
            <Link
              to={link}
              className="inline-flex items-center gap-1 rounded-plate text-caption font-semibold text-accent underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
            >
              {row.suggestion.kind === 'DISCIPLINE_FINISHED' ? 'Оформити «Вичит.» у замінах' : 'Додати пари в замінах'}
              <ArrowRight aria-hidden="true" className="size-3.5" />
            </Link>
          ) : null}
        </span>
      ) : null}
    </span>
  );
}
