import { cn } from '@/lib/cn';

interface ScoreBarProps {
  /** 0..100 */
  value: number;
  /** Підпис для читача екрана: «Придатність кандидата». */
  label?: string;
  /** Показати число праворуч. */
  showValue?: boolean;
  size?: 'sm' | 'md';
  className?: string;
}

/**
 * Смуга оцінки кандидата на заміну (0–100). Найкращі варіанти — насичений маршрутний синій,
 * середні — приглушений, слабкі — сірий. Число завжди поруч, колір лише підсилює.
 */
export function ScoreBar({ value, label = 'Оцінка', showValue = true, size = 'md', className }: ScoreBarProps) {
  const score = Math.max(0, Math.min(100, Math.round(value)));
  const tier = score >= 70 ? 'high' : score >= 40 ? 'mid' : 'low';
  return (
    <div className={cn('flex items-center gap-2', className)}>
      <div
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={score}
        aria-valuetext={`${score} зі 100`}
        className={cn('relative flex-1 overflow-hidden rounded-full bg-sunken', size === 'md' ? 'h-2' : 'h-1.5')}
      >
        <span
          className={cn(
            'absolute inset-y-0 start-0 rounded-full transition-[width] duration-300 ease-out',
            tier === 'high' && 'bg-accent',
            tier === 'mid' && 'bg-[var(--border-color-accent-subtle)]',
            tier === 'low' && 'bg-[var(--border-color-strong)]',
          )}
          style={{ width: `${score}%` }}
        />
      </div>
      {showValue ? (
        <span className="w-7 text-end font-condensed text-ui font-bold figures text-primary" aria-hidden="true">
          {score}
        </span>
      ) : null}
    </div>
  );
}
