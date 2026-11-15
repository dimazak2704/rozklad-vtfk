import { CalendarRange } from 'lucide-react';
import type { PracticeDto } from '@/api/types';
import { cn } from '@/lib/cn';
import type { Perspective } from '@/lib/schedule';
import { useGridNames } from './grid-names';
import { blocksLabel, blockTitle, supervisorsLine } from './practice';

interface PeriodBlockProps {
  practices: readonly PracticeDto[];
  perspective: Perspective;
  className?: string;
}

/**
 * Період замість пар (практика, НС, збори): група бачить «{Вид}: назва, ауд. 401» з керівниками, викладач —
 * «Керівництво: {Вид}: назва, група». Якщо керівника не призначили, публічно лишається тільки назва.
 */
export function PeriodBlock({ practices, perspective, className }: PeriodBlockProps) {
  const names = useGridNames();
  if (practices.length === 0) return null;
  return (
    <ul className={cn('flex flex-col gap-2', className)} aria-label={blocksLabel(practices, perspective)}>
      {practices.map((practice) => {
        const supervisors = perspective === 'group' ? supervisorsLine(practice, names) : null;
        return (
          <li key={practice.periodId} className="flex items-start gap-2 text-start">
            <CalendarRange aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted" />
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="text-ui font-[600] text-primary">{blockTitle(practice, perspective)}</span>
              {supervisors ? <span className="text-caption text-secondary">{supervisors}</span> : null}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
