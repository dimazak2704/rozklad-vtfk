import { ChevronRight, CircleCheck } from 'lucide-react';
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

// рядок виходить за край колонки на 12 px (`-mx-3` у списку): текст стоїть на одній лінії з заголовком розділу,
// а тоновання наведення виступає назовні, як у Linear чи Notion
const ROW =
  'group/row grid min-h-14 items-center gap-x-3 px-3 py-2.5 rounded-popover transition-[background-color] duration-150 ease-out hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus';

/**
 * Стрілка «перейти»: на дотику видно завжди; мишею — проступає й підʼїжджає на 4 px лише під наведенням чи фокусом,
 * щоб у спокої список не був парканом із шевронів.
 */
const ARROW =
  'size-4 text-muted rtl:rotate-180 transition-[opacity,translate] duration-200 ease-out motion-reduce:transition-none pointer-fine:opacity-0 pointer-fine:-translate-x-1 group-hover/row:translate-x-0 group-hover/row:opacity-100 group-focus-visible/row:translate-x-0 group-focus-visible/row:opacity-100';

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
    // добра новина — повним реченням у кольорі тексту з галочкою, а не сірою приміткою
    return (
      <p className="flex items-start gap-2 py-1 text-body font-[550] text-primary">
        <CircleCheck aria-hidden="true" className="mt-1 size-4 shrink-0 text-add" />
        <span>{calm}</span>
      </p>
    );
  }

  return (
    <div className="-mx-3 flex flex-col gap-1">
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
                  <span className="text-ui font-semibold text-secondary figures">{lesson.startsAt ?? lesson.pair}</span>
                  <span className="text-meta text-muted">{lesson.pair} пара</span>
                </span>
                <span className="flex min-w-0 flex-col gap-0.5">
                  {/* головне в рядку — яка група й що в неї зависло: на крок більше й жирніше за решту */}
                  <span className="truncate text-cell font-[650] text-primary">
                    <span className="font-display font-semibold tracking-[0.01em]">{lesson.group.code}</span> · {lesson.discipline.shortName}
                    <span className="text-ui font-[500] text-secondary"> · {entry.absence.teacher.shortName}</span>
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
                <ChevronRight aria-hidden="true" className={ARROW} />
              </Row>
            </li>
          );
        })}
        {rest.map((alert, index) => (
          <li key={`${alert.kind}-${index}`}>
            {/* сповіщення — один рядок: нижчий за пару, а крапка й текст стоять на тій самій лінії, що й стан пари
                вище («● Потрібне рішення»), — один лівий край тексту на весь список */}
            <Row to={alert.link} className="min-h-11 grid-cols-[3.25rem_minmax(0,1fr)_auto] py-2">
              <span aria-hidden="true" />
              <span className="flex min-w-0 items-start gap-2 text-ui text-primary">
                <span aria-hidden="true" className={cn('mt-[calc(0.5lh-4px)] size-2 shrink-0 rounded-full', SEVERITY_DOT[alert.severity])} />
                <span className="min-w-0">{alert.message}</span>
              </span>
              {alert.link ? <ChevronRight aria-hidden="true" className={ARROW} /> : <span />}
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
