import { Check, ChevronRight, FilePlus2, Plus } from 'lucide-react';
import type { AbsenceDto, Uuid } from '@/api/types';
import { Button, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { ABSENCE_REASON_LABEL, PAIR_FORMS_GEN, plural } from '@/lib/labels';
import { pairsText } from '../format';
import { formatDateRange, formatShortWeekdayDate } from '@/lib/date';
import { isWaiting, LESSON_STATE, tallyEntries, type LessonEntry } from '../model';
import { useTeacherLabel } from '../useTeacherLabel';
import type { LessonsStatus } from './useChangesWorkspace';

/**
 * «увесь день · лікарняний», а для кількох днів — «11–13 листопада · лікарняний». У поданні «Тиждень» одноденна
 * відсутність каже й день («Пт, 20.11 · увесь день · лікарняний»): той самий викладач буває у списку двічі.
 */
export function absenceCaption(absence: AbsenceDto, withDate = false): string {
  const single = absence.dateFrom === absence.dateTo;
  const when = single ? pairsText(absence.pairs) : formatDateRange(absence.dateFrom, absence.dateTo);
  const day = single && withDate ? `${formatShortWeekdayDate(absence.dateFrom)} · ` : '';
  return `${day}${when} · ${ABSENCE_REASON_LABEL[absence.reasonCategory].toLocaleLowerCase('uk')}`;
}

/**
 * Скільки пар відсутності вже вирішено: короткі відрізки (вирішені — зелені, ті, що чекають відповіді, — бурштинові,
 * решта — нейтральні) і підпис словами. Червоного тут немає: незроблене — це ще не помилка. Пара, що чекає
 * відповіді, не «вирішена»: викладач може змовчати, тож «усе вирішено» з галочкою — лише коли чекати нічого.
 */
export function AbsenceProgress({ entries, status }: { entries: readonly LessonEntry[]; status?: LessonsStatus }) {
  // пари ще їдуть — тиха смужка тієї ж висоти, а не хибне «пар немає» чи «вирішено 1 з 3»
  if (status === 'pending') {
    return (
      <span className="flex h-[1.125rem] items-center">
        <Skeleton className="h-2.5 w-28" />
        <span className="sr-only">завантажуємо пари</span>
      </span>
    );
  }
  if (status === 'failed') return <span className="text-caption text-muted">пари не вдалося завантажити</span>;
  if (entries.length === 0) return <span className="text-caption text-muted">пар у цей період немає</span>;
  const { done, waiting, total } = tallyEntries(entries);
  const waitingText = waiting > 0 ? `${waiting} ${waiting === 1 ? 'чекає' : 'чекають'} відповіді` : null;
  return (
    <span className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
      {entries.length <= 8 ? (
        <span aria-hidden="true" className="flex shrink-0 gap-0.5">
          {entries.map((entry) => (
            <span
              key={`${entry.item.date}-${entry.item.lesson.id}`}
              data-segment={LESSON_STATE[entry.state].needsAction ? 'open' : isWaiting(entry.state) ? 'waiting' : 'done'}
              className={cn(
                'h-1 w-3 rounded-full',
                LESSON_STATE[entry.state].needsAction
                  ? 'bg-[var(--fill-track-future)]'
                  : isWaiting(entry.state)
                    ? 'bg-[var(--fill-change)]'
                    : 'bg-[var(--fill-add)]',
              )}
            />
          ))}
        </span>
      ) : null}
      {done === total ? (
        <span className="inline-flex items-center gap-1 text-caption text-add">
          <Check aria-hidden="true" className="size-3.5" />
          усе вирішено
        </span>
      ) : done + waiting === total ? (
        <span className="text-caption font-[550] text-change">{waitingText}</span>
      ) : (
        <span className="text-caption text-secondary">
          вирішено <span className="figures">{done}</span> з <span className="figures">{total}</span>
          <span className="sr-only"> {plural(total, PAIR_FORMS_GEN)}</span>
          {waitingText ? <span className="text-change"> · {waitingText}</span> : null}
        </span>
      )}
    </span>
  );
}

interface AbsenceListProps {
  absences: readonly AbsenceDto[];
  entries: readonly LessonEntry[];
  /** Відсутності, чиї пари ще не відомі (`useChangesWorkspace`). */
  lessonsStatus?: ReadonlyMap<Uuid, LessonsStatus>;
  selectedId: Uuid | null;
  onSelect: (id: Uuid) => void;
  onAdd: () => void;
  /** Телефон: «Нова зміна» поруч із «Додати відсутність» — у шапці на вузькому екрані її немає. */
  onAddChange?: () => void;
  loading: boolean;
  /** Телефон: рядок веде на наступний рівень (стрілка), а не просто підсвічується. */
  drill?: boolean;
  /** Подання «Тиждень»: у підписі одноденної відсутності — день і дата. */
  showDates?: boolean;
}

/** Колонка «Відсутні»: хто, коли й чому, і скільки пар уже вирішено. Вибір показує пари цього викладача. */
export function AbsenceList({ absences, entries, lessonsStatus, selectedId, onSelect, onAdd, onAddChange, loading, drill = false, showDates = false }: AbsenceListProps) {
  const name = useTeacherLabel();
  if (loading && absences.length === 0) {
    return (
      <div className="flex flex-col gap-1 px-1" aria-hidden="true">
        {[0, 1, 2].map((key) => (
          <div key={key} className="flex flex-col gap-2 px-3 py-3">
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="h-3 w-1/2" />
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <ul className="flex flex-col gap-0.5" aria-label="Відсутні викладачі">
        {absences.map((absence) => {
          const own = entries.filter((e) => e.absence.id === absence.id);
          const selected = !drill && absence.id === selectedId;
          return (
            <li key={absence.id}>
              <button
                type="button"
                aria-pressed={drill ? undefined : selected}
                onClick={() => onSelect(absence.id)}
                className={cn(
                  'flex w-full items-center gap-3 rounded-popover px-3 py-2.5 text-start pointer-coarse:min-h-14',
                  'transition-[background-color] duration-150 ease-out focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                  selected ? 'bg-selected' : 'hover-fine:bg-hover',
                )}
              >
                <span className="flex min-w-0 flex-1 flex-col gap-1">
                  <span className="truncate text-cell font-[650] text-primary">{name(absence.teacher)}</span>
                  <span className="text-caption text-secondary">{absenceCaption(absence, showDates)}</span>
                  <AbsenceProgress entries={own} status={lessonsStatus?.get(absence.id)} />
                </span>
                {drill ? <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted rtl:rotate-180" /> : null}
              </button>
            </li>
          );
        })}
      </ul>
      <div className="flex flex-wrap gap-1">
        <Button variant="ghost" size="sm" onClick={onAdd} iconStart={<Plus aria-hidden="true" className="size-4" />}>
          Додати відсутність
        </Button>
        {onAddChange ? (
          <Button variant="ghost" size="sm" onClick={onAddChange} iconStart={<FilePlus2 aria-hidden="true" className="size-4" />}>
            Нова зміна
          </Button>
        ) : null}
      </div>
    </div>
  );
}
