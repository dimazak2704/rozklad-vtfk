import { Pencil, Plus, Trash } from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import {
  useCatalogOptions,
  useDeleteCatalogItem,
  type CatalogBuilding,
  type CatalogCycleCommission,
  type CatalogDepartment,
  type CatalogSpecialty,
} from '@/api/catalog';
import { CatalogNav } from '@/components/catalog/CatalogNav';
import { DeleteDialog, type DeleteTarget } from '@/components/catalog/DeleteDialog';
import { EmptyValue } from '@/components/catalog/FormParts';
import { DepartmentFormDialog, NamedFormDialog, SpecialtyFormDialog } from '@/components/catalog/StructureForms';
import {
  Button,
  DataTable,
  EmptyState,
  PageHeader,
  Plate,
  RowActions,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  type DataTableColumn,
} from '@/components/ui';
import { useRegisterCommands } from '@/stores/commands';

type Tab = 'departments' | 'specialties' | 'commissions' | 'buildings';
const TABS: readonly Tab[] = ['departments', 'specialties', 'commissions', 'buildings'];

type Editing =
  | { tab: 'departments'; item: CatalogDepartment | null }
  | { tab: 'specialties'; item: CatalogSpecialty | null }
  | { tab: 'commissions'; item: CatalogCycleCommission | null }
  | { tab: 'buildings'; item: CatalogBuilding | null };

const DELETE_TEXT: Record<Tab, { title: string; description: string }> = {
  departments: { title: 'Видалити відділення?', description: 'Відділення, до якого належать спеціальності, видалити не можна.' },
  specialties: { title: 'Видалити спеціальність?', description: 'Спеціальність, до якої належать групи, видалити не можна.' },
  commissions: { title: 'Видалити циклову комісію?', description: 'Комісію, до якої належать викладачі, видалити не можна.' },
  buildings: { title: 'Видалити корпус?', description: 'Корпус, у якому є аудиторії, видалити не можна.' },
};

function TabPanel({
  description,
  addLabel,
  onAdd,
  children,
}: {
  description: string;
  addLabel: string;
  onAdd: () => void;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-prose text-ui text-secondary">{description}</p>
        <Button variant="secondary" size="sm" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={onAdd}>
          {addLabel}
        </Button>
      </div>
      {children}
    </div>
  );
}

