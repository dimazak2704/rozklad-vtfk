import { ChevronRight } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { DashboardAlert, IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { timeToMinutes } from '@/lib/date';
import { durationLabel } from '@/lib/labels';
import { LESSON_STATE, type LessonEntry } from '../model';
import { StateText } from '../StateBadge';

const MAX_PAIRS = 6;

/** Ці сповіщення сервера вже видно рядками пар нижче — двічі не кажемо. */
const COVERED_BY_PAIRS = new Set(['UNCOVERED_LESSONS', 'DECLINED']);

const SEVERITY_DOT: Record<DashboardAlert['severity'], string> = {
  ERROR: 'bg-[var(--fill-cancel)]',
  WARN: 'bg-[var(--fill-change)]',
  INFO: 'border-[1.5px] border-[var(--fill-muted)] bg-transparent',
};

const ROW =
  'grid min-h-14 items-center gap-x-3 px-3 py-2.5 rounded-popover transition-[background-color] duration-150 ease-out hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus';

function Row({ to, children, className }: { to: string | null; children: ReactNode; className?: string }) {
  if (!to) return <div className={cn(ROW, 'hover:bg-transparent', className)}>{children}</div>;
  return (
    <Link to={to} className={cn(ROW, className)}>
      {children}
    </Link>
  );
}

interface NeedsListProps {
  entries: readonly LessonEntry[];
  alerts: readonly DashboardAlert[];
  date: IsoDate;
  /** Пари відомі (робочий простір завантажився): тоді сповіщення про них не дублюємо. */
  pairsKnown: boolean;
  /** Речення, коли рішень не потрібно. */
  calm: ReactNode;
  /** Зламані сервіси (бот, генератор) — лише коли щось не так, рядками після сповіщень сервера. */
  serviceAlerts?: readonly DashboardAlert[];
}

/**
 * «Що потребує рішення»: спершу пари без рішення за часом (сьогодні — з «вже йде» / «за 35 хв» після слова
 * стану), далі решта сповіщень сервера і зламані сервіси реченнями. Колір несе лише крапка; кожен рядок веде туди, де це вирішують.
 */
export function NeedsList({ entries, alerts, date, pairsKnown, calm, serviceAlerts = [] }: NeedsListProps) {
  const { clock } = useNow();
  const today = clock.date === date;
  const open = entries
    .filter((entry) => LESSON_STATE[entry.state].needsAction)
    // без профілю дзвінків часу немає — тоді впорядковуємо за номером пари
    .sort((a, b) => a.item.lesson.pair - b.item.lesson.pair || a.item.lesson.group.code.localeCompare(b.item.lesson.group.code, 'uk'));
  const rest = [...alerts.filter((alert) => !(pairsKnown && COVERED_BY_PAIRS.has(alert.kind))), ...serviceAlerts];

  if (open.length === 0 && rest.length === 0) {
    return <p className="px-3 py-2 text-body text-secondary">{calm}</p>;
  }

  return (
    <div className="flex flex-col gap-1">
      <ul className="flex flex-col gap-0.5">
        {open.slice(0, MAX_PAIRS).map((entry) => {
          const { lesson } = entry.item;
          const minutes = lesson.startsAt ? timeToMinutes(lesson.startsAt) - clock.minutes : null;
          const started = today && minutes !== null && minutes <= 0;
          const over = started && lesson.endsAt !== null && timeToMinutes(lesson.endsAt) <= clock.minutes;
          const timing = !today || minutes === null ? null : over ? 'уже минула' : started ? 'вже йде' : `за ${durationLabel(minutes)}`;
          return (
            <li key={`${entry.item.date}-${lesson.id}`}>
              <Row to={`/admin/changes?date=${date}&lesson=${lesson.id}`} className="grid-cols-[3.25rem_minmax(0,1fr)_auto]">
                <span className="flex flex-col">
                  <span className="text-ui font-semibold text-primary figures">{lesson.startsAt ?? lesson.pair}</span>
                  <span className="text-meta text-muted">{lesson.pair} пара</span>
                </span>
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="truncate text-ui font-[650] text-primary">
                    <span className="figures">{lesson.group.code}</span> · {lesson.discipline.shortName}
                    <span className="font-normal text-secondary"> · {entry.absence.teacher.shortName}</span>
                  </span>
                  <StateText state={entry.state}>
                    {entry.state === 'declined' ? 'Відмова — потрібен інший варіант' : 'Потрібне рішення'}
                    {/* коли — одразу після слова стану, а не в далекій правій колонці; на вузькому переноситься цілим */}
                    {timing ? (
                      <span className={cn('whitespace-nowrap max-sm:block', started && !over ? 'font-semibold text-cancel' : 'font-normal text-muted')}>
                        <span className="max-sm:hidden"> · </span>
                        {timing}
                      </span>
                    ) : null}
                  </StateText>
                </span>
                <ChevronRight aria-hidden="true" className="size-4 text-muted rtl:rotate-180" />
              </Row>
            </li>
          );
        })}
        {rest.map((alert, index) => (
          <li key={`${alert.kind}-${index}`}>
            <Row to={alert.link} className="grid-cols-[3.25rem_minmax(0,1fr)_auto]">
              <span aria-hidden="true" className="flex justify-center">
                <span className={cn('size-2 rounded-full', SEVERITY_DOT[alert.severity])} />
              </span>
              <span className="text-ui text-primary">{alert.message}</span>
              {alert.link ? <ChevronRight aria-hidden="true" className="size-4 text-muted rtl:rotate-180" /> : <span />}
            </Row>
          </li>
        ))}
      </ul>
      {open.length > MAX_PAIRS ? (
        <Link
          to={`/admin/changes?date=${date}`}
          className="self-start rounded-control px-3 py-2 text-ui font-semibold text-accent underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-focus"
        >
          Ще {open.length - MAX_PAIRS} — до замін
        </Link>
      ) : null}
    </div>
  );
}
