import { GitMerge, Pencil, Plus, Trash } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import { useGroupTheory } from '@/api/accounting';
import { useSemesters, activeSemesterOf, useShiftWindows } from '@/api/calendar';
import { useCatalogOptions } from '@/api/catalog';
import { useDeleteLoadItem, useLoadItems, type LoadItem } from '@/api/load';
import type { LoadSource } from '@/api/types';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { useTableUrlState } from '@/components/catalog/useTableUrlState';
import { DeriveDialog } from '@/components/load/DeriveDialog';
import { LoadFilters } from '@/components/load/LoadFilters';
import { LoadFormDialog } from '@/components/load/LoadForm';
import { formatHours } from '@/components/load/pattern';
import { GroupWeekSummary, TeacherLoadSummary } from '@/components/load/LoadSummaries';
import { DEFAULT_LOAD_SOURCE, useLoadColumns } from '@/components/load/useLoadColumns';
import { Link } from 'react-router';
import { Button, buttonClass, DataTable, EmptyState, PageErrorBanner, PageHeader, Panel, RowActions, Skeleton, Tooltip } from '@/components/ui';
import { matchScore } from '@/lib/search';
import { sortRows } from '@/lib/sort';
import { useRowCursor } from '@/components/catalog/useRowCursor';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useRegisterCommands } from '@/stores/commands';
import { useTeacherLabel } from '@/components/admin-daily/useTeacherLabel';

const PAGE_SIZE = 50;

