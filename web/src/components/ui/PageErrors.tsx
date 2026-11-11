import { CircleAlert, RotateCcw, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { isApiError } from '@/api/errors';
import { cn } from '@/lib/cn';
import { Button } from './Button';
import { ErrorScopeContext, errorTitle, type QueryLike } from './error-scope';

export interface PageErrorBannerProps {
  /** Помилка з запиту (ApiError або будь-яка інша). */
  error: unknown;
  /** Повторити всі невдалі запити сторінки. Без нього кнопки немає. */
  onRetry?: () => void;
  retrying?: boolean;
  /** Своє речення замість типового («Не вдалося завантажити заміни»). */
  title?: ReactNode;
  className?: string;
}

/**
 * Одна помилка на сторінку: що сталося, що робити, одна кнопка «Спробувати ще раз».
 * Тихий рядок над вмістом, а не червона коробка: решта сторінки (кешовані дані, навігація) працює.
 */
export function PageErrorBanner({ error, onRetry, retrying = false, title, className }: PageErrorBannerProps) {
  const apiError = isApiError(error) ? error : null;
  const offline = apiError?.kind === 'network' || apiError?.kind === 'timeout';
  const Icon = offline ? WifiOff : CircleAlert;
  const hint = offline ? 'Перевірте інтернет і спробуйте ще раз.' : 'Спробуйте ще раз трохи згодом.';
  return (
    <div
      role="alert"
      className={cn(
        'flex flex-wrap items-center gap-x-4 gap-y-3 rounded-popover border border-default bg-surface px-4 py-3',
        className,
      )}
    >
      <Icon aria-hidden="true" className="size-5 shrink-0 text-danger" />
      <p className="min-w-0 flex-1 basis-56 text-ui text-secondary">
        <span className="font-[650] text-primary">{title ?? errorTitle(error)}.</span> {hint}
        {apiError?.requestId ? (
          <span className="block text-caption text-muted">
            Код запиту: <span className="font-mono select-all">{apiError.requestId}</span>
          </span>
        ) : null}
      </p>
      {onRetry ? (
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
    </div>
  );
}

export interface PageErrorScopeProps {
  /**
   * Запити сторінки (результати useQuery). Якщо хоч один упав, над вмістом зʼявляється одна
   * `PageErrorBanner`, а `ErrorState` усередині області стають тихим рядком без власної кнопки.
   */
  queries: ReadonlyArray<QueryLike | null | undefined>;
  title?: ReactNode;
  className?: string;
  bannerClassName?: string;
  children: ReactNode;
}

/**
 * Область сторінки з однією спільною помилкою. Замість чотирьох однакових «Сервіс недоступний»
 * з чотирма кнопками людина бачить одне речення й одну дію, що повторює всі невдалі запити.
 *
 * ```tsx
 * <PageErrorScope queries={[meta, bells, groups]}>
 *   <NowPanel /> <GroupsDirectory />
 * </PageErrorScope>
 * ```
 */
export function PageErrorScope({ queries, title, className, bannerClassName, children }: PageErrorScopeProps) {
  const failed = queries.filter((query): query is QueryLike => Boolean(query?.isError));
  const first = failed[0];
  const retrying = failed.some((query) => query.isFetching);
  return (
    <ErrorScopeContext.Provider value={Boolean(first)}>
      <div className={cn('flex flex-col gap-6', className)}>
        {first ? (
          <PageErrorBanner
            error={first.error}
            title={title}
            retrying={retrying}
            onRetry={() => failed.forEach((query) => void query.refetch())}
            className={bannerClassName}
          />
        ) : null}
        {children}
      </div>
    </ErrorScopeContext.Provider>
  );
}
