import { QRCodeSVG } from 'qrcode.react';
import { useEffect, useState } from 'react';
import { createPortal, flushSync } from 'react-dom';
import type { LessonView, ScheduleDto } from '@/api/types';
import { formatDateRange, formatInstant, parseIsoDate } from '@/lib/date';
import { capitalize, PARITY_LABEL, weekdayName } from '@/lib/labels';
import { lessonCounterpart, lessonsByPair, pairRange, roomLabel, rowTimes, type Perspective } from '@/lib/schedule';
import { changeSummary } from '../schedule/schedule-utils';
import type { PrintMode } from './usePrintMode';
import './print.css';

interface SchedulePrintProps {
  mode: PrintMode;
  schedule: ScheduleDto | undefined;
  perspective: Perspective;
  /** «Розклад групи», «Розклад викладача», «Аудиторія». */
  kindLabel: string;
  /** «4КІ1», «Кренцін», «301». */
  title: string;
  /** Повна адреса сторінки — для QR-коду. */
  url: string;
  weekNumber?: number | null;
}

/** Мітка часу друку: оновлюється перед кожним друком, щоб на папері було видно, наскільки він свіжий. */
function usePrintedAt(): Date | null {
  const [printedAt, setPrintedAt] = useState<Date | null>(null);
  useEffect(() => {
    const onBeforePrint = () => flushSync(() => setPrintedAt(new Date()));
    window.addEventListener('beforeprint', onBeforePrint);
    return () => window.removeEventListener('beforeprint', onBeforePrint);
  }, []);
  return printedAt;
}

function PrintedLesson({ lesson, perspective }: { lesson: LessonView; perspective: Perspective }) {
  const cancelled = lesson.kind === 'CANCELLED';
  const changed = lesson.kind !== 'REGULAR';
  const counterpart = lessonCounterpart(lesson, perspective);
  return (
    <div className="flex flex-col gap-px leading-tight">
      <span className="flex items-baseline gap-1">
        <b className={`font-condensed text-ui ${cancelled ? 'line-through' : ''}`}>{lesson.discipline.shortName}</b>
        {lesson.subgroup > 0 ? <span className="text-meta">{lesson.subgroup} п/г</span> : null}
        {lesson.online ? <span className="text-meta">онлайн</span> : null}
      </span>
      <span className={`text-meta ${cancelled ? 'line-through' : ''}`}>
        {[counterpart, roomLabel(lesson.room?.name)].filter(Boolean).join(' · ')}
      </span>
      {changed ? (
        <span className="text-meta font-semibold">
          {cancelled ? 'ПАРИ НЕ БУДЕ' : `ЗМІНА: ${changeSummary(lesson)}`}
          {lesson.reasonText ? ` (${lesson.reasonText})` : ''}
        </span>
      ) : null}
    </div>
  );
}

