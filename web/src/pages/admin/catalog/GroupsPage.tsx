import { CalendarDays, Pencil, Plus, Power, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { paths } from '@/app/routes';
import { useCatalogList, useCatalogOptions, useDeleteCatalogItem, type CatalogGroup } from '@/api/catalog';
import { CatalogNav, CatalogTitle } from '@/components/catalog/CatalogNav';
import { ActiveFilterControl, CatalogToolbar, FilterField } from '@/components/catalog/CatalogToolbar';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { EmptyValue } from '@/components/catalog/FormParts';
import { GroupFormDialog } from '@/components/catalog/GroupForm';
import { activeParam, CATALOG_PAGE_SIZE, toActiveFilter } from '@/components/catalog/labels';
import { groupRequest } from '@/components/catalog/requests';
import { useTableUrlState } from '@/components/catalog/useTableUrlState';
import { useToggleActive } from '@/components/catalog/useToggleActive';
import {
  Button,
  DataTable,
  EmptyState,
  PageHeader,
  Panel,
  RowActions,
  SegmentedControl,
  Select,
  Tooltip,
  notify,
  type DataTableColumn,
} from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useHotkeys } from '@/hooks/useHotkeys';
import { countLabel } from '@/lib/labels';
import { toSortParam } from '@/lib/sort';
import { useRegisterCommands } from '@/stores/commands';

const NOUN = ['група', 'групи', 'груп'] as const;
const ALL = 'all';
const COURSES = ['1', '2', '3', '4'] as const;
const SHIFT_HINT = { 1: 'Пари 1–4', 2: 'Пари 5–8 (пн–чт), 4–6 (пт)' } as const;

