import { ArrowRight, Pencil, UserRoundCheck } from 'lucide-react';
import type { AbsenceDto, Uuid } from '@/api/types';
import { HoverCard, HoverCardContent, HoverCardTrigger, Plate, RowActions } from '@/components/ui';
import { LessonDetails } from '@/components/schedule';
import { cn } from '@/lib/cn';
import { formatInstantTime, formatWeekdayDate } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { absencePeriod, decisionSummary } from '../format';
import { LESSON_STATE, lessonButtonId, type LessonEntry, type TeacherGroup } from '../model';
import { PendingCountdown, StateBadge } from '../StateBadge';

function DecisionLine({ entry }: { entry: LessonEntry }) {
  const change = entry.change;
  switch (entry.state) {
    case 'uncovered':
      return <span className="text-danger">Потрібне рішення</span>;
    case 'declined':
      return (
        <span className="text-danger">
          Відмова: {change?.new.teacher?.shortName ?? 'викладач'} — потрібен інший варіант
        </span>
      );
    case 'cancelled':
      return <span className="text-cancel">Пари не буде</span>;
    case 'pending':
    case 'confirmed':
    case 'applied':
      return (
        <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
          <ArrowRight aria-label="заміна" className="size-3.5 shrink-0 text-change rtl:rotate-180" />
          <span className="truncate font-semibold text-primary">{change ? decisionSummary(change) : 'Рішення ухвалено'}</span>
          {entry.state === 'pending' ? <PendingCountdown deadline={change?.confirmDeadline} className="text-caption" /> : null}
          {entry.state === 'confirmed' && change?.respondedAt ? (
            <span className="text-muted figures">о {formatInstantTime(change.respondedAt)}</span>
          ) : null}
        </span>
      );
  }
}

interface LessonRowProps {
  entry: LessonEntry;
  selected: boolean;
  pulsing: boolean;
  onSelect: (entry: LessonEntry) => void;
}

function LessonRow({ entry, selected, pulsing, onSelect }: LessonRowProps) {
  const { lesson, date } = entry.item;
  const needsAction = LESSON_STATE[entry.state].needsAction;
  return (
    <li>
      <HoverCard openDelay={350} closeDelay={120}>
        <HoverCardTrigger asChild>
          <button
            type="button"
            id={lessonButtonId(entry)}
            aria-current={selected ? 'true' : undefined}
            onClick={() => onSelect(entry)}
            // картка деталей — лише для миші: з клавіатури ті самі відомості є в панелі варіантів
            onFocus={(event) => event.preventDefault()}
            className={cn(
              'grid w-full grid-cols-[2.75rem_minmax(0,1fr)_auto] items-start gap-x-3 rounded-control border px-2.5 py-2 text-start',
              'transition-[background-color,border-color,box-shadow] duration-150 ease-out',
              'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
              selected ? 'border-accent bg-selected' : 'border-transparent hover:bg-hover',
              pulsing && 'motion-safe:animate-[live-flash_1.8s_var(--ease-out)] ring-2 ring-[var(--border-color-accent-subtle)]',
            )}
          >
            <span className="flex flex-col items-center leading-none">
              <span className={cn('font-condensed text-title font-bold figures', needsAction ? 'text-primary' : 'text-secondary')}>
                {lesson.pair}
              </span>
              {lesson.startsAt ? <span className="mt-1 text-meta font-normal text-muted figures">{lesson.startsAt}</span> : null}
            </span>
            <span className="flex min-w-0 flex-col gap-1">
              <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                <Plate size="sm">{lesson.group.code}</Plate>
                {lesson.subgroup > 0 ? <span className="text-meta text-secondary">{lesson.subgroup} підгр.</span> : null}
                <span className="flex min-w-0 items-center gap-1.5">
                  <span {...lineAttr(lesson.discipline)} aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
                  <span className="truncate font-condensed text-ui font-bold text-primary">{lesson.discipline.shortName}</span>
                </span>
                {lesson.room ? <span className="text-caption text-muted">ауд. {lesson.room.name}</span> : null}
              </span>
              <span className="text-caption">
                <DecisionLine entry={entry} />
              </span>
            </span>
            <StateBadge state={entry.state} className="mt-0.5" />
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
  groups: readonly TeacherGroup[];
  /** Чи показувати дату над парами (тиждень або багатоденна відсутність). */
  showDates: boolean;
  selectedKey: string | null;
  pulsing: ReadonlySet<Uuid>;
  onSelect: (entry: LessonEntry) => void;
  onEditAbsence: (absence: AbsenceDto) => void;
  onCancelAbsence: (absence: AbsenceDto) => void;
}

/** Середня колонка: пари відсутніх, згруповані за викладачем і датою, зі станом рішення. */
export function LessonList({ groups, showDates, selectedKey, pulsing, onSelect, onEditAbsence, onCancelAbsence }: LessonListProps) {
  return (
    <div className="flex flex-col gap-5">
      {groups.map((group) => {
        const total = group.days.reduce((sum, day) => sum + day.entries.length, 0);
        const open = group.days.reduce((sum, day) => sum + day.entries.filter((e) => LESSON_STATE[e.state].needsAction).length, 0);
        const headingId = `absence-${group.absence.id}`;
        return (
          <section key={group.absence.id} aria-labelledby={headingId} className="flex flex-col gap-1.5">
            <header className="flex items-start justify-between gap-2 px-1">
              <div className="flex min-w-0 flex-col">
                <h3 id={headingId} className="text-ui font-semibold text-primary">
                  {group.absence.teacher.shortName}
                </h3>
                <p className="text-caption text-muted">
                  {absencePeriod(group.absence)}
                  {open > 0 ? <span className="text-danger"> · {open} з {total} без рішення</span> : <span> · усі {total} вирішено</span>}
                </p>
              </div>
              <RowActions
                rowLabel={group.absence.teacher.shortName}
                actions={[
                  { label: 'Змінити дати чи пари', icon: <Pencil aria-hidden="true" />, onSelect: () => onEditAbsence(group.absence) },
                  {
                    label: 'Викладач на місці — скасувати',
                    icon: <UserRoundCheck aria-hidden="true" />,
                    onSelect: () => onCancelAbsence(group.absence),
                    tone: 'danger',
                    separatorBefore: true,
                  },
                ]}
              />
            </header>
            {group.days.map((day) => (
              <div key={day.date} className="flex flex-col gap-1">
                {showDates ? (
                  <h4 className="sticky top-0 z-10 bg-surface px-1 pt-1 text-meta font-semibold text-secondary">{formatWeekdayDate(day.date)}</h4>
                ) : null}
                <ul className="flex flex-col gap-1">
                  {day.entries.map((entry) => (
                    <LessonRow
                      key={lessonButtonId(entry)}
                      entry={entry}
                      selected={selectedKey === lessonButtonId(entry)}
                      pulsing={pulsing.has(entry.item.lesson.id)}
                      onSelect={onSelect}
                    />
                  ))}
                </ul>
              </div>
            ))}
          </section>
        );
      })}
    </div>
  );
}
