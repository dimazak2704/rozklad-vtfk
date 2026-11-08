import { useMemo } from 'react';
import type { CalendarPeriod, CalendarWeek } from '@/api/calendar';
import type { CalendarPeriodKind, IsoDate } from '@/api/types';
import { HoverDetails, ParityMark, Tooltip } from '@/components/ui';
import { cn } from '@/lib/cn';
import { addDays, compareIsoDates, formatDateRange, formatNumericDate, parseIsoDate, startOfWeek } from '@/lib/date';
import { MONTHS_NOMINATIVE, PARITY_LABEL } from '@/lib/labels';
import { PERIOD_KIND_LABEL, STUDY_MODE_LABEL } from './labels';
import { periodAudience } from './periods';

interface SemesterTimelineProps {
  weeks: readonly CalendarWeek[];
  periods: readonly CalendarPeriod[];
  today: IsoDate;
  /** Перемкнути парність тижня (null — недоступно, наприклад під час збереження). */
  onToggleWeek: ((week: CalendarWeek) => void) | null;
  onOpenPeriod: (period: CalendarPeriod) => void;
}

/** Смуга періоду кольором його дії: зняті пари — червоний, практика — бурштин, форма навчання — блакитний. */
const BAR_TONE: Record<CalendarPeriodKind, string> = {
  HOLIDAY: 'border-cancel bg-cancel-subtle text-cancel',
  NO_CLASSES: 'border-cancel bg-cancel-subtle text-cancel',
  PRACTICE: 'border-change bg-change-subtle text-change',
  STUDY_MODE: 'border-room bg-room-subtle text-room',
  EXAM_SESSION: 'border-accent-subtle bg-selected text-accent',
};

/**
 * Семестр як лінія маршруту: тижні-зупинки з чисельником/знаменником (натиск міняє парність тижня
 * вручну) і під ними — періоди календаря смугами. Видно одразу, де практика, де дистанційка.
 */
