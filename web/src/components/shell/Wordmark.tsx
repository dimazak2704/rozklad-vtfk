import { Link } from 'react-router';
import { cn } from '@/lib/cn';

interface WordmarkProps {
  to?: string;
  /** Лише табличка «ВТФК» (згорнута бічна панель, вузька верхня панель). */
  compact?: boolean;
  /**
   * `shell` — у синій смузі: біле слово й біла табличка з синім кодом;
   * `surface` — на білій панелі диспетчера: табличка емаллю, слово чорнилом.
   */
  tone?: 'shell' | 'surface';
  className?: string;
}

/**
 * Словесний знак: «Розклад» і табличка «ВТФК», як номер маршруту на емальованій табличці зупинки.
 * Офіційного логотипа коледжу в системі немає — герб не вигадуємо.
 */
export function Wordmark({ to = '/', compact = false, tone = 'shell', className }: WordmarkProps) {
  const onShell = tone === 'shell';
  return (
    <Link
      to={to}
      aria-label="Розклад ВТФК — на головну"
      className={cn(
        'group/wordmark inline-flex shrink-0 items-center gap-2 rounded-control',
        'focus-visible:outline-2 focus-visible:outline-offset-4',
        onShell ? 'focus-visible:outline-focus-on-shell' : 'focus-visible:outline-focus',
        className,
      )}
    >
      {!compact ? (
        <span
          className={cn(
            'leading-none',
            onShell
              ? 'font-condensed text-[1.375rem] font-bold tracking-[-0.01em] text-on-shell'
              : 'text-heading font-semibold text-primary',
          )}
        >
          Розклад
        </span>
      ) : null}
      <span
        aria-hidden="true"
        className={cn(
          'rounded-plate px-1.5 py-0.5 font-condensed text-ui leading-[1.125rem] font-bold tracking-[0.03em]',
          onShell ? 'bg-plate text-plate' : 'bg-shell text-on-shell',
        )}
      >
        ВТФК
      </span>
    </Link>
  );
}
