import { CalendarDays, Pencil, Plus, Power, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { paths } from '@/app/routes';
import {
  useCatalogList,
  useCatalogOptions,
  useDeleteCatalogItem,
  type CatalogRoom,
} from '@/api/catalog';
import type { RoomType } from '@/api/types';
import { CatalogNav, CatalogTitle } from '@/components/catalog/CatalogNav';
import { ActiveFilterControl, CatalogToolbar, FilterField } from '@/components/catalog/CatalogToolbar';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { EmptyValue } from '@/components/catalog/FormParts';
import { activeParam, CATALOG_PAGE_SIZE, ROOM_TYPE_OPTIONS, toActiveFilter } from '@/components/catalog/labels';
import { roomRequest } from '@/components/catalog/requests';
import { RoomFormDialog } from '@/components/catalog/RoomForm';
import { useTableUrlState } from '@/components/catalog/useTableUrlState';
import { useToggleActive } from '@/components/catalog/useToggleActive';
import {
  Button,
  DataTable,
  EmptyState,
  PageHeader,
  Panel,
  RowActions,
  Select,
  notify,
  type DataTableColumn,
} from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useHotkeys } from '@/hooks/useHotkeys';
import { capitalize, ROOM_TYPE_LABEL } from '@/lib/labels';
import { toSortParam } from '@/lib/sort';
import { useRegisterCommands } from '@/stores/commands';

const NOUN = ['аудиторія', 'аудиторії', 'аудиторій'] as const;
const ALL = 'all';

