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

function clockLabel(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(Math.floor(minutes % 60)).padStart(2, '0')}`;
}

/**
 * Тижнева сітка (DESIGN.md, «Тижнева сітка») — справжня таблиця на одній панелі з горизонтальними лініями:
 * ліворуч колонка часу 88 px (номер пари, початок, кінець — кожне своїм рядком), дні — рівні колонки.
 * Сьогодні — тонована колонка й число в акцентній пігулці. «Зараз» — лінія 2 px через колонку сьогодні
 * на точній хвилині з крапкою ліворуч; пігулка з часом стоїть у колонці часу й не закриває заняття.
 * Рядки ростуть під вміст — нічого не обрізається.
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
      <div className={cn('rounded-panel border border-default bg-surface px-5 py-8', className)}>
        <p className="text-body font-[650] text-primary">На цей тиждень пар немає.</p>
        <p className="mt-1 text-ui text-secondary">Перегляньте інший тиждень або заміни на конкретний день.</p>
      </div>
    );
  }

  const pairs = Array.from({ length: range.last - range.first + 1 }, (_, i) => range.first + i);
  const liveDayIndex = days.findIndex((day) => day.date === liveDate);
  const liveDay = liveDayIndex >= 0 ? days[liveDayIndex] : undefined;

  // рядок і місце лінії «зараз»: усередині пари — частка її тривалості; у перерві — верхній край наступної пари
  let nowRow: number | null = null;
  let nowTop = 0;
  if (liveDay?.studyDay) {
    for (const pair of pairs) {
      const time = times.get(pair);
      if (!time) continue;
      const start = timeToMinutes(time.startsAt);
      const end = timeToMinutes(time.endsAt);
      if (nowMinutes >= start && nowMinutes < end) {
        nowRow = pair;
        nowTop = (nowMinutes - start) / (end - start);
        break;
      }
      if (nowMinutes < start) {
        // перерва перед цією парою (але не до першої пари дня)
        if (pair !== pairs[0]) {
          nowRow = pair;
          nowTop = 0;
        }
        break;
      }
    }
  }

  // «вільно до кінця дня» — у першому порожньому рядку сьогодні після останньої пари
  const todayLessons = liveDayIndex >= 0 ? byDay[liveDayIndex] : undefined;
  const lastTodayPair = todayLessons ? Math.max(0, ...[...todayLessons.keys()]) : 0;
  const freeRow = todayLessons && lastTodayPair > 0 && lastTodayPair < range.last ? lastTodayPair + 1 : null;

  return (
    <div className={cn('overflow-x-auto rounded-panel border border-default bg-surface lg:overflow-clip', className)}>
      <table className="w-full min-w-[46rem] table-fixed border-separate border-spacing-0">
        <caption className="sr-only">
          Розклад {schedule.owner.label} на {formatDateRange(schedule.from, schedule.to)}
          {weekParity ? `, тиждень — ${PARITY_LABEL[weekParity]}` : ''}
        </caption>
        <colgroup>
          <col className="w-[5.5rem]" />
          {days.map((day) => (
            <col key={day.date} />
          ))}
        </colgroup>
        <thead>
          <tr>
            <th scope="col" className="sticky left-0 z-20 h-14 border-b border-separator bg-surface px-4" style={{ top: stickyTop }}>
              <span className="sr-only">Пара</span>
            </th>
            {days.map((day) => {
              const isToday = day.date === liveDate || day.date === today;
              return (
                <th
                  key={day.date}
                  scope="col"
                  aria-current={isToday ? 'date' : undefined}
                  className={cn(
                    'sticky z-10 h-14 border-b border-separator px-3.5 text-start align-middle font-normal',
                    isToday ? 'bg-today-wash' : 'bg-surface',
                  )}
                  style={{ top: stickyTop }}
                >
                  <span className="flex items-center gap-2 whitespace-nowrap">
                    <span className={cn('text-ui', isToday ? 'font-[650] text-primary' : 'font-[600] text-primary')}>
                      <span className="hidden xl:inline">{capitalize(weekdayName(day.weekday))}</span>
                      <span className="xl:hidden">{weekdayShort(day.weekday)}</span>
                    </span>
                    <span
                      className={cn(
                        'inline-flex h-6 min-w-6 items-center justify-center rounded-full px-1.5 text-ui figures',
                        isToday ? 'bg-accent font-semibold text-on-accent' : 'text-secondary',
                      )}
                    >
                      {Number(day.date.slice(8, 10))}
                    </span>
                    {isToday ? <span className="sr-only">сьогодні</span> : null}
                  </span>
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {pairs.map((pair, rowIndex) => {
            const time = times.get(pair);
            const lastRow = rowIndex === pairs.length - 1;
            const rowEnd = time ? timeToMinutes(time.endsAt) : null;
            const currentRow = nowRow === pair && nowTop > 0;
            const line = nowRow === pair;
            return (
              <tr key={pair}>
                <th
                  scope="row"
                  className={cn(
                    'sticky left-0 z-[5] h-[6.5rem] bg-surface px-4 py-3 text-start align-top font-normal',
                    !lastRow && 'border-b border-separator',
                  )}
                >
                  <span className="flex flex-col">
                    <span className={cn('text-title leading-7 font-semibold figures', currentRow ? 'text-accent' : 'text-primary')}>{pair}</span>
                    {time ? (
                      <>
                        <span className={cn('mt-1 text-caption figures', currentRow ? 'text-accent' : 'text-secondary')}>{time.startsAt}</span>
                        <span className="text-caption text-muted figures">
                          <span className="sr-only">до </span>
                          {time.endsAt}
                        </span>
                      </>
                    ) : null}
                  </span>
                  {/* пігулка «зараз» — у колонці часу, тож не закриває жодного заняття */}
                  {line ? (
                    <span
                      aria-hidden="true"
                      className="absolute end-1 z-10 flex h-5 -translate-y-1/2 items-center rounded-full bg-accent px-1.5 text-meta font-semibold text-on-accent figures transition-[top] duration-1000 ease-linear"
                      style={{ top: `${nowTop * 100}%` }}
                    >
                      {clockLabel(nowMinutes)}
                    </span>
                  ) : null}
                </th>
                {days.map((day, dayIndex) => {
                  const lessons = byDay[dayIndex]?.get(pair) ?? [];
                  const isLiveDay = day.date === liveDate;
                  const isToday = isLiveDay || day.date === today;
                  if (!day.studyDay) {
                    return pair === range.first ? (
                      <td key={day.date} rowSpan={pairs.length} className="hatch bg-block-subtle px-3.5 py-4 text-center align-middle">
                        <span className="inline-block rounded-tag bg-surface px-2 py-1 text-caption text-secondary">
                          {day.note ?? 'Не навчальний день'}
                        </span>
                      </td>
                    ) : null;
                  }
                  const past = isLiveDay && rowEnd !== null && nowMinutes >= rowEnd;
                  return (
                    <td
                      key={day.date}
                      className={cn('relative h-px p-0 align-top', !lastRow && 'border-b border-separator', isToday && 'bg-today-wash')}
                    >
                      {lessons.length === 0 ? (
                        isLiveDay && pair === freeRow ? (
                          <span className="flex h-full min-h-[6.5rem] items-center justify-center px-3.5 text-caption text-muted">
                            вільно до кінця дня
                          </span>
                        ) : (
                          <span className="sr-only">Пари немає</span>
                        )
                      ) : (
                        <div className="flex h-full flex-col divide-y divide-[var(--border-color-separator)]">
                          {lessons.map((lesson) => {
                            const start = lesson.startsAt ? timeToMinutes(lesson.startsAt) : null;
                            const end = lesson.endsAt ? timeToMinutes(lesson.endsAt) : null;
                            const current = isLiveDay && start !== null && end !== null && nowMinutes >= start && nowMinutes < end;
                            const lessonPast = isLiveDay && end !== null ? nowMinutes >= end : past;
                            return (
                              <LessonCell
                                key={lesson.id}
                                lesson={lesson}
                                perspective={perspective}
                                date={day.date}
                                dayParity={day.parity}
                                now={current}
                                past={lessonPast}
                                showTime={Boolean(time && (time.startsAt !== lesson.startsAt || time.endsAt !== lesson.endsAt))}
                                className="flex-1"
                              />
                            );
                          })}
                        </div>
                      )}
                      {/* лінія «зараз» 2 px з крапкою 6 px ліворуч; ковзає щохвилини */}
                      {line && isLiveDay ? (
                        <span
                          aria-hidden="true"
                          className="pointer-events-none absolute inset-x-0 z-10 h-0.5 -translate-y-1/2 bg-[var(--fill-now)] transition-[top] duration-1000 ease-linear"
                          style={{ top: `${nowTop * 100}%` }}
                        >
                          <span className="absolute start-0 top-1/2 size-1.5 -translate-y-1/2 rounded-full bg-[var(--fill-now)]" />
                        </span>
                      ) : null}
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
