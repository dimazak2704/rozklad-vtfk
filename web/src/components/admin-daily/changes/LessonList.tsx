import { Pencil, UserRoundCheck } from 'lucide-react';
import type { ReactNode } from 'react';
import type { AbsenceDto, ChangeDto, Uuid } from '@/api/types';
import { HoverCard, HoverCardContent, HoverCardTrigger, MoreMenu } from '@/components/ui';
import { LessonDetails } from '@/components/schedule';
import { cn } from '@/lib/cn';
import { formatWeekdayDate } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { ABSENCE_REASON_LABEL } from '@/lib/labels';
import { ABSENCE_SOURCE_TEXT, absencePeriod, decisionSummary, momentText } from '../format';
import { lessonButtonId, noticeAnswer, type LessonEntry, type TeacherGroup } from '../model';
import { PendingCountdown, StateText } from '../StateBadge';
import type { TeacherLabel } from '../teacher-label';
import { useTeacherLabel } from '../useTeacherLabel';

/** Хто відмовився і коли — тихий рядок аудиту під станом пари; відмова не сьогодні — з датою. */
function declinedLine(change: ChangeDto | null, name: TeacherLabel): string | null {
  if (!change) return null;
  const last = [...change.history].reverse().find((step) => step.to === 'DECLINED');
  if (!last) return null;
  return `${change.new.teacher ? name(change.new.teacher) : 'Викладач'} відмовився ${momentText(last.at)}`;
}

/** Стан пари одним рядком: слово стану, далі — що саме вирішено. */
function StatusLine({ entry, name }: { entry: LessonEntry; name: TeacherLabel }) {
  const change = entry.change;
  const summary = change ? decisionSummary(change, name) : null;
  const rest = (content: ReactNode) => <span className="font-normal text-secondary"> · {content}</span>;
  switch (entry.state) {
    case 'uncovered':
      return <StateText state="uncovered">Потрібне рішення</StateText>;
    case 'declined':
      return (
        <StateText state="declined">
          <span>Відмова</span>
          {rest('потрібен інший варіант')}
        </StateText>
      );
    case 'substituteAbsent':
      return (
        <StateText state="substituteAbsent">
          <span>Замінник відсутній</span>
          {rest('скасуйте рішення')}
        </StateText>
      );
    case 'objected':
      return (
        <StateText state="objected">
          <span>Потрібне доповнення</span>
          {rest(`${change?.new.teacher ? name(change.new.teacher) : 'викладач'} не може`)}
        </StateText>
      );
    case 'cancelled':
      return <StateText state="cancelled">Пари не буде</StateText>;
    case 'pending':
      // стан і строк — одним рядком, а хто проведе — окремим тихим рядком нижче (≤ 2 розділювачі в рядку)
      return (
        <>
          <StateText state="pending">
            <span className="whitespace-nowrap">Чекає відповіді</span>
            {/* на телефоні строк — під словом стану; у вузькій колонці він переноситься цілим, а не обрізається */}
            {change?.confirmDeadline ? (
              <>
                <span className="max-sm:hidden"> </span>
                <span className="font-normal whitespace-nowrap text-secondary max-sm:block">
                  <span className="max-sm:hidden">·&nbsp;</span>
                  <PendingCountdown deadline={change.confirmDeadline} lesson={{ date: entry.item.date, endsAt: entry.item.lesson.endsAt }} />
                </span>
              </>
            ) : null}
          </StateText>
          {summary ? <span className="ps-4 text-caption text-secondary">{summary}</span> : null}
        </>
      );
    case 'confirmed':
    case 'applied': {
      const ok = noticeAnswer(change) === 'ok';
      return (
        <StateText state={entry.state}>
          <span>{entry.state === 'applied' ? 'У розпорядженні' : 'Підтверджено'}</span>
          {summary ? rest(summary) : null}
          {ok ? rest('«Добре»') : null}
        </StateText>
      );
    }
  }
}

interface LessonRowProps {
  entry: LessonEntry;
  selected: boolean;
  pulsing: boolean;
  onSelect: (entry: LessonEntry) => void;
  name: TeacherLabel;
}

