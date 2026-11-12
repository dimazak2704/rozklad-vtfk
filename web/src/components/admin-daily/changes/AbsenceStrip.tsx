import { Plus } from 'lucide-react';
import type { AbsenceDto, Uuid } from '@/api/types';
import { Button } from '@/components/ui';
import { cn } from '@/lib/cn';
import { LESSON_STATE, type LessonEntry } from '../model';
import type { LessonsStatus } from './useChangesWorkspace';

interface AbsenceStripProps {
  absences: readonly AbsenceDto[];
  entries: readonly LessonEntry[];
  /** Чиї пари ще не відомі: для них не кажемо «усе вирішено». */
  lessonsStatus?: ReadonlyMap<Uuid, LessonsStatus>;
  selectedId: Uuid | null;
  onSelect: (id: Uuid) => void;
  onAdd: () => void;
}

/**
 * Середня ширина: відсутні — рядком над робочою панеллю. Замість лічильників — бурштинова крапка в тих,
 * у кого ще є пари без рішення (число — у підписі для читача екрана).
 */
export function AbsenceStrip({ absences, entries, lessonsStatus, selectedId, onSelect, onAdd }: AbsenceStripProps) {
  const open = (absenceId: Uuid) => entries.filter((e) => e.absence.id === absenceId && LESSON_STATE[e.state].needsAction).length;
  return (
    <div role="group" aria-label="Відсутні викладачі" className="flex flex-wrap items-center gap-1.5">
      {absences.map((absence) => {
        const selected = absence.id === selectedId;
        const left = open(absence.id);
        const unknown = lessonsStatus?.has(absence.id) ?? false;
        return (
          <button
            key={absence.id}
            type="button"
            aria-pressed={selected}
            aria-label={`${absence.teacher.shortName}${unknown ? '' : left > 0 ? `, без рішення: ${left}` : ', усе вирішено'}`}
            onClick={() => onSelect(absence.id)}
            className={cn(
              'inline-flex h-9 items-center gap-2 rounded-full border px-3.5 text-ui font-[550] pointer-coarse:h-11',
              'transition-[background-color,border-color] duration-150 ease-out focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
              selected ? 'border-transparent bg-selected text-primary' : 'border-default bg-surface text-secondary hover-fine:bg-hover hover-fine:text-primary',
            )}
          >
            {absence.teacher.shortName}
            {left > 0 ? <span aria-hidden="true" className="size-2 rounded-full bg-[var(--fill-change)]" /> : null}
          </button>
        );
      })}
      <Button variant="ghost" size="sm" onClick={onAdd} iconStart={<Plus aria-hidden="true" className="size-4" />}>
        Додати відсутність
      </Button>
    </div>
  );
}
