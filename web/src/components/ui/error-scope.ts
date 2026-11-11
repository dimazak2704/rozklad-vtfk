/** Спільне для ErrorState, PageErrorScope і PageHeader. Окремий файл, щоб компоненти лишалися «чистими» для Fast Refresh. */
import { createContext, useContext, type ReactNode } from 'react';
import { defaultMessage, isApiError } from '@/api/errors';

/** Те, що потрібно від результату useQuery. */
export interface QueryLike {
  isError: boolean;
  error: unknown;
  isFetching?: boolean;
  refetch: () => unknown;
}

/** Помилка сторінки, яку показує один рядок під заголовком (або вгорі області, якщо заголовка немає). */
export interface PageFailure {
  error: unknown;
  title?: ReactNode;
  retrying: boolean;
  onRetry: () => void;
}

export interface ErrorScopeValue {
  /** Невдалий запит сторінки; null — усе гаразд. */
  failure: PageFailure | null;
  /**
   * PageHeader усередині області реєструється тут і сам показує рядок помилки під заголовком:
   * назва сторінки й шлях назад лишаються на місці. Повертає функцію зняття реєстрації.
   */
  registerHeader: () => () => void;
}

export const ErrorScopeContext = createContext<ErrorScopeValue | null>(null);

/** true — сторінка вже показує одну спільну помилку; блоки всередині не повторюють її. */
export function useErrorCovered(): boolean {
  return Boolean(useContext(ErrorScopeContext)?.failure);
}

export function useErrorScope(): ErrorScopeValue | null {
  return useContext(ErrorScopeContext);
}

/** Що сталося — одним коротким реченням без крапки. */
export function errorTitle(error: unknown): string {
  if (!isApiError(error)) return 'Не вдалося завантажити';
  if (error.kind === 'network' || error.kind === 'timeout') return 'Немає звʼязку із сервером';
  if (error.isNotFound) return 'Не знайдено';
  if (error.isForbidden) return 'Немає доступу';
  if (error.isUnauthorized) return 'Потрібно увійти';
  // gateway: сервіс зупинено чи перезапускається (503) або він не відповів вчасно (504)
  if (error.status === 503) return 'Сервіс тимчасово недоступний';
  if (error.status === 504) return 'Сервер не відповів вчасно';
  if (error.status >= 500) return 'Сервер не відповів як слід';
  return 'Не вдалося виконати запит';
}

/** Що робити: сервер міг дати свою пораду, інакше — коротка загальна. */
export function errorHint(error: unknown): string {
  const apiError = isApiError(error) ? error : null;
  if (apiError?.kind === 'network' || apiError?.kind === 'timeout') return 'Перевірте інтернет і спробуйте ще раз.';
  // типове повідомлення 5xx повторило б заголовок («Сервіс тимчасово недоступний»); порада сервера — лишається
  if (apiError && apiError.status >= 500 && apiError.message === defaultMessage(apiError.status, apiError.kind)) return 'Спробуйте ще раз трохи згодом.';
  if (error instanceof Error && error.message) return error.message;
  return 'Спробуйте ще раз трохи згодом.';
}

/** Рядок закінчується крапкою, щоб «Що сталося. Що робити.» читалося як два речення. */
export function asSentence(text: ReactNode): ReactNode {
  if (typeof text !== 'string') return text;
  const trimmed = text.trim();
  return /[.!?…]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}
