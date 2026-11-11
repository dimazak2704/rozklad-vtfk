import { GitMerge, ListFilter, Pencil, Plus, Search, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useSemesters, activeSemesterOf, useShiftWindows } from '@/api/calendar';
import { useCatalogOptions } from '@/api/catalog';
import { useDeleteLoadItem, useLoadItems, type LoadItem } from '@/api/load';
import type { LoadSource } from '@/api/types';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { useTableUrlState } from '@/components/catalog/useTableUrlState';
import { DeriveDialog } from '@/components/load/DeriveDialog';
import { CONTROL_FORM_LABEL, LOAD_SOURCE_HINT, LOAD_SOURCE_LABEL } from '@/components/load/labels';
import { LoadFormDialog } from '@/components/load/LoadForm';
import { formatHours, pairsPerWeek } from '@/components/load/pattern';
import { WeeklyPatternView } from '@/components/load/WeeklyPatternView';
import { Link } from 'react-router';
import {
  Button,
  buttonClass,
  Combobox,
  DataTable,
  EmptyState,
  Input,
  PageErrorBanner,
  PageHeader,
  Panel,
  Popover,
  PopoverContent,
  PopoverTrigger,
  RowActions,
  Select,
  Skeleton,
  Tooltip,
  type DataTableColumn,
} from '@/components/ui';
import { disciplineLine } from '@/lib/discipline-color';
import { countLabel, LESSON_KIND_LABEL } from '@/lib/labels';
import { matchScore } from '@/lib/search';
import { sortRows } from '@/lib/sort';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useRegisterCommands } from '@/stores/commands';

const PAGE_SIZE = 50;
const ALL = 'all';
const SOURCES: readonly LoadSource[] = ['TIMETABLE_DERIVED', 'MANUAL', 'CURRICULUM', 'PROGRAM'];