/** Довідник груп: код, курс, спеціальність, зміна, куратор, домашня аудиторія, підгрупи. */
export default function GroupsPage() {
  const navigate = useNavigate();
  const table = useTableUrlState({ columnId: 'code', direction: 'asc' }, ['course', 'specialty', 'shift', 'active']);
  const q = useDebouncedValue(table.q.trim(), 250);
  const course = table.get('course');
  const list = useCatalogList('groups', {
    q,
    page: table.page,
    size: CATALOG_PAGE_SIZE,
    sort: toSortParam(table.sort),
    course: course ? Number(course) : undefined,
    specialtyId: table.get('specialty'),
    shift: table.get('shift') ? Number(table.get('shift')) : undefined,
    active: activeParam(table.get('active')),
  });
  const specialties = useCatalogOptions('specialties');
  const remove = useDeleteCatalogItem('groups');
  const toggleActive = useToggleActive('groups', groupRequest, (g, active) =>
    active ? `Група ${g.code} знову навчається` : `Групу ${g.code} позначено неактивною`,
  );
  const [editing, setEditing] = useState<CatalogGroup | 'new' | null>(null);
  const [deleting, setDeleting] = useState<CatalogGroup | null>(null);

  const openNew = () => setEditing('new');
  useHotkeys([{ keys: 'n', handler: openNew, description: 'Нова група', group: 'Групи' }]);
  useRegisterCommands([{ id: 'new-group', label: 'Додати групу', group: 'Групи', icon: Plus, shortcut: 'n', perform: openNew }]);

  const specialtyName = useMemo(() => new Map((specialties.items ?? []).map((s) => [s.id, s.name])), [specialties.items]);

  const columns = useMemo<Array<DataTableColumn<CatalogGroup>>>(
    () => [
      {
        id: 'code',
        header: 'Група',
        sortable: true,
        rowHeader: true,
        width: '7.5rem',
        cell: (g) => (
          <span className="flex items-baseline gap-2">
            <span className="figures font-semibold">{g.code}</span>
            {g.active ? null : <span className="text-caption text-muted">неактивна</span>}
          </span>
        ),
      },
      { id: 'course', header: 'Курс', sortable: true, numeric: true, width: '5rem', cell: (g) => g.course },
      {
        id: 'specialty',
        header: 'Спеціальність',
        hideBelow: 'md',
        className: 'max-md:hidden!',
        cell: (g) =>
          g.specialty ? (
            <span className="flex flex-col">
              <span className="font-semibold">{g.specialty.label}</span>
              {g.specialtyId && specialtyName.get(g.specialtyId) && specialtyName.get(g.specialtyId) !== g.specialty.label ? (
                <span className="text-caption text-muted">{specialtyName.get(g.specialtyId)}</span>
              ) : null}
            </span>
          ) : (
            <EmptyValue />
          ),
      },
      {
        id: 'shift',
        header: 'Зміна',
        sortable: true,
        numeric: true,
        width: '5.5rem',
        cell: (g) => (
          <Tooltip content={SHIFT_HINT[g.shift]}>
            <span tabIndex={0} className="rounded-tag figures">
              {g.shift}
            </span>
          </Tooltip>
        ),
      },
      { id: 'curator', header: 'Куратор', hideBelow: 'lg', className: 'max-md:hidden!', cell: (g) => g.curator?.shortName ?? <EmptyValue /> },
      {
        id: 'homeRoom',
        header: 'Аудиторія',
        hideBelow: 'md',
        className: 'max-md:hidden!',
        cell: (g) => (g.homeRoom ? <span className="figures">{g.homeRoom.name}</span> : <EmptyValue />),
      },
      {
        id: 'subgroups',
        header: 'Підгрупи',
        hideBelow: 'lg',
        className: 'max-md:hidden!',
        cell: (g) => (g.subgroups.length > 0 ? countLabel(g.subgroups.length, ['підгрупа', 'підгрупи', 'підгруп']) : <EmptyValue label="не поділена" />),
      },
      { id: 'size', header: 'Студентів', sortable: true, numeric: true, hideBelow: 'lg', className: 'max-md:hidden!', cell: (g) => g.size ?? <EmptyValue label="невідомо" /> },
    ],
    [specialtyName],
  );

  const data = list.data;
  // «—» у кожному рядку нічого не каже: колонку кількості студентів показуємо, лише коли вона десь відома
  const visibleColumns = useMemo(
    () => (data && data.items.length > 0 && data.items.every((g) => g.size == null) ? columns.filter((c) => c.id !== 'size') : columns),
    [columns, data],
  );
  const specialtyOptions = [
    { value: ALL, label: 'Усі спеціальності' },
    ...(specialties.items ?? []).map((s) => ({ value: s.id, label: s.letters, description: s.name !== s.letters ? s.name : undefined })),
  ];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title={<CatalogTitle section="Групи" />}
        primaryAction={
          <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={openNew}>
            Додати групу
          </Button>
        }
      />
      <CatalogNav />
      <CatalogToolbar
        search={table.q}
        onSearch={(value) => table.set({ q: value })}
        placeholder="Група або куратор"
        searchLabel="Пошук групи"
        total={data?.total}
        noun={NOUN}
        filtered={table.filtered}
        onReset={table.reset}
        activeFilters={(course ? 1 : 0) + (table.get('specialty') ? 1 : 0) + (table.get('shift') ? 1 : 0) + (table.get('active') ? 1 : 0)}
      >
        <FilterField label="Курс">
          <SegmentedControl<string>
            aria-label="Курс"
            stretch
            value={course ?? ALL}
            onValueChange={(value) => table.set({ course: value === ALL ? null : value })}
            options={[{ value: ALL, label: 'Усі' }, ...COURSES.map((c) => ({ value: c, label: c, ariaLabel: `${c} курс` }))]}
          />
        </FilterField>
        <FilterField label="Спеціальність">
        <Select<string>
          aria-label="Спеціальність"
          className="w-full"
          value={table.get('specialty') ?? ALL}
          onValueChange={(value) => table.set({ specialty: value === ALL ? null : value })}
          options={specialtyOptions}
        />
        </FilterField>
        <FilterField label="Зміна">
        <Select<string>
          aria-label="Зміна"
          className="w-full"
          value={table.get('shift') ?? ALL}
          onValueChange={(value) => table.set({ shift: value === ALL ? null : value })}
          options={[
            { value: ALL, label: 'Обидві зміни' },
            { value: '1', label: '1 зміна', description: SHIFT_HINT[1] },
            { value: '2', label: '2 зміна', description: SHIFT_HINT[2] },
          ]}
        />
        </FilterField>
        <FilterField label="Стан">
          <ActiveFilterControl
            value={toActiveFilter(table.get('active'))}
            onChange={(value) => table.set({ active: value === 'all' ? null : value })}
          />
        </FilterField>
      </CatalogToolbar>
      <Panel flush>
      <DataTable
        caption="Групи коледжу"
        columns={visibleColumns}
        rows={data?.items}
        getRowId={(g) => g.id}
        loading={list.isPending || list.isPlaceholderData}
        error={list.error}
        onRetry={() => void list.refetch()}
        manualSorting
        sort={table.sort}
        onSortChange={table.setSort}
        onRowClick={(g) => setEditing(g)}
        rowClassName={(g) => (g.active ? undefined : 'text-muted')}
        mobileRow={(g) => ({
          title: <span className="figures">{g.code}</span>,
          caption: `${g.course} курс · ${g.shift} зміна`,
          status: g.active ? null : <span className="text-muted">неактивна</span>,
        })}
        rowActions={(g) => (
          <RowActions
            rowLabel={`група ${g.code}`}
            actions={[
              { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, onSelect: () => setEditing(g) },
              { label: 'Розклад групи', icon: <CalendarDays aria-hidden="true" />, onSelect: () => void navigate(paths.group(g.code)) },
              {
                label: g.active ? 'Позначити неактивною' : 'Зробити активною',
                icon: <Power aria-hidden="true" />,
                onSelect: () => void toggleActive(g).catch((error: unknown) => notify.error(error, 'Не вдалося змінити стан групи')),
              },
              { label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger', separatorBefore: true, onSelect: () => setDeleting(g) },
            ]}
          />
        )}
        pagination={data ? { page: data.page, size: data.size, total: data.total, onPageChange: table.setPage, noun: 'груп' } : undefined}
        empty={
          table.filtered ? (
            <EmptyState
              size="sm"
              title={table.q ? `Групи «${table.q}» немає.` : 'За цим фільтром груп немає.'}
              description="Код можна писати латиницею: «4ki1» знайде 4КІ1."
              action={
                <Button variant="ghost" size="sm" onClick={table.reset}>
                  Показати всі
                </Button>
              }
            />
          ) : (
            <EmptyState
              size="sm"
              title="Груп ще немає."
              description="Імпортуйте таблицю розкладу — групи з неї зʼявляться тут разом із кураторами й аудиторіями."
              action={
                <Button variant="ghost" size="sm" onClick={openNew}>
                  Додати групу вручну
                </Button>
              }
            />
          )
        }
      />
      </Panel>

      {editing ? (
        <GroupFormDialog
          key={editing === 'new' ? 'new' : editing.id}
          group={editing === 'new' ? null : editing}
          open
          onOpenChange={(open) => {
            if (!open) setEditing(null);
          }}
        />
      ) : null}
      <DeleteDialog
        target={deleting ? { id: deleting.id, name: `Група ${deleting.code}` } : null}
        onClose={() => setDeleting(null)}
        title="Видалити групу?"
        description="Група зникне з довідника разом з підгрупами. Групу з розкладом, навантаженням чи замінами видалити не можна."
        onDelete={(id) => remove.mutateAsync(id)}
        successMessage={(name) => `${name}: видалено`}
        deactivate={
          deleting?.active
            ? {
                label: 'Позначити неактивною',
                run: () => toggleActive(deleting, { undo: false }),
                successMessage: (name) => `${name}: позначено неактивною`,
              }
            : undefined
        }
      />
    </div>
  );
}
