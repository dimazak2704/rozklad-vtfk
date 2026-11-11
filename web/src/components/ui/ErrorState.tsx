import { CircleAlert, RotateCcw, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { isApiError } from '@/api/errors';
import { cn } from '@/lib/cn';
import { Button } from './Button';
import { ErrorDetails } from './ErrorDetails';
import { asSentence, errorHint, errorTitle, useErrorCovered } from './error-scope';

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
 * Помилка на місці вмісту: одне речення «що сталося — що робити» і «Спробувати ще раз». Без рамки й коробки,
 * код запиту — за тихими «Подробицями». Решта сторінки (заголовок, шлях назад) працює.
 * Усередині `PageErrorScope`, що вже показує спільну помилку, блок нічого не малює: одна помилка на сторінку.
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
  if (covered) return null;

  const apiError = isApiError(error) ? error : null;
  const offline = apiError?.kind === 'network';
  const Icon = offline ? WifiOff : CircleAlert;
  const message = description ?? errorHint(error);
  const canRetry = onRetry && (!apiError || apiError.isRetryable || apiError.status === 0 || apiError.status >= 500);

  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col gap-3',
        align === 'center' ? 'items-center px-4 text-center' : 'items-start text-start',
        inset && align === 'start' && 'px-4',
        size === 'md' ? 'py-6' : 'py-4',
        className,
      )}
    >
      <p className={cn('flex max-w-prose gap-2 text-secondary', size === 'md' ? 'text-body' : 'text-ui', align === 'center' && 'justify-center')}>
        <Icon aria-hidden="true" className={cn('shrink-0 text-danger', size === 'md' ? 'mt-[0.1875rem] size-[1.125rem]' : 'mt-0.5 size-4')} />
        <span>
          <span className="font-[650] text-primary">{asSentence(title ?? errorTitle(error))}</span> {asSentence(message)}
        </span>
      </p>
      {canRetry || action || apiError?.requestId ? (
        <div className={cn('flex flex-wrap items-center gap-2', align === 'center' && 'justify-center')}>
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
          <ErrorDetails error={error} />
        </div>
      ) : null}
    </div>
  );
}
