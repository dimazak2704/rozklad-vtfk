import { useMemo } from 'react';
import type { IsoDate, ScheduleDto } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatDateRange, timeToMinutes, todayInKyiv } from '@/lib/date';
import { capitalize, PARITY_LABEL, weekdayName, weekdayShort } from '@/lib/labels';
import { lessonsByPair, pairRange, rowTimes, type Perspective } from '@/lib/schedule';
import { ParityMark } from '../ui/ParityBadge';
import { LessonCell } from './LessonCell';

export interface WeekGridProps {
  schedule: ScheduleDto;
  perspective?: Perspective;
  today?: IsoDate;
  /** Зафіксований момент у хвилинах від півночі — для демо й тестів. */
  at?: number;
  /** Відступ липкої шапки від верху вікна (висота шапки сайту). */
  stickyTop?: string;
  className?: string;
}

/**
 * Тижнева сітка (десктоп) — справжня таблиця: рядки — пари з великим номером і часом,
 * стовпці — дні. Ритм розкладу на зупинці: година великою, хвилини дрібно.
 */
export function WeekGrid({ schedule, perspective = 'group', today = todayInKyiv(), at, stickyTop = '0px', className }: WeekGridProps) {
  const days = schedule.days;
  const range = useMemo(() => pairRange(days), [days]);
  const times = useMemo(() => rowTimes(days), [days]);
  const byDay = useMemo(() => days.map((day) => lessonsByPair(day.lessons)), [days]);
  const live = useNow();
  const nowMinutes = at ?? live.clock.minutes + live.clock.seconds / 60;
  const liveDate = at !== undefined ? today : live.clock.date;
  const weekParity = days[0]?.parity;

  if (!range) {
    return (
      <div className={cn('rounded-panel border border-default bg-surface px-4 py-10 text-center', className)}>
        <p className="text-heading font-semibold text-primary">На цей тиждень пар немає</p>
        <p className="mt-1 text-ui text-secondary">Перевірте інший тиждень або розклад змін на конкретний день.</p>
      </div>
    );
  }

  const pairs = Array.from({ length: range.last - range.first + 1 }, (_, i) => range.first + i);

  return (
    <div className={cn('overflow-x-auto rounded-panel border border-default bg-surface lg:overflow-clip', className)}>
      <table className="w-full min-w-[46rem] table-fixed border-separate border-spacing-0">
        <caption className="sr-only">
          Розклад {schedule.owner.label} на {formatDateRange(schedule.from, schedule.to)}
          {weekParity ? `, тиждень — ${PARITY_LABEL[weekParity]}` : ''}
        </caption>
        <colgroup>
          <col className="w-[6.75rem]" />
          {days.map((day) => (
            <col key={day.date} />
          ))}
        </colgroup>
        <thead>
          <tr>
            <th
              scope="col"
              className="sticky left-0 z-20 border-e border-b border-default bg-sunken px-2 py-2 text-start align-bottom"
              style={{ top: stickyTop }}
            >
              {weekParity ? (
                <span className="flex items-center gap-1.5 text-meta font-semibold tracking-wide text-secondary uppercase">
                  <ParityMark parity={weekParity} className="text-accent" />
                  {PARITY_LABEL[weekParity]}
                </span>
              ) : (
                <span className="sr-only">Пара</span>
              )}
            </th>
            {days.map((day) => {
              const isToday = day.date === liveDate || day.date === today;
              return (
                <th
                  key={day.date}
                  scope="col"
                  aria-current={isToday ? 'date' : undefined}
                  className={cn(
                    'sticky z-10 border-b border-default bg-sunken px-3 py-2 text-start align-bottom',
                    isToday && 'border-b-2 border-b-[var(--border-color-accent)] bg-selected',
                  )}
                  style={{ top: stickyTop }}
                >
                  <span className="flex items-baseline gap-2">
                    <span className="text-ui font-semibold text-primary">
                      <span className="hidden xl:inline">{capitalize(weekdayName(day.weekday))}</span>
                      <span className="xl:hidden">{weekdayShort(day.weekday)}</span>
                    </span>
                    <span className="text-caption text-secondary figures">{day.date.slice(8, 10)}.{day.date.slice(5, 7)}</span>
                    {isToday ? <span className="text-caption font-semibold text-accent">сьогодні</span> : null}
                  </span>
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {pairs.map((pair) => {
            const time = times.get(pair);
            return (
              <tr key={pair}>
                <th
                  scope="row"
                  className="sticky left-0 z-[5] border-e border-b border-separator bg-surface px-2 py-2 text-start align-top"
                >
                  <span className="flex flex-col leading-none">
                    <span className="font-condensed text-headline font-bold text-primary figures">{pair}</span>
                    {time ? (
                      <span className="mt-1 text-meta text-muted figures">
                        <span className="font-semibold text-secondary">{time.startsAt}</span>–{time.endsAt}
                      </span>
                    ) : null}
                  </span>
                </th>
                {days.map((day, dayIndex) => {
                  const lessons = byDay[dayIndex]?.get(pair) ?? [];
                  const isLiveDay = day.date === liveDate;
                  if (!day.studyDay) {
                    return pair === range.first ? (
                      <td
                        key={day.date}
                        rowSpan={pairs.length}
                        className="hatch border-b border-separator bg-block-subtle px-3 py-4 text-center align-middle text-caption text-secondary"
                      >
                        {day.note ?? 'Не навчальний день'}
                      </td>
                    ) : null;
                  }
                  return (
                    <td key={day.date} className="border-b border-separator p-1 align-top">
                      {lessons.length === 0 ? (
                        <span className="block px-2 py-2 text-caption text-disabled" aria-label="Пари немає">
                          —
                        </span>
                      ) : (
                        <div className="flex flex-col gap-1">
                          {lessons.map((lesson) => {
                            const start = timeToMinutes(lesson.startsAt);
                            const end = timeToMinutes(lesson.endsAt);
                            const current = isLiveDay && nowMinutes >= start && nowMinutes < end;
                            return (
                              <LessonCell
                                key={lesson.id}
                                lesson={lesson}
                                perspective={perspective}
                                date={day.date}
                                dayParity={day.parity}
                                now={current}
                                progress={current ? (nowMinutes - start) / (end - start) : 0}
                                showTime={Boolean(time && (time.startsAt !== lesson.startsAt || time.endsAt !== lesson.endsAt))}
                              />
                            );
                          })}
                        </div>
                      )}
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
