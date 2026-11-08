import { MousePointerClick, Move, Pencil, Pin, PinOff, Trash2 } from 'lucide-react';
import type { TimetableConflict } from '@/api/timetable';
import type { TemplateLessonDto } from '@/api/types';
import { Button, Kbd, KbdCombo } from '@/components/ui';
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

/** Вибрана пара: повні відомості й дії з клавішами — для тих, хто працює з клавіатури. */
export function LessonInspector({ lesson, conflicts, editable, busy, onEdit, onMove, onTogglePin, onDelete, className }: LessonInspectorProps) {
  return (
    <section aria-labelledby="inspector-title" aria-live="polite" className={cn('rounded-panel border border-default bg-surface', className)}>
      <h2 id="inspector-title" className="border-b border-separator px-3 py-2.5 text-heading font-semibold text-primary">
        Вибрана пара
      </h2>
      {lesson ? (
        <div className="flex flex-col gap-3 p-3">
          <LessonDetailsCard lesson={lesson} conflicts={conflicts} />
          {editable ? (
            <div className="grid grid-cols-2 gap-2">
              <Button size="sm" onClick={() => onEdit(lesson)} disabled={busy} iconStart={<Pencil aria-hidden="true" className="size-3.5" />}>
                Змінити <Kbd className="ms-auto">E</Kbd>
              </Button>
              <Button size="sm" onClick={() => onMove(lesson)} disabled={busy} iconStart={<Move aria-hidden="true" className="size-3.5" />}>
                Перемістити <Kbd className="ms-auto">M</Kbd>
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
          ) : (
            <p className="text-caption text-muted">Ця версія лише для перегляду: змінювати можна чернетку.</p>
          )}
        </div>
      ) : (
        <div className="flex flex-col gap-2 p-3 text-caption text-secondary">
          <p className="flex items-center gap-2 text-ui text-primary">
            <MousePointerClick aria-hidden="true" className="size-4 text-muted" />
            Клацніть пару в сітці
          </p>
          <ul className="flex list-disc flex-col gap-1 ps-5 marker:text-muted">
            <li>Перетягніть пару в інший слот — зелена рамка означає «вільно», червона — конфлікт.</li>
            <li>
              З клавіатури: фокус на пару, <Kbd>Enter</Kbd> — підхопити, стрілки — слот, <Kbd>Enter</Kbd> — поставити.
            </li>
            <li>
              Порожня клітинка — додати пару; <KbdCombo keys="mod+z" /> — скасувати правку.
            </li>
          </ul>
        </div>
      )}
    </section>
  );
}
