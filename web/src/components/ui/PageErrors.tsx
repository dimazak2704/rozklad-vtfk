import { CircleAlert, RotateCcw, WifiOff } from 'lucide-react';
import { useCallback, useState, type ReactNode } from 'react';
import { isApiError } from '@/api/errors';
import { cn } from '@/lib/cn';
import { Button } from './Button';
import { ErrorDetails } from './ErrorDetails';
import { asSentence, errorHint, ErrorScopeContext, errorTitle, type QueryLike } from './error-scope';

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
 * Одна помилка на сторінку: одне речення «що сталося — що робити» і «Спробувати ще раз» поруч.
 * Без рамки й тла: це рядок на місці вмісту, а не червона коробка. Код запиту — за «Подробицями».
 */
export function PageErrorBanner({ error, onRetry, retrying = false, title, className }: PageErrorBannerProps) {
  const apiError = isApiError(error) ? error : null;
  const offline = apiError?.kind === 'network' || apiError?.kind === 'timeout';
  const Icon = offline ? WifiOff : CircleAlert;
  return (
    // той самий вигляд, що й ErrorState: речення, під ним дії — один патерн помилки на весь застосунок
    <div role="alert" className={cn('flex flex-col items-start gap-3', className)}>
      <p className="flex min-w-0 max-w-prose gap-2 text-body text-secondary">
        <Icon aria-hidden="true" className="mt-[0.1875rem] size-[1.125rem] shrink-0 text-danger" />
        <span>
          <span className="font-[650] text-primary">{asSentence(title ?? errorTitle(error))}</span> {asSentence(errorHint(error))}
        </span>
      </p>
      <div className="flex flex-wrap items-center gap-2">
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
        <ErrorDetails error={error} />
      </div>
    </div>
  );
}

export interface PageErrorScopeProps {
  /**
   * Запити сторінки (результати useQuery). Якщо хоч один упав, зʼявляється один рядок помилки
   * під заголовком сторінки, а `ErrorState` усередині області не малюються зовсім.
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
 * Рядок стоїть одразу під `PageHeader` області (назва й «‹ Назад» лишаються на місці);
 * якщо заголовка немає (головна), — на початку області.
 *
 * ```tsx
 * <PageErrorScope queries={[meta, bells, groups]}>
 *   <PageHeader title="Дзвінки" /> <NowPanel /> <GroupsDirectory />
 * </PageErrorScope>
 * ```
 */
export function PageErrorScope({ queries, title, className, bannerClassName, children }: PageErrorScopeProps) {
  const failed = queries.filter((query): query is QueryLike => Boolean(query?.isError));
  const first = failed[0];
  const retrying = failed.some((query) => query.isFetching);
  const [headers, setHeaders] = useState(0);
  const registerHeader = useCallback(() => {
    setHeaders((count) => count + 1);
    return () => setHeaders((count) => count - 1);
  }, []);

  // обʼєкт новий щорендеру: споживачі (PageHeader, ErrorState) — діти області й так перемальовуються разом із нею
  const failure = first
    ? { error: first.error, title, retrying, onRetry: () => failed.forEach((query) => void query.refetch()) }
    : null;
  const value = { failure, registerHeader };

  return (
    <ErrorScopeContext.Provider value={value}>
      <div className={cn('flex flex-col gap-6', className)}>
        {failure && headers === 0 ? (
          <PageErrorBanner
            error={failure.error}
            title={failure.title}
            retrying={failure.retrying}
            onRetry={failure.onRetry}
            className={bannerClassName}
          />
        ) : null}
        {children}
      </div>
    </ErrorScopeContext.Provider>
  );
}
