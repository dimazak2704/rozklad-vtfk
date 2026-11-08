import { Link } from 'react-router';
import { cn } from '@/lib/cn';

interface WordmarkProps {
  to?: string;
  /** Лише квадратний знак «ВТФК» емаллю (згорнута бічна панель на 64 px). */
  compact?: boolean;
  /**
   * `shell` — у синій смузі: білі літери, риска напівпрозора;
   * `surface` — на білій панелі диспетчера: чорнило й звичайна лінія.
   */
  tone?: 'shell' | 'surface';
  className?: string;
}

/**
 * Словесний знак — один і той самий скрізь: «ВТФК» вузьким жирним, тонка вертикальна риска і «Розклад»,
 * як рядок назви на титулі надрукованого розкладу. Без «таблички»: біла плашка на смузі читалась як бейдж версії.
 * Квадратний знак емаллю — лише там, де справді потрібен квадрат (згорнута панель, іконка застосунку).
 * Офіційного логотипа коледжу в системі немає — герб не вигадуємо.
 */
export function Wordmark({ to = '/', compact = false, tone = 'shell', className }: WordmarkProps) {
  const onShell = tone === 'shell';
  return (
    <Link
      to={to}
      aria-label="Розклад ВТФК — на головну"
      className={cn(
        'inline-flex shrink-0 items-center rounded-control',
        'focus-visible:outline-2 focus-visible:outline-offset-4',
        onShell ? 'focus-visible:outline-focus-on-shell' : 'focus-visible:outline-focus',
        className,
      )}
    >
      {compact ? (
        <span
          aria-hidden="true"
          className="inline-flex size-8 items-center justify-center rounded-control bg-shell font-condensed text-[0.8125rem] leading-none font-bold tracking-[0.02em] text-on-shell"
        >
          ВТФК
        </span>
      ) : (
        <span aria-hidden="true" className="inline-flex items-center gap-2.5 leading-none">
          <span
            className={cn(
              'font-condensed text-[1.0625rem] leading-5 font-bold tracking-[0.02em] md:text-[1.125rem] md:leading-5',
              onShell ? 'text-on-shell' : 'text-primary',
            )}
          >
            ВТФК
          </span>
          <span
            className={cn('h-5 w-px', onShell ? 'bg-[var(--text-color-on-shell)] opacity-35' : 'bg-[var(--border-color-default)]')}
          />
          <span className={cn('text-[0.9375rem] leading-5 font-medium md:text-body md:leading-5', onShell ? 'text-on-shell' : 'text-primary')}>
            Розклад
          </span>
        </span>
      )}
    </Link>
  );
}