export function SemesterTimeline({ weeks, periods, today, onToggleWeek, onOpenPeriod }: SemesterTimelineProps) {
  const currentWeek = startOfWeek(today);
  const months = useMemo(() => {
    const out: Array<{ index: number; label: string }> = [];
    let previous = -1;
    weeks.forEach((week, index) => {
      // тиждень належить місяцю свого четверга (як ISO-тижні): 31.08–06.09 — це вже вересень
      const shown = parseIsoDate(addDays(week.weekStart, 3)).month;
      if (shown !== previous) {
        out.push({ index, label: MONTHS_NOMINATIVE[shown - 1] ?? '' });
        previous = shown;
      }
    });
    return out;
  }, [weeks]);

  const bars = useMemo(
    () =>
      periods
        .map((period) => {
          const first = weeks.findIndex((w) => compareIsoDates(w.weekEnd, period.startsOn) >= 0);
          const lastFound = weeks.findLastIndex((w) => compareIsoDates(w.weekStart, period.endsOn) <= 0);
          return { period, first, last: lastFound };
        })
        .filter((bar) => bar.first >= 0 && bar.last >= bar.first),
    [periods, weeks],
  );

  const columns = `repeat(${weeks.length}, minmax(3.25rem, 1fr))`;

  return (
    <div className="overflow-x-auto overscroll-x-contain rounded-panel border border-default bg-surface">
      <div
        className="p-3"
        // тижні ділять ширину порівну; вужче за 3,25rem на тиждень — горизонтальна прокрутка
        style={{ display: 'grid', gridTemplateColumns: columns, columnGap: '0.25rem', rowGap: '0.375rem', minWidth: `${weeks.length * 3.5 + 1.5}rem` }}
      >
        {months.map((month, i) => (
          <span
            key={`${month.label}-${month.index}`}
            className="text-meta font-semibold text-muted first-letter:uppercase"
            style={{ gridRow: 1, gridColumn: `${month.index + 1} / ${(months[i + 1]?.index ?? weeks.length) + 1}` }}
          >
            {month.label}
          </span>
        ))}
        {weeks.map((week, index) => {
          const isCurrent = week.weekStart === currentWeek;
          const next = week.parity === 'NUMERATOR' ? 'DENOMINATOR' : 'NUMERATOR';
          const hint = `${week.weekNumber} тиждень, ${formatDateRange(week.weekStart, week.weekEnd)}: ${PARITY_LABEL[week.parity]}${
            week.overridden ? ' (змінено вручну)' : ''
          }.`;
          return (
            <Tooltip
              key={week.weekStart}
              content={onToggleWeek ? `${hint} Натисніть, щоб зробити ${PARITY_LABEL[next]}ом.` : hint}
            >
              <button
                type="button"
                onClick={onToggleWeek ? () => onToggleWeek(week) : undefined}
                aria-disabled={onToggleWeek ? undefined : true}
                aria-current={isCurrent ? 'date' : undefined}
                aria-label={`${hint}${onToggleWeek ? ` Зробити ${PARITY_LABEL[next]}ом` : ''}`}
                style={{ gridRow: 2, gridColumn: index + 1 }}
                className={cn(
                  'group relative flex h-16 flex-col items-center justify-center gap-0.5 rounded-control border text-center',
                  'transition-[background-color,border-color] duration-150 ease-out',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                  week.parity === 'DENOMINATOR' ? 'bg-sunken' : 'bg-surface',
                  week.overridden ? 'border-accent' : 'border-default hover:border-strong',
                  onToggleWeek ? 'cursor-pointer hover:bg-hover' : 'cursor-default',
                  isCurrent && 'ring-2 ring-[var(--border-color-accent)] ring-offset-1 ring-offset-[var(--background-color-surface)]',
                )}
              >
                <span className="font-condensed text-heading leading-none font-bold figures text-primary">{week.weekNumber}</span>
                <span className={cn('flex items-center gap-1 text-meta font-semibold', week.overridden ? 'text-accent' : 'text-secondary')}>
                  <ParityMark parity={week.parity} className="h-3 w-2.5" />
                  {week.parity === 'NUMERATOR' ? 'чис' : 'знам'}
                </span>
                <span className="text-meta font-normal text-muted figures">{formatNumericDate(week.weekStart).slice(0, 5)}</span>
                {week.overridden ? (
                  <span aria-hidden="true" className="absolute -top-1 -right-1 size-2.5 rounded-full border-2 border-[var(--background-color-surface)] bg-accent" />
                ) : null}
              </button>
            </Tooltip>
          );
        })}
        {bars.map(({ period, first, last }, row) => (
          <HoverDetails
            key={period.id}
            label={`Період: ${period.title}`}
            content={
              <div className="flex flex-col gap-1.5 text-ui">
                <p className="font-semibold text-primary">{period.title}</p>
                <p className="text-secondary">
                  {PERIOD_KIND_LABEL[period.kind]}
                  {period.studyMode ? ` · ${STUDY_MODE_LABEL[period.studyMode]}` : ''} · {periodAudience(period)}
                </p>
                <p className="text-caption text-muted figures">
                  {formatNumericDate(period.startsOn)} – {formatNumericDate(period.endsOn)}
                </p>
                {period.orderNote ? <p className="text-caption text-secondary">У розпорядженні: «{period.orderNote}»</p> : null}
              </div>
            }
          >
            <button
              type="button"
              onClick={() => onOpenPeriod(period)}
              style={{ gridRow: row + 3, gridColumn: `${first + 1} / ${last + 2}` }}
              className={cn(
                'flex h-7 min-w-0 items-center gap-1.5 truncate rounded-plate border px-2 text-start text-caption font-semibold',
                'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-focus',
                BAR_TONE[period.kind],
              )}
            >
              <span className="truncate">
                {period.title} · {periodAudience(period)}
              </span>
            </button>
          </HoverDetails>
        ))}
      </div>
    </div>
  );
}
