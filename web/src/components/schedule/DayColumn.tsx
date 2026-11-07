import { useMemo } from 'react';
import type { BellSlotDto, DayDto, IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatDayMonth, timeToMinutes, todayInKyiv } from '@/lib/date';
import { capitalize, weekdayName } from '@/lib/labels';
import { daySlots, type Perspective } from '@/lib/schedule';
import { ParityMark } from '../ui/ParityBadge';
import { LessonCell } from './LessonCell';

export interface DayColumnProps {
  day: DayDto;
  bells?: readonly BellSlotDto[] | null;
  perspective?: Perspective;
  today?: IsoDate;
  at?: number;
  /** Компактний заголовок (у бічних панелях). */
  compact?: boolean;
  className?: string;
}

/**
 * Один день колонкою: заголовок дня і пари з номером та часом зліва.
 * Для денного подання на десктопі й бічних панелей (заміни викладача, деталі групи).
 */
export function DayColumn({ day, bells, perspective = 'group', today = todayInKyiv(), at, compact = false, className }: DayColumnProps) {
  const slots = useMemo(() => daySlots(day, bells), [day, bells]);
  const live = useNow(null, { date: day.date });
  const isToday = day.date === today;
  const minutes = at ?? (isToday && live.isToday ? live.clock.minutes + live.clock.seconds / 60 : null);

  return (
    <section
      aria-label={`${capitalize(weekdayName(day.weekday))}, ${formatDayMonth(day.date)}`}
      className={cn('flex min-w-0 flex-col overflow-hidden rounded-panel border border-default bg-surface', className)}
    >
      <header
        className={cn(
          'flex items-center justify-between gap-2 border-b border-separator px-3',
          compact ? 'py-2' : 'py-2.5',
          isToday && 'border-b-2 border-b-[var(--border-color-accent)]',
        )}
      >
        <p className="flex min-w-0 items-baseline gap-2">
          <span className={cn('font-semibold text-primary', compact ? 'text-ui' : 'text-heading')}>
            {capitalize(weekdayName(day.weekday))}
          </span>
          <span className="text-caption text-secondary figures">{formatDayMonth(day.date)}</span>
          {isToday ? <span className="text-caption font-semibold text-accent">сьогодні</span> : null}
        </p>
        <ParityMark parity={day.parity} className="text-muted" />
      </header>

      {day.note ? <p className="border-b border-separator bg-sunken px-3 py-2 text-caption text-secondary">{day.note}</p> : null}

      {!day.studyDay || slots.length === 0 ? (
        <p className="px-3 py-6 text-center text-caption text-muted">{day.studyDay ? 'Пар немає' : 'Не навчальний день'}</p>
      ) : (
        <ol className="flex flex-col divide-y divide-[var(--border-color-separator)]">
          {slots.map((slot) => {
            const start = slot.startsAt ? timeToMinutes(slot.startsAt) : null;
            const end = slot.endsAt ? timeToMinutes(slot.endsAt) : null;
            const current = minutes !== null && start !== null && end !== null && minutes >= start && minutes < end;
            const progress = current && start !== null && end !== null ? (minutes - start) / (end - start) : 0;
            return (
              <li key={slot.pair} className="grid grid-cols-[3rem_minmax(0,1fr)] gap-2 px-2 py-1.5" aria-current={current ? 'time' : undefined}>
                <div className="flex flex-col items-center pt-1 leading-none">
                  <span className={cn('font-condensed text-title font-bold figures', current ? 'text-accent' : slot.isWindow ? 'text-muted' : 'text-primary')}>
                    {slot.pair}
                  </span>
                  {slot.startsAt ? <span className="mt-1 text-meta text-muted figures">{slot.startsAt}</span> : null}
                </div>
                <div className="flex min-w-0 flex-col gap-1">
                  {slot.isWindow ? (
                    <p className="py-2 text-caption text-muted">Вікно</p>
                  ) : (
                    slot.lessons.map((lesson) => (
                      <LessonCell
                        key={lesson.id}
                        lesson={lesson}
                        perspective={perspective}
                        variant="grid"
                        date={day.date}
                        dayParity={day.parity}
                        now={current}
                        progress={progress}
                      />
                    ))
                  )}
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
