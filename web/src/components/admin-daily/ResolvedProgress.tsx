import { Check } from 'lucide-react';
import { cn } from '@/lib/cn';
import { PAIR_FORMS_GEN, plural } from '@/lib/labels';

interface ResolvedProgressProps {
  done: number;
  total: number;
  className?: string;
}

/**
 * «Вирішено 3 з 7 пар» і тонка смуга 160 px: одне число, що справді щось вирішує (DESIGN §7).
 * Усе закрито — «Усі пари вирішено» з галочкою, без смуги.
 */
export function ResolvedProgress({ done, total, className }: ResolvedProgressProps) {
  if (total === 0) return null;
  if (done >= total) {
    return (
      <span className={cn('inline-flex items-center gap-1.5 text-ui text-add', className)}>
        <Check aria-hidden="true" className="size-4" />
        Усі пари вирішено
      </span>
    );
  }
  return (
    <span className={cn('inline-flex flex-wrap items-center gap-x-3 gap-y-1.5', className)}>
      <span className="text-ui text-secondary">
        Вирішено <span className="figures text-primary">{done}</span> з <span className="figures text-primary">{total}</span>{' '}
        {plural(total, PAIR_FORMS_GEN)}
      </span>
      <span
        role="progressbar"
        aria-label="Вирішено пар"
        aria-valuemin={0}
        aria-valuemax={total}
        aria-valuenow={done}
        className="relative h-1 w-40 overflow-hidden rounded-full bg-[var(--fill-track-future)]"
      >
        <span
          className="absolute inset-0 origin-left rounded-full bg-[var(--fill-accent)] transition-transform duration-200 ease-out rtl:origin-right"
          style={{ transform: `scaleX(${done / total})` }}
        />
      </span>
    </span>
  );
}
