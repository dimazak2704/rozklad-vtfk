import { CircleAlert, RotateCcw, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { isApiError } from '@/api/errors';
import { cn } from '@/lib/cn';
import { Button } from './Button';

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
  className?: string;
}

function titleFor(error: unknown): string {
  if (!isApiError(error)) return 'Не вдалося завантажити';
  if (error.kind === 'network' || error.kind === 'timeout') return 'Немає звʼязку із сервером';
  if (error.isNotFound) return 'Не знайдено';
  if (error.isForbidden) return 'Немає доступу';
  if (error.isUnauthorized) return 'Потрібно увійти';
  if (error.status >= 500) return 'Сервер не відповів як слід';
  return 'Не вдалося виконати запит';
}

/** Помилка в межах блоку: що сталося, що робити, кнопка «Спробувати ще раз». Решта сторінки працює. */
export function ErrorState({
  error,
  title,
  description,
  onRetry,
  retrying = false,
  action,
  align = 'start',
  size = 'md',
  className,
}: ErrorStateProps) {
  const apiError = isApiError(error) ? error : null;
  const offline = apiError?.kind === 'network';
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
        'flex flex-col gap-2',
        align === 'center' ? 'items-center text-center' : 'items-start text-start',
        size === 'md' ? 'px-4 py-8' : 'px-4 py-5',
        className,
      )}
    >
      <p className={cn('flex items-center gap-2 font-semibold text-primary', size === 'md' ? 'text-heading' : 'text-ui')}>
        {offline ? (
          <WifiOff aria-hidden="true" className="size-5 text-danger" />
        ) : (
          <CircleAlert aria-hidden="true" className="size-5 text-danger" />
        )}
        {title ?? titleFor(error)}
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
              size={size === 'md' ? 'md' : 'sm'}
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
