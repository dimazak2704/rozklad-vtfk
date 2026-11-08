import { GitMerge, Pencil, Plus, Search, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useSemesters, activeSemesterOf, useShiftWindows } from '@/api/calendar';
import { useCatalogOptions } from '@/api/catalog';
import { useDeleteLoadItem, useLoadItems, type LoadItem } from '@/api/load';
import type { LoadSource } from '@/api/types';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { useTableUrlState } from '@/components/catalog/useTableUrlState';
import { DeriveDialog } from '@/components/load/DeriveDialog';
import { CONTROL_FORM_LABEL, LOAD_SOURCE_HINT, LOAD_SOURCE_LABEL, LOAD_SOURCE_TONE } from '@/components/load/labels';
import { LoadFormDialog } from '@/components/load/LoadForm';
import { formatHours, pairsPerWeek } from '@/components/load/pattern';
import { WeeklyPatternView } from '@/components/load/WeeklyPatternView';
import {
  Badge,
  Button,
  Combobox,
  DataTable,
  EmptyState,
  ErrorState,
  Input,
  PageHeader,
  Panel,
  Plate,
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
              cell: (item: LoadItem) => <Plate size="sm">{item.group.code}</Plate>,
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
              <span className="font-condensed text-body font-bold">{item.discipline.shortName}</span>
              {item.splitSubgroups ? (
                <Badge size="sm" tone="neutral">
                  за підгрупами
                </Badge>
              ) : null}
              {item.lessonKind !== 'MIXED' ? (
                <Badge size="sm" tone="neutral">
                  {LESSON_KIND_LABEL[item.lessonKind]}
                </Badge>
              ) : null}
              {item.controlForm !== 'NONE' ? (
                <Badge size="sm" tone="accent">
                  {CONTROL_FORM_LABEL[item.controlForm].toLocaleLowerCase('uk')}
                </Badge>
              ) : null}
            </span>
            {item.discipline.name !== item.discipline.shortName ? (
              <span className="line-clamp-1 text-caption font-normal text-muted" title={item.discipline.name}>
                {item.discipline.name}
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
        hideBelow: 'sm',
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
        sortValue: (item) => LOAD_SOURCE_LABEL[item.source],
        cell: (item) => (
          <Tooltip content={item.note ? `${LOAD_SOURCE_HINT[item.source]} ${item.note}` : LOAD_SOURCE_HINT[item.source]}>
            <span tabIndex={0} className="inline-flex rounded-plate">
              <Badge size="sm" tone={LOAD_SOURCE_TONE[item.source]}>
                {LOAD_SOURCE_LABEL[item.source]}
              </Badge>
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
  if (semesters.isError) return <ErrorState error={semesters.error} onRetry={() => void semesters.refetch()} />;
  if (!semester) {
    return (
      <div className="flex flex-col gap-5">
        <PageHeader title="Навантаження" />
        <EmptyState title="Семестрів ще немає" description="Створіть семестр у календарі — навантаження рахується в межах семестру." />
      </div>
    );
  }

  const summary = groupId && items.data ? groupSummary(items.data) : null;
  const capacity = group ? weeklyCapacity(shiftWindows.data ?? [], group.shift) : null;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Навантаження"
        description="Скільки годин на тиждень кожна група має з кожної дисципліни і хто їх веде. Генератор розкладу будує сітку саме з цього."
        actions={
          <>
            <Button variant="secondary" iconStart={<GitMerge aria-hidden="true" className="size-4" />} onClick={() => setDeriving(true)}>
              Вивести з розкладу
            </Button>
            <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={openNew}>
              Додати навантаження
            </Button>
          </>
        }
      />

      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-caption font-semibold text-secondary">
          Семестр
          <Select<string>
            size="sm"
            className="w-60"
            value={semester.id}
            onValueChange={(value) => table.set({ semester: value === activeSemesterOf(semesters.data)?.id ? null : value })}
            options={semesters.data.map((s) => ({ value: s.id, label: s.name, description: s.active ? 'Активний' : undefined }))}
          />
        </label>
        <label className="flex flex-col gap-1 text-caption font-semibold text-secondary">
          Група
          <Combobox
            size="sm"
            className="w-44"
            value={groupId}
            onValueChange={(value) => table.set({ group: value })}
            options={(groups.items ?? []).map((g) => ({ value: g.id, label: g.code, description: `${g.course} курс` }))}
            placeholder="Усі групи"
            searchPlaceholder="Код: 4КІ1 або 4ki1"
            loading={groups.isPending}
            clearable
          />
        </label>
        <label className="flex flex-col gap-1 text-caption font-semibold text-secondary">
          Джерело
          <Select<string>
            size="sm"
            className="w-48"
            value={source ?? ALL}
            onValueChange={(value) => table.set({ source: value === ALL ? null : value })}
            options={[{ value: ALL, label: 'Будь-яке' }, ...SOURCES.map((s) => ({ value: s, label: LOAD_SOURCE_LABEL[s] }))]}
          />
        </label>
        <Input
          type="search"
          size="sm"
          value={table.q}
          onChange={(event) => table.set({ q: event.target.value })}
          placeholder="Дисципліна або викладач"
          iconStart={<Search aria-hidden="true" className="size-4" />}
          aria-label="Пошук у навантаженні"
          wrapperClassName="w-full sm:w-64"
        />
      </div>

      {summary && group ? (
        <Panel flush>
          <dl className="flex flex-wrap items-center gap-x-8 gap-y-3 px-4 py-3">
            <div className="flex items-center gap-3">
              <Plate size="lg">{group.code}</Plate>
              <span className="text-caption text-muted">{group.shift} зміна</span>
            </div>
            <div className="flex flex-col">
              <dt className="text-caption text-muted">Годин на тиждень</dt>
              <dd className="font-condensed text-title font-bold figures text-primary">{formatHours(summary.hours)}</dd>
            </div>
            <div className="flex min-w-56 flex-col gap-1">
              <dt className="text-caption text-muted">Пар на тиждень у середньому</dt>
              <dd className="flex items-center gap-3">
                <span className="font-condensed text-title font-bold figures text-primary">{formatHours(summary.pairs)}</span>
                {capacity ? (
                  <span className="flex flex-1 flex-col gap-1">
                    <span
                      role="meter"
                      aria-label="Заповнення тижня групи"
                      aria-valuemin={0}
                      aria-valuemax={capacity}
                      aria-valuenow={summary.pairs}
                      className="relative h-2 overflow-hidden rounded-full bg-sunken"
                    >
                      <span
                        className={`absolute inset-y-0 start-0 rounded-full ${summary.pairs > capacity ? 'bg-[var(--fill-cancel)]' : 'bg-accent'}`}
                        style={{ width: `${Math.min(100, (summary.pairs / capacity) * 100)}%` }}
                      />
                    </span>
                    <span className="text-meta text-muted figures">з {capacity} місць у сітці зміни</span>
                  </span>
                ) : null}
              </dd>
            </div>
            <div className="flex flex-col">
              <dt className="text-caption text-muted">За семестр</dt>
              <dd className="font-condensed text-title font-bold figures text-primary">{summary.planned} год</dd>
            </div>
            <div className="flex flex-col">
              <dt className="text-caption text-muted">Без викладача</dt>
              <dd className={`font-condensed text-title font-bold figures ${summary.unassigned > 0 ? 'text-change' : 'text-primary'}`}>
                {summary.unassigned}
              </dd>
            </div>
          </dl>
        </Panel>
      ) : null}

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
              title="Нічого не знайдено"
              description="Змініть пошук чи джерело, або подивіться всі групи."
              action={
                <Button size="sm" variant="secondary" onClick={table.reset}>
                  Скинути фільтри
                </Button>
              }
            />
          ) : (
            <EmptyState
              size="sm"
              title={group ? `У ${group.code} ще немає навантаження` : 'Навантаження ще немає'}
              description="Виведіть його з опублікованого розкладу одним кліком або додайте дисципліни вручну."
              action={
                <Button size="sm" variant="primary" iconStart={<GitMerge aria-hidden="true" className="size-4" />} onClick={() => setDeriving(true)}>
                  Вивести з розкладу
                </Button>
              }
              secondaryAction={
                <Button size="sm" variant="secondary" onClick={openNew}>
                  Додати вручну
                </Button>
              }
            />
          )
        }
      />
      {items.data && !groupId ? (
        <p className="text-caption text-muted">
          {countLabel(filtered.length, ['запис', 'записи', 'записів'])} у{' '}
          {countLabel(new Set(filtered.map((i) => i.group.id)).size, ['групі', 'групах', 'групах'])}. Оберіть групу, щоб побачити підсумок тижня.
        </p>
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