/** Навантаження: години на тиждень за групою × дисципліною, тижневий шаблон «1 + ½», викладачі, джерело годин. */
export default function LoadPage() {
  const name = useTeacherLabel();
  const table = useTableUrlState(null, ['group', 'source']);
  const semesters = useSemesters();
  const semester = semesters.data?.find((s) => s.id === table.get('semester')) ?? activeSemesterOf(semesters.data);
  const groupId = table.get('group') ?? null;
  const source = table.get('source') as LoadSource | undefined;
  const items = useLoadItems(semester?.id, groupId);
  const groups = useCatalogOptions('groups');
  // тижні теорії групи — лише коли групу вибрано: за ними система рахує план «За семестр»
  const theory = useGroupTheory(groupId ? semester?.id : null, groupId);
  const groupTheory = theory.data?.find((t) => t.group.id === groupId);
  const shiftWindows = useShiftWindows();
  const remove = useDeleteLoadItem();
  const [editing, setEditing] = useState<LoadItem | 'new' | null>(null);
  const [deleting, setDeleting] = useState<LoadItem | null>(null);
  const [deriving, setDeriving] = useState(false);

  const openNew = () => setEditing('new');
  const searchRef = useRef<HTMLInputElement>(null);
  useHotkeys([
    { keys: 'n', handler: openNew, description: 'Нове навантаження', group: 'Навантаження' },
    { keys: '/', handler: () => searchRef.current?.focus(), description: 'Пошук у навантаженні', group: 'Навантаження' },
  ]);
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

  // за замовчуванням: усі групи — за групою, одна група — за дисципліною
  const sort = table.sort ?? { columnId: groupId ? 'discipline' : 'group', direction: 'asc' as const };
  // у таблиці за групами код групи — лише в першому рядку групи: решта рядків читаються як її частина
  const [groupStarts, setGroupStarts] = useState<ReadonlySet<string> | null>(null);

  const columns = useLoadColumns({ groupId, groupStarts, groupTheory });

  // сортуємо всі знайдені записи, а не лише сторінку, і лише потім ділимо на сторінки
  const sorted = useMemo(() => {
    const column = columns.find((c) => c.id === sort.columnId);
    return column?.sortValue ? sortRows(filtered, column.sortValue, sort.direction) : filtered;
  }, [columns, filtered, sort.columnId, sort.direction]);
  const pageRows = useMemo(
    () => (groupId ? sorted : sorted.slice(table.page * PAGE_SIZE, (table.page + 1) * PAGE_SIZE)),
    [groupId, sorted, table.page],
  );
  const starts = useMemo(() => (sort.columnId === 'group' ? firstOfGroup(pageRows) : null), [pageRows, sort.columnId]);
  // «Джерело» показує лише винятки; якщо на сторінці їх немає, порожня колонка не займає ширину
  const visibleColumns = useMemo(
    () => (pageRows.some((item) => item.source !== DEFAULT_LOAD_SOURCE || item.note) ? columns : columns.filter((column) => column.id !== 'source')),
    [columns, pageRows],
  );
  if (!sameSet(starts, groupStarts)) setGroupStarts(starts);
  const cursor = useRowCursor({
    rows: pageRows,
    getId: (item) => item.id,
    onOpen: (item) => setEditing(item),
    onDelete: (item) => setDeleting(item),
    group: 'Навантаження',
    enabled: editing === null && deleting === null && !deriving,
  });

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

  const searching = table.q.trim() !== '';

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Навантаження"
        primaryAction={
          <Tooltip content={null} shortcut="n">
            <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={openNew}>
              Додати навантаження
            </Button>
          </Tooltip>
        }
        more={[{ label: 'Вивести з розкладу', icon: <GitMerge aria-hidden="true" className="size-4" />, onSelect: () => setDeriving(true) }]}
      />

      <LoadFilters
        table={table}
        groupId={groupId}
        groupOptions={(groups.items ?? []).map((g) => ({ value: g.id, label: g.code, description: `${g.course} курс` }))}
        groupsLoading={groups.isPending}
        searchRef={searchRef}
        semester={semester}
        semesters={semesters.data}
        activeSemesterId={activeSemesterOf(semesters.data)?.id}
        source={source}
      />

      {groupId && items.data && group ? <GroupWeekSummary group={group} items={items.data} shiftWindows={shiftWindows.data} groupTheory={groupTheory} /> : null}
      {searching && items.data ? <TeacherLoadSummary rows={filtered} /> : null}

      <Panel flush>
      <DataTable
        caption={group ? `Навантаження групи ${group.code}` : `Навантаження: ${semester.name}`}
        columns={visibleColumns}
        rows={items.data ? pageRows : undefined}
        getRowId={(item) => item.id}
        loading={items.isPending}
        error={items.error}
        onRetry={() => void items.refetch()}
        manualSorting
        sort={sort}
        onSortChange={table.setSort}
        selectedRowId={cursor.selectedRowId}
        onRowClick={(item) => {
          cursor.setCursor(item.id);
          setEditing(item);
        }}
        mobileRow={(item) => ({
          title: groupId ? item.discipline.shortName : `${item.group.code} · ${item.discipline.shortName}`,
          caption: [
            `${formatHours(item.hoursPerWeek)} год/тиж`,
            item.teachers.length === 0 ? 'викладача не призначено' : item.teachers.map((t) => name(t.teacher)).join(', '),
          ].join(' · '),
        })}
        rowActions={(item) => (
          <RowActions
            rowLabel={`${item.group.code} · ${item.discipline.shortName}`}
            actions={[
              { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, shortcut: 'e', onSelect: () => setEditing(item) },
              { label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger', separatorBefore: true, shortcut: 'delete', onSelect: () => setDeleting(item) },
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


/** Рядки, з яких починається нова група (перший рядок сторінки — завжди). */
function firstOfGroup(rows: readonly LoadItem[]): ReadonlySet<string> {
  const out = new Set<string>();
  rows.forEach((row, index) => {
    if (index === 0 || rows[index - 1]?.group.id !== row.group.id) out.add(row.id);
  });
  return out;
}

function sameSet(a: ReadonlySet<string> | null, b: ReadonlySet<string> | null): boolean {
  if (a === b) return true;
  if (!a || !b || a.size !== b.size) return false;
  for (const value of a) if (!b.has(value)) return false;
  return true;
}
