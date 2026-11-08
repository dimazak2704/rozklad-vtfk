import { ArrowLeftRight } from 'lucide-react';
import type { IsoDate } from '@/api/types';
import { TONE_BADGE } from '@/components/schedule';
import { Badge } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatShortWeekdayDate } from '@/lib/date';
import { countLabel, LESSON_VIEW_KIND_LABEL } from '@/lib/labels';
import { LESSON_TONE } from '@/lib/schedule';
import { changeSummary, reasonHint, type ChangedLesson } from './schedule-utils';

interface ChangesStripProps {
  items: readonly ChangedLesson[];
  /** Перехід до дня зміни (у поданні «День»). Без нього рядки — просто текст. */
  onSelect?: (date: IsoDate) => void;
  title?: string;
  /** Показувати дату (на тижні) чи лише пару (у межах одного дня). */
  showDate?: boolean;
  className?: string;
}

/**
 * Оголошення про «обʼїзд»: усі зміни періоду одним списком — щоб їх неможливо було пропустити
 * навіть у щільній сітці тижня. Колір змін дублюється бейджем і текстом.
 */
export function ChangesStrip({ items, onSelect, title = 'Зміни цього тижня', showDate = true, className }: ChangesStripProps) {
  if (items.length === 0) return null;
  return (
    <section
      aria-label={title}
      className={cn('flex flex-col gap-2 rounded-panel border border-change bg-change-subtle px-3.5 py-3', className)}
    >
      <h2 className="flex items-center gap-2 text-ui font-semibold text-primary">
        <ArrowLeftRight aria-hidden="true" className="size-4 text-change" />
        {title}
        <span className="text-caption font-normal text-secondary">{countLabel(items.length, ['зміна', 'зміни', 'змін'])}</span>
      </h2>
      <ul className="flex flex-col gap-1">
        {items.map(({ day, lesson, pairTo }) => {
          const tone = LESSON_TONE[lesson.kind];
          const reason = lesson.reasonText;
          const hint = reasonHint(reason);
          const body = (
            <>
              <span className="min-w-[8.25rem] shrink-0 text-caption font-semibold whitespace-nowrap text-secondary figures">
                {showDate ? `${formatShortWeekdayDate(day.date)} · ` : ''}
                {pairTo ? `${lesson.pair}–${pairTo} пари` : `${lesson.pair} пара`}
              </span>
              <span className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-0.5">
                <Badge tone={TONE_BADGE[tone]} size="sm">
                  {LESSON_VIEW_KIND_LABEL[lesson.kind]}
                </Badge>
                <span className="min-w-0 text-ui text-primary">
                  {lesson.subgroup > 0 ? <span className="text-secondary">{lesson.subgroup} п/г · </span> : null}
                  {changeSummary(lesson)}
                </span>
                {reason ? (
                  <span className="text-caption font-semibold text-secondary" title={hint ?? undefined}>
                    {reason}
                    {hint ? <span className="sr-only"> ({hint})</span> : null}
                  </span>
                ) : null}
              </span>
            </>
          );
          return (
            <li key={`${day.date}-${lesson.id}`}>
              {onSelect ? (
                <button
                  type="button"
                  onClick={() => onSelect(day.date)}
                  className="flex w-full items-baseline gap-2 rounded-control px-1.5 py-1 text-start hover:bg-[color-mix(in_oklab,var(--background-color-change)_18%,transparent)] focus-visible:outline-2 focus-visible:outline-focus"
                >
                  {body}
                </button>
              ) : (
                <div className="flex items-baseline gap-2 px-1.5 py-1">{body}</div>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
