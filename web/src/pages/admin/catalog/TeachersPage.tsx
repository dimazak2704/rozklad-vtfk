import { CalendarDays, Pencil, Plus, Power, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { paths } from '@/app/routes';
import { useCatalogList, useCatalogOptions, useDeleteCatalogItem, type CatalogTeacher } from '@/api/catalog';
import { CatalogNav, CatalogTitle } from '@/components/catalog/CatalogNav';
import { ActiveFilterControl, CatalogToolbar, FilterField } from '@/components/catalog/CatalogToolbar';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { informativeColumns } from '@/components/catalog/columns';
import { EmptyValue } from '@/components/catalog/FormParts';
import { activeParam, CATALOG_PAGE_SIZE, EMPLOYMENT_LABEL, toActiveFilter } from '@/components/catalog/labels';
import { teacherRequest } from '@/components/catalog/requests';
import { TeacherFormDialog } from '@/components/catalog/TeacherForm';
import { useRowCursor } from '@/components/catalog/useRowCursor';
import { useTableUrlState } from '@/components/catalog/useTableUrlState';
import { useToggleActive } from '@/components/catalog/useToggleActive';
import {
  Button,
  DataTable,
  EmptyState,
  PageHeader,
  Panel,
  RowActions,
  Tooltip,
  Select,
  Switch,
  notify,
  type DataTableColumn,
} from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useHotkeys } from '@/hooks/useHotkeys';
import { openScheduleHint } from '@/lib/labels';
import { toSortParam } from '@/lib/sort';
import { formatTeacherName } from '@/lib/teacher-name';
import { useRegisterCommands } from '@/stores/commands';

const NOUN = ['викладач', 'викладачі', 'викладачів'] as const;
const ALL = 'all';

/**
 * Підпис під іменем: як викладача написано в розкладі («Кренцін М.Д.»), коли рядок уже називає його
 * «Прізвище Імʼя»; без імені назва рядка й так з ініціалами — підпису немає. По батькові — у формі.
 */