/** Навантаження: години на тиждень за групою × дисципліною, тижневий шаблон «1 + ½», викладачі, джерело годин. */
export default function LoadPage() {
  const table = useTableUrlState(null, ['group', 'source']);
  const semesters = useSemesters();
  const semester = semesters.data?.find((s) => s.id === table.get('semester')) ?? activeSemesterOf(semesters.data);
  const groupId = table.get('group') ?? null;
  const source = table.get('source') as LoadSource | undefined;
  const items = useLoadItems(semester?.id, groupId);
  const groups = useCatalogOptions('groups');
  const shiftWindows = useShiftWindows();
  const remove = useDeleteLoadItem();
  const [editing, setEditing] = useState<LoadItem | 'new' | null>(null);
  const [deleting, setDeleting] = useState<LoadItem | null>(null);
  const [deriving, setDeriving] = useState(false);

  const openNew = () => setEditing('new');
  useHotkeys([{ keys: 'n', handler: openNew, description: 'Нове навантаження', group: 'Навантаження' }]);
  useRegisterCommands([
    { id: 'new-load', label: 'Додати навантаження', group: 'Навантаження', icon: Plus, shortcut: 'n', perform: openNew },
    { id: 'derive-load', label: 'Вивести навантаження з розкладу', group: 'Навантаження', icon: GitMerge, perform: () => setDeriving(true) },
  ]);

  const group = groups.items?.find((g) => g.id === groupId);
  const filtered = useMemo(() => {
    const query = table.q.trim();
    return (items.data ?? []).filter(
      (item) =>
        (!source || item.source === source) &&
        (!query || matchScore(query, item.discipline.shortName, item.discipline.name, ...item.teachers.map((t) => t.teacher.shortName)) > 0),
    );
  }, [items.data, table.q, source]);

  const columns = useMemo<Array<DataTableColumn<LoadItem>>>(
    () => [
      ...(groupId
        ? []
        : [
            {
              id: 'group',
              header: 'Група',
              width: '6rem',
              sortValue: (item: LoadItem) => item.group.code,
              cell: (item: LoadItem) => <span className="figures font-semibold">{item.group.code}</span>,
            } satisfies DataTableColumn<LoadItem>,
          ]),
      {
        id: 'discipline',
        header: 'Дисципліна',
        rowHeader: true,
        width: '34%',
        sortValue: (item) => item.discipline.shortName,
        cell: (item) => (
          <span className="flex min-w-0 flex-col">
            <span className="flex items-center gap-2">
              <span data-line={disciplineLine(item.discipline)} aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
              <span className="text-cell font-[650]">{item.discipline.shortName}</span>
            </span>
            {/* повна назва й особливості одним тихим рядком, а не трьома «табличками» */}
            {disciplineNote(item) ? (
              <span className="line-clamp-1 ps-4 text-caption font-normal text-muted" title={disciplineNote(item)}>
                {disciplineNote(item)}
              </span>
            ) : null}
          </span>
        ),
      },
      {
        id: 'hours',
        header: 'Год/тиж',
        numeric: true,
        width: '6rem',
        sortValue: (item) => item.hoursPerWeek,
        cell: (item) => formatHours(item.hoursPerWeek),
      },
      {
        id: 'pattern',
        header: 'Тиждень',
        width: '9.5rem',
        sortValue: (item) => pairsPerWeek(item.pattern),
        cell: (item) => <WeeklyPatternView pattern={item.pattern} line={disciplineLine(item.discipline)} />,
      },
      {
        id: 'planned',
        header: 'За семестр',
        numeric: true,
        width: '7rem',
        hideBelow: 'md',
        className: 'max-md:hidden!',
        sortValue: (item) => item.plannedHours,
        cell: (item) => `${item.plannedHours} год`,
      },
      {
        id: 'teachers',
        header: 'Викладачі',
        hideBelow: 'md',
        sortValue: (item) => item.teachers[0]?.teacher.shortName,
        cell: (item) =>
          item.teachers.length === 0 ? (
            <span className="text-caption text-change">Не призначено</span>
          ) : (
            <span className="flex flex-col">
              {item.teachers.map((t) => (
                <span key={t.subgroup} className="whitespace-nowrap">
                  {t.subgroup > 0 ? <span className="text-caption text-muted">{t.subgroup} п. · </span> : null}
                  {t.teacher.shortName}
                </span>
              ))}
            </span>
          ),
      },
      {
        id: 'source',
        header: 'Джерело',
        // на планшеті джерело видно у фільтрі «Джерело», а ширина потрібна викладачам
        hideBelow: 'lg',
        className: 'max-md:hidden!',
        sortValue: (item) => LOAD_SOURCE_LABEL[item.source],
        cell: (item) => (
          <Tooltip content={item.note ? `${LOAD_SOURCE_HINT[item.source]} ${item.note}` : LOAD_SOURCE_HINT[item.source]}>
            <span tabIndex={0} className="inline-flex rounded-tag text-secondary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus">
              {LOAD_SOURCE_LABEL[item.source]}
            </span>
          </Tooltip>
        ),
      },
    ],
    [groupId],
  );

  // за замовчуванням: усі групи — за групою, одна група — за дисципліною
  const sort = table.sort ?? { columnId: groupId ? 'discipline' : 'group', direction: 'asc' as const };
  // сортуємо всі знайдені записи, а не лише сторінку, і лише потім ділимо на сторінки
  const sorted = useMemo(() => {
    const column = columns.find((c) => c.id === sort.columnId);
    return column?.sortValue ? sortRows(filtered, column.sortValue, sort.direction) : filtered;
  }, [columns, filtered, sort.columnId, sort.direction]);
  const pageRows = groupId ? sorted : sorted.slice(table.page * PAGE_SIZE, (table.page + 1) * PAGE_SIZE);

  if (semesters.isPending) {
    return (
      <div className="flex flex-col gap-5">
        <PageHeader title="Навантаження" />
        <Skeleton className="h-9 w-80" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }
  if (semesters.isError) {
    return (
      <div className="flex flex-col gap-5">
        <PageHeader title="Навантаження" />
        <PageErrorBanner error={semesters.error} title="Семестри не завантажено" retrying={semesters.isFetching} onRetry={() => void semesters.refetch()} />
      </div>
    );
  }
  if (!semester) {
    return (
      <div className="flex flex-col gap-5">
        <PageHeader title="Навантаження" />
        <EmptyState
          title="Семестрів ще немає."
          description="Навантаження рахується в межах семестру."
          action={
            <Link to="/admin/calendar" className={buttonClass({ variant: 'ghost', size: 'sm' })}>
              Створити семестр у календарі
            </Link>
          }
        />
      </div>
    );
  }

  const summary = groupId && items.data ? groupSummary(items.data) : null;
  const extraFilters = (source ? 1 : 0) + (semester.id !== activeSemesterOf(semesters.data)?.id ? 1 : 0);
  const capacity = group ? weeklyCapacity(shiftWindows.data ?? [], group.shift) : null;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Навантаження"
        primaryAction={
          <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={openNew}>
            Додати навантаження
          </Button>
        }
        more={[{ label: 'Вивести з розкладу', icon: <GitMerge aria-hidden="true" className="size-4" />, onSelect: () => setDeriving(true) }]}
      />

      <div className="flex flex-wrap items-end gap-3">
        <label className={FILTER_LABEL}>
          Група
          <Combobox
            className="w-full sm:w-44"
            value={groupId}
            onValueChange={(value) => table.set({ group: value })}
            options={(groups.items ?? []).map((g) => ({ value: g.id, label: g.code, description: `${g.course} курс` }))}
            placeholder="Усі групи"
            searchPlaceholder="Код: 4КІ1 або 4ki1"
            loading={groups.isPending}
            clearable
          />
        </label>
        <Input
          type="search"
          value={table.q}
          onChange={(event) => table.set({ q: event.target.value })}
          placeholder="Дисципліна або викладач"
          iconStart={<Search aria-hidden="true" className="size-4" />}
          aria-label="Пошук у навантаженні"
          wrapperClassName="w-full sm:w-64"
        />
        {/* семестр і джерело змінюють рідко — вони за кнопкою «Фільтр», а не чотирма полями в ряд */}
        <Popover>
          <PopoverTrigger asChild>
            <Button iconStart={<ListFilter aria-hidden="true" className="size-4" />}>
              Фільтр
              {extraFilters > 0 ? (
                <span aria-hidden="true" className="ms-0.5 inline-flex size-5 items-center justify-center rounded-full bg-accent text-meta text-on-accent figures">
                  {extraFilters}
                </span>
              ) : null}
            </Button>
          </PopoverTrigger>
          <PopoverContent title="Що показати" width="md">
            <div className="flex flex-col gap-4">
              <label className={FILTER_LABEL}>
                Семестр
                <Select<string>
                  className="w-full"
                  value={semester.id}
                  onValueChange={(value) => table.set({ semester: value === activeSemesterOf(semesters.data)?.id ? null : value })}
                  options={semesters.data.map((s) => ({ value: s.id, label: s.name, description: s.active ? 'Активний' : undefined }))}
                />
              </label>
              <label className={FILTER_LABEL}>
                Джерело годин
                <Select<string>
                  className="w-full"
                  value={source ?? ALL}
                  onValueChange={(value) => table.set({ source: value === ALL ? null : value })}
                  options={[{ value: ALL, label: 'Будь-яке' }, ...SOURCES.map((s) => ({ value: s, label: LOAD_SOURCE_LABEL[s] }))]}
                />
              </label>
            </div>
          </PopoverContent>
        </Popover>
      </div>
      {semester.id !== activeSemesterOf(semesters.data)?.id ? <p className="-mt-2 text-caption text-secondary">Показано {semester.name}.</p> : null}

      {summary && group ? (
        <section aria-label={`Тиждень групи ${group.code}`} className="flex max-w-3xl flex-col gap-2">
          <p className="text-body text-secondary">
            <span className="font-[650] text-primary">{group.code}</span>, {group.shift} зміна: {formatHours(summary.hours)} год на тиждень, у середньому{' '}
            {formatHours(summary.pairs)} {capacity ? `пар із ${capacity} місць у сітці` : 'пар'}; за семестр {summary.planned} год.
            {summary.unassigned > 0 ? (
              <span className="text-change"> {countLabel(summary.unassigned, ['дисципліна', 'дисципліни', 'дисциплін'])} без викладача.</span>
            ) : null}
          </p>
          {capacity ? (
            <span
              role="meter"
              aria-label="Заповнення тижня групи"
              aria-valuemin={0}
              aria-valuemax={capacity}
              aria-valuenow={summary.pairs}
              className="relative h-1.5 w-full max-w-sm overflow-hidden rounded-full bg-[var(--fill-track-future)]"
            >
              <span
                className={`absolute inset-y-0 start-0 rounded-full ${summary.pairs > capacity ? 'bg-[var(--fill-cancel)]' : 'bg-accent'}`}
                style={{ width: `${Math.min(100, (summary.pairs / capacity) * 100)}%` }}
              />
            </span>
          ) : null}
        </section>
      ) : null}

      <Panel flush>
      <DataTable
        caption={group ? `Навантаження групи ${group.code}` : `Навантаження: ${semester.name}`}
        columns={columns}
        rows={items.data ? pageRows : undefined}
        getRowId={(item) => item.id}
        loading={items.isPending}
        error={items.error}
        onRetry={() => void items.refetch()}
        manualSorting
        sort={sort}
        onSortChange={table.setSort}
        onRowClick={(item) => setEditing(item)}
        rowActions={(item) => (
          <RowActions
            rowLabel={`${item.group.code} · ${item.discipline.shortName}`}
            actions={[
              { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, onSelect: () => setEditing(item) },
              { label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger', separatorBefore: true, onSelect: () => setDeleting(item) },
            ]}
          />
        )}
        pagination={
          !groupId && filtered.length > PAGE_SIZE
            ? { page: table.page, size: PAGE_SIZE, total: filtered.length, onPageChange: table.setPage, noun: 'записів' }
            : undefined
        }
        empty={
          table.filtered ? (
            <EmptyState
              size="sm"
              title={table.q ? `«${table.q}» не знайдено.` : 'За цими умовами нічого немає.'}
              action={
                <Button size="sm" variant="ghost" onClick={table.reset}>
                  Показати все навантаження
                </Button>
              }
            />
          ) : (
            <EmptyState
              size="sm"
              title={group ? `У ${group.code} ще немає навантаження.` : 'Навантаження ще немає.'}
              description="Найшвидше — вивести його з опублікованого розкладу."
              action={
                <Button size="sm" variant="secondary" iconStart={<GitMerge aria-hidden="true" className="size-4" />} onClick={() => setDeriving(true)}>
                  Вивести з розкладу
                </Button>
              }
            />
          )
        }
      />
      </Panel>
      {items.data && !groupId && filtered.length > 0 ? (
        <p className="text-caption text-muted">Оберіть групу, щоб побачити, як заповнено її тиждень.</p>
      ) : null}

      {editing ? (
        <LoadFormDialog
          key={editing === 'new' ? 'new' : editing.id}
          semester={semester}
          item={editing === 'new' ? null : editing}
          defaultGroupId={groupId}
          open
          onOpenChange={(open) => {
            if (!open) setEditing(null);
          }}
        />
      ) : null}
      {deriving ? <DeriveDialog semester={semester} open onOpenChange={setDeriving} /> : null}
      <DeleteDialog
        target={deleting ? { id: deleting.id, name: `${deleting.group.code} · ${deleting.discipline.shortName} (${formatHours(deleting.hoursPerWeek)} год/тиж)` } : null}
        onClose={() => setDeleting(null)}
        title="Видалити навантаження?"
        description="Генератор розкладу більше не плануватиме ці пари, а облік годин втратить план для цієї дисципліни."
        onDelete={(id) => remove.mutateAsync(id)}
        successMessage={(name) => `Видалено: ${name}`}
      />
    </div>
  );
}

const FILTER_LABEL = 'flex w-full flex-col gap-1 text-caption font-[650] text-primary sm:w-auto';

/** Другий рядок дисципліни: повна назва, вид занять, форма контролю, поділ на підгрупи. */
function disciplineNote(item: LoadItem): string {
  const parts: string[] = [];
  if (item.discipline.name !== item.discipline.shortName) parts.push(item.discipline.name);
  if (item.lessonKind !== 'MIXED') parts.push(LESSON_KIND_LABEL[item.lessonKind]);
  if (item.controlForm !== 'NONE') parts.push(CONTROL_FORM_LABEL[item.controlForm].toLocaleLowerCase('uk'));
  if (item.splitSubgroups) parts.push('за підгрупами');
  return parts.join(' · ');
}

function groupSummary(items: readonly LoadItem[]) {
  return {
    hours: items.reduce((sum, item) => sum + item.hoursPerWeek, 0),
    pairs: items.reduce((sum, item) => sum + pairsPerWeek(item.pattern), 0),
    planned: items.reduce((sum, item) => sum + item.plannedHours, 0),
    unassigned: items.filter((item) => item.teachers.length === 0).length,
  };
}

/** Скільки пар на тиждень вміщує сітка зміни: 1 зміна — 4 × 5, 2 зміна — 4 × 4 + 3. */
function weeklyCapacity(windows: ReadonlyArray<{ shift: number; firstPair: number; lastPair: number }>, shift: number): number | null {
  const own = windows.filter((w) => w.shift === shift);
  if (own.length === 0) return null;
  return own.reduce((sum, w) => sum + (w.lastPair - w.firstPair + 1), 0);
}
