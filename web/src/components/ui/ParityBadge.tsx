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
 * Знак парності — диск 14 px, заповнений наполовину: верхня половина — чисельник (верхній рядок
 * друкованого розкладу), нижня — знаменник. Безбарвний: парність не має кольору. Завжди поруч зі словом.
 */
export function ParityMark({ parity, className }: ParityMarkProps) {
  const top = parity === 'NUMERATOR';
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true" className={cn('size-3.5 shrink-0', className)}>
      <circle cx="8" cy="8" r="6.75" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <path d={top ? 'M1.25 8a6.75 6.75 0 0 1 13.5 0Z' : 'M1.25 8a6.75 6.75 0 0 0 13.5 0Z'} fill="currentColor" />
    </svg>
  );
}

/** Коротка підказка до знака: що означає парність цього тижня. */
const PARITY_TIP: Record<WeekParity, string> = {
  NUMERATOR: 'Чисельник: пари з верхнього рядка розкладу',
  DENOMINATOR: 'Знаменник: пари з нижнього рядка розкладу',
};

interface ParityBadgeProps {
  /** null — дата поза семестром: парності немає, значок не показуємо (не вигадуємо її). */
  parity: WeekParity | null;
  /** Номер навчального тижня семестру. */
  weekNumber?: number | null;
  /** Слово перед парністю (необовʼязково). */
  prefix?: string;
  /** Застаріле: колишня синя смуга; вигляд тепер один. */
  tone?: 'default' | 'on-shell';
  size?: 'sm' | 'md';
  /** Просто текст, без кнопки з поясненням (коли пояснення вже поруч). */
  plain?: boolean;
  /** Диск парності перед словом. Показуйте парність один раз на екрані. */
  mark?: boolean;
  className?: string;
}

/**
 * «◐ Чисельник · 11-й тиждень» — словами, звичайним регістром; диск — лише підказка форми.
 * Натискання (мишею, дотиком чи Enter) відкриває пояснення, як чергуються тижні.
 * Правило: парність і номер тижня — один раз на екран.
 */
export function ParityBadge({ parity, weekNumber, prefix, size = 'md', plain = false, mark = true, className }: ParityBadgeProps) {
  if (!parity) return null;
  const content = (
    <>
      {mark ? <ParityMark parity={parity} className="text-secondary" /> : null}
      {prefix ? <span className="text-secondary">{prefix}</span> : null}
      <span className="font-[650] text-primary">{capitalize(PARITY_LABEL[parity])}</span>
      {weekNumber ? (
        <span className="text-secondary">
          <span aria-hidden="true">· </span>
          {weekOrdinal(weekNumber)}
        </span>
      ) : null}
    </>
  );
  const shape = cn(
    'inline-flex items-center gap-1.5 rounded-control whitespace-nowrap',
    size === 'md' ? 'h-9 px-2 text-ui' : 'h-7 text-caption',
    size === 'sm' && !plain && '-mx-1.5 px-1.5 pointer-coarse:h-11',
    className,
  );

  if (plain) return <span className={shape}>{content}</span>;

  return (
    <Popover>
      <Tooltip content={PARITY_TIP[parity]}>
        <PopoverTrigger
          className={cn(
            shape,
            'transition-[background-color] duration-150 ease-out hover:bg-hover active:bg-active',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
          )}
          aria-label={`${capitalize(PARITY_LABEL[parity])}${weekNumber ? `, ${weekOrdinal(weekNumber)}` : ''}. Як чергуються тижні`}
        >
          {content}
        </PopoverTrigger>
      </Tooltip>
      <PopoverContent width="sm" align="start" title={`Цього тижня ${PARITY_LABEL[parity]}`}>
        <p className="text-ui text-secondary">{PARITY_HINT[parity]}</p>
      </PopoverContent>
    </Popover>
  );
}
