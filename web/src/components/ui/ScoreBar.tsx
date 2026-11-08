import { cn } from '@/lib/cn';

interface ScoreBarProps {
  /** 0..100 */
  value: number;
  /** Підпис для читача екрана: «Придатність кандидата». */
  label?: string;
  /** Показати число (за замовчуванням так; без числа оцінка лишається лише для читача екрана). */
  showValue?: boolean;
  /** Ширина колонки: `md` — 40 px у рядку кандидата, `sm` — 32 px. */
  size?: 'sm' | 'md';
  /** Рекомендований варіант (перший): число чорнилом, решта — тихіше. Колір якість не градуює. */
  recommended?: boolean;
  className?: string;
}

/**
 * Оцінка кандидата на заміну (0–100) — лише число табличними цифрами в колонці праворуч.
 * Шкали-«смужки навички» немає: вона не додавала інформації до числа і робила список схожим на рейтинг
 * збігів. Що складає оцінку — текстом у рядку кандидата і в підказці «Чому?».
 */
export function ScoreBar({ value, label = 'Оцінка', showValue = true, size = 'md', recommended = false, className }: ScoreBarProps) {
  const score = Math.max(0, Math.min(100, Math.round(value)));
  return (
    <span
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={score}
      aria-valuetext={`${score} зі 100`}
      className={cn(
        'inline-flex shrink-0 items-center justify-end text-ui font-semibold figures',
        size === 'md' ? 'w-10' : 'w-8',
        recommended ? 'text-primary' : 'text-secondary',
        !showValue && 'sr-only',
        className,
      )}
    >
      {score}
    </span>
  );
}
