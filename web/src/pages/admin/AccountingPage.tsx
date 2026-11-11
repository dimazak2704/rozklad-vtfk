import { ArrowRight } from 'lucide-react';
import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useAccountingRows, type AccountingRow } from '@/api/accounting';
import { activeSemesterOf, useSemesters } from '@/api/calendar';
import { useCatalogOptions } from '@/api/catalog';
import { HoursBar } from '@/components/accounting/HoursBar';
import {
  accountingSummary,
  changesLink,
  matchesFilter,
  needsAttention,
  STATUS_ORDER,
  STATUS_TEXT,
  statusLabel,
  suggestionAction,
  type StatusFilter,
} from '@/components/accounting/model';
import { TeacherStats } from '@/components/accounting/TeacherStats';
import {
  Combobox,
  DataTable,
  EmptyState,
  PageErrorBanner,
  PageHeader,
  Panel,
  Select,
  Skeleton,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  buttonClass,
  type DataTableColumn,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatNumericDate, todayInKyiv } from '@/lib/date';
import { disciplineLine } from '@/lib/discipline-color';

const FILTERS: ReadonlyArray<{ value: StatusFilter; label: string }> = [
  { value: 'attention', label: 'Потребують уваги' },
  { value: 'WILL_OVERRUN', label: 'Перевищать план' },
  { value: 'WILL_UNDERRUN', label: 'Не встигнуть' },
  { value: 'FINISHED', label: 'Вичитано' },
  { value: 'ON_TRACK', label: 'За планом' },
  { value: 'all', label: 'Усі дисципліни' },
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
    <div className="flex flex-col gap-6">
      <PageHeader title="Облік годин" />
      <Tabs value={tab} onValueChange={(value) => set({ tab: value === 'groups' ? null : value })}>
        <TabsList aria-label="Розділи обліку">
          <TabsTrigger value="groups">За групами</TabsTrigger>
          <TabsTrigger value="teachers">Заміни викладачів</TabsTrigger>
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
            (groupId
              ? 0
              : a.group.code.localeCompare(b.group.code, 'uk', {
                  numeric: true,
                })) ||
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
              width: '5.5rem',
              cell: (row: AccountingRow) => <span className="figures font-semibold">{row.group.code}</span>,
            } satisfies DataTableColumn<AccountingRow>,
          ]),
      {
        id: 'discipline',
        header: 'Дисципліна',
        rowHeader: true,
        width: '15rem',
        cell: (row) => (
          <span className="flex min-w-0 flex-col">
            <span className="flex items-center gap-2">
              <span data-line={disciplineLine(row.discipline)} aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
              <span className="text-cell font-[650]">{row.discipline.shortName}</span>
            </span>
            {row.discipline.name !== row.discipline.shortName ? (
              <span className="line-clamp-1 ps-4 text-caption font-normal text-muted" title={row.discipline.name}>
                {row.discipline.name}
              </span>
            ) : null}
          </span>
        ),
      },
      {
        id: 'fact',
        header: 'Вичитано',
        width: '7rem',
        className: 'whitespace-nowrap',
        cell: (row) => (
          <span className="text-ui text-secondary">
            <span className="figures font-semibold text-primary">{row.fact}</span> з <span className="figures">{row.plan}</span> год
          </span>
        ),
      },
      {
        id: 'bar',
        header: 'Хід',
        hideBelow: 'md',
        // у стосі на телефоні смуга без місця — числа «30 з 54 год» уже сказали те саме
        className: 'max-md:hidden!',
        cell: (row) => <HoursBar row={row} />,
      },
      {
        id: 'status',
        header: 'Стан',
        width: '19rem',
        cell: (row) => <StatusCell row={row} />,
      },
    ],
    [groupId],
  );

  if (semesters.isPending) return <Skeleton className="h-64 w-full" />;
  if (semesters.isError) {
    return (
      <PageErrorBanner
        error={semesters.error}
        onRetry={() => void semesters.refetch()}
        retrying={semesters.isFetching}
        title="Семестри не завантажено"
      />
    );
  }
  if (!semester) {
    return (
      <EmptyState
        title="Семестрів ще немає."
        description="Облік рахується в межах семестру."
        action={
          <Link to="/admin/calendar" className={buttonClass({ variant: 'ghost', size: 'sm' })}>
            Створити семестр у календарі
          </Link>
        }
      />
    );
  }

  const group = groups.items?.find((g) => g.id === groupId);
  // показуємо лише стани, що є в даних (і поточний) — без «Перевищать 0»
  const filterOptions = FILTERS.filter((f) => f.value === filter || f.value === 'all' || (counts.get(f.value) ?? 0) > 0).map((f) => ({
    value: f.value,
    label: rows.data ? `${f.label} · ${counts.get(f.value) ?? 0}` : f.label,
  }));

  return (
    <div className="flex flex-col gap-4">
      {rows.data ? <p className="text-body text-secondary">{accountingSummary(rows.data)}</p> : null}
      <div className="flex flex-wrap items-end gap-3">
        {semesters.data.length > 1 ? (
          <label className="flex w-full flex-col gap-1 text-caption font-[650] text-primary sm:w-auto">
            Семестр
            <Select<string>
              className="w-full sm:w-60"
              value={semester.id}
              onValueChange={(value) =>
                set({
                  semester: value === activeSemesterOf(semesters.data)?.id ? null : value,
                })
              }
              options={semesters.data.map((s) => ({
                value: s.id,
                label: s.name,
                description: s.active ? 'Активний' : undefined,
              }))}
            />
          </label>
        ) : null}
        <label className="flex w-full flex-col gap-1 text-caption font-[650] text-primary sm:w-auto">
          Група
          <Combobox
            className="w-full sm:w-44"
            value={groupId}
            onValueChange={(value) => set({ group: value, status: null })}
            options={(groups.items ?? []).map((g) => ({
              value: g.id,
              label: g.code,
              description: `${g.course} курс`,
            }))}
            placeholder="Усі групи"
            searchPlaceholder="Код групи"
            loading={groups.isPending}
            clearable
          />
        </label>
        <label className="flex w-full flex-col gap-1 text-caption font-[650] text-primary sm:w-auto">
          Показати
          <Select<StatusFilter> className="w-full sm:w-64" value={filter} onValueChange={(value) => set({ status: value })} options={filterOptions} />
        </label>
      </div>
      <Panel flush>
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
                title={filter === 'attention' ? 'Усе за планом.' : 'Таких дисциплін немає.'}
                description={filter === 'attention' ? 'Прогноз оновлюється після кожного розпорядження.' : undefined}
                action={
                  <button type="button" className={buttonClass({ variant: 'ghost', size: 'sm' })} onClick={() => set({ status: 'all' })}>
                    Показати всі дисципліни
                  </button>
                }
              />
            ) : (
              <EmptyState
                size="sm"
                title="Плану годин ще немає."
                description="Облік рахується від навантаження."
                action={
                  <Link to="/admin/load" className={buttonClass({ variant: 'ghost', size: 'sm' })}>
                    Внести навантаження
                  </Link>
                }
              />
            )
          }
        />
      </Panel>
    </div>
  );
}

