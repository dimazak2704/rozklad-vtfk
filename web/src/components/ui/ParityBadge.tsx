import type { WeekParity } from '@/api/types';
import { cn } from '@/lib/cn';
import { capitalize, PARITY_HINT, PARITY_LABEL } from '@/lib/labels';
import { weekOrdinal } from '@/lib/ordinal';
import { Popover, PopoverContent, PopoverTrigger } from './Popover';
import { Tooltip } from './Tooltip';

interface ParityMarkProps {
  parity: WeekParity;
  className?: string;
}

/**
 * Знак парності — дріб із друкованого розкладу: дві смужки й риска між ними. Верхня смужка — чисельник,
 * нижня — знаменник; заповнена та, що діє цього тижня.
 * У шапках і сітці його більше немає: при 12–16 px він читався як значок бази даних, тож парність
 * завжди називає слово. Лишається для пояснення правила (картка деталей, сторінка «Про систему»).
 */
export function ParityMark({ parity, className }: ParityMarkProps) {
  const top = parity === 'NUMERATOR';
  const filled = (y: number) => <rect x="3" y={y} width="10" height="4" rx="1" fill="currentColor" />;
  const hollow = (y: number) => (
    <rect x="3.75" y={y + 0.75} width="8.5" height="2.5" rx="0.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
  );
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true" className={cn('size-3.5 shrink-0', className)}>
      {top ? filled(2) : hollow(2)}
      <rect x="1" y="7.5" width="14" height="1" fill="currentColor" />
      {top ? hollow(10) : filled(10)}
    </svg>
  );
}

/** Коротка підказка до знака: що означає парність цього тижня. */
const PARITY_TIP: Record<WeekParity, string> = {
  NUMERATOR: 'Чисельник: пари з верхнього рядка розкладу',
  DENOMINATOR: 'Знаменник: пари з нижнього рядка розкладу',
};

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
 * «Знаменник · 10-й тиждень» — словами, звичайним регістром, як у реченні; без вигаданого знака.
 * Натискання (мишею, дотиком чи Enter) відкриває пояснення, як чергуються тижні.
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
      <Tooltip content={PARITY_TIP[parity]}>
        <PopoverTrigger
          className={cn(
            shape,
            'transition-[background-color] duration-100 ease-out focus-visible:outline-2 focus-visible:outline-offset-2',
            onShell
              ? 'hover:bg-shell-hover active:bg-shell-raised focus-visible:outline-focus-on-shell'
              : 'hover:bg-hover active:bg-active focus-visible:outline-focus',
          )}
          aria-label={`${capitalize(PARITY_LABEL[parity])}${weekNumber ? `, ${weekOrdinal(weekNumber)}` : ''}. Як чергуються тижні`}
        >
          {content}
        </PopoverTrigger>
      </Tooltip>
      <PopoverContent width="sm" align="end" title={`Цього тижня — ${PARITY_LABEL[parity]}`}>
        <p className="text-ui text-secondary">{PARITY_HINT[parity]}</p>
      </PopoverContent>
    </Popover>
  );
}
