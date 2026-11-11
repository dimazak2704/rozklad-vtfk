import { Move, Pencil, Pin, PinOff, Trash2 } from 'lucide-react';
import type { TimetableConflict } from '@/api/timetable';
import type { TemplateLessonDto } from '@/api/types';
import { Button } from '@/components/ui';
import { cn } from '@/lib/cn';
import { LessonDetailsCard } from '../board/LessonDetailsCard';

interface LessonInspectorProps {
  lesson: TemplateLessonDto | null;
  conflicts: readonly TimetableConflict[];
  editable: boolean;
  busy: boolean;
  onEdit: (lesson: TemplateLessonDto) => void;
  onMove: (lesson: TemplateLessonDto) => void;
  onTogglePin: (lesson: TemplateLessonDto) => void;
  onDelete: (lesson: TemplateLessonDto) => void;
  className?: string;
}

/**
 * Вибрана пара: повні відомості й дії. Гарячі клавіші (E, M, Delete, Ctrl+Z) — у довідці «?», а не тут:
 * інструкції з клавішами в інтерфейсі лише заважають.
 */
export function LessonInspector({ lesson, conflicts, editable, busy, onEdit, onMove, onTogglePin, onDelete, className }: LessonInspectorProps) {
  return (
    <section aria-labelledby="inspector-title" aria-live="polite" className={cn('rounded-panel border border-default bg-surface', className)}>
      <h2 id="inspector-title" className="border-b border-separator px-3 py-2.5 text-heading font-bold text-primary">
        Вибрана пара
      </h2>
      {lesson ? (
        <div className="flex flex-col gap-3 p-3">
          <LessonDetailsCard lesson={lesson} conflicts={conflicts} />
          {editable ? (
            <div className="grid grid-cols-2 gap-2">
              <Button size="sm" onClick={() => onEdit(lesson)} disabled={busy} iconStart={<Pencil aria-hidden="true" className="size-3.5" />}>
                Змінити
              </Button>
              <Button size="sm" onClick={() => onMove(lesson)} disabled={busy} iconStart={<Move aria-hidden="true" className="size-3.5" />}>
                Перемістити
              </Button>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => onTogglePin(lesson)}
                disabled={busy}
                iconStart={lesson.pinned ? <PinOff aria-hidden="true" className="size-3.5" /> : <Pin aria-hidden="true" className="size-3.5" />}
              >
                {lesson.pinned ? 'Відкріпити' : 'Закріпити'}
              </Button>
              <Button
                size="sm"
                variant="danger-ghost"
                onClick={() => onDelete(lesson)}
                disabled={busy}
                iconStart={<Trash2 aria-hidden="true" className="size-3.5" />}
              >
                Видалити
              </Button>
            </div>
          ) : null}
        </div>
      ) : (
        <p className="p-3 text-ui text-secondary">
          {editable
            ? 'Клацніть пару, щоб побачити подробиці, або перетягніть її в інший слот.'
            : 'Клацніть пару, щоб побачити подробиці.'}
        </p>
      )}
    </section>
  );
}
