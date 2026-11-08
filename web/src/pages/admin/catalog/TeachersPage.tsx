import { CalendarDays, Pencil, Plus, Power, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { paths } from '@/app/routes';
import { useCatalogList, useCatalogOptions, useDeleteCatalogItem, type CatalogTeacher } from '@/api/catalog';
import { CatalogNav } from '@/components/catalog/CatalogNav';
import { ActiveFilterControl, CatalogToolbar } from '@/components/catalog/CatalogToolbar';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { EmptyValue } from '@/components/catalog/FormParts';
import { activeParam, CATALOG_PAGE_SIZE, EMPLOYMENT_LABEL, toActiveFilter } from '@/components/catalog/labels';
import { teacherRequest } from '@/components/catalog/requests';
import { TeacherFormDialog } from '@/components/catalog/TeacherForm';
import { useTableUrlState } from '@/components/catalog/useTableUrlState';
import { useToggleActive } from '@/components/catalog/useToggleActive';
import {
  Badge,
  Button,
  Chip,
  DataTable,
  EmptyState,
  PageHeader,
  RowActions,
  Select,
  StatusDot,
  notify,
  type DataTableColumn,
} from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useHotkeys } from '@/hooks/useHotkeys';
import { toSortParam } from '@/lib/sort';
import { useRegisterCommands } from '@/stores/commands';

const NOUN = ['викладач', 'викладачі', 'викладачів'] as const;
const ALL = 'all';

