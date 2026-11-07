import { Link } from 'react-router';
import { cn } from '@/lib/cn';

interface WordmarkProps {
  to?: string;
  /** Лише табличка «ВТФК» (згорнута бічна панель). */
  compact?: boolean;
  className?: string;
}

/**
 * Словесний знак: «Розклад» і табличка «ВТФК», як номер маршруту на емальованій табличці зупинки.
 * Офіційного логотипа коледжу в системі немає — герб не вигадуємо.
 */
export function Wordmark({ to = '/', compact = false, className }: WordmarkProps) {
  return (
    <Link
      to={to}
      aria-label="Розклад ВТФК — на головну"
      className={cn(
        'group/wordmark inline-flex shrink-0 items-center gap-2 rounded-control',
        'focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-focus-on-shell',
        className,
      )}
    >
      {!compact ? (
        <span className="font-condensed text-[1.375rem] leading-none font-bold tracking-[-0.01em] text-on-shell">
          Розклад
        </span>
      ) : null}
      <span
        aria-hidden="true"
        className="rounded-plate bg-plate px-1.5 py-1 font-condensed text-[0.9375rem] leading-none font-extrabold tracking-[0.04em] text-plate"
      >
        ВТФК
      </span>
    </Link>
  );
}
