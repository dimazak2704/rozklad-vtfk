import { CornerDownRight } from 'lucide-react';
import type { LessonView } from '@/api/types';
import { cn } from '@/lib/cn';
import type { Perspective } from '@/lib/schedule';

interface VacatedSlotProps {
  /** Заняття, перенесені з цієї пари (уже на новому місці). */
  lessons: readonly LessonView[];
  perspective?: Perspective;
  /** Денний список — повна назва дисципліни, як у клітинках поруч; сітка — коротка. */
  fullTitle?: boolean;
  className?: string;
}

/**
 * Пара, яку звільнило перенесення: «Пари не буде» тоном скасування, закреслене заняття (як у скасованій
 * клітинці) і куди його перенесли. Пара в парі з клітинкою «Перенесено · з 2-ї пари», тож людина бачить
 * обидва кінці перестановки, а не «вікно» на місці пари, яку розпорядження прямо скасовує.
 */
export function VacatedSlot({ lessons, perspective = 'group', fullTitle = false, className }: VacatedSlotProps) {
  const targets = [...new Set(lessons.map((lesson) => lesson.pair))].sort((a, b) => a - b);
  if (targets.length === 0) return null;
  return (
    <div data-vacated="" className={cn('flex min-w-0 flex-col gap-1 px-3.5 py-3', className)}>
      <span className="text-meta font-semibold text-cancel">Пари не буде</span>
      {lessons.map((lesson) => {
        const full = lesson.discipline.name || lesson.discipline.shortName;
        const shown = perspective === 'teacher' ? lesson.group.code : fullTitle ? full : lesson.discipline.shortName;
        return (
          <span
            key={lesson.id}
            // картки деталей тут немає, тож повну назву показує підказка; у списку — до двох рядків, а не обрізана одним
            title={perspective === 'teacher' ? undefined : full}
            className={cn(
              'min-w-0 text-cell font-[650] text-secondary line-through decoration-[var(--text-decoration-color-cancel)] decoration-2',
              fullTitle && perspective !== 'teacher' ? 'line-clamp-2 wrap-break-word' : 'truncate',
            )}
          >
            <span className="sr-only">Було: </span>
            {shown}
          </span>
        );
      })}
      <span className="flex items-center gap-1 text-caption text-secondary">
        <CornerDownRight aria-hidden="true" className="size-3.5 shrink-0 text-muted rtl:-scale-x-100" />
        перенесено на {targets.join(', ')} пару
      </span>
    </div>
  );
}
