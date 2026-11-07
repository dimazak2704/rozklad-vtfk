import type { WeekParity } from '@/api/types';
import { cn } from '@/lib/cn';
import { PARITY_HINT, PARITY_LABEL } from '@/lib/labels';
import { Tooltip } from './Tooltip';

interface ParityMarkProps {
  parity: WeekParity;
  className?: string;
}

/**
 * Знак парності з друкованого розкладу: пара займає два рядки, верхній — чисельник, нижній — знаменник.
 * Заповнена половина показує, який рядок діє цього тижня.
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
  /** «Зараз» — для шапки сайту. */
  prefix?: string;
  tone?: 'default' | 'on-shell';
  size?: 'sm' | 'md';
  /** Без підказки (коли пояснення вже поруч). */
  plain?: boolean;
  className?: string;
}

/** «Зараз ЗНАМЕННИК · 8 тиждень» з підказкою, як працює чергування. */
export function ParityBadge({
  parity,
  weekNumber,
  prefix,
  tone = 'default',
  size = 'md',
  plain = false,
  className,
}: ParityBadgeProps) {
  const label = PARITY_LABEL[parity];
  const badge = (
    <span
      tabIndex={plain ? undefined : 0}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-control font-semibold whitespace-nowrap',
        size === 'md' ? 'h-8 px-2.5 text-ui' : 'h-6 px-2 text-caption',
        tone === 'default' && 'border border-default bg-surface text-primary',
        tone === 'on-shell' && 'bg-shell-raised text-on-shell',
        !plain && 'cursor-help focus-visible:outline-2 focus-visible:outline-offset-2',
        !plain && (tone === 'on-shell' ? 'focus-visible:outline-focus-on-shell' : 'focus-visible:outline-focus'),
        className,
      )}
    >
      <ParityMark parity={parity} className={tone === 'on-shell' ? 'text-on-shell' : 'text-accent'} />
      {prefix ? <span className={tone === 'on-shell' ? 'text-on-shell-muted' : 'text-secondary'}>{prefix}</span> : null}
      <span className="font-condensed text-[1.05em] font-bold tracking-wider uppercase">{label}</span>
      {weekNumber ? (
        <>
          <span aria-hidden="true" className={tone === 'on-shell' ? 'text-on-shell-muted' : 'text-muted'}>
            ·
          </span>
          <span className={cn('figures', tone === 'on-shell' ? 'text-on-shell-muted' : 'text-secondary')}>
            {weekNumber}&nbsp;тиждень
          </span>
        </>
      ) : null}
    </span>
  );
  if (plain) return badge;
  return <Tooltip content={PARITY_HINT[parity]}>{badge}</Tooltip>;
}