/** Довідник аудиторій: тип, корпус, місткість і скільки груп можуть займатися одночасно. */
export default function RoomsPage() {
  const navigate = useNavigate();
  const table = useTableUrlState({ columnId: 'name', direction: 'asc' }, ['type', 'building', 'active']);
  const q = useDebouncedValue(table.q.trim(), 250);
  const type = table.get('type') as RoomType | undefined;
  const list = useCatalogList('rooms', {
    q,
    page: table.page,
    size: CATALOG_PAGE_SIZE,
    sort: toSortParam(table.sort),
    type,
    buildingId: table.get('building'),
    active: activeParam(table.get('active')),
  });
  const buildings = useCatalogOptions('buildings');
  const remove = useDeleteCatalogItem('rooms');
  const toggleActive = useToggleActive('rooms', roomRequest, (room, active) =>
    active ? `Аудиторія ${room.name} знову діє` : `Аудиторію ${room.name} позначено неактивною`,
  );
  const [editing, setEditing] = useState<CatalogRoom | 'new' | null>(null);
  const [deleting, setDeleting] = useState<CatalogRoom | null>(null);

  const openNew = () => setEditing('new');
  useHotkeys([{ keys: 'n', handler: openNew, description: 'Нова аудиторія', group: 'Аудиторії' }]);
  useRegisterCommands([{ id: 'new-room', label: 'Додати аудиторію', group: 'Аудиторії', icon: Plus, shortcut: 'n', perform: openNew }]);

  const columns = useMemo<Array<DataTableColumn<CatalogRoom>>>(
    () => [
      {
        id: 'name',
        header: 'Аудиторія',
        sortable: true,
        rowHeader: true,
        width: '9rem',
        cell: (room) => (
          <span className="flex items-baseline gap-2">
            <span className="figures font-semibold">{room.name}</span>
            {room.active ? null : <span className="text-caption text-muted">неактивна</span>}
          </span>
        ),
      },
      { id: 'type', header: 'Тип', sortable: true, cell: (room) => capitalize(ROOM_TYPE_LABEL[room.type]) },
      {
        id: 'building',
        header: 'Корпус',
        hideBelow: 'md',
        cell: (room) => room.building?.label ?? <EmptyValue label="корпус не вказано" />,
      },
      {
        id: 'capacity',
        header: 'Місткість',
        sortable: true,
        numeric: true,
        cell: (room) => (room.capacity ?? <EmptyValue label="невідомо" />),
      },
      {
        id: 'maxParallel',
        header: 'Груп одночасно',
        numeric: true,
        hideBelow: 'sm',
        cell: (room) => room.maxParallel,
      },
    ],
    [],
  );

  const data = list.data;
  // колонки, де в усіх рядках однаково або порожньо, нічого не кажуть: «Аудиторія» в кожному рядку, «—» скрізь
  const visibleColumns = useMemo(() => {
    const items = data?.items ?? [];
    if (items.length === 0) return columns;
    const uniform = (pick: (room: CatalogRoom) => unknown) => new Set(items.map(pick)).size <= 1;
    return columns.filter((column) => {
      if (column.id === 'type') return Boolean(type) || !uniform((room) => room.type);
      if (column.id === 'building') return items.some((room) => room.building);
      if (column.id === 'capacity') return items.some((room) => room.capacity != null);
      if (column.id === 'maxParallel') return !uniform((room) => room.maxParallel);
      return true;
    });
  }, [columns, data?.items, type]);
  const activeFilters = (type ? 1 : 0) + (table.get('building') ? 1 : 0) + (table.get('active') ? 1 : 0);
  const buildingOptions = [{ value: ALL, label: 'Усі корпуси' }, ...(buildings.items ?? []).map((b) => ({ value: b.id, label: b.name }))];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title={<CatalogTitle section="Аудиторії" />}
        primaryAction={
          <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={openNew}>
            Додати аудиторію
          </Button>
        }
      />
      <CatalogNav />
      <CatalogToolbar
        search={table.q}
        onSearch={(value) => table.set({ q: value })}
        placeholder="Номер або назва"
        searchLabel="Пошук аудиторії"
        total={data?.total}
        noun={NOUN}
        filtered={table.filtered}
        onReset={table.reset}
        activeFilters={activeFilters}
      >
        <FilterField label="Тип приміщення">
          <Select<string>
            aria-label="Тип приміщення"
            className="w-full"
            value={type ?? ALL}
            onValueChange={(value) => table.set({ type: value === ALL ? null : value })}
            options={[{ value: ALL, label: 'Усі типи' }, ...ROOM_TYPE_OPTIONS]}
          />
        </FilterField>
        {buildingOptions.length > 1 ? (
          <FilterField label="Корпус">
            <Select<string>
              aria-label="Корпус"
              className="w-full"
              value={table.get('building') ?? ALL}
              onValueChange={(value) => table.set({ building: value === ALL ? null : value })}
              options={buildingOptions}
            />
          </FilterField>
        ) : null}
        <FilterField label="Стан">
          <ActiveFilterControl
            value={toActiveFilter(table.get('active'))}
            onChange={(value) => table.set({ active: value === 'all' ? null : value })}
          />
        </FilterField>
      </CatalogToolbar>
      <Panel flush>
      <DataTable
        caption="Аудиторії коледжу"
        columns={visibleColumns}
        rows={data?.items}
        getRowId={(room) => room.id}
        loading={list.isPending || list.isPlaceholderData}
        error={list.error}
        onRetry={() => void list.refetch()}
        manualSorting
        sort={table.sort}
        onSortChange={table.setSort}
        onRowClick={(room) => setEditing(room)}
        rowClassName={(room) => (room.active ? undefined : 'text-muted')}
        rowActions={(room) => (
          <RowActions
            rowLabel={`аудиторія ${room.name}`}
            actions={[
              { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, onSelect: () => setEditing(room) },
              { label: 'Розклад аудиторії', icon: <CalendarDays aria-hidden="true" />, onSelect: () => void navigate(paths.room(room.id)) },
              {
                label: room.active ? 'Позначити неактивною' : 'Зробити активною',
                icon: <Power aria-hidden="true" />,
                onSelect: () => void toggleActive(room).catch((error: unknown) => notify.error(error, 'Не вдалося змінити стан аудиторії')),
              },
              { label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger', separatorBefore: true, onSelect: () => setDeleting(room) },
            ]}
          />
        )}
        pagination={data ? { page: data.page, size: data.size, total: data.total, onPageChange: table.setPage, noun: 'аудиторій' } : undefined}
        empty={
          table.filtered ? (
            <EmptyState
              size="sm"
              title={table.q ? `Аудиторії «${table.q}» немає.` : 'За цим фільтром аудиторій немає.'}
              action={
                <Button variant="ghost" size="sm" onClick={table.reset}>
                  Показати всі
                </Button>
              }
            />
          ) : (
            <EmptyState
              size="sm"
              title="Аудиторій ще немає."
              description="Імпортуйте таблицю розкладу — аудиторії з неї зʼявляться тут."
              action={
                <Button variant="ghost" size="sm" onClick={openNew}>
                  Додати аудиторію вручну
                </Button>
              }
            />
          )
        }
      />
      </Panel>

      {editing ? (
        <RoomFormDialog
          key={editing === 'new' ? 'new' : editing.id}
          room={editing === 'new' ? null : editing}
          open
          onOpenChange={(open) => {
            if (!open) setEditing(null);
          }}
        />
      ) : null}
      <DeleteDialog
        target={deleting ? { id: deleting.id, name: `Аудиторія ${deleting.name}` } : null}
        onClose={() => setDeleting(null)}
        title="Видалити аудиторію?"
        description="Аудиторія зникне з довідника. Якщо вона вже є в розкладі чи навантаженні, система не дасть її видалити."
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