/** Структура коледжу: відділення, спеціальності (літери в кодах груп), циклові комісії, корпуси. */
export default function StructurePage() {
  const [params, setParams] = useSearchParams();
  const rawTab = params.get('tab');
  const tab: Tab = TABS.includes(rawTab as Tab) ? (rawTab as Tab) : 'departments';
  const departments = useCatalogOptions('departments');
  const specialties = useCatalogOptions('specialties');
  const commissions = useCatalogOptions('cycle-commissions');
  const buildings = useCatalogOptions('buildings');
  const groups = useCatalogOptions('groups');
  const teachers = useCatalogOptions('teachers');
  const rooms = useCatalogOptions('rooms');
  const [editing, setEditing] = useState<Editing | null>(null);
  const [deleting, setDeleting] = useState<(DeleteTarget & { tab: Tab }) | null>(null);
  const removeDepartment = useDeleteCatalogItem('departments');
  const removeSpecialty = useDeleteCatalogItem('specialties');
  const removeCommission = useDeleteCatalogItem('cycle-commissions');
  const removeBuilding = useDeleteCatalogItem('buildings');
  const removers = { departments: removeDepartment, specialties: removeSpecialty, commissions: removeCommission, buildings: removeBuilding };

  const selectTab = (next: Tab) =>
    setParams(
      (prev) => {
        const copy = new URLSearchParams(prev);
        if (next === 'departments') copy.delete('tab');
        else copy.set('tab', next);
        return copy;
      },
      { replace: true },
    );

  useRegisterCommands([
    { id: 'new-department', label: 'Додати відділення', group: 'Структура', icon: Plus, perform: () => setEditing({ tab: 'departments', item: null }) },
    { id: 'new-specialty', label: 'Додати спеціальність', group: 'Структура', icon: Plus, perform: () => setEditing({ tab: 'specialties', item: null }) },
    { id: 'new-commission', label: 'Додати циклову комісію', group: 'Структура', icon: Plus, perform: () => setEditing({ tab: 'commissions', item: null }) },
    { id: 'new-building', label: 'Додати корпус', group: 'Структура', icon: Plus, perform: () => setEditing({ tab: 'buildings', item: null }) },
  ]);

  const counts = useMemo(() => {
    const by = <T,>(items: readonly T[] | undefined, key: (item: T) => string | null | undefined) => {
      const map = new Map<string, number>();
      for (const item of items ?? []) {
        const id = key(item);
        if (id) map.set(id, (map.get(id) ?? 0) + 1);
      }
      return map;
    };
    return {
      specialtiesByDepartment: by(specialties.items, (s) => s.departmentId),
      groupsBySpecialty: by(groups.items, (g) => g.specialtyId),
      teachersByCommission: by(teachers.items, (t) => t.cycleCommission?.id),
      roomsByBuilding: by(rooms.items, (r) => r.building?.id),
    };
  }, [specialties.items, groups.items, teachers.items, rooms.items]);

  const actions = (tab: Tab, id: string, name: string, edit: () => void) => (
    <RowActions
      rowLabel={name}
      actions={[
        { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, onSelect: edit },
        { label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger', separatorBefore: true, onSelect: () => setDeleting({ tab, id, name }) },
      ]}
    />
  );

  const departmentColumns: Array<DataTableColumn<CatalogDepartment>> = [
    { id: 'name', header: 'Відділення', rowHeader: true, sortValue: (d) => d.name, cell: (d) => d.name },
    { id: 'shortName', header: 'Скорочення', hideBelow: 'sm', cell: (d) => d.shortName ?? <EmptyValue /> },
    {
      id: 'specialties',
      header: 'Спеціальності',
      cell: (d) => {
        const own = (specialties.items ?? []).filter((s) => s.departmentId === d.id);
        return own.length > 0 ? (
          <span className="flex flex-wrap gap-1">
            {own.map((s) => (
              <Plate key={s.id} size="sm">
                {s.letters}
              </Plate>
            ))}
          </span>
        ) : (
          <EmptyValue label="немає" />
        );
      },
    },
  ];

  const specialtyColumns: Array<DataTableColumn<CatalogSpecialty>> = [
    { id: 'letters', header: 'Літери', rowHeader: true, width: '7rem', sortValue: (s) => s.letters, cell: (s) => <Plate size="md">{s.letters}</Plate> },
    { id: 'name', header: 'Назва', sortValue: (s) => s.name, cell: (s) => (s.name === s.letters ? <span className="text-secondary">Повна назва невідома</span> : s.name) },
    { id: 'code', header: 'Код', width: '6rem', hideBelow: 'sm', sortValue: (s) => s.code, cell: (s) => s.code ?? <EmptyValue /> },
    { id: 'department', header: 'Відділення', hideBelow: 'md', sortValue: (s) => s.department?.label, cell: (s) => s.department?.label ?? <EmptyValue /> },
    {
      id: 'groups',
      header: 'Групи',
      numeric: true,
      width: '6rem',
      sortValue: (s) => counts.groupsBySpecialty.get(s.id) ?? 0,
      cell: (s) => counts.groupsBySpecialty.get(s.id) ?? 0,
    },
  ];

  const commissionColumns: Array<DataTableColumn<CatalogCycleCommission>> = [
    { id: 'name', header: 'Циклова комісія', rowHeader: true, sortValue: (c) => c.name, cell: (c) => c.name },
    {
      id: 'teachers',
      header: 'Викладачів',
      numeric: true,
      width: '8rem',
      sortValue: (c) => counts.teachersByCommission.get(c.id) ?? 0,
      cell: (c) => counts.teachersByCommission.get(c.id) ?? 0,
    },
  ];

  const buildingColumns: Array<DataTableColumn<CatalogBuilding>> = [
    { id: 'name', header: 'Корпус', rowHeader: true, sortValue: (b) => b.name, cell: (b) => b.name },
    {
      id: 'rooms',
      header: 'Аудиторій',
      numeric: true,
      width: '8rem',
      sortValue: (b) => counts.roomsByBuilding.get(b.id) ?? 0,
      cell: (b) => counts.roomsByBuilding.get(b.id) ?? 0,
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Структура коледжу"
        description="Відділення, спеціальності з літерами для кодів груп, циклові комісії викладачів і корпуси з аудиторіями."
      />
      <CatalogNav />
      <Tabs value={tab} onValueChange={(value) => selectTab(value as Tab)}>
        <TabsList aria-label="Розділи структури">
          <TabsTrigger value="departments" count={departments.items?.length}>
            Відділення
          </TabsTrigger>
          <TabsTrigger value="specialties" count={specialties.items?.length}>
            Спеціальності
          </TabsTrigger>
          <TabsTrigger value="commissions" count={commissions.items?.length}>
            Циклові комісії
          </TabsTrigger>
          <TabsTrigger value="buildings" count={buildings.items?.length}>
            Корпуси
          </TabsTrigger>
        </TabsList>

        <TabsContent value="departments">
          <TabPanel
            description="Відділення обʼєднують спеціальності; їх назви бачать на сайті поруч із групою."
            addLabel="Додати відділення"
            onAdd={() => setEditing({ tab: 'departments', item: null })}
          >
            <DataTable
              caption="Відділення"
              columns={departmentColumns}
              rows={departments.items}
              getRowId={(d) => d.id}
              loading={departments.isPending}
              error={departments.error}
              onRetry={() => void departments.refetch()}
              defaultSort={{ columnId: 'name', direction: 'asc' }}
              onRowClick={(d) => setEditing({ tab: 'departments', item: d })}
              rowActions={(d) => actions('departments', d.id, d.name, () => setEditing({ tab: 'departments', item: d }))}
              empty={<EmptyState size="sm" title="Відділень ще немає" description="Додайте відділення, щоб привʼязати до них спеціальності." />}
            />
          </TabPanel>
        </TabsContent>

        <TabsContent value="specialties">
          <TabPanel
            description="Літери спеціальності входять у код групи: «КІ» у «4КІ1». За ними нова група сама знаходить спеціальність."
            addLabel="Додати спеціальність"
            onAdd={() => setEditing({ tab: 'specialties', item: null })}
          >
            <DataTable
              caption="Спеціальності"
              columns={specialtyColumns}
              rows={specialties.items}
              getRowId={(s) => s.id}
              loading={specialties.isPending}
              error={specialties.error}
              onRetry={() => void specialties.refetch()}
              defaultSort={{ columnId: 'letters', direction: 'asc' }}
              onRowClick={(s) => setEditing({ tab: 'specialties', item: s })}
              rowActions={(s) => actions('specialties', s.id, `Спеціальність ${s.letters}`, () => setEditing({ tab: 'specialties', item: s }))}
              empty={<EmptyState size="sm" title="Спеціальностей ще немає" description="Без спеціальності не вийде додати групу з такими літерами в коді." />}
            />
          </TabPanel>
        </TabsContent>

        <TabsContent value="commissions">
          <TabPanel
            description="Комісія викладача важить при підборі замін: колега з тієї самої комісії отримує +20 балів."
            addLabel="Додати комісію"
            onAdd={() => setEditing({ tab: 'commissions', item: null })}
          >
            <DataTable
              caption="Циклові комісії"
              columns={commissionColumns}
              rows={commissions.items}
              getRowId={(c) => c.id}
              loading={commissions.isPending}
              error={commissions.error}
              onRetry={() => void commissions.refetch()}
              defaultSort={{ columnId: 'name', direction: 'asc' }}
              onRowClick={(c) => setEditing({ tab: 'commissions', item: c })}
              rowActions={(c) => actions('commissions', c.id, c.name, () => setEditing({ tab: 'commissions', item: c }))}
              empty={
                <EmptyState
                  size="sm"
                  title="Циклових комісій ще немає"
                  description="У таблиці розкладу їх немає, тому довідник порожній. Додайте комісії й призначте їх викладачам."
                  action={
                    <Button variant="primary" size="sm" onClick={() => setEditing({ tab: 'commissions', item: null })}>
                      Додати комісію
                    </Button>
                  }
                />
              }
            />
          </TabPanel>
        </TabsContent>

        <TabsContent value="buildings">
          <TabPanel
            description="Корпус допомагає розрізнити однакові номери аудиторій і показати, де пара."
            addLabel="Додати корпус"
            onAdd={() => setEditing({ tab: 'buildings', item: null })}
          >
            <DataTable
              caption="Корпуси"
              columns={buildingColumns}
              rows={buildings.items}
              getRowId={(b) => b.id}
              loading={buildings.isPending}
              error={buildings.error}
              onRetry={() => void buildings.refetch()}
              defaultSort={{ columnId: 'name', direction: 'asc' }}
              onRowClick={(b) => setEditing({ tab: 'buildings', item: b })}
              rowActions={(b) => actions('buildings', b.id, b.name, () => setEditing({ tab: 'buildings', item: b }))}
              empty={
                <EmptyState
                  size="sm"
                  title="Корпусів ще немає"
                  description="У документах коледжу корпуси не вказані. Додайте їх, щоб привʼязати аудиторії."
                  action={
                    <Button variant="primary" size="sm" onClick={() => setEditing({ tab: 'buildings', item: null })}>
                      Додати корпус
                    </Button>
                  }
                />
              }
            />
          </TabPanel>
        </TabsContent>
      </Tabs>

      {editing?.tab === 'departments' ? (
        <DepartmentFormDialog key={editing.item?.id ?? 'new'} item={editing.item} open onOpenChange={(open) => !open && setEditing(null)} />
      ) : null}
      {editing?.tab === 'specialties' ? (
        <SpecialtyFormDialog key={editing.item?.id ?? 'new'} item={editing.item} open onOpenChange={(open) => !open && setEditing(null)} />
      ) : null}
      {editing?.tab === 'commissions' ? (
        <NamedFormDialog
          key={editing.item?.id ?? 'new'}
          entity="cycle-commissions"
          item={editing.item}
          open
          onOpenChange={(open) => !open && setEditing(null)}
        />
      ) : null}
      {editing?.tab === 'buildings' ? (
        <NamedFormDialog key={editing.item?.id ?? 'new'} entity="buildings" item={editing.item} open onOpenChange={(open) => !open && setEditing(null)} />
      ) : null}
      <DeleteDialog
        target={deleting}
        onClose={() => setDeleting(null)}
        title={deleting ? DELETE_TEXT[deleting.tab].title : ''}
        description={deleting ? DELETE_TEXT[deleting.tab].description : undefined}
        onDelete={(id) => (deleting ? removers[deleting.tab].mutateAsync(id) : Promise.resolve())}
        successMessage={(name) => `Видалено: ${name}`}
      />
    </div>
  );
}