/** Стан словом і одна дія з пропозиції — без повтору того самого в бейджі, реченні й посиланні. */
function StatusCell({ row }: { row: AccountingRow }) {
  const link = changesLink(row);
  const finish = row.forecastFinishDate && row.status !== 'WILL_UNDERRUN' ? formatNumericDate(row.forecastFinishDate) : null;
  return (
    <span className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
      {/* колір — лише в крапці: 93 червоні рядки поспіль кричали б, а не інформували */}
      <span className="inline-flex items-center gap-2 text-ui font-[550] whitespace-nowrap text-primary">
        <span aria-hidden="true" className={cn('size-2 rounded-full bg-current', STATUS_TEXT[row.status])} />
        {statusLabel(row)}
        {finish && row.status === 'ON_TRACK' ? <span className="font-normal text-muted figures">до {finish}</span> : null}
      </span>
      {row.suggestion && link ? (
        <Link
          to={link}
          aria-label={`${suggestionAction(row.suggestion)}: ${row.group.code}, ${row.discipline.shortName} — у замінах`}
          className="inline-flex min-h-6 items-center gap-1 rounded-tag text-caption font-[650] text-accent underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:min-h-11"
        >
          {suggestionAction(row.suggestion)}
          <ArrowRight aria-hidden="true" className="size-3.5 rtl:rotate-180" />
        </Link>
      ) : null}
    </span>
  );
}
