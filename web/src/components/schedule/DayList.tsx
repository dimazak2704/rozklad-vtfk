import { Info } from 'lucide-react';
import { useMemo } from 'react';
import type { BellSlotDto, DayDto, IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatWeekdayDate, relativeDayLabel, timeToMinutes, todayInKyiv } from '@/lib/date';
import { daySlots, type DaySlot, type Perspective } from '@/lib/schedule';
import { EmptyState } from '../ui/EmptyState';
import { Skeleton } from '../ui/Skeleton';
import { LessonCell } from './LessonCell';

export interface DayListProps {
  day: DayDto;
  /** Дзвінки дня — дають час «вікон» і живий стан. Без них час береться із занять. */
  bells?: readonly BellSlotDto[] | null;
  perspective?: Perspective;
  /** Сьогоднішня дата в Києві (з /public/meta), щоб позначити «сьогодні». */
  today?: IsoDate;
  /** Зафіксований момент у хвилинах від півночі — для демо й тестів. */
  at?: number;
  showHeader?: boolean;
  headingLevel?: 2 | 3;
  className?: string;
}

type SlotState = 'past' | 'current' | 'future';

function slotState(slot: DaySlot, minutes: number | null): { state: SlotState; fill: number } {
  if (minutes === null || !slot.startsAt || !slot.endsAt) return { state: 'future', fill: 0 };
  const start = timeToMinutes(slot.startsAt);
  const end = timeToMinutes(slot.endsAt);
  if (minutes >= end) return { state: 'past', fill: 1 };
  if (minutes >= start) return { state: 'current', fill: (minutes - start) / (end - start) };
  return { state: 'future', fill: 0 };
}

/**
 * День як лінія маршруту (телефон): пари — зупинки на вертикальній лінії. Пройдена частина тихіша,
 * поточна зупинка — єдина синя з однією хвилею, «вікна» — пунктиром. Зміни видно прямо в рядку.
 */
