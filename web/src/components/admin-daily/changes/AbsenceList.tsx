import { Check, ChevronRight, Plus } from 'lucide-react';
import type { AbsenceDto, Uuid } from '@/api/types';
import { Button, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { ABSENCE_REASON_LABEL, PAIR_FORMS_GEN, plural } from '@/lib/labels';
import { pairsText } from '../format';
import { formatDateRange } from '@/lib/date';
import { LESSON_STATE, type LessonEntry } from '../model';

/** «увесь день · лікарняний», а для кількох днів — «11–13 листопада · лікарняний». */
function absenceCaption(absence: AbsenceDto): string {
  const when = absence.dateFrom === absence.dateTo ? pairsText(absence.pairs) : formatDateRange(absence.dateFrom, absence.dateTo);
  return `${when} · ${ABSENCE_REASON_LABEL[absence.reasonCategory].toLocaleLowerCase('uk')}`;
}

/**
 * Скільки пар відсутності вже вирішено: короткі відрізки (вирішені — зелені, решта — нейтральні) і підпис
 * словами. Червоного тут немає: незроблене — це ще не помилка.
 */
export function AbsenceProgress({ entries }: { entries: readonly LessonEntry[] }) {
  if (entries.length === 0) return <span className="text-caption text-muted">пар у цей період немає</span>;
  const done = entries.filter((e) => !LESSON_STATE[e.state].needsAction).length;
  const all = done === entries.length;
  return (
    <span className="flex items-center gap-2">
      {entries.length <= 8 ? (
        <span aria-hidden="true" className="flex shrink-0 gap-0.5">
          {entries.map((entry) => (
            <span
              key={`${entry.item.date}-${entry.item.lesson.id}`}
              className={cn(
                'h-1 w-3 rounded-full',
                LESSON_STATE[entry.state].needsAction ? 'bg-[var(--fill-track-future)]' : 'bg-[var(--fill-add)]',
              )}
            />
          ))}
        </span>
      ) : null}
      {all ? (
        <span className="inline-flex items-center gap-1 text-caption text-add">
          <Check aria-hidden="true" className="size-3.5" />
          усе вирішено
        </span>
      ) : (
        <span className="text-caption text-secondary">
          вирішено <span className="figures">{done}</span> з <span className="figures">{entries.length}</span>
          <span className="sr-only"> {plural(entries.length, PAIR_FORMS_GEN)}</span>
        </span>
      )}
    </span>
  );
}

interface AbsenceListProps {
  absences: readonly AbsenceDto[];
  entries: readonly LessonEntry[];
  selectedId: Uuid | null;
  onSelect: (id: Uuid) => void;
  onAdd: () => void;
  loading: boolean;
  /** Телефон: рядок веде на наступний рівень (стрілка), а не просто підсвічується. */
  drill?: boolean;
}

/** Колонка «Відсутні»: хто, коли й чому, і скільки пар уже вирішено. Вибір показує пари цього викладача. */
export function AbsenceList({ absences, entries, selectedId, onSelect, onAdd, loading, drill = false }: AbsenceListProps) {
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
                  selected ? 'bg-selected' : 'hover:bg-hover',
                )}
              >
                <span className="flex min-w-0 flex-1 flex-col gap-1">
                  <span className="truncate text-cell font-[650] text-primary">{absence.teacher.shortName}</span>
                  <span className="text-caption text-secondary">{absenceCaption(absence)}</span>
                  <AbsenceProgress entries={own} />
                </span>
                {drill ? <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted rtl:rotate-180" /> : null}
              </button>
            </li>
          );
        })}
      </ul>
      <Button variant="ghost" size="sm" className="self-start" onClick={onAdd} iconStart={<Plus aria-hidden="true" className="size-4" />}>
        Додати відсутність
      </Button>
    </div>
  );
}
