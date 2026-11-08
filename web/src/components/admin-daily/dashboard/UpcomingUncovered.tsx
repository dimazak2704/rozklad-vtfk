import { Link } from 'react-router';
import type { IsoDate } from '@/api/types';
import { Plate } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { timeToMinutes } from '@/lib/date';
import { durationLabel } from '@/lib/labels';
import { LESSON_STATE, type LessonEntry } from '../model';
import { StateBadge } from '../StateBadge';

const MAX_ROWS = 6;

interface UpcomingUncoveredProps {
  entries: readonly LessonEntry[];
  date: IsoDate;
}

/** Пари без рішення за часом початку; сьогодні — з відліком «за 35 хв», минулі — приглушені. */
export function UpcomingUncovered({ entries, date }: UpcomingUncoveredProps) {
  const { clock } = useNow();
  const open = entries
    .filter((entry) => LESSON_STATE[entry.state].needsAction)
    .sort((a, b) => a.item.lesson.startsAt.localeCompare(b.item.lesson.startsAt) || a.item.lesson.group.code.localeCompare(b.item.lesson.group.code, 'uk'));
  const today = clock.date === date;
  return (
    <ul className="flex flex-col">
      {open.slice(0, MAX_ROWS).map((entry) => {
        const { lesson } = entry.item;
        const minutes = timeToMinutes(lesson.startsAt) - clock.minutes;
        const started = today && minutes <= 0;
        return (
          <li key={`${entry.item.date}-${lesson.id}`} className="border-b border-separator last:border-b-0">
            <Link
              to={`/admin/changes?date=${date}&lesson=${lesson.id}`}
              className="grid grid-cols-[2.75rem_minmax(0,1fr)_auto] items-center gap-x-3 px-4 py-2.5 transition-[background-color] duration-100 ease-out hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus"
            >
              <span className="flex flex-col items-center leading-none">
                <span className="font-condensed text-heading font-bold text-primary figures">{lesson.pair}</span>
                <span className="mt-1 text-meta text-muted figures">{lesson.startsAt}</span>
              </span>
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="flex min-w-0 items-center gap-2">
                  <Plate size="sm">{lesson.group.code}</Plate>
                  <span className="truncate font-condensed text-ui font-bold text-primary">{lesson.discipline.shortName}</span>
                </span>
                <span className="truncate text-caption text-muted">{entry.absence.teacher.shortName}</span>
              </span>
              <span className="flex flex-col items-end gap-1">
                <StateBadge state={entry.state} />
                {today ? (
                  <span className={cn('text-meta font-semibold figures', started ? 'text-danger' : minutes <= 60 ? 'text-change' : 'text-muted')}>
                    {started ? 'вже йде' : `за ${durationLabel(minutes)}`}
                  </span>
                ) : null}
              </span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
