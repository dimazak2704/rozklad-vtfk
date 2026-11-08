import { ParityMark, Tooltip } from '@/components/ui';
import { cn } from '@/lib/cn';
import { patternDescription, patternLabel, type WeeklyPattern } from './pattern';

interface WeeklyPatternViewProps {
  pattern: WeeklyPattern;
  /** Колір лінії дисципліни (data-line) для клітинок. */
  line?: number;
  size?: 'sm' | 'md';
  className?: string;
}

const MAX_CELLS = 8;

/**
 * «1 + ½» як дві смужки тижнів: у чисельник і знаменник ідуть цілі пари, а половинка — лише в один
 * із тижнів (її клітинка в іншому тижні порожня й пунктирна). Видно, скільки місця займе дисципліна.
 */
export function WeeklyPatternView({ pattern, line, size = 'sm', className }: WeeklyPatternViewProps) {
  const label = patternLabel(pattern);
  const cells = Math.min(pattern.whole + pattern.half, MAX_CELLS);
  const cell = size === 'sm' ? 'h-3 w-3' : 'h-3.5 w-4';
  const rows = [
    { parity: 'NUMERATOR' as const, halfFilled: true },
    { parity: 'DENOMINATOR' as const, halfFilled: false },
  ];

  if (cells === 0) {
    return <span className={cn('text-muted', className)}>—</span>;
  }

  return (
    <Tooltip content={patternDescription(pattern)}>
      <span
        tabIndex={0}
        role="img"
        aria-label={`Шаблон тижня ${label}: ${patternDescription(pattern)}`}
        data-line={line}
        className={cn('inline-flex items-center gap-2 rounded-plate focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus', className)}
      >
        <span aria-hidden="true" className="flex flex-col gap-0.5">
          {rows.map((row) => (
            <span key={row.parity} className="flex items-center gap-0.5">
              <ParityMark parity={row.parity} className="me-0.5 h-3 w-2.5 text-muted" />
              {Array.from({ length: cells }, (_, index) => {
                const isHalf = index >= pattern.whole;
                const filled = !isHalf || row.halfFilled;
                return (
                  <span
                    key={index}
                    className={cn(
                      cell,
                      'rounded-[2px]',
                      filled
                        ? line !== undefined
                          ? 'bg-[var(--line-dot)]'
                          : 'bg-[var(--fill-accent)]'
                        : 'border border-dashed border-[var(--border-color-strong)]',
                      isHalf && filled && 'opacity-80',
                    )}
                  />
                );
              })}
            </span>
          ))}
        </span>
        <span className="font-condensed text-ui font-bold whitespace-nowrap figures text-primary">{label}</span>
      </span>
    </Tooltip>
  );
}
