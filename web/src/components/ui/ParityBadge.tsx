import type { WeekParity } from '@/api/types';
import { cn } from '@/lib/cn';
import { capitalize, PARITY_HINT, PARITY_LABEL } from '@/lib/labels';
import { weekOrdinal } from '@/lib/ordinal';
import { Popover, PopoverContent, PopoverTrigger } from './Popover';

interface ParityMarkProps {
  parity: WeekParity;
  className?: string;
}

/**
 * Знак парності з друкованого розкладу: пара займає два рядки, верхній — чисельник, нижній — знаменник.
 * Заповнена половина показує, який рядок діє цього тижня. Ніколи не стоїть сам — поруч завжди слово.
 */
export function ParityMark({ parity, className }: ParityMarkProps) {
  return (
    <svg viewBox="0 0 12 14" aria-hidden="true" className={cn('h-3.5 w-3 shrink-0', className)}>
      <rect x="0.75" y="0.75" width="10.5" height="12.5" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <rect
        x="0.75"
        y={parity === 'NUMERATOR' ? 0.75 : 7}
        width="10.5"
        height="6.25"
        rx={1.5}
        fill="currentColor"
      />
    </svg>
  );
}

interface ParityBadgeProps {
  parity: WeekParity;
  /** Номер навчального тижня семестру. */
  weekNumber?: number;
  /** Слово перед парністю (необовʼязково; у шапці його немає). */
  prefix?: string;
  tone?: 'default' | 'on-shell';
  size?: 'sm' | 'md';
  /** Просто текст, без кнопки з поясненням (коли пояснення вже поруч). */
  plain?: boolean;
  className?: string;
}

/**
 * «Знаменник · 10-й тиждень» — звичайним регістром, як у реченні. Натискання (мишею, дотиком чи Enter)
 * відкриває пояснення, як чергуються тижні.
 */
export function ParityBadge({
  parity,
  weekNumber,
  prefix,
  tone = 'default',
  size = 'md',
  plain = false,
  className,
}: ParityBadgeProps) {
  const onShell = tone === 'on-shell';
  const quiet = onShell ? 'text-on-shell-muted' : 'text-secondary';
  const content = (
    <>
      <ParityMark parity={parity} className={cn(size === 'md' ? 'h-4 w-3.5' : 'h-3.5 w-3', onShell ? 'text-on-shell' : 'text-accent')} />
      {prefix ? <span className={quiet}>{prefix}</span> : null}
      <span className={cn('font-semibold', onShell ? 'text-on-shell' : 'text-primary')}>{capitalize(PARITY_LABEL[parity])}</span>
      {weekNumber ? (
        <span className={cn('figures', quiet)}>
          <span aria-hidden="true">· </span>
          {weekOrdinal(weekNumber)}
        </span>
      ) : null}
    </>
  );
  const shape = cn(
    'inline-flex items-center gap-1.5 rounded-control whitespace-nowrap',
    size === 'md' ? 'h-9 px-2 text-ui' : 'h-6 text-caption',
    size === 'sm' && !plain && 'px-1.5',
    className,
  );

  if (plain) return <span className={shape}>{content}</span>;

  return (
    <Popover>
      <PopoverTrigger
        className={cn(
          shape,
          'transition-[background-color] duration-100 ease-out focus-visible:outline-2 focus-visible:outline-offset-2',
          onShell ? 'hover:bg-shell-hover focus-visible:outline-focus-on-shell' : 'hover:bg-hover focus-visible:outline-focus',
        )}
        aria-label={`${capitalize(PARITY_LABEL[parity])}${weekNumber ? `, ${weekOrdinal(weekNumber)}` : ''}. Як чергуються тижні`}
      >
        {content}
      </PopoverTrigger>
      <PopoverContent width="sm" align="end" title={`Цього тижня — ${PARITY_LABEL[parity]}`}>
        <p className="text-ui text-secondary">{PARITY_HINT[parity]}</p>
      </PopoverContent>
    </Popover>
  );
}
