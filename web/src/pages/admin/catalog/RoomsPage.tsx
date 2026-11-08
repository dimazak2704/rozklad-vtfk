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
import { CatalogNav } from '@/components/catalog/CatalogNav';
import { ActiveFilterControl, CatalogToolbar } from '@/components/catalog/CatalogToolbar';
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
  Plate,
  RowActions,
  Select,
  StatusDot,
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
          <span className="flex items-center gap-2">
            <Plate size="md" className={room.active ? undefined : 'opacity-60'}>
              {room.name}
            </Plate>
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
      {
        id: 'active',
        header: 'Стан',
        sortable: true,
        cell: (room) =>
          room.active ? (
            <StatusDot tone="add" label="Діє" showLabel />
          ) : (
            <StatusDot tone="neutral" label="Неактивна" showLabel />
          ),
      },
    ],
    [],
  );

  const data = list.data;
  const buildingOptions = [{ value: ALL, label: 'Усі корпуси' }, ...(buildings.items ?? []).map((b) => ({ value: b.id, label: b.name }))];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Аудиторії"
        description="Аудиторії з розкладу та їхні обмеження: тип, місткість і скільки груп можуть займатися одночасно."
        actions={
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
      >
        <Select<string>
          aria-label="Тип приміщення"
          size="sm"
          className="w-48"
          value={type ?? ALL}
          onValueChange={(value) => table.set({ type: value === ALL ? null : value })}
          options={[{ value: ALL, label: 'Усі типи' }, ...ROOM_TYPE_OPTIONS]}
        />
        {buildingOptions.length > 1 ? (
          <Select<string>
            aria-label="Корпус"
            size="sm"
            className="w-44"
            value={table.get('building') ?? ALL}
            onValueChange={(value) => table.set({ building: value === ALL ? null : value })}
            options={buildingOptions}
          />
        ) : null}
        <ActiveFilterControl
          value={toActiveFilter(table.get('active'))}
          onChange={(value) => table.set({ active: value === 'all' ? null : value })}
        />
      </CatalogToolbar>
      <DataTable
        caption="Аудиторії коледжу"
        columns={columns}
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
              title="Нічого не знайдено"
              description="Перевірте написання номера або скиньте фільтри."
              action={
                <Button variant="secondary" size="sm" onClick={table.reset}>
                  Скинути фільтри
                </Button>
              }
            />
          ) : (
            <EmptyState
              size="sm"
              title="Аудиторій ще немає"
              description="Додайте першу аудиторію або імпортуйте таблицю розкладу — аудиторії з неї зʼявляться тут."
              action={
                <Button variant="primary" size="sm" onClick={openNew}>
                  Додати аудиторію
                </Button>
              }
            />
          )
        }
      />

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
