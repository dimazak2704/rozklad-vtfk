import { CalendarX } from 'lucide-react';
import type { AbsenceDto, Uuid } from '@/api/types';
import { Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { ABSENCE_REASON_LABEL } from '@/lib/labels';
import { ABSENCE_SOURCE_TEXT, absencePeriod } from '../format';
import { LESSON_STATE, type LessonEntry, type LessonState } from '../model';

const SEGMENT_FILL: Record<LessonState, string> = {
  uncovered: 'bg-[var(--fill-cancel)]',
  declined: 'bg-[var(--fill-cancel)]',
  substituteAbsent: 'bg-[var(--fill-cancel)]',
  pending: 'bg-[var(--fill-change)]',
  confirmed: 'bg-[var(--fill-add)]',
  applied: 'bg-[var(--fill-accent)]',
  cancelled: 'bg-[var(--fill-muted)]',
};

/** Пари відсутності як відрізки лінії: видно, скільки вже закрито, а скільки ще ні. */
function ProgressSegments({ entries }: { entries: readonly LessonEntry[] }) {
  if (entries.length === 0) {
    return <span className="text-caption text-muted">У цей період пар немає</span>;
  }
  const done = entries.filter((e) => !LESSON_STATE[e.state].needsAction).length;
  return (
    <span className="flex items-center gap-2">
      <span aria-hidden="true" className="flex h-1.5 min-w-0 flex-1 gap-0.5">
        {entries.map((entry) => (
          <span key={`${entry.item.date}-${entry.item.lesson.id}`} className={cn('h-full min-w-1 flex-1 rounded-full', SEGMENT_FILL[entry.state])} />
        ))}
      </span>
      <span className="shrink-0 text-meta text-secondary figures">
        {done} з {entries.length}
        <span className="sr-only"> пар вирішено</span>
      </span>
    </span>
  );
}

interface AbsenceListProps {
  absences: readonly AbsenceDto[];
  entries: readonly LessonEntry[];
  selectedId: Uuid | null;
  onSelect: (id: Uuid | null) => void;
  loading: boolean;
}

/** Ліва колонка «Відсутні»: хто, коли, чому і скільки пар уже закрито. Вибір звужує список пар. */
export function AbsenceList({ absences, entries, selectedId, onSelect, loading }: AbsenceListProps) {
  if (loading && absences.length === 0) {
    return (
      <div className="flex flex-col gap-2" aria-hidden="true">
        {[0, 1, 2].map((key) => (
          <div key={key} className="flex flex-col gap-2 rounded-control border border-separator p-3">
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="h-3 w-1/2" />
            <Skeleton className="h-1.5 w-full" />
          </div>
        ))}
      </div>
    );
  }

  const totalNeeds = entries.filter((e) => LESSON_STATE[e.state].needsAction).length;

  return (
    <ul className="flex flex-col gap-1" aria-label="Відсутні викладачі">
      <li>
        <button
          type="button"
          aria-pressed={selectedId === null}
          onClick={() => onSelect(null)}
          className={cn(
            'flex w-full items-center justify-between gap-2 rounded-control border px-3 py-2 text-start text-ui font-semibold',
            'transition-[background-color,border-color,color] duration-100 ease-out focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
            selectedId === null ? 'border-accent bg-selected text-accent' : 'border-transparent text-secondary hover:bg-hover hover:text-primary',
          )}
        >
          <span>Усі відсутні</span>
          <span className="text-meta figures">
            {absences.length}
            {totalNeeds > 0 ? <span className="text-danger"> · {totalNeeds} без рішення</span> : null}
          </span>
        </button>
      </li>
      {absences.map((absence) => {
        const own = entries.filter((e) => e.absence.id === absence.id);
        const selected = absence.id === selectedId;
        return (
          <li key={absence.id}>
            <button
              type="button"
              aria-pressed={selected}
              onClick={() => onSelect(selected ? null : absence.id)}
              className={cn(
                'flex w-full flex-col gap-1.5 rounded-control border px-3 py-2.5 text-start',
                'transition-[background-color,border-color] duration-100 ease-out focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                selected ? 'border-accent bg-selected' : 'border-transparent hover:bg-hover',
              )}
            >
              <span className="flex min-w-0 items-center gap-2">
                <CalendarX aria-hidden="true" className={cn('size-4 shrink-0', selected ? 'text-accent' : 'text-muted')} />
                <span className="truncate text-ui font-semibold text-primary">{absence.teacher.shortName}</span>
              </span>
              <span className="text-caption text-secondary">{absencePeriod(absence)}</span>
              <span className="text-caption text-muted">
                {ABSENCE_REASON_LABEL[absence.reasonCategory]} · {ABSENCE_SOURCE_TEXT[absence.source]}
              </span>
              <ProgressSegments entries={own} />
            </button>
          </li>
        );
      })}
    </ul>
  );
}
