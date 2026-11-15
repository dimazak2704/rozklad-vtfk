import { Pencil, Plus, Power, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { periodKindRequest, useDeletePeriodKind, usePeriodKinds, useSavePeriodKind } from '@/api/period-kinds';
import { qk } from '@/api/queryKeys';
import type { PeriodKindDto } from '@/api/types';
import { KindSwatch } from '@/components/calendar/KindSwatch';
import { EFFECT_LABEL } from '@/components/calendar/labels';
import { PeriodKindFormDialog } from '@/components/calendar/PeriodKindForm';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { Button, DataTable, EmptyState, PageHeader, Panel, RowActions, Tooltip, notify, type DataTableColumn } from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { countLabel } from '@/lib/labels';
import { track } from '@/lib/undo/actions';
import { useRegisterCommands } from '@/stores/commands';

const KINDS = ['вид', 'види', 'видів'] as const;
const PERIODS = ['період', 'періоди', 'періодів'] as const;

/** «15 видів · 1 вимкнено» — під заголовком лише факт про дані. */
function kindsSentence(kinds: readonly PeriodKindDto[]): string {
  const off = kinds.filter((k) => !k.active).length;
  return `${countLabel(kinds.length, KINDS)}${off > 0 ? ` · ${off} вимкнено` : ''}`;
}

/**
 * Довідник «Види періодів»: код як у графіку освітнього процесу, назва, колір і що вид робить із парами.
 * Новий вид, як НС цього року, диспетчер додає тут — без програміста.
 */
export default function PeriodKindsPage() {
  const kinds = usePeriodKinds();
  const save = useSavePeriodKind();
  const remove = useDeletePeriodKind();
  const [editing, setEditing] = useState<PeriodKindDto | 'new' | null>(null);
  const [deleting, setDeleting] = useState<PeriodKindDto | null>(null);

  const openNew = () => setEditing('new');
  useHotkeys([{ keys: 'n', handler: openNew, description: 'Новий вид періоду', group: 'Види періодів', enabled: editing === null }]);
  useRegisterCommands([{ id: 'new-period-kind', label: 'Додати вид періоду', group: 'Види періодів', icon: Plus, shortcut: 'n', perform: openNew }]);

  /** Увімкнути чи вимкнути вид однією дією, зі «Скасувати» в журналі. */
  const toggleActive = async (kind: PeriodKindDto) => {
    const next = !kind.active;
    let version = (await save.mutateAsync({ id: kind.id, body: { ...periodKindRequest(kind), active: next } })).version;
    const put = async (active: boolean) => {
      version = (await save.mutateAsync({ id: kind.id, body: { ...periodKindRequest(kind), active, version } })).version;
    };
    track({
      label: next ? `Вид «${kind.code}» знову можна вибирати` : `Вид «${kind.code}» вимкнено`,
      invalidate: [qk.admin.calendar.all],
      undo: () => put(kind.active),
      redo: () => put(next),
    });
  };

  const columns = useMemo<Array<DataTableColumn<PeriodKindDto>>>(
    () => [
      {
        id: 'code',
        header: 'Код',
        rowHeader: true,
        width: '7rem',
        cell: (kind) => (
          <span className="inline-flex items-center gap-2">
            <KindSwatch color={kind.color} effect={kind.effect} />
            <span className="font-semibold">{kind.code}</span>
          </span>
        ),
      },
      {
        id: 'name',
        header: 'Назва',
        cell: (kind) => (
          <span className="flex flex-col gap-0.5">
            <span>
              {kind.name}
              {kind.active ? null : <span className="text-caption text-muted"> · вимкнено</span>}
            </span>
            {kind.aliases.length > 0 ? <span className="text-caption text-secondary">Також: {kind.aliases.join(', ')}</span> : null}
          </span>
        ),
      },
      { id: 'effect', header: 'Що робить із парами', hideBelow: 'sm', cell: (kind) => EFFECT_LABEL[kind.effect] },
      {
        id: 'usage',
        header: 'Періодів',
        numeric: true,
        width: '7rem',
        cell: (kind) => (kind.usage > 0 ? kind.usage : <span className="text-muted">0</span>),
      },
      {
        id: 'builtin',
        header: 'Звідки',
        hideBelow: 'md',
        width: '9rem',
        cell: (kind) => <span className="text-secondary">{kind.builtin ? 'Вбудований' : 'Доданий'}</span>,
      },
    ],
    [],
  );

  const list = kinds.data;
  // «Звідки» мовчить, поки всі види вбудовані: колонка з однаковим словом у кожному рядку нічого не каже
  const visibleColumns = useMemo(
    () => (list?.some((k) => !k.builtin) ? columns : columns.filter((column) => column.id !== 'builtin')),
    [columns, list],
  );

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Види періодів"
        meta={list ? kindsSentence(list) : undefined}
        primaryAction={
          <Tooltip content={null} shortcut="n">
            <Button variant="primary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={openNew}>
              Додати вид
            </Button>
          </Tooltip>
        }
      />
      <Panel flush>
        <DataTable
          caption="Види періодів навчального процесу"
          columns={visibleColumns}
          rows={list}
          getRowId={(kind) => kind.id}
          loading={kinds.isPending}
          error={kinds.error}
          onRetry={() => void kinds.refetch()}
          onRowClick={(kind) => setEditing(kind)}
          rowClassName={(kind) => (kind.active ? undefined : 'text-muted')}
          mobileRow={(kind) => ({
            title: (
              <span className="inline-flex items-center gap-2">
                <KindSwatch color={kind.color} effect={kind.effect} />
                <span>
                  {kind.code} · {kind.name}
                </span>
              </span>
            ),
            caption: `${EFFECT_LABEL[kind.effect]}${kind.usage > 0 ? ` · ${countLabel(kind.usage, PERIODS)}` : ''}`,
            status: kind.active ? null : <span className="text-muted">вимкнено</span>,
          })}
          rowActions={(kind) => (
            <RowActions
              rowLabel={`вид ${kind.code}`}
              actions={[
                { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, onSelect: () => setEditing(kind) },
                {
                  label: kind.active ? 'Вимкнути' : 'Увімкнути',
                  icon: <Power aria-hidden="true" />,
                  onSelect: () => void toggleActive(kind).catch((error: unknown) => notify.error(error, 'Не вдалося змінити стан виду')),
                },
                // вбудований вид сервер не видаляє ніколи (лише вимкнути): не пропонуємо марну дію
                ...(kind.builtin
                  ? []
                  : [{ label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger' as const, separatorBefore: true, onSelect: () => setDeleting(kind) }]),
              ]}
            />
          )}
          empty={
            <EmptyState
              size="sm"
              title="Видів періодів ще немає."
              description="Канікули, практика, НС: вид каже, що період робить із парами."
              action={
                <Button size="sm" variant="ghost" onClick={openNew}>
                  Додати вид
                </Button>
              }
            />
          }
        />
      </Panel>
      {editing ? (
        <PeriodKindFormDialog
          key={editing === 'new' ? 'new' : `${editing.id}-${editing.version}`}
          kind={editing === 'new' ? null : editing}
          open
          onOpenChange={(open) => {
            if (!open) setEditing(null);
          }}
        />
      ) : null}
      <DeleteDialog
        target={deleting ? { id: deleting.id, name: `${deleting.code} · ${deleting.name}` } : null}
        onClose={() => setDeleting(null)}
        title="Видалити вид періоду?"
        description="Вид зникне з довідника, а імпорт графіка перестане розпізнавати його код."
        onDelete={(id) => remove.mutateAsync(id)}
        successMessage={(name) => `Видалено вид ${name}`}
        deactivate={
          deleting?.active
            ? {
                label: 'Вимкнути вид',
                run: () => toggleActive(deleting),
              }
            : undefined
        }
      />
    </div>
  );
}
