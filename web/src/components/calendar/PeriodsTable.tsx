import { Pencil, Plus, Tags, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { useCalendarPeriods, useDeleteCalendarPeriod, type CalendarPeriod, type CalendarSemester } from '@/api/calendar';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { EmptyValue } from '@/components/catalog/FormParts';
import { Button, buttonClass, DataTable, EmptyState, RowActions, Section, Tooltip, type DataTableColumn } from '@/components/ui';
import { formatNumericDate } from '@/lib/date';
import { KindSwatch } from './KindSwatch';
import { PeriodFormDialog } from './PeriodForm';
import { periodAudience, periodKindLabel, periodKindOf, supervisorsLabel } from './periods';

interface PeriodsTableProps {
  semester: CalendarSemester;
  /** Відкрити період ззовні (клік по смузі на шкалі семестру). */
  opened: CalendarPeriod | 'new' | null;
  onOpen: (period: CalendarPeriod | 'new' | null) => void;
}

/**
 * Періоди семестру таблицею: вид (крапка — колір виду з графіка або тон ефекту), назва, дати, кого стосується,
 * примітка для розпорядження. У періоду «група не на парах» під назвою — аудиторія й керівники.
 */
export function PeriodsTable({ semester, opened, onOpen }: PeriodsTableProps) {
  const periods = useCalendarPeriods(semester.id);
  const remove = useDeleteCalendarPeriod();
  const [deleting, setDeleting] = useState<CalendarPeriod | null>(null);

  const columns = useMemo<Array<DataTableColumn<CalendarPeriod>>>(
    () => [
      {
        id: 'kind',
        header: 'Вид',
        width: '10rem',
        sortValue: (p) => periodKindLabel(p),
        // вид словом; колір — лише крапка, як на смузі семестру
        cell: (p) => {
          const kind = periodKindOf(p);
          return (
            <span className="inline-flex items-baseline gap-2">
              <KindSwatch color={kind.color} effect={kind.effect} className="translate-y-[-1px]" />
              {/* для форми навчання вистачає самої форми: «Дистанційно», а не «Форма навчання, дистанційно» */}
              <span>{periodKindLabel(p)}</span>
            </span>
          );
        },
      },
      {
        id: 'title',
        header: 'Назва',
        rowHeader: true,
        sortValue: (p) => p.title,
        // у періоду «група не на парах» під назвою аудиторія й керівники; без керівників видимого другого рядка немає
        // (рядок з одним «—» під кожною назвою лише шумів), а читач екрана чує «керівника не призначено»
        cell: (p) =>
          periodKindOf(p).effect === 'SUSPEND' ? (
            <span className="flex flex-col gap-0.5">
              <span>
                {p.title}
                {p.room ? <span className="text-secondary">, ауд. {p.room.name}</span> : null}
              </span>
              {p.supervisors && p.supervisors.length > 0 ? (
                <span className="text-caption text-secondary">{supervisorsLabel(p.supervisors)}</span>
              ) : (
                <span className="sr-only">керівника не призначено</span>
              )}
            </span>
          ) : (
            p.title
          ),
      },
      {
        id: 'dates',
        header: 'Дати',
        width: '12rem',
        sortValue: (p) => p.startsOn,
        className: 'figures whitespace-nowrap',
        cell: (p) => `${formatNumericDate(p.startsOn)} – ${formatNumericDate(p.endsOn)}`,
      },
      { id: 'scope', header: 'Кого стосується', hideBelow: 'md', cell: (p) => periodAudience(p) },
      {
        id: 'note',
        header: 'Примітка в розпорядженні',
        hideBelow: 'lg',
        className: 'max-md:hidden!',
        cell: (p) =>
          p.orderNote ? (
            <Tooltip content={p.orderNote}>
              <span tabIndex={0} className="line-clamp-2 max-w-[28rem] text-caption text-secondary">
                {p.orderNote}
              </span>
            </Tooltip>
          ) : (
            <EmptyValue label="без примітки" />
          ),
      },
    ],
    [],
  );

  return (
    <Section
      title="Періоди"
      headingLevel={3}
      actions={
        <span className="flex flex-wrap gap-2">
          <Link to="/admin/period-kinds" className={buttonClass({ variant: 'ghost', size: 'sm' })}>
            <Tags aria-hidden="true" className="size-4" />
            Види періодів
          </Link>
          <Button size="sm" variant="secondary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={() => onOpen('new')}>
            Додати період
          </Button>
        </span>
      }
    >
      <DataTable
        caption={`Календарні періоди: ${semester.name}`}
        columns={columns}
        rows={periods.data}
        getRowId={(p) => p.id}
        loading={periods.isPending}
        error={periods.error}
        onRetry={() => void periods.refetch()}
        defaultSort={{ columnId: 'dates', direction: 'asc' }}
        onRowClick={(p) => onOpen(p)}
        rowActions={(p) => (
          <RowActions
            rowLabel={p.title}
            actions={[
              { label: 'Редагувати', icon: <Pencil aria-hidden="true" />, onSelect: () => onOpen(p) },
              { label: 'Видалити', icon: <Trash aria-hidden="true" />, tone: 'danger', separatorBefore: true, onSelect: () => setDeleting(p) },
            ]}
          />
        )}
        empty={
          <EmptyState
            size="sm"
            title="Періодів немає."
            description="Канікули, практика, НС чи дистанційне навчання змінюють заняття на свої дати й додають примітку в розпорядження."
            action={
              <Button size="sm" variant="ghost" onClick={() => onOpen('new')}>
                Додати період
              </Button>
            }
          />
        }
      />
      {opened ? (
        <PeriodFormDialog
          key={opened === 'new' ? 'new' : `${opened.id}-${opened.version}`}
          semester={semester}
          period={opened === 'new' ? null : opened}
          open
          onOpenChange={(open) => {
            if (!open) onOpen(null);
          }}
        />
      ) : null}
      <DeleteDialog
        target={deleting ? { id: deleting.id, name: `${periodKindOf(deleting).name}: ${deleting.title}` } : null}
        onClose={() => setDeleting(null)}
        title="Видалити період?"
        description="Заняття на ці дати повернуться до звичайного розкладу, примітка зникне з нових розпоряджень."
        onDelete={(id) => remove.mutateAsync(id)}
        successMessage={(name) => `Видалено: ${name}`}
      />
    </Section>
  );
}
