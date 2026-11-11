import { Pencil, Plus, Trash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useCalendarPeriods, useDeleteCalendarPeriod, type CalendarPeriod, type CalendarSemester } from '@/api/calendar';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { EmptyValue } from '@/components/catalog/FormParts';
import { Button, DataTable, EmptyState, RowActions, Section, Tooltip, type DataTableColumn } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatNumericDate } from '@/lib/date';
import type { CalendarPeriodKind } from '@/api/types';
import { PERIOD_KIND_LABEL, STUDY_MODE_LABEL } from './labels';
import { PeriodFormDialog } from './PeriodForm';
import { periodAudience } from './periods';

const KIND_DOT: Record<CalendarPeriodKind, string> = {
  HOLIDAY: 'bg-[var(--fill-cancel)]',
  NO_CLASSES: 'bg-[var(--fill-cancel)]',
  PRACTICE: 'bg-[var(--fill-change)]',
  STUDY_MODE: 'bg-[var(--fill-room)]',
  EXAM_SESSION: 'bg-accent',
};

interface PeriodsTableProps {
  semester: CalendarSemester;
  /** Відкрити період ззовні (клік по смузі на шкалі семестру). */
  opened: CalendarPeriod | 'new' | null;
  onOpen: (period: CalendarPeriod | 'new' | null) => void;
}

/** Періоди семестру таблицею: вид, назва, дати, кого стосується, примітка для розпорядження. */
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
        sortValue: (p) => PERIOD_KIND_LABEL[p.kind],
        // вид словом; колір — лише крапка, як на смузі семестру
        cell: (p) => (
          <span className="inline-flex items-baseline gap-2">
            <span aria-hidden="true" className={cn('size-2 shrink-0 translate-y-[-1px] rounded-full', KIND_DOT[p.kind])} />
            <span>
              {/* для форми навчання вистачає самої форми: «Дистанційно», а не «Форма навчання, дистанційно» */}
              {p.kind === 'STUDY_MODE' && p.studyMode ? STUDY_MODE_LABEL[p.studyMode] : PERIOD_KIND_LABEL[p.kind]}
            </span>
          </span>
        ),
      },
      { id: 'title', header: 'Назва', rowHeader: true, sortValue: (p) => p.title, cell: (p) => p.title },
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
        <Button size="sm" variant="secondary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={() => onOpen('new')}>
          Додати період
        </Button>
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
            description="Канікули, практика чи дистанційне навчання змінюють заняття на свої дати й додають примітку в розпорядження."
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
        target={deleting ? { id: deleting.id, name: `${PERIOD_KIND_LABEL[deleting.kind]}: ${deleting.title}` } : null}
        onClose={() => setDeleting(null)}
        title="Видалити період?"
        description="Заняття на ці дати повернуться до звичайного розкладу, примітка зникне з нових розпоряджень."
        onDelete={(id) => remove.mutateAsync(id)}
        successMessage={(name) => `Видалено: ${name}`}
      />
    </Section>
  );
}
