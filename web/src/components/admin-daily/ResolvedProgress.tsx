import { Check } from 'lucide-react';
import { cn } from '@/lib/cn';
import { PAIR_FORMS_GEN, plural } from '@/lib/labels';

interface ResolvedProgressProps {
  done: number;
  total: number;
  /** Пари, що чекають відповіді викладача: не вирішені, але й дії диспетчера ще не треба. */
  waiting?: number;
  className?: string;
}

/**
 * «Вирішено 3 з 7 пар» і тонка смуга 160 px: одне число, що справді щось вирішує (DESIGN §7).
 * Пари, що чекають відповіді, рахуються окремо («· 1 чекає відповіді», бурштиновий відрізок смуги).
 * Усе закрито — «Усі пари вирішено» з галочкою, без смуги.
 */
export function ResolvedProgress({ done, total, waiting = 0, className }: ResolvedProgressProps) {
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
        {waiting > 0 ? (
          <span className="text-change">
            {' '}
            · <span className="figures">{waiting}</span> {waiting === 1 ? 'чекає' : 'чекають'} відповіді
          </span>
        ) : null}
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
        {waiting > 0 ? (
          <span
            aria-hidden="true"
            className="absolute inset-y-0 rounded-full bg-[var(--fill-change)] transition-[left,width] duration-200 ease-out"
            style={{ insetInlineStart: `${(done / total) * 100}%`, width: `${(waiting / total) * 100}%` }}
          />
        ) : null}
      </span>
    </span>
  );
}