export function DayList({
  day,
  bells,
  perspective = 'group',
  today = todayInKyiv(),
  at,
  showHeader = true,
  headingLevel = 2,
  className,
}: DayListProps) {
  const slots = useMemo(() => daySlots(day, bells), [day, bells]);
  const live = useNow(null, { date: day.date });
  const isToday = day.date === (at !== undefined ? day.date : live.clock.date);
  const minutes = isToday ? (at ?? live.clock.minutes + live.clock.seconds / 60) : null;
  const Heading = headingLevel === 2 ? 'h2' : 'h3';
  const relative = relativeDayLabel(day.date, today);

  return (
    <section className={cn('flex flex-col gap-3', className)} aria-label={formatWeekdayDate(day.date)}>
      {showHeader ? (
        <header className="flex flex-wrap items-baseline gap-x-2">
          <Heading className="text-body font-semibold text-primary">{formatWeekdayDate(day.date)}</Heading>
          {relative ? <span className="text-caption font-semibold text-accent">{relative.toLocaleLowerCase('uk')}</span> : null}
        </header>
      ) : null}

      {day.note ? (
        <p className="flex items-start gap-2 text-caption text-secondary">
          <Info aria-hidden="true" className="mt-px size-4 shrink-0 text-muted" />
          {day.note}
        </p>
      ) : null}

      {!day.studyDay || slots.length === 0 ? (
        <EmptyState
          size="sm"
          align="start"
          className="px-0"
          title={day.studyDay ? 'Пар немає' : 'Не навчальний день'}
          description={day.studyDay ? 'У цей день занять за розкладом немає.' : 'Занять у цей день не проводять.'}
        />
      ) : (
        <ol className="flex flex-col">
          {slots.map((slot, index) => {
            const { state, fill } = slotState(slot, minutes);
            const first = index === 0;
            const last = index === slots.length - 1;
            const current = state === 'current';
            const past = state === 'past';
            return (
              <li
                key={slot.pair}
                aria-current={current ? 'time' : undefined}
                className="grid min-h-16 grid-cols-[3rem_1rem_minmax(0,1fr)] gap-x-2.5"
              >
                {/* номер пари великою цифрою, під ним час — як година в розкладі на зупинці */}
                <div className="flex flex-col items-end pt-1.5 pb-3 leading-none">
                  <span
                    className={cn(
                      'font-condensed text-headline leading-none font-bold figures',
                      current ? 'text-accent' : slot.isWindow || past ? 'text-muted' : 'text-primary',
                    )}
                  >
                    {slot.pair}
                  </span>
                  {slot.startsAt ? (
                    // початок — основним, кінець — тихіше і з тире, щоб другий рядок не читався як окремий час
                    <span className="mt-1.5 flex flex-col items-end text-caption leading-[1.125rem] figures">
                      <span className={current ? 'text-accent' : past || slot.isWindow ? 'text-muted' : 'text-secondary'}>{slot.startsAt}</span>
                      {slot.endsAt ? (
                        <span className="text-muted">
                          <span aria-hidden="true">–</span>
                          <span className="sr-only">до </span>
                          {slot.endsAt}
                        </span>
                      ) : null}
                    </span>
                  ) : null}
                </div>

                {/* лінія маршруту і зупинка */}
                <div className="relative flex justify-center" aria-hidden="true">
                  <span
                    className={cn(
                      'absolute w-0.5',
                      first ? 'top-4' : 'top-0',
                      last ? 'h-4' : 'bottom-0',
                      slot.isWindow
                        ? 'border-s-2 border-dashed border-default bg-transparent'
                        : past
                          ? 'bg-[var(--border-color-accent-subtle)]'
                          : 'bg-[var(--border-color-default)]',
                    )}
                  />
                  {current && !last ? (
                    <span
                      className="absolute top-0 w-0.5 bg-accent transition-[height] duration-1000 ease-linear"
                      style={{ height: `${Math.round(fill * 100)}%` }}
                    />
                  ) : null}
                  <span
                    key={current ? `now-${slot.pair}` : slot.pair}
                    className={cn(
                      'relative z-[1] mt-2.5 block shrink-0 rounded-full',
                      slot.isWindow && 'size-2 border-2 border-dashed border-default bg-[var(--fill-surface)]',
                      !slot.isWindow && past && 'size-2 bg-[var(--border-color-accent-subtle)]',
                      !slot.isWindow && current && 'live-ring size-3 bg-accent',
                      !slot.isWindow && state === 'future' && 'size-2.5 border-2 border-strong bg-[var(--fill-surface)]',
                    )}
                  />
                </div>

                <div className="flex min-w-0 flex-col gap-1.5 pb-3">
                  {slot.isWindow ? (
                    <p className="pt-1.5 text-caption text-muted">Вікно — пари немає</p>
                  ) : (
                    slot.lessons.map((lesson) => (
                      <LessonCell
                        key={lesson.id}
                        lesson={lesson}
                        perspective={perspective}
                        variant="list"
                        date={day.date}
                        dayParity={day.parity}
                        now={current}
                        progress={fill}
                        past={past}
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

/** Силует денного списку: 4 рядки по 64 px — номер, час, дві лінії тексту. */
export function DayListSkeleton({ rows = 4, className }: { rows?: number; className?: string }) {
  return (
    <ol aria-hidden="true" className={cn('flex flex-col', className)}>
      {Array.from({ length: rows }, (_, index) => (
        <li key={index} className="grid min-h-16 grid-cols-[3rem_1rem_minmax(0,1fr)] gap-x-2.5 pb-3">
          <span className="flex flex-col items-end gap-1.5 pt-1.5">
            <Skeleton className="h-7 w-5" />
            <Skeleton className="h-2.5 w-9" />
          </span>
          <span className="flex justify-center pt-2.5">
            <span className="size-2.5 rounded-full border-2 border-default" />
          </span>
          <span className="flex flex-col gap-2 pt-2">
            <Skeleton className="h-4 w-3/5" />
            <Skeleton className="h-3.5 w-2/5" />
          </span>
        </li>
      ))}
    </ol>
  );
}
