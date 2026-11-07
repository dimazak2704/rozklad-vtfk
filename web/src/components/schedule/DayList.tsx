import { Info } from 'lucide-react';
import { useMemo } from 'react';
import type { BellSlotDto, DayDto, IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatWeekdayDate, relativeDayLabel, timeToMinutes, todayInKyiv } from '@/lib/date';
import { daySlots, type DaySlot, type Perspective } from '@/lib/schedule';
import { EmptyState } from '../ui/EmptyState';
import { ParityBadge } from '../ui/ParityBadge';
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
 * День як лінія маршруту (телефон): пари — зупинки, пройдене — «живим» кольором,
 * поточна зупинка пульсує, «вікна» — пунктиром. Зміни видно прямо в рядку.
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
        <header className="flex flex-wrap items-center justify-between gap-2">
          <Heading className="flex items-baseline gap-2 text-heading font-semibold text-primary">
            {formatWeekdayDate(day.date)}
            {relative ? <span className="text-ui font-semibold text-accent">{relative}</span> : null}
          </Heading>
          <ParityBadge parity={day.parity} size="sm" />
        </header>
      ) : null}

      {day.note ? (
        <p className="flex items-start gap-2 rounded-control border border-default bg-sunken px-3 py-2 text-caption text-secondary">
          <Info aria-hidden="true" className="mt-px size-4 shrink-0 text-accent" />
          {day.note}
        </p>
      ) : null}

      {!day.studyDay || slots.length === 0 ? (
        <EmptyState
          size="sm"
          align="start"
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
            return (
              <li
                key={slot.pair}
                aria-current={current ? 'time' : undefined}
                className="grid grid-cols-[2.75rem_1.25rem_minmax(0,1fr)] gap-x-2.5"
              >
                {/* час: велика цифра пари, як година в розкладі на зупинці */}
                <div className="flex flex-col items-end pt-1.5 pb-3 leading-none">
                  <span
                    className={cn(
                      'font-condensed text-headline font-bold figures',
                      current ? 'text-accent' : slot.isWindow ? 'text-muted' : 'text-primary',
                    )}
                  >
                    {slot.pair}
                  </span>
                  {slot.startsAt ? (
                    <span className="mt-1 flex flex-col items-end text-meta text-muted figures">
                      <span className="font-semibold text-secondary">{slot.startsAt}</span>
                      <span>{slot.endsAt}</span>
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
                      slot.isWindow ? 'border-s-2 border-dashed border-default bg-transparent' : 'bg-[var(--border-color-default)]',
                    )}
                  />
                  {fill > 0 && !last ? (
                    <span
                      className="absolute top-0 w-0.5 bg-accent transition-[height] duration-1000 ease-linear"
                      style={{ height: `${Math.round(fill * 100)}%` }}
                    />
                  ) : null}
                  <span
                    className={cn(
                      'relative z-[1] mt-3 block shrink-0 rounded-full border-2',
                      slot.isWindow ? 'size-2.5 border-dashed border-default bg-[var(--fill-surface)]' : 'size-3.5',
                      !slot.isWindow && state === 'past' && 'border-accent bg-accent',
                      !slot.isWindow && current && 'border-accent bg-[var(--fill-surface)] motion-safe:animate-live',
                      !slot.isWindow && state === 'future' && 'border-strong bg-[var(--fill-surface)]',
                    )}
                  />
                </div>

                <div className="flex min-w-0 flex-col gap-1.5 pb-3">
                  {slot.isWindow ? (
                    <p className="pt-2.5 text-caption text-muted">Вікно — пари немає</p>
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
                        past={state === 'past'}
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
