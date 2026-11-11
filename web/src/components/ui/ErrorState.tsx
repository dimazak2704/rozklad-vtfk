import { CircleAlert, RotateCcw, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { isApiError } from '@/api/errors';
import { cn } from '@/lib/cn';
import { Button } from './Button';
import { errorTitle, useErrorCovered } from './error-scope';

export interface ErrorStateProps {
  /** Помилка з запиту (ApiError або будь-яка інша). */
  error?: unknown;
  title?: ReactNode;
  description?: ReactNode;
  /** Повторити запит. Кнопка показується, лише якщо повтор має сенс. */
  onRetry?: () => void;
  retrying?: boolean;
  /** Додаткова дія: «На головну», «Увійти». */
  action?: ReactNode;
  align?: 'start' | 'center';
  size?: 'sm' | 'md';
  /** Внутрішній відступ панелі (16 px) — коли блок стоїть просто в рамці картки. */
  inset?: boolean;
  className?: string;
}

/**
 * Помилка в межах блоку: що сталося й що робити, одна кнопка «Спробувати ще раз». Решта сторінки працює.
 * Усередині `PageErrorScope`, що вже показує спільну помилку, блок стає тихим рядком без кнопки —
 * людина бачить одне речення й одну дію на сторінку, а не чотири однакові.
 */
export function ErrorState({
  error,
  title,
  description,
  onRetry,
  retrying = false,
  action,
  align = 'start',
  size = 'md',
  inset = false,
  className,
}: ErrorStateProps) {
  const covered = useErrorCovered();
  const apiError = isApiError(error) ? error : null;
  const offline = apiError?.kind === 'network';

  if (covered) {
    return (
      <p className={cn('py-3 text-ui text-muted', inset && 'px-4', align === 'center' && 'text-center', className)}>
        Не вдалося завантажити.
      </p>
    );
  }

  const message =
    description ??
    (offline || apiError?.kind === 'timeout'
      ? 'Перевірте інтернет і спробуйте ще раз.'
      : error instanceof Error && error.message
        ? error.message
        : 'Спробуйте ще раз трохи згодом.');
  const canRetry = onRetry && (!apiError || apiError.isRetryable || apiError.status === 0 || apiError.status >= 500);

  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col gap-1.5',
        align === 'center' ? 'items-center px-4 text-center' : 'items-start text-start',
        inset && align === 'start' && 'px-4',
        size === 'md' ? 'py-6' : 'py-4',
        className,
      )}
    >
      <p className={cn('flex items-center gap-2 font-[650] text-primary', size === 'md' ? 'text-body' : 'text-ui')}>
        {offline ? (
          <WifiOff aria-hidden="true" className="size-[1.125rem] text-danger" />
        ) : (
          <CircleAlert aria-hidden="true" className="size-[1.125rem] text-danger" />
        )}
        {title ?? errorTitle(error)}
      </p>
      <p className={cn('max-w-prose text-secondary', size === 'md' ? 'text-ui' : 'text-caption')}>{message}</p>
      {apiError?.requestId ? (
        <p className="text-caption text-muted">
          Код запиту: <span className="font-mono select-all">{apiError.requestId}</span>
        </p>
      ) : null}
      {canRetry || action ? (
        <div className={cn('mt-2 flex flex-wrap gap-2', align === 'center' && 'justify-center')}>
          {canRetry ? (
            <Button
              variant="secondary"
              size="sm"
              onClick={onRetry}
              loading={retrying}
              iconStart={<RotateCcw aria-hidden="true" className="size-4" />}
            >
              Спробувати ще раз
            </Button>
          ) : null}
          {action}
        </div>
      ) : null}
    </div>
  );
}