function WeekSheet({ schedule, perspective, kindLabel, title, url, weekNumber, printedAt }: Omit<SchedulePrintProps, 'mode'> & { schedule: ScheduleDto; printedAt: Date | null }) {
  const range = pairRange(schedule.days);
  const times = rowTimes(schedule.days);
  const byDay = schedule.days.map((day) => lessonsByPair(day.lessons));
  const parity = schedule.days[0]?.parity;
  const pairs = range ? Array.from({ length: range.last - range.first + 1 }, (_, i) => range.first + i) : [];
  const { year } = parseIsoDate(schedule.from);

  return (
    <div className="flex flex-col gap-3 text-primary">
      <header className="flex items-start justify-between gap-6 border-b-2 border-strong pb-2">
        <div className="flex flex-col gap-1">
          <p className="text-ui">{kindLabel}</p>
          <p className="font-condensed text-display leading-none font-bold">{title}</p>
          <p className="text-ui">
            {formatDateRange(schedule.from, schedule.to)} {year}
            {parity ? ` · ${PARITY_LABEL[parity]}` : ''}
            {weekNumber ? ` · ${weekNumber} тиждень` : ''}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <p className="max-w-[16rem] text-end text-meta">
            Актуальний розклад і заміни — за кодом:
            <br />
            <span className="break-all">{url}</span>
          </p>
          <QRCodeSVG value={url} size={92} level="M" marginSize={0} bgColor="transparent" fgColor="currentColor" />
        </div>
      </header>

      {pairs.length === 0 ? (
        <p className="py-10 text-center text-body">На цей тиждень пар немає.</p>
      ) : (
        <table className="w-full table-fixed border-collapse text-start">
          <colgroup>
            <col className="w-[4.5rem]" />
            {schedule.days.map((day) => (
              <col key={day.date} />
            ))}
          </colgroup>
          <thead>
            <tr>
              <th className="border border-strong px-1.5 py-1 text-start text-meta">Пара</th>
              {schedule.days.map((day) => (
                <th key={day.date} className="border border-strong px-1.5 py-1 text-start text-ui">
                  {capitalize(weekdayName(day.weekday))}{' '}
                  <span className="font-normal figures">
                    {day.date.slice(8, 10)}.{day.date.slice(5, 7)}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {pairs.map((pair) => {
              const time = times.get(pair);
              return (
                <tr key={pair}>
                  <th scope="row" className="border border-strong px-1.5 py-1 text-start align-top">
                    <span className="block font-condensed text-title leading-none font-bold">{pair}</span>
                    {time ? (
                      <span className="text-meta font-normal figures">
                        {time.startsAt}–{time.endsAt}
                      </span>
                    ) : null}
                  </th>
                  {schedule.days.map((day, index) => {
                    const lessons = byDay[index]?.get(pair) ?? [];
                    return (
                      <td key={day.date} className="border border-strong px-1.5 py-1 align-top">
                        {!day.studyDay ? (
                          pair === pairs[0] ? <span className="text-meta">{day.note ?? 'Не навчальний день'}</span> : null
                        ) : (
                          <div className="flex flex-col gap-1.5">
                            {lessons.map((lesson) => (
                              <PrintedLesson key={lesson.id} lesson={lesson} perspective={perspective} />
                            ))}
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
      )}

      <footer className="flex justify-between gap-6 text-meta">
        <span>{printedAt ? `Станом на ${formatInstant(printedAt.toISOString())}. ` : ''}Зміни після друку дивіться за QR-кодом.</span>
        <span className="font-semibold">Розклад ВТФК</span>
      </footer>
    </div>
  );
}

function DoorSheet({ kindLabel, title, url }: Pick<SchedulePrintProps, 'kindLabel' | 'title' | 'url'>) {
  return (
    <div className="flex min-h-[262mm] flex-col items-center justify-center gap-[9mm] text-center text-primary">
      <p className="text-title">{kindLabel}</p>
      <p className="font-condensed text-[118pt] leading-none font-extrabold tracking-[-0.01em]">{title}</p>
      <QRCodeSVG value={url} size={420} level="M" marginSize={0} bgColor="transparent" fgColor="currentColor" />
      <p className="max-w-[150mm] text-title">Наведіть камеру телефона на код: розклад на сьогодні й тиждень, заміни та дзвінки</p>
      <p className="text-body break-all">{url}</p>
      <p className="text-heading font-bold">Розклад ВТФК</p>
    </div>
  );
}

/**
 * Аркуш для друку (портал у <body>): тиждень на A4 альбомно або табличка з QR-кодом на двері (A4 книжково).
 * Ховається на екрані; під час друку ховається все, крім нього (print.css).
 */
export function SchedulePrint(props: SchedulePrintProps) {
  const printedAt = usePrintedAt();
  const { mode, schedule } = props;
  if (mode === 'week' && !schedule) return null;
  return createPortal(
    <div data-theme="light" className={`print-sheet print-sheet--${mode} bg-surface`} aria-hidden="true">
      {mode === 'week' && schedule ? (
        <WeekSheet {...props} schedule={schedule} printedAt={printedAt} />
      ) : (
        <DoorSheet kindLabel={props.kindLabel} title={props.title} url={props.url} />
      )}
    </div>,
    document.body,
  );
}
