import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useMemo } from 'react';
import { Link } from 'react-router';
import { useTeacherStats } from '@/api/accounting';
import type { TeacherStatsDto } from '@/api/types';
import { paths } from '@/app/routes';
import { Button, DataTable, EmptyState, IconButton, Panel, type DataTableColumn } from '@/components/ui';
import { monthLabel, shiftMonth } from './model';

interface TeacherStatsProps {
  month: string;
  onMonthChange: (month: string) => void;
  /** Поточний місяць (далі гортати немає сенсу). */
  currentMonth: string;
}

/** Хто скільки замін провів за місяць і скільки власних пар пропустив. */
export function TeacherStats({ month, onMonthChange, currentMonth }: TeacherStatsProps) {
  const stats = useTeacherStats(month);
  const max = Math.max(1, ...(stats.data ?? []).map((s) => s.substitutionsCount));

  const columns = useMemo<Array<DataTableColumn<TeacherStatsDto>>>(
    () => [
      {
        id: 'teacher',
        header: 'Викладач',
        rowHeader: true,
        sortValue: (s) => s.teacher.shortName,
        cell: (s) => (
          <Link
            to={paths.teacher(s.teacher.id)}
            className="rounded-tag font-semibold text-primary underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
          >
            {s.teacher.shortName}
          </Link>
        ),
      },
      {
        id: 'substitutions',
        header: 'Провів замін',
        sortValue: (s) => s.substitutionsCount,
        width: '40%',
        // смуга нейтральна: синім у системі позначено лише «зараз» і вибране
        cell: (s) => (
          <span className="flex items-center gap-3">
            <span aria-hidden="true" className="relative h-1.5 flex-1 overflow-hidden rounded-full">
              {s.substitutionsCount > 0 ? (
                <span className="absolute inset-y-0 start-0 rounded-full bg-[var(--fill-track-past)]" style={{ width: `${(s.substitutionsCount / max) * 100}%` }} />
              ) : null}
            </span>
            <span className="w-8 text-end text-ui figures">
              {s.substitutionsCount > 0 ? <span className="font-[650]">{s.substitutionsCount}</span> : <span className="text-muted">—</span>}
            </span>
          </span>
        ),
      },
      {
        id: 'hours',
        header: 'Годин замін',
        numeric: true,
        sortValue: (s) => s.substitutionsHours,
        cell: (s) => (s.substitutionsHours > 0 ? s.substitutionsHours : <span className="text-muted">—</span>),
      },
      {
        id: 'missed',
        header: 'Пропустив пар',
        numeric: true,
        sortValue: (s) => s.missedCount,
        cell: (s) => (s.missedCount > 0 ? <span className="font-semibold text-change">{s.missedCount}</span> : <span className="text-muted">—</span>),
      },
    ],
    [max],
  );

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <IconButton label="Попередній місяць" icon={<ChevronLeft aria-hidden="true" className="size-4" />} onClick={() => onMonthChange(shiftMonth(month, -1))} />
        <p className="min-w-40 text-center text-heading font-semibold text-primary first-letter:uppercase" aria-live="polite">
          {monthLabel(month)}
        </p>
        <IconButton
          label="Наступний місяць"
          icon={<ChevronRight aria-hidden="true" className="size-4" />}
          onClick={() => onMonthChange(shiftMonth(month, 1))}
          disabled={month >= currentMonth}
        />
        <p className="text-caption text-muted sm:ms-auto">За опублікованими розпорядженнями</p>
      </div>
      <Panel flush>
        <DataTable
          caption={`Заміни викладачів: ${monthLabel(month)}`}
          columns={columns}
          rows={stats.data}
          getRowId={(s) => s.teacher.id}
          loading={stats.isPending}
          error={stats.error}
          onRetry={() => void stats.refetch()}
          defaultSort={{ columnId: 'substitutions', direction: 'desc' }}
          empty={
            <EmptyState
              size="sm"
              title={`За ${monthLabel(month)} замін не було.`}

              action={
                month !== currentMonth ? (
                  <Button size="sm" variant="secondary" onClick={() => onMonthChange(currentMonth)}>
                    До поточного місяця
                  </Button>
                ) : undefined
              }
            />
          }
        />
      </Panel>
    </div>
  );
}
