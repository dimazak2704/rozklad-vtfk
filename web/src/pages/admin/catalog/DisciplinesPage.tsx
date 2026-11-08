import { Pencil, Plus, Power, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useCatalogList, useDeleteCatalogItem, type CatalogDiscipline } from '@/api/catalog';
import { CatalogNav } from '@/components/catalog/CatalogNav';
import { ActiveFilterControl, CatalogToolbar } from '@/components/catalog/CatalogToolbar';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { DisciplineFormDialog } from '@/components/catalog/DisciplineForm';
import { lineName, lineOf } from '@/components/catalog/discipline-palette';
import { activeParam, CATALOG_PAGE_SIZE, toActiveFilter } from '@/components/catalog/labels';
import { disciplineRequest } from '@/components/catalog/requests';
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
  StatusDot,
  Tooltip,
  notify,
  type DataTableColumn,
} from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useHotkeys } from '@/hooks/useHotkeys';
import { toSortParam } from '@/lib/sort';
import { useRegisterCommands } from '@/stores/commands';

const NOUN = ['дисципліна', 'дисципліни', 'дисциплін'] as const;

/** Довідник дисциплін: скорочення з розкладу, повні назви, колір лінії, псевдоніми. */
export default function DisciplinesPage() {
  const table = useTableUrlState({ columnId: 'shortName', direction: 'asc' }, ['review', 'active']);
  const q = useDebouncedValue(table.q.trim(), 250);
  const review = table.get('review') === '1';
  const list = useCatalogList('disciplines', {
    q,
    page: table.page,
    size: CATALOG_PAGE_SIZE,
    sort: toSortParam(table.sort),
    needsReview: review ? true : undefined,
    active: activeParam(table.get('active')),
  });
  const reviewCount = useCatalogList('disciplines', { needsReview: true, size: 1 });
  const remove = useDeleteCatalogItem('disciplines');
  const toggleActive = useToggleActive('disciplines', disciplineRequest, (d, active) =>
    active ? `Дисципліна ${d.shortName} знову викладається` : `Дисципліну ${d.shortName} позначено неактивною`,
  );
  const [editing, setEditing] = useState<CatalogDiscipline | 'new' | null>(null);
  const [deleting, setDeleting] = useState<CatalogDiscipline | null>(null);

  const openNew = () => setEditing('new');
  useHotkeys([{ keys: 'n', handler: openNew, description: 'Нова дисципліна', group: 'Дисципліни' }]);
  useRegisterCommands([{ id: 'new-discipline', label: 'Додати дисципліну', group: 'Дисципліни', icon: Plus, shortcut: 'n', perform: openNew }]);

  const columns = useMemo<Array<DataTableColumn<CatalogDiscipline>>>(
    () => [
      {
        id: 'shortName',
        header: 'Скорочення',
        sortable: true,
        rowHeader: true,
        width: '11rem',
        cell: (d) => {
          const line = lineOf(d.id, d.color);
          return (
            <span className="flex items-center gap-2">
              <Tooltip content={d.color ? `Лінія: ${lineName(line)}` : `Лінія: ${lineName(line)} (автоматично)`}>
                <span
                  data-line={line}
                  role="img"
                  aria-label={`Колір лінії: ${lineName(line)}`}
                  className="size-2.5 shrink-0 rounded-full bg-[var(--line-dot)]"
                />
              </Tooltip>
              <span className="font-condensed text-body font-bold">{d.shortName}</span>
            </span>
          );
        },
      },
      {
        id: 'name',
        header: 'Повна назва',
        sortable: true,
        cell: (d) => (
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className={d.needsReview ? 'text-secondary' : undefined}>{d.name}</span>
            {d.needsReview ? (
              <Badge tone="warning" size="sm">
                Перевірити назву
              </Badge>
            ) : null}
          </span>
        ),
      },
      {
        id: 'active',
        header: 'Стан',
        sortable: true,
        width: '9rem',
        hideBelow: 'sm',
        cell: (d) =>
          d.active ? <StatusDot tone="add" label="Викладається" showLabel /> : <StatusDot tone="neutral" label="Неактивна" showLabel />,
      },
    ],
    [],
  );

  const data = list.data;
  const needsReviewTotal = reviewCount.data?.total;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Дисципліни"
        description="Скорочення з розкладу й розпоряджень, повні назви з навчального плану, колір лінії в розкладі."
        actions={
          <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={openNew}>
            Додати дисципліну
          </Button>
        }
      />
      <CatalogNav />
      <CatalogToolbar
        search={table.q}
        onSearch={(value) => table.set({ q: value })}
        placeholder="Скорочення, назва або псевдонім"
        searchLabel="Пошук дисципліни"
        total={data?.total}
        noun={NOUN}
        filtered={table.filtered}
        onReset={table.reset}
      >
        <Chip selected={review} onToggle={() => table.set({ review: review ? null : '1' })} count={needsReviewTotal}>
          Перевірити назву
        </Chip>
        <ActiveFilterControl
          value={toActiveFilter(table.get('active'))}
          onChange={(value) => table.set({ active: value === 'all' ? null : value })}
        />
      </CatalogToolbar>
      <DataTable
        caption="Дисципліни"
        columns={columns}
        rows={data?.items}
        getRowId={(d) => d.id}
        loading={list.isPending || list.isPlaceholderData}
        error={list.error}
        onRetry={() => void list.refetch()}
        manualSorting
        sort={table.sort}
        onSortChange={table.setSort}
        onRowClick={(d) => setEditing(d)}
        rowClassName={(d) => (d.active ? undefined : 'text-muted')}
        rowActions={(d) => (
          <RowActions
            rowLabel={`дисципліна ${d.shortName}`}
            actions={[
              { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, onSelect: () => setEditing(d) },
              {
                label: d.active ? 'Позначити неактивною' : 'Зробити активною',
                icon: <Power aria-hidden="true" />,
                onSelect: () => void toggleActive(d).catch((error: unknown) => notify.error(error, 'Не вдалося змінити стан дисципліни')),
              },
              { label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger', separatorBefore: true, onSelect: () => setDeleting(d) },
            ]}
          />
        )}
        pagination={data ? { page: data.page, size: data.size, total: data.total, onPageChange: table.setPage, noun: 'дисциплін' } : undefined}
        empty={
          table.filtered ? (
            <EmptyState
              size="sm"
              title="Нічого не знайдено"
              description="Спробуйте інше написання або скиньте фільтри."
              action={
                <Button variant="secondary" size="sm" onClick={table.reset}>
                  Скинути фільтри
                </Button>
              }
            />
          ) : (
            <EmptyState
              size="sm"
              title="Дисциплін ще немає"
              description="Імпортуйте таблицю розкладу — скорочення з неї стануть дисциплінами, або додайте першу вручну."
              action={
                <Button variant="primary" size="sm" onClick={openNew}>
                  Додати дисципліну
                </Button>
              }
            />
          )
        }
      />

      {editing ? (
        <DisciplineFormDialog
          key={editing === 'new' ? 'new' : editing.id}
          discipline={editing === 'new' ? null : editing}
          open
          onOpenChange={(open) => {
            if (!open) setEditing(null);
          }}
        />
      ) : null}
      <DeleteDialog
        target={deleting ? { id: deleting.id, name: `${deleting.shortName} — ${deleting.name}` } : null}
        onClose={() => setDeleting(null)}
        title="Видалити дисципліну?"
        description="Разом з дисципліною зникнуть її псевдоніми й компетенції викладачів. Дисципліну з розкладу чи навантаження видалити не можна."
        onDelete={(id) => remove.mutateAsync(id)}
        successMessage={(name) => `Видалено: ${name}`}
        deactivate={
          deleting?.active
            ? {
                label: 'Позначити неактивною',
                run: () => toggleActive(deleting, { undo: false }),
                successMessage: (name) => `Позначено неактивною: ${name}`,
              }
            : undefined
        }
      />
    </div>
  );
}
