import { Fragment, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
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
  /** Заняття, зміна в яких щойно прийшла наживо (`useArrivedChanges`). */
  arrived?: ReadonlySet<string>;
  className?: string;
}

function clockLabel(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(Math.floor(minutes % 60)).padStart(2, '0')}`;
}

/**
 * Шар на всю висоту клітинки, зсунутий на пройдену частку пари: його верхній край — це «зараз».
 * Хвилина минає — шар ковзає `translateY` за 1 с лінійно (лише композиція, без перерахунку макета).
 * Відсоток у translate береться від висоти самого шару, тобто від висоти клітинки.
 */
function AtNow({ fraction, className, children }: { fraction: number; className?: string; children: ReactNode }) {
  return (
    <span
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-x-0 top-0 h-full transition-transform duration-1000 ease-linear', className)}
      style={{ transform: `translateY(${fraction * 100}%)` }}
    >
      {children}
    </span>
  );
}

/** Половина висоти пігулки «зараз» (20 px). */
const PILL_REACH = 10;
/** Рядок підпису вищий за самі цифри: зверху й знизу по ~3 px порожнього міжрядкового простору. */
const CAPTION_LEADING = 3;

/**
 * Номер пари, початок і кінець у колонці часу. Пігулка «зараз» стоїть тут же, на точній хвилині. Підпис,
 * який вона накрила б, на цей час тихо зникає (як в Apple Calendar): пігулка й так називає час, а читач
 * екрана підпис чує. Так пігулка ніколи не лягає на текст, хоч би якою високою була клітинка.
 */
function PairGutter({
  pair,
  time,
  current,
  now,
}: {
  pair: number;
  time: { startsAt: string; endsAt: string } | undefined;
  current: boolean;
  now: { fraction: number; label: string } | null;
}) {
  const pillRef = useRef<HTMLSpanElement>(null);
  const startRef = useRef<HTMLSpanElement>(null);
  const endRef = useRef<HTMLSpanElement>(null);
  const [covered, setCovered] = useState({ start: false, end: false });
  const fraction = now?.fraction ?? null;

  useLayoutEffect(() => {
    const pill = pillRef.current;
    const cell = pill?.closest('th');
    if (fraction === null || !pill || !(cell instanceof HTMLElement)) {
      setCovered((prev) => (prev.start || prev.end ? { start: false, end: false } : prev));
      return;
    }
    const measure = () => {
      const box = cell.getBoundingClientRect();
      // jsdom і схована вкладка не мають розмірів — нічого не ховаємо
      if (box.height === 0) return;
      const centre = box.top + fraction * box.height;
      const pillLeft = box.right - pill.offsetWidth - 4;
      const hits = (el: HTMLElement | null) => {
        if (!el) return false;
        const r = el.getBoundingClientRect();
        return r.right > pillLeft - 2 && r.bottom - CAPTION_LEADING > centre - PILL_REACH && r.top + CAPTION_LEADING < centre + PILL_REACH;
      };
      const next = { start: hits(startRef.current), end: hits(endRef.current) };
      setCovered((prev) => (prev.start === next.start && prev.end === next.end ? prev : next));
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(cell);
    return () => observer.disconnect();
  }, [fraction]);

  return (
    <>
      <span className="flex flex-col">
        <span className={cn('text-title leading-7 font-semibold figures', current ? 'text-accent' : 'text-primary')}>{pair}</span>
        {time ? (
          <>
            <span
              ref={startRef}
              data-covered={covered.start || undefined}
              className={cn(
                'mt-1 text-caption figures transition-opacity duration-200',
                current ? 'text-accent' : 'text-secondary',
                covered.start && 'opacity-0',
              )}
            >
              {time.startsAt}
            </span>
            <span
              ref={endRef}
              data-covered={covered.end || undefined}
              className={cn('text-caption text-muted figures transition-opacity duration-200', covered.end && 'opacity-0')}
            >
              <span className="sr-only">до </span>
              {time.endsAt}
            </span>
          </>
        ) : null}
      </span>
      {now ? (
        <>
          {/* пігулка «зараз» — у колонці часу, тож не закриває жодного заняття; тонкий штрих веде від неї до днів */}
          <AtNow fraction={now.fraction} className="z-10">
            <span className="absolute end-0 top-0 h-px w-1.5 -translate-y-1/2 bg-[var(--fill-now)] opacity-40" />
            <span
              ref={pillRef}
              className="absolute end-1 top-0 flex h-5 -translate-y-1/2 items-center rounded-full bg-accent px-1.5 text-meta font-semibold text-on-accent figures"
            >
              {now.label}
            </span>
          </AtNow>
        </>
      ) : null}
    </>
  );
}

/**
 * Тижнева сітка (DESIGN.md, «Тижнева сітка») — справжня таблиця на одній панелі з горизонтальними лініями:
 * ліворуч колонка часу 88 px (номер пари, початок, кінець — кожне своїм рядком), дні — рівні колонки.
 * Сьогодні — тонована колонка й число в акцентній пігулці. «Зараз» — лінія 2 px через колонку сьогодні
 * на точній хвилині з крапкою ліворуч; пігулка з часом стоїть у колонці часу й не закриває заняття.
 * Лінія лежить під вмістом клітинки й через текст не йде (інакше заняття читалося б закресленим, тобто
 * скасованим): у зайнятій клітинці від неї лишається лише крапка на краю колонки. Від колонки часу
 * до сьогодні веде ледь помітна лінія 1 px — теж лише порожніми клітинками.
 * Рядки ростуть під вміст — нічого не обрізається.
 */
export function WeekGrid({ schedule, perspective = 'group', today = todayInKyiv(), at, stickyTop = '0px', arrived, className }: WeekGridProps) {
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
    <div className={cn('overflow-x-auto overflow-y-hidden rounded-panel border border-default bg-surface lg:overflow-clip', className)}>
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
                {/* клітинка рядка з лінією «зараз» стоїть на шар вище за сусідні липкі клітинки: інакше в останні
                    хвилини пари наступний рядок (той самий z, але пізніше в DOM) накрив би нижню половину пігулки */}
                <th
                  scope="row"
                  data-now-row={line || undefined}
                  className={cn(
                    'sticky left-0 h-[6.5rem] bg-surface px-4 py-3 text-start align-top font-normal',
                    line ? 'z-[6]' : 'z-[5]',
                    !lastRow && 'border-b border-separator',
                  )}
                >
                  <PairGutter
                    pair={pair}
                    time={time}
                    current={currentRow}
                    now={line ? { fraction: nowTop, label: clockLabel(nowMinutes) } : null}
                  />
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
                  const cellBg = isToday ? 'bg-today-wash' : 'bg-surface';
                  // лінія «зараз» — лише в колонці сьогодні (2 px); у днях до неї вона ховалася б під заняттями
                  // й виринала уривками над порожніми клітинками, тож час біля неї показує пігулка в колонці часу
                  const nowRule = line && isLiveDay ? 'strong' : null;
                  return (
                    <td key={day.date} className={cn('relative h-px p-0 align-top', !lastRow && 'border-b border-separator', cellBg)}>
                      {/* лінія йде першою й без z-index: вміст клітинки (з тим самим тлом) малюється поверх неї */}
                      {nowRule ? (
                        // у перерві лінія лежить на межі рядків, де тексту немає, — тож поверх
                        <AtNow fraction={nowTop} className={cn(nowTop === 0 && 'z-[1]')}>
                          <span
                            data-now-line={nowRule}
                            className={cn(
                              'absolute inset-x-0 top-0 -translate-y-1/2 bg-[var(--fill-now)]',
                              'h-0.5',
                            )}
                          />
                        </AtNow>
                      ) : null}
                      {lessons.length === 0 ? (
                        isLiveDay && pair === freeRow ? (
                          <span className="flex h-full min-h-[6.5rem] items-center justify-center px-3.5">
                            <span className={cn('relative px-1.5 text-caption text-muted', cellBg)}>вільно до кінця дня</span>
                          </span>
                        ) : (
                          <span className="sr-only">Пари немає</span>
                        )
                      ) : (
                        <div className={cn('relative flex h-full flex-col', cellBg)}>
                          {lessons.map((lesson, index) => {
                            const start = lesson.startsAt ? timeToMinutes(lesson.startsAt) : null;
                            const end = lesson.endsAt ? timeToMinutes(lesson.endsAt) : null;
                            const current = isLiveDay && start !== null && end !== null && nowMinutes >= start && nowMinutes < end;
                            const lessonPast = isLiveDay && end !== null ? nowMinutes >= end : past;
                            return (
                              <Fragment key={lesson.id}>
                                {/* підгрупи ділить пряма лінія на всю ширину клітинки, а не рамка заокругленої кнопки */}
                                {index > 0 ? <span aria-hidden="true" className="h-px shrink-0 bg-[var(--border-color-separator)]" /> : null}
                                <LessonCell
                                  lesson={lesson}
                                  perspective={perspective}
                                  date={day.date}
                                  dayParity={day.parity}
                                  now={current}
                                  past={lessonPast}
                                  arrived={arrived?.has(lesson.id)}
                                  showTime={Boolean(time && (time.startsAt !== lesson.startsAt || time.endsAt !== lesson.endsAt))}
                                  className="flex-1"
                                />
                              </Fragment>
                            );
                          })}
                        </div>
                      )}
                      {/* крапка 8 px на лівому краї колонки сьогодні: початок лінії, видна й над зайнятою клітинкою */}
                      {nowRule === 'strong' ? (
                        <AtNow fraction={nowTop} className="z-[1]">
                          <span
                            // нова пара — новий ключ: ореол крапки розходиться двічі й затихає
                            key={currentRow ? pair : 'break'}
                            data-now-marker=""
                            className={cn(
                              'absolute start-0 top-0 size-2 -translate-x-1/2 -translate-y-1/2 rounded-full bg-[var(--fill-now)] ring-2 ring-[var(--background-color-today-wash)] rtl:translate-x-1/2',
                              currentRow && 'anim-halo',
                            )}
                          />
                        </AtNow>
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
