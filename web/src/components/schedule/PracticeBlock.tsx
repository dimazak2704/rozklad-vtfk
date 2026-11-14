import { Wrench } from 'lucide-react';
import type { PracticeDto } from '@/api/types';
import { cn } from '@/lib/cn';
import type { Perspective } from '@/lib/schedule';
import { useGridNames } from './grid-names';
import { practiceTitle, supervisorsLine } from './practice';

interface PracticeBlockProps {
  practices: readonly PracticeDto[];
  perspective: Perspective;
  className?: string;
}

/**
 * Практика замість пар: група бачить «Практика: назва» з керівниками, викладач — «Керівництво
 * практикою: назва, група». Якщо керівника не призначили, публічно лишається тільки назва.
 */
export function PracticeBlock({ practices, perspective, className }: PracticeBlockProps) {
  const names = useGridNames();
  if (practices.length === 0) return null;
  return (
    <ul className={cn('flex flex-col gap-2', className)} aria-label="Практика">
      {practices.map((practice) => {
        const supervisors = perspective === 'group' ? supervisorsLine(practice, names) : null;
        return (
          <li key={practice.periodId} className="flex items-start gap-2 text-start">
            <Wrench aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted" />
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="text-ui font-[600] text-primary">{practiceTitle(practice, perspective)}</span>
              {supervisors ? <span className="text-caption text-secondary">{supervisors}</span> : null}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