function LessonRow({ entry, selected, pulsing, onSelect, name }: LessonRowProps) {
  const { lesson, date } = entry.item;
  const subgroup = lesson.subgroup > 0 ? `${lesson.subgroup} п/г` : null;
  const audit = entry.state === 'declined' ? declinedLine(entry.change, name) : null;
  return (
    <li>
      <HoverCard openDelay={400} closeDelay={120}>
        <HoverCardTrigger asChild>
          <button
            type="button"
            id={lessonButtonId(entry)}
            aria-current={selected ? 'true' : undefined}
            onClick={() => onSelect(entry)}
            // картка деталей — лише для миші: з клавіатури ті самі відомості є в панелі варіантів
            onFocus={(event) => event.preventDefault()}
            className={cn(
              'grid w-full grid-cols-[3.25rem_minmax(0,1fr)] items-start gap-x-3 rounded-popover px-3 py-2.5 text-start',
              'transition-[background-color,box-shadow] duration-150 ease-out',
              'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
              selected ? 'bg-selected' : 'hover-fine:bg-hover',
              pulsing && 'ring-2 ring-[var(--border-color-accent-subtle)] motion-safe:animate-[live-flash_1.8s_var(--ease-out)]',
            )}
          >
            <span className="flex flex-col">
              <span className="text-ui font-semibold text-secondary figures">{lesson.startsAt ?? `${lesson.pair}`}</span>
              <span className="text-meta text-muted">{lesson.pair} пара</span>
            </span>
            <span className="flex min-w-0 flex-col gap-1">
              {/* головне — група й дисципліна: на крок більше за час і стан, код групи — Geologica, як скрізь */}
              <span className="flex min-w-0 flex-wrap items-center gap-x-1.5 text-cell font-[650] text-primary">
                <span className="font-display font-semibold tracking-[0.01em]">{lesson.group.code}</span>
                {subgroup ? <span className="text-ui font-normal text-secondary">{subgroup}</span> : null}
                {/* кольорова крапка дисципліни сама відділяє її від групи — без «·» поруч (дві крапки підряд) */}
                <span className="ms-1 inline-flex min-w-0 items-center gap-1.5">
                  <span {...lineAttr(lesson.discipline)} aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
                  <span className="truncate">{lesson.discipline.shortName}</span>
                </span>
                {lesson.room ? <span className="text-ui font-normal text-secondary">· ауд. {lesson.room.name}</span> : null}
              </span>
              <StatusLine entry={entry} name={name} />
              {audit ? <span className="text-caption text-muted">{audit}</span> : null}
            </span>
          </button>
        </HoverCardTrigger>
        <HoverCardContent side="left" align="start" className="hidden lg:block">
          <LessonDetails lesson={lesson} date={date} />
        </HoverCardContent>
      </HoverCard>
    </li>
  );
}

interface LessonListProps {
  group: TeacherGroup;
  /** Чи показувати дату над парами (тиждень або багатоденна відсутність). */
  showDates: boolean;
  selectedKey: string | null;
  pulsing: ReadonlySet<Uuid>;
  onSelect: (entry: LessonEntry) => void;
  onEditAbsence: (absence: AbsenceDto) => void;
  onCancelAbsence: (absence: AbsenceDto) => void;
  /** Рядок над заголовком: «‹ Усі відсутні» на телефоні. */
  before?: ReactNode;
}

/** Середня колонка: пари обраного відсутнього (за датою), кожна зі станом рішення одним рядком. */
export function LessonList({ group, showDates, selectedKey, pulsing, onSelect, onEditAbsence, onCancelAbsence, before }: LessonListProps) {
  const { absence } = group;
  const headingId = `absence-${absence.id}`;
  const name = useTeacherLabel();
  return (
    <section aria-labelledby={headingId} className="flex min-w-0 flex-col gap-2">
      {before}
      <header className="flex items-start justify-between gap-2 px-3">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h3 id={headingId} className="text-heading font-bold text-primary">
            {name(absence.teacher)}
          </h3>
          <p className="text-caption text-secondary">
            {absencePeriod(absence)} · {ABSENCE_REASON_LABEL[absence.reasonCategory].toLocaleLowerCase('uk')}
            <span className="text-muted"> · {ABSENCE_SOURCE_TEXT[absence.source]}</span>
          </p>
        </div>
        <MoreMenu
          label={`Дії з відсутністю: ${name(absence.teacher)}`}
          items={[
            { label: 'Змінити дати чи пари', icon: <Pencil aria-hidden="true" />, onSelect: () => onEditAbsence(absence) },
            {
              label: 'Викладач на місці — скасувати',
              icon: <UserRoundCheck aria-hidden="true" />,
              onSelect: () => onCancelAbsence(absence),
              tone: 'danger',
              separatorBefore: true,
            },
          ]}
        />
      </header>
      {group.days.length === 0 ? (
        <p className="px-3 py-2 text-ui text-secondary">У цей період пар у викладача немає.</p>
      ) : (
        group.days.map((day) => (
          <div key={day.date} className="flex flex-col gap-0.5">
            {showDates ? <h4 className="px-3 pt-2 pb-1 text-caption font-semibold text-secondary">{formatWeekdayDate(day.date)}</h4> : null}
            <ul className="flex flex-col gap-0.5">
              {day.entries.map((entry) => (
                <LessonRow
                  key={lessonButtonId(entry)}
                  entry={entry}
                  selected={selectedKey === lessonButtonId(entry)}
                  pulsing={pulsing.has(entry.item.lesson.id)}
                  onSelect={onSelect}
                  name={name}
                />
              ))}
            </ul>
          </div>
        ))
      )}
    </section>
  );
}
