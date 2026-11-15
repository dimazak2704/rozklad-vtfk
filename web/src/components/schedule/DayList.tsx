import { Info } from 'lucide-react';
import { Fragment, useMemo } from 'react';
import type { BellSlotDto, DayDto, IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatWeekdayDate, relativeDayLabel, timeToMinutes, todayInKyiv } from '@/lib/date';
import { daySlots, type DaySlot, type Perspective } from '@/lib/schedule';
import { EmptyState } from '../ui/EmptyState';
import { Skeleton } from '../ui/Skeleton';
import { LessonCell } from './LessonCell';
import { dayPractices, noteWithoutPractices } from './practice';
import { PeriodBlock } from './PeriodBlock';
import { VacatedSlot } from './VacatedSlot';

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
  /** Заняття, зміна в яких щойно прийшла наживо (`useArrivedChanges`). */
  arrived?: ReadonlySet<string>;
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

function clockLabel(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(Math.floor(minutes % 60)).padStart(2, '0')}`;
}

/** Лінія «зараз» між рядками: пігулка з часом ліворуч і лінія 2 px через рядок. */
function NowRule({ minutes }: { minutes: number }) {
  return (
    <li aria-hidden="true" className="relative grid h-0 grid-cols-[3.5rem_minmax(0,1fr)] gap-x-3">
      <span className="absolute inset-x-0 top-0 h-0.5 -translate-y-1/2 bg-[var(--fill-now)]" />
      <span className="relative z-[1] flex h-5 w-fit -translate-y-1/2 items-center rounded-full bg-accent px-1.5 text-meta font-semibold text-on-accent figures">
        {clockLabel(minutes)}
      </span>
    </li>
  );
}

/**
 * День списком (телефон і денне подання): колонка часу (початок і номер пари) → тонка лінія → заняття
 * з тією самою анатомією, що й клітинка сітки. Завершені сьогодні пари «осідають», «вікна» — тихим рядком.
 * «Зараз» ніколи не перетинає заняття: у перерві це лінія 2 px між рядками з пігулкою часу ліворуч, а під час
 * пари — акцентна смуга на лінії між часом і заняттям, що росте зверху вниз до пройденої частки пари.
 */
export function DayList({
  day,
  bells,
  perspective = 'group',
  today = todayInKyiv(),
  at,
  showHeader = true,
  headingLevel = 2,
  arrived,
  className,
}: DayListProps) {
  const slots = useMemo(() => daySlots(day, bells), [day, bells]);
  const live = useNow(null, { date: day.date });
  const isToday = day.date === (at !== undefined ? day.date : live.clock.date);
  const minutes = isToday ? (at ?? live.clock.minutes + live.clock.seconds / 60) : null;
  const Heading = headingLevel === 2 ? 'h2' : 'h3';
  const relative = relativeDayLabel(day.date, today);
  const practices = dayPractices(day, perspective);
  const note = noteWithoutPractices(day, practices);
  // перерва: лінія «зараз» стоїть перед першою парою, що ще не почалась (але не перед першою парою дня)
  const breakBefore =
    minutes === null
      ? -1
      : slots.findIndex((slot, index) => index > 0 && slot.startsAt && minutes < timeToMinutes(slot.startsAt) && slotState(slots[index - 1] as DaySlot, minutes).state === 'past');

  return (
    <section className={cn('flex flex-col gap-3', className)} aria-label={formatWeekdayDate(day.date)}>
      {showHeader ? (
        <header className="flex flex-wrap items-baseline gap-x-2">
          <Heading className="text-heading font-bold text-primary">{formatWeekdayDate(day.date)}</Heading>
          {relative ? <span className="text-caption font-semibold text-accent">{relative.toLocaleLowerCase('uk')}</span> : null}
        </header>
      ) : null}

      {note ? (
        <p className="flex items-start gap-2 text-caption text-secondary">
          <Info aria-hidden="true" className="mt-px size-4 shrink-0 text-muted" />
          {note}
        </p>
      ) : null}

      {/* практика групи стоїть замість «Не навчальний день»; керівник бачить її над своїми парами */}
      {practices.length > 0 ? <PeriodBlock practices={practices} perspective={perspective} /> : null}

      {!day.studyDay || slots.length === 0 ? (
        practices.length > 0 ? null : (
          <EmptyState
            size="sm"
            align="start"
            title={day.studyDay ? 'Цього дня пар немає.' : 'Не навчальний день.'}
            description={day.studyDay ? undefined : 'Занять у цей день не проводять.'}
          />
        )
      ) : (
        <ol className="flex flex-col">
          {slots.map((slot, index) => {
            const { state, fill } = slotState(slot, minutes);
            const current = state === 'current';
            const past = state === 'past';
            const last = index === slots.length - 1;
            return (
              <Fragment key={slot.pair}>
                {index === breakBefore && minutes !== null ? <NowRule minutes={minutes} /> : null}
                <li
                  aria-current={current ? 'time' : undefined}
                  className={cn('relative grid min-h-16 grid-cols-[3.5rem_minmax(0,1fr)] gap-x-3', !last && 'border-b border-separator')}
                >
                  {/* час: початок пари і її номер; кінець — у картці деталей */}
                  <div className="relative flex flex-col border-e border-separator py-3 pe-3">
                    {slot.startsAt ? (
                      <span
                        className={cn(
                          'anim-settle text-ui leading-5 font-semibold figures',
                          current ? 'text-accent' : past || slot.isWindow ? 'text-muted' : 'text-primary',
                        )}
                      >
                        {slot.startsAt}
                        {slot.endsAt ? <span className="sr-only"> до {slot.endsAt}</span> : null}
                      </span>
                    ) : null}
                    <span className={cn('text-meta', current ? 'font-semibold text-accent' : 'text-muted')}>{slot.pair} пара</span>
                    {/* поточна пара: лінія між часом і заняттям заповнюється акцентом до пройденої частки — повз текст */}
                    {/* рух — лише transform: смуга росте scaleY від верху, крапка ковзає translateY (1 с лінійно) */}
                    {current ? (
                      <span
                        aria-hidden="true"
                        className="pointer-events-none absolute inset-y-0 -end-px w-0.5 translate-x-[0.5px] rtl:-translate-x-[0.5px]"
                      >
                        <span
                          data-now-progress=""
                          className="absolute inset-0 origin-top rounded-full bg-[var(--fill-now)] transition-transform duration-1000 ease-linear"
                          style={{ transform: `scaleY(${Math.max(0.04, fill)})` }}
                        />
                        <span
                          className="absolute start-0 top-0 h-full w-full transition-transform duration-1000 ease-linear"
                          style={{ transform: `translateY(${fill * 100}%)` }}
                        >
                          {/* нова пара — новий елемент: ореол крапки розходиться двічі й затихає */}
                          <span
                            key={slot.pair}
                            className="anim-halo absolute start-1/2 top-0 size-1.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-[var(--fill-now)] rtl:translate-x-1/2"
                          />
                        </span>
                      </span>
                    ) : null}
                  </div>

                  <div className="flex min-w-0 flex-col py-1.5">
                    {slot.movedAway.length > 0 ? (
                      <VacatedSlot lessons={slot.movedAway} perspective={perspective} fullTitle className="py-1.5" />
                    ) : slot.isWindow ? (
                      <p className="px-3.5 py-1.5 text-caption text-muted">Вікно — пари немає</p>
                    ) : (
                      slot.lessons.map((lesson) => (
                        <LessonCell
                          key={lesson.id}
                          lesson={lesson}
                          perspective={perspective}
                          variant="list"
                          date={day.date}
                          now={current}
                          past={past}
                          arrived={arrived?.has(lesson.id)}
                        />
                      ))
                    )}
                  </div>

                </li>
              </Fragment>
            );
          })}
        </ol>
      )}
    </section>
  );
}

/** Силует денного списку: рядки по 64 px — час, номер, дві лінії тексту. */
export function DayListSkeleton({ rows = 4, className }: { rows?: number; className?: string }) {
  return (
    <ol aria-hidden="true" className={cn('flex flex-col', className)}>
      {Array.from({ length: rows }, (_, index) => (
        <li key={index} className="grid min-h-16 grid-cols-[3.5rem_minmax(0,1fr)] gap-x-3 border-b border-separator last:border-b-0">
          <span className="flex flex-col gap-1.5 border-e border-separator py-3 pe-3">
            <Skeleton className="h-4 w-10" />
            <Skeleton className="h-3 w-8" />
          </span>
          <span className="flex flex-col gap-2 px-3.5 py-3.5">
            <Skeleton className="h-4 w-3/5" />
            <Skeleton className="h-3.5 w-2/5" />
          </span>
        </li>
      ))}
    </ol>
  );
}