/** Довідник викладачів: імена для розкладу й сайту, комісії, псевдоніми з таблиць, дисципліни для замін. */
export default function TeachersPage() {
  const navigate = useNavigate();
  const table = useTableUrlState({ columnId: 'shortName', direction: 'asc' }, ['commission', 'review', 'unlinked', 'active']);
  const q = useDebouncedValue(table.q.trim(), 250);
  const review = table.get('review') === '1';
  const unlinked = table.get('unlinked') === '1';
  const list = useCatalogList('teachers', {
    q,
    page: table.page,
    size: CATALOG_PAGE_SIZE,
    sort: toSortParam(table.sort),
    cycleCommissionId: table.get('commission'),
    needsReview: review ? true : undefined,
    linked: unlinked ? false : undefined,
    active: activeParam(table.get('active')),
  });
  const commissions = useCatalogOptions('cycle-commissions');
  const remove = useDeleteCatalogItem('teachers');
  const toggleActive = useToggleActive('teachers', teacherRequest, (t, active) =>
    active ? `${t.shortName}: знову в списку викладачів` : `${t.shortName}: позначено неактивним`,
  );
  const [editing, setEditing] = useState<CatalogTeacher | 'new' | null>(null);
  const [deleting, setDeleting] = useState<CatalogTeacher | null>(null);

  const openNew = () => setEditing('new');
  useHotkeys([{ keys: 'n', handler: openNew, description: 'Новий викладач', group: 'Викладачі' }]);
  useRegisterCommands([{ id: 'new-teacher', label: 'Додати викладача', group: 'Викладачі', icon: Plus, shortcut: 'n', perform: openNew }]);

  const columns = useMemo<Array<DataTableColumn<CatalogTeacher>>>(
    () => [
      {
        id: 'shortName',
        header: 'Викладач',
        sortable: true,
        rowHeader: true,
        cell: (t) => (
          <span className="flex flex-col">
            <span>{t.shortName}</span>
            {[t.lastName, t.firstName, t.middleName].filter(Boolean).length > 1 ? (
              <span className="text-caption font-normal text-muted">{[t.lastName, t.firstName, t.middleName].filter(Boolean).join(' ')}</span>
            ) : null}
          </span>
        ),
      },
      { id: 'displayName', header: 'На сайті', sortable: true, hideBelow: 'md', cell: (t) => t.displayName },
      {
        id: 'commission',
        header: 'Циклова комісія',
        hideBelow: 'lg',
        cell: (t) => t.cycleCommission?.label ?? <EmptyValue />,
      },
      { id: 'employment', header: 'Зайнятість', sortable: true, hideBelow: 'lg', cell: (t) => EMPLOYMENT_LABEL[t.employment] },
      {
        id: 'linked',
        header: 'Вхід у систему',
        hideBelow: 'sm',
        cell: (t) =>
          t.linked ? (
            <Badge tone="accent" size="sm">
              Має обліковий запис
            </Badge>
          ) : (
            <span className="text-caption text-muted">Немає</span>
          ),
      },
      {
        id: 'active',
        header: 'Стан',
        sortable: true,
        width: '10rem',
        cell: (t) => (
          <span className="flex flex-wrap items-center gap-1.5">
            {t.active ? <StatusDot tone="add" label="Працює" showLabel /> : <StatusDot tone="neutral" label="Неактивний" showLabel />}
            {t.needsReview ? (
              <Badge tone="warning" size="sm">
                Перевірити
              </Badge>
            ) : null}
          </span>
        ),
      },
    ],
    [],
  );

  const data = list.data;
  const commissionOptions = [{ value: ALL, label: 'Усі комісії' }, ...(commissions.items ?? []).map((c) => ({ value: c.id, label: c.name }))];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Викладачі"
        description="Імена так, як їх пишуть у розкладі й на сайті, циклові комісії, псевдоніми з таблиць і дисципліни для підбору замін."
        actions={
          <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={openNew}>
            Додати викладача
          </Button>
        }
      />
      <CatalogNav />
      <CatalogToolbar
        search={table.q}
        onSearch={(value) => table.set({ q: value })}
        placeholder="Прізвище або псевдонім"
        searchLabel="Пошук викладача"
        total={data?.total}
        noun={NOUN}
        filtered={table.filtered}
        onReset={table.reset}
      >
        {commissionOptions.length > 1 ? (
          <Select<string>
            aria-label="Циклова комісія"
            size="sm"
            className="w-52"
            value={table.get('commission') ?? ALL}
            onValueChange={(value) => table.set({ commission: value === ALL ? null : value })}
            options={commissionOptions}
          />
        ) : null}
        <Chip selected={review} onToggle={() => table.set({ review: review ? null : '1' })}>
          Перевірити дані
        </Chip>
        <Chip selected={unlinked} onToggle={() => table.set({ unlinked: unlinked ? null : '1' })}>
          Без облікового запису
        </Chip>
        <ActiveFilterControl
          value={toActiveFilter(table.get('active'))}
          onChange={(value) => table.set({ active: value === 'all' ? null : value })}
        />
      </CatalogToolbar>
      <DataTable
        caption="Викладачі коледжу"
        columns={columns}
        rows={data?.items}
        getRowId={(t) => t.id}
        loading={list.isPending || list.isPlaceholderData}
        error={list.error}
        onRetry={() => void list.refetch()}
        manualSorting
        sort={table.sort}
        onSortChange={table.setSort}
        onRowClick={(t) => setEditing(t)}
        rowClassName={(t) => (t.active ? undefined : 'text-muted')}
        rowActions={(t) => (
          <RowActions
            rowLabel={t.shortName}
            actions={[
              { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, onSelect: () => setEditing(t) },
              { label: 'Розклад викладача', icon: <CalendarDays aria-hidden="true" />, onSelect: () => void navigate(paths.teacher(t.id)) },
              {
                label: t.active ? 'Позначити неактивним' : 'Зробити активним',
                icon: <Power aria-hidden="true" />,
                onSelect: () => void toggleActive(t).catch((error: unknown) => notify.error(error, 'Не вдалося змінити стан викладача')),
              },
              { label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger', separatorBefore: true, onSelect: () => setDeleting(t) },
            ]}
          />
        )}
        pagination={data ? { page: data.page, size: data.size, total: data.total, onPageChange: table.setPage, noun: 'викладачів' } : undefined}
        empty={
          table.filtered ? (
            <EmptyState
              size="sm"
              title="Нікого не знайдено"
              description="Перевірте прізвище — пошук враховує й псевдоніми з таблиць. Або скиньте фільтри."
              action={
                <Button variant="secondary" size="sm" onClick={table.reset}>
                  Скинути фільтри
                </Button>
              }
            />
          ) : (
            <EmptyState
              size="sm"
              title="Викладачів ще немає"
              description="Імпортуйте таблицю розкладу — прізвища з неї стануть викладачами, або додайте першого вручну."
              action={
                <Button variant="primary" size="sm" onClick={openNew}>
                  Додати викладача
                </Button>
              }
            />
          )
        }
      />

      {editing ? (
        <TeacherFormDialog
          key={editing === 'new' ? 'new' : editing.id}
          teacher={editing === 'new' ? null : editing}
          open
          onOpenChange={(open) => {
            if (!open) setEditing(null);
          }}
        />
      ) : null}
      <DeleteDialog
        target={deleting ? { id: deleting.id, name: deleting.shortName } : null}
        onClose={() => setDeleting(null)}
        title="Видалити викладача?"
        description="Разом з ним зникнуть його псевдоніми й дисципліни. Викладача із заняттями, навантаженням чи кураторством видалити не можна."
        onDelete={(id) => remove.mutateAsync(id)}
        successMessage={(name) => `Викладача ${name} видалено`}
        deactivate={
          deleting?.active
            ? {
                label: 'Позначити неактивним',
                run: () => toggleActive(deleting, { undo: false }),
                successMessage: (name) => `${name}: позначено неактивним`,
              }
            : undefined
        }
      />
    </div>
  );
}
