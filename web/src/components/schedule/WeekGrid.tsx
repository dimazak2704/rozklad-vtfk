import { Fragment, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { BellSlotDto, IsoDate, ScheduleDto } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { formatDateRange, timeToMinutes, todayInKyiv } from '@/lib/date';
import { capitalize, PARITY_LABEL, WEEKDAY_ACCUSATIVE, weekdayName, weekdayShort } from '@/lib/labels';
import { lessonsByPair, pairRange, vacatedPairs, type Perspective, type ProvisionalState } from '@/lib/schedule';
import { LessonCell } from './LessonCell';
import { nowPillShift } from './now-pill';
import { blocksLabel, dayPractices, noteWithoutPractices } from './practice';
import { PeriodBlock } from './PeriodBlock';
import { VacatedSlot } from './VacatedSlot';
import { weekTimes, type PairTime } from './week-times';

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
  /** Заняття із заміною, що ще не остаточна (кабінет викладача): id → стан. */
  provisional?: ReadonlyMap<string, ProvisionalState>;
  /** Дзвінки за днями тижня (ISO 1..7, `bellsByWeekday`): у пʼятницю пари йдуть в інший час. */
  bells?: ReadonlyMap<number, readonly BellSlotDto[]> | null;
  className?: string;
}

function weekdayAccusative(isoWeekday: number): string {
  return WEEKDAY_ACCUSATIVE[isoWeekday] ?? '';
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

/**
 * Номер пари, початок і кінець у колонці часу. Пігулка «зараз» стоїть тут же, на точній хвилині, а якщо там
 * підпис — зсувається над чи під нього: час пари, що йде зараз, лишається видно (штрих біля пігулки й далі
 * показує точну хвилину). Так пігулка ніколи не лягає на текст, хоч би якою високою була клітинка.
 */
function PairGutter({
  pair,
  time,
  exceptions = [],
  current,
  now,
}: {
  pair: number;
  time: PairTime | undefined;
  /** Дні з іншим часом цієї пари — окремим підписом під основним: «Пт 12:20 / 13:20». */
  exceptions?: ReadonlyArray<{ weekday: number; time: PairTime }>;
  current: boolean;
  now: { fraction: number; label: string } | null;
}) {
  const pillRef = useRef<HTMLSpanElement>(null);
  const labelsRef = useRef<HTMLSpanElement>(null);
  const [shift, setShift] = useState(0);
  const fraction = now?.fraction ?? null;

  useLayoutEffect(() => {
    const pill = pillRef.current;
    const cell = pill?.closest('th');
    if (fraction === null || !pill || !(cell instanceof HTMLElement)) {
      setShift(0);
      return;
    }
    const measure = () => {
      const box = cell.getBoundingClientRect();
      // jsdom і схована вкладка не мають розмірів — нічого не зсуваємо
      if (box.height === 0) return;
      const centre = box.top + fraction * box.height;
      const pillLeft = box.right - pill.offsetWidth - 4;
      const labels = [...(labelsRef.current?.children ?? [])]
        .map((element) => element.getBoundingClientRect())
        .filter((rect) => rect.height > 0 && rect.right > pillLeft - 2);
      const next = Math.round(nowPillShift(centre, box, labels));
      setShift((prev) => (prev === next ? prev : next));
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(cell);
    return () => observer.disconnect();
  }, [fraction]);

  return (
    <>
      <span ref={labelsRef} className="flex flex-col">
        <span className={cn('text-title leading-7 font-semibold figures', current ? 'text-accent' : 'text-primary')}>{pair}</span>
        {time ? (
          <>
            <span className={cn('mt-1 text-caption figures', current ? 'text-accent' : 'text-secondary')}>{time.startsAt}</span>
            <span className="text-caption text-muted figures">
              <span className="sr-only">до </span>
              {time.endsAt}
            </span>
          </>
        ) : null}
        {exceptions.map(({ weekday, time: other }) => (
          <span key={weekday} data-weekday-time={weekday} className="mt-1.5 flex flex-col text-meta text-muted figures">
            <span>
              <span className="sr-only">{`у ${weekdayAccusative(weekday)} `}</span>
              <span aria-hidden="true">{weekdayShort(weekday)} </span>
              {other.startsAt}
            </span>
            <span>
              <span className="sr-only">до </span>
              {other.endsAt}
            </span>
          </span>
        ))}
      </span>
      {now ? (
        <>
          {/* пігулка «зараз» — у колонці часу, тож не закриває жодного заняття; тонкий штрих веде від неї до днів */}
          <AtNow fraction={now.fraction} className="z-10">
            <span className="absolute end-0 top-0 h-px w-1.5 -translate-y-1/2 bg-[var(--fill-now)] opacity-40" />
            <span
              ref={pillRef}
              data-shift={shift || undefined}
              style={{ top: shift }}
              className="absolute end-1 flex h-5 -translate-y-1/2 items-center rounded-full bg-accent px-1.5 text-meta font-semibold text-on-accent figures transition-[top] duration-200 motion-reduce:transition-none"
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
 * Сьогодні — тонована колонка й число в акцентній пігулці. «Зараз» — як у денному списку: у перерві лінія
 * 2 px через колонку сьогодні на межі рядків, а під час пари — смуга на лівому краї клітинки, що росте до
 * пройденої частки, з крапкою на точній хвилині. Через текст заняття ніщо не йде (інакше воно читалося б
 * закресленим, тобто скасованим). Пігулка з часом стоїть у колонці часу й не закриває заняття.
 * Рядки ростуть під вміст — нічого не обрізається.
 */
export function WeekGrid({ schedule, perspective = 'group', today = todayInKyiv(), at, stickyTop = '0px', arrived, provisional, bells, className }: WeekGridProps) {
  const days = schedule.days;
  // керівник бачить свої блоки (практика, НС) окремим рядком над парами; група — у клітинці замість пар
  const supervisedRow = perspective === 'teacher' && days.some((day) => dayPractices(day, perspective).length > 0);
  const range = useMemo(() => pairRange(days, { withVacated: true }), [days]);
  // колонка часу — найчастіший час пари; дні з іншими дзвінками (пʼятниця) — підписом під ним і власним часом у «зараз»
  const { rows: times, byDay: dayTimes, exceptions } = useMemo(() => weekTimes(days, bells), [days, bells]);
  const byDay = useMemo(() => days.map((day) => lessonsByPair(day.lessons)), [days]);
  // пари, які звільнило перенесення: «Пари не буде», а не порожня клітинка
  const vacatedByDay = useMemo(() => days.map((day) => vacatedPairs(day.lessons)), [days]);
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
  const liveTimes = liveDayIndex >= 0 ? dayTimes[liveDayIndex] : undefined;

  // рядок і місце лінії «зараз»: усередині пари — частка її тривалості; у перерві — верхній край наступної пари
  let nowRow: number | null = null;
  let nowTop = 0;
  if (liveDay?.studyDay) {
    for (const pair of pairs) {
      const time = liveTimes?.get(pair);
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
          {supervisedRow ? (
            <tr>
              <th scope="row" className="sticky left-0 z-[5] border-b border-separator bg-surface px-4 py-3 text-start align-top font-normal">
                <span className="text-caption text-secondary">{blocksLabel(days.flatMap((day) => dayPractices(day, perspective)), perspective)}</span>
              </th>
              {days.map((day) => (
                <td key={day.date} className="border-b border-separator bg-surface px-3.5 py-3 align-top">
                  <PeriodBlock practices={dayPractices(day, perspective)} perspective={perspective} />
                </td>
              ))}
            </tr>
          ) : null}
          {pairs.map((pair, rowIndex) => {
            const time = times.get(pair);
            const lastRow = rowIndex === pairs.length - 1;
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
                    exceptions={exceptions.get(pair)}
                    current={currentRow}
                    now={line ? { fraction: nowTop, label: clockLabel(nowMinutes) } : null}
                  />
                </th>
                {days.map((day, dayIndex) => {
                  const lessons = byDay[dayIndex]?.get(pair) ?? [];
                  const movedAway = vacatedByDay[dayIndex]?.get(pair);
                  const isLiveDay = day.date === liveDate;
                  const isToday = isLiveDay || day.date === today;
                  if (!day.studyDay) {
                    const practices = perspective === 'group' ? dayPractices(day, perspective) : [];
                    const note = noteWithoutPractices(day, practices);
                    return pair === range.first ? (
                      <td key={day.date} rowSpan={pairs.length} className="hatch bg-block-subtle px-3.5 py-4 text-center align-middle">
                        {practices.length > 0 ? (
                          <PeriodBlock practices={practices} perspective={perspective} className="rounded-tag bg-surface px-3 py-2.5" />
                        ) : null}
                        {note || practices.length === 0 ? (
                          <span className={cn('inline-block rounded-tag bg-surface px-2 py-1 text-caption text-secondary', practices.length > 0 && 'mt-2')}>
                            {note ?? 'Не навчальний день'}
                          </span>
                        ) : null}
                      </td>
                    ) : null;
                  }
                  const dayTime = dayTimes[dayIndex]?.get(pair);
                  const past = isLiveDay && dayTime !== undefined && nowMinutes >= timeToMinutes(dayTime.endsAt);
                  const cellBg = isToday ? 'bg-today-wash' : 'bg-surface';
                  // «зараз» — лише в колонці сьогодні; у днях до неї лінія ховалася б під заняттями й виринала
                  // уривками над порожніми клітинками, тож час біля неї показує пігулка в колонці часу.
                  // Як у денному списку: у перерві — лінія 2 px на межі рядків (там тексту немає), а під час пари —
                  // смуга на лівому краї клітинки, що росте зверху до пройденої частки. Горизонтальна лінія під
                  // заняттям ховалася б під карткою (лишалася б «сирітська» крапка), а поверх — читалась би закресленням.
                  const nowHere = line && isLiveDay;
                  const nowBreak = nowHere && nowTop === 0;
                  const nowPair = nowHere && nowTop > 0;
                  return (
                    <td key={day.date} className={cn('relative h-px p-0 align-top', !lastRow && 'border-b border-separator', cellBg)}>
                      {nowBreak ? (
                        <AtNow fraction={0} className="z-[1]">
                          <span data-now-line="strong" className="absolute inset-x-0 top-0 h-0.5 -translate-y-1/2 bg-[var(--fill-now)]" />
                        </AtNow>
                      ) : null}
                      {lessons.length === 0 ? (
                        movedAway ? (
                          <VacatedSlot lessons={movedAway} perspective={perspective} className={cn('relative h-full', cellBg)} />
                        ) : isLiveDay && pair === freeRow ? (
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
                                  now={current}
                                  past={lessonPast}
                                  arrived={arrived?.has(lesson.id)}
                                  provisional={provisional?.get(lesson.id)}
                                  showTime={Boolean(dayTime && (dayTime.startsAt !== lesson.startsAt || dayTime.endsAt !== lesson.endsAt))}
                                  className="flex-1"
                                />
                              </Fragment>
                            );
                          })}
                        </div>
                      )}
                      {/* пара йде: смуга 2 px на лівому краї клітинки заповнюється акцентом — повз текст заняття;
                          росте лише transform (scaleY від верху, 1 с лінійно) */}
                      {nowPair ? (
                        <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 start-0 z-[1] w-0.5 -translate-x-1/2 rtl:translate-x-1/2">
                          <span
                            data-now-progress=""
                            className="absolute inset-0 origin-top rounded-full bg-[var(--fill-now)] transition-transform duration-1000 ease-linear"
                            style={{ transform: `scaleY(${Math.max(0.04, nowTop)})` }}
                          />
                        </span>
                      ) : null}
                      {/* крапка 8 px на лівому краї колонки сьогодні: «зараз» на точній хвилині, кінець смуги */}
                      {nowHere ? (
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
