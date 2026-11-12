import type { IsoDate } from '@/api/types';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import type { ScheduleView } from './schedule-params';
import type { ChangedLesson } from './schedule-utils';

/** Обгортка тижневої сітки: за нею «2 заміни цього тижня» знаходить першу змінену клітинку. */
export const WEEK_REGION_ID = 'schedule-week';

const CHANGE_FORMS = ['заміна', 'заміни', 'замін'] as const;

/** Перша видима клітинка зі словом стану (заміна, скасування, додаткова, інша аудиторія). */
function firstChangedCell(): HTMLElement | null {
  const region = document.getElementById(WEEK_REGION_ID);
  if (!region) return null;
  const marks = region.querySelectorAll<HTMLElement>('button .text-change, button .text-cancel, button .text-add, button .text-room');
  for (const mark of marks) {
    const cell = mark.closest('button');
    if (cell && cell.getClientRects().length > 0) return cell;
  }
  return null;
}

interface ChangesNoteProps {
  items: readonly ChangedLesson[];
  view: ScheduleView;
  /** Відкрити день першої зміни (подання «День»), коли клітинки на екрані немає. */
  onOpenDay: (date: IsoDate) => void;
  className?: string;
}

/**
 * «• 2 заміни цього тижня» — один рядок замість окремої смуги над сіткою: самі зміни видно в клітинках.
 * Натискання веде фокус до першої зміненої клітинки, а на телефоні відкриває день першої зміни.
 */
export function ChangesNote({ items, view, onOpenDay, className }: ChangesNoteProps) {
  const first = items[0];
  if (!first) return null;
  const go = () => {
    const cell = view === 'week' ? firstChangedCell() : null;
    if (cell) {
      cell.scrollIntoView({ block: 'center', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
      cell.focus({ preventScroll: true });
    } else {
      onOpenDay(first.day.date);
    }
  };
  return (
    <button
      type="button"
      onClick={go}
      className={cn(
        'inline-flex min-h-8 w-fit items-center gap-2 rounded-tag text-ui font-[650] text-change pointer-coarse:min-h-11',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
        className,
      )}
    >
      <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-change" />
      <span className="u-line">{countLabel(items.length, CHANGE_FORMS)} цього тижня</span>
    </button>
  );
}