function scheduleName(t: CatalogTeacher): string | null {
  return t.firstName ? t.shortName : null;
}

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
    active ? `Викладача ${t.shortName} повернуто до списку` : `Викладача ${t.shortName} позначено неактивним`,
  );
  const [editing, setEditing] = useState<CatalogTeacher | 'new' | null>(null);
  const [deleting, setDeleting] = useState<CatalogTeacher | null>(null);

  const openNew = () => setEditing('new');
  useHotkeys([{ keys: 'n', handler: openNew, description: 'Новий викладач', group: 'Викладачі' }]);
  const cursor = useRowCursor({
    rows: list.data?.items,
    getId: (row) => row.id,
    onOpen: (row) => setEditing(row),
    onDelete: (row) => setDeleting(row),
    group: 'Викладачі',
    enabled: editing === null && deleting === null,
  });
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
            <span className="flex flex-wrap items-baseline gap-x-2">
              <span className="font-[650]">{formatTeacherName(t, 'full')}</span>
              {t.active ? null : <span className="text-caption font-normal text-muted">неактивний</span>}
              {t.needsReview ? <span className="text-caption font-normal text-change">перевірити дані</span> : null}
            </span>
            {scheduleName(t) ? <span className="text-caption font-normal text-muted">{scheduleName(t)}</span> : null}
          </span>
        ),
      },
      {
        id: 'displayName',
        header: 'На сайті',
        sortable: true,
        hideBelow: 'md',
        className: 'max-md:hidden!',
        // типово на сайті — прізвище, і воно вже є в назві рядка; показуємо лише інше написання
        cell: (t) => (t.displayName === t.lastName ? null : t.displayName),
      },
      {
        id: 'commission',
        header: 'Циклова комісія',
        hideBelow: 'lg',
        className: 'max-md:hidden!',
        cell: (t) => t.cycleCommission?.label ?? <EmptyValue />,
      },
      { id: 'employment', header: 'Зайнятість', sortable: true, hideBelow: 'lg', className: 'max-md:hidden!', cell: (t) => EMPLOYMENT_LABEL[t.employment] },
      {
        id: 'linked',
        header: 'Вхід у систему',
        hideBelow: 'sm',
        cell: (t) => (t.linked ? <span>Так</span> : <span className="text-muted">—</span>),
      },
    ],
    [],
  );

  const data = list.data;
  const visibleColumns = useMemo(
    () =>
      informativeColumns(columns, data?.items, {
        displayName: (t) => (t.displayName === t.lastName ? null : t.displayName),
        commission: (t) => t.cycleCommission?.label,
        employment: (t) => t.employment,
        linked: (t) => t.linked,
      }),
    [columns, data?.items],
  );
  const commissionOptions = [{ value: ALL, label: 'Усі комісії' }, ...(commissions.items ?? []).map((c) => ({ value: c.id, label: c.name }))];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title={<CatalogTitle section="Викладачі" />}
        primaryAction={
          <Tooltip content={null} shortcut="n">
            <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={openNew}>
              Додати викладача
            </Button>
          </Tooltip>
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
        activeFilters={(table.get('commission') ? 1 : 0) + (review ? 1 : 0) + (unlinked ? 1 : 0) + (table.get('active') ? 1 : 0)}
      >
        {commissionOptions.length > 1 ? (
          <FilterField label="Циклова комісія">
            <Select<string>
              aria-label="Циклова комісія"
              className="w-full"
              value={table.get('commission') ?? ALL}
              onValueChange={(value) => table.set({ commission: value === ALL ? null : value })}
              options={commissionOptions}
            />
          </FilterField>
        ) : null}
        <Switch label="Лише ті, де треба перевірити дані" checked={review} onCheckedChange={(checked) => table.set({ review: checked ? '1' : null })} />
        <Switch label="Лише без облікового запису" checked={unlinked} onCheckedChange={(checked) => table.set({ unlinked: checked ? '1' : null })} />
        <FilterField label="Стан">
          <ActiveFilterControl
            value={toActiveFilter(table.get('active'))}
            onChange={(value) => table.set({ active: value === 'all' ? null : value })}
          />
        </FilterField>
      </CatalogToolbar>
      {/* коли лишається одна-дві колонки, таблиця на всю ширину — короткі прізвища в порожній смузі */}
      <Panel flush className={visibleColumns.length <= 2 ? 'w-full max-w-2xl' : undefined}>
      <DataTable
        caption="Викладачі коледжу"
        columns={visibleColumns}
        rows={data?.items}
        getRowId={(t) => t.id}
        loading={list.isPending || list.isPlaceholderData}
        error={list.error}
        onRetry={() => void list.refetch()}
        manualSorting
        sort={table.sort}
        onSortChange={table.setSort}
        selectedRowId={cursor.selectedRowId}
        onRowClick={(t) => {
          cursor.setCursor(t.id);
          setEditing(t);
        }}
        rowClassName={(t) => (t.active ? undefined : 'text-muted')}
        // на телефоні — рядок списку: «⋯» по центру поруч з іменем, без порожніх рядків сітки стосу
        mobileRow={(t) => ({
          title: formatTeacherName(t, 'full'),
          caption: scheduleName(t) ?? t.cycleCommission?.label ?? null,
          status: t.needsReview ? (
            <span className="text-change">перевірити дані</span>
          ) : t.active ? null : (
            <span className="text-muted">неактивний</span>
          ),
        })}
        rowActions={(t) => (
          <RowActions
            rowLabel={t.shortName}
            actions={[
              { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, shortcut: 'e', onSelect: () => setEditing(t) },
              { label: openScheduleHint('TEACHER'), icon: <CalendarDays aria-hidden="true" />, onSelect: () => void navigate(paths.teacher(t.id)) },
              {
                label: t.active ? 'Позначити неактивним' : 'Зробити активним',
                icon: <Power aria-hidden="true" />,
                onSelect: () => void toggleActive(t).catch((error: unknown) => notify.error(error, 'Не вдалося змінити стан викладача')),
              },
              { label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger', separatorBefore: true, shortcut: 'delete', onSelect: () => setDeleting(t) },
            ]}
          />
        )}
        pagination={data ? { page: data.page, size: data.size, total: data.total, onPageChange: table.setPage, noun: 'викладачів' } : undefined}
        empty={
          table.filtered ? (
            <EmptyState
              size="sm"
              title={table.q ? `«${table.q}» не знайдено.` : 'За цим фільтром нікого немає.'}
              description={table.q ? 'Пошук враховує й псевдоніми з таблиць.' : undefined}
              action={
                <Button variant="ghost" size="sm" onClick={table.reset}>
                  Показати всіх
                </Button>
              }
            />
          ) : (
            <EmptyState
              size="sm"
              title="Викладачів ще немає."
              description="Імпортуйте таблицю розкладу — прізвища з неї стануть викладачами."
              action={
                <Button variant="ghost" size="sm" onClick={openNew}>
                  Додати викладача вручну
                </Button>
              }
            />
          )
        }
      />
      </Panel>

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
        successMessage={(name) => `Видалено викладача ${name}`}
        deactivate={
          deleting?.active
            ? {
                label: 'Позначити неактивним',
                run: () => toggleActive(deleting),
              }
            : undefined
        }
      />
    </div>
  );
}
