import { cn } from '@/lib/cn';

interface ScoreBarProps {
  /** 0..100 */
  value: number;
  /** Підпис для читача екрана: «Придатність кандидата». */
  label?: string;
  /** Показати число ліворуч від шкали. */
  showValue?: boolean;
  /** `md` — 48×4 px у колонці 96 px рядка кандидата, `sm` — 32×4 px. */
  size?: 'sm' | 'md';
  /** Рекомендований варіант (перший): лише він має синю заливку. */
  recommended?: boolean;
  className?: string;
}

/**
 * Оцінка кандидата на заміну (0–100): число 16/600 і коротка шкала 4 px. Колір не градуює якість —
 * шкала нейтральна, синя лише в рекомендованого варіанта. Причина оцінки — текстом у рядку кандидата.
 */
export function ScoreBar({ value, label = 'Оцінка', showValue = true, size = 'md', recommended = false, className }: ScoreBarProps) {
  const score = Math.max(0, Math.min(100, Math.round(value)));
  return (
    <div className={cn('inline-flex items-center justify-end gap-2', className)}>
      {showValue ? (
        <span className="min-w-7 text-end text-body font-semibold text-primary figures" aria-hidden="true">
          {score}
        </span>
      ) : null}
      <div
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={score}
        aria-valuetext={`${score} зі 100`}
        className={cn('relative h-1 shrink-0 overflow-hidden rounded-[2px] bg-hover', size === 'md' ? 'w-12' : 'w-8')}
      >
        <span
          className={cn(
            'absolute inset-y-0 start-0 rounded-[2px] transition-[width] duration-300 ease-out',
            recommended ? 'bg-accent' : 'bg-[var(--border-color-strong)]',
          )}
          style={{ width: `${score}%` }}
        />
      </div>
    </div>
  );
}
