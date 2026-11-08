import type { AbsenceDto, Uuid } from '@/api/types';
import { Chip } from '@/components/ui';
import { LESSON_STATE, type LessonEntry } from '../model';

interface AbsenceStripProps {
  absences: readonly AbsenceDto[];
  entries: readonly LessonEntry[];
  selectedId: Uuid | null;
  onSelect: (id: Uuid | null) => void;
}

/** Вузька розкладка: відсутні — рядком фільтрів; число — пари без рішення. */
export function AbsenceStrip({ absences, entries, selectedId, onSelect }: AbsenceStripProps) {
  const open = (absenceId?: Uuid) =>
    entries.filter((e) => (!absenceId || e.absence.id === absenceId) && LESSON_STATE[e.state].needsAction).length;
  return (
    <div role="group" aria-label="Відсутні викладачі" className="flex flex-wrap gap-1.5">
      <Chip selected={selectedId === null} onToggle={() => onSelect(null)} count={open()} aria-label={`Усі відсутні, без рішення: ${open()}`}>
        Усі
      </Chip>
      {absences.map((absence) => (
        <Chip
          key={absence.id}
          selected={absence.id === selectedId}
          onToggle={() => onSelect(absence.id === selectedId ? null : absence.id)}
          count={open(absence.id)}
          aria-label={`${absence.teacher.shortName}, без рішення: ${open(absence.id)}`}
          className="shrink-0"
        >
          {absence.teacher.shortName}
        </Chip>
      ))}
    </div>
  );
}
