import { useMemo } from 'react';
import type { IsoDate, ScheduleDto } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatDateRange, timeToMinutes, todayInKyiv } from '@/lib/date';
import { capitalize, PARITY_LABEL, weekdayName, weekdayShort } from '@/lib/labels';
import { lessonsByPair, pairRange, rowTimes, type Perspective } from '@/lib/schedule';
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
 * Тижнева сітка (десктоп) — справжня таблиця, як надрукований аркуш: рядки — пари з великим номером
 * і часом, стовпці — дні через тонку лінію. Зміни — тонування клітинки від краю до краю, як маркер.
 * «Зараз» — номер і час рядка синім і доріжка прогресу вгорі поточної клітинки; більше нічого не світиться.
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
      <div className={cn('rounded-panel border border-default bg-surface px-4 py-8', className)}>
        <p className="text-heading font-semibold text-primary">На цей тиждень пар немає</p>
        <p className="mt-1 text-ui text-secondary">Перевірте інший тиждень або заміни на конкретний день.</p>
      </div>
    );
  }

  const pairs = Array.from({ length: range.last - range.first + 1 }, (_, i) => range.first + i);
  const liveDayIndex = days.findIndex((day) => day.date === liveDate);

  return (
    <div className={cn('overflow-x-auto rounded-panel border border-default bg-surface lg:overflow-clip', className)}>
      <table className="w-full min-w-[46rem] table-fixed border-separate border-spacing-0">
        <caption className="sr-only">
          Розклад {schedule.owner.label} на {formatDateRange(schedule.from, schedule.to)}
          {weekParity ? `, тиждень — ${PARITY_LABEL[weekParity]}` : ''}
        </caption>
        <colgroup>
          <col className="w-[6.5rem]" />
          {days.map((day) => (
            <col key={day.date} />
          ))}
        </colgroup>
        <thead>
          <tr>
            <th
              scope="col"
              className="sticky left-0 z-20 h-10 border-b border-default bg-sunken px-3 text-start align-middle font-normal"
              style={{ top: stickyTop }}
            >
              {weekParity ? (
                <span className="text-meta font-semibold text-secondary">{capitalize(PARITY_LABEL[weekParity])}</span>
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
                    'sticky z-10 h-10 border-s border-b border-s-separator border-b-default bg-sunken px-3 text-start align-middle',
                    isToday && 'shadow-[inset_0_-2px_0_var(--border-color-accent)]',
                  )}
                  style={{ top: stickyTop }}
                >
                  <span className="flex items-baseline gap-2 whitespace-nowrap">
                    <span className={cn('text-ui text-primary', isToday ? 'font-semibold' : 'font-medium')}>
                      <span className="hidden xl:inline">{capitalize(weekdayName(day.weekday))}</span>
                      <span className="xl:hidden">{weekdayShort(day.weekday)}</span>
                    </span>
                    <span className="text-caption font-normal text-secondary figures">
                      {day.date.slice(8, 10)}.{day.date.slice(5, 7)}
                    </span>
                    {isToday ? <span className="text-meta font-semibold text-accent">сьогодні</span> : null}
                  </span>
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {pairs.map((pair, rowIndex) => {
            const time = times.get(pair);
            const rowStart = time ? timeToMinutes(time.startsAt) : null;
            const rowEnd = time ? timeToMinutes(time.endsAt) : null;
            const currentRow =
              liveDayIndex >= 0 && rowStart !== null && rowEnd !== null && nowMinutes >= rowStart && nowMinutes < rowEnd;
            const lastRow = rowIndex === pairs.length - 1;
            return (
              <tr key={pair}>
                <th
                  scope="row"
                  className={cn('sticky left-0 z-[5] bg-surface px-3 py-2 text-start align-top font-normal', !lastRow && 'border-b border-separator')}
                >
                  <span className="flex flex-col leading-none">
                    <span className={cn('font-condensed text-headline font-bold figures', currentRow ? 'text-accent' : 'text-primary')}>
                      {pair}
                    </span>
                    {time ? (
                      <span className={cn('mt-1 text-caption figures', currentRow ? 'font-medium text-accent' : 'text-muted')}>
                        {time.startsAt}–{time.endsAt}
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
                        className="hatch border-s border-separator bg-block-subtle px-3 py-4 text-center align-middle"
                      >
                        <span className="inline-block rounded-plate bg-surface px-1.5 py-0.5 text-caption text-secondary">
                          {day.note ?? 'Не навчальний день'}
                        </span>
                      </td>
                    ) : null;
                  }
                  return (
                    <td
                      key={day.date}
                      className={cn('h-px border-s border-separator p-0 align-top', !lastRow && 'border-b')}
                    >
                      {lessons.length === 0 ? (
                        <span className="block px-3 py-2 text-caption text-muted" aria-label="Пари немає">
                          —
                        </span>
                      ) : (
                        <div className="flex h-full flex-col divide-y divide-[var(--border-color-separator)]">
                          {lessons.map((lesson) => {
                            const start = lesson.startsAt ? timeToMinutes(lesson.startsAt) : null;
                            const end = lesson.endsAt ? timeToMinutes(lesson.endsAt) : null;
                            const current = isLiveDay && start !== null && end !== null && nowMinutes >= start && nowMinutes < end;
                            return (
                              <LessonCell
                                key={lesson.id}
                                lesson={lesson}
                                perspective={perspective}
                                date={day.date}
                                dayParity={day.parity}
                                now={current}
                                progress={current && start !== null && end !== null ? (nowMinutes - start) / (end - start) : 0}
                                showTime={Boolean(time && (time.startsAt !== lesson.startsAt || time.endsAt !== lesson.endsAt))}
                                className="flex-1"
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
