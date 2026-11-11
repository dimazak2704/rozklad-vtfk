/** Спільне для ErrorState і PageErrorScope. Окремий файл, щоб компоненти лишалися «чистими» для Fast Refresh. */
import { createContext, useContext } from 'react';
import { isApiError } from '@/api/errors';

/** Те, що потрібно від результату useQuery. */
export interface QueryLike {
  isError: boolean;
  error: unknown;
  isFetching?: boolean;
  refetch: () => unknown;
}

/** true — сторінка вже показує одну спільну помилку; блоки всередині не повторюють її. */
export const ErrorScopeContext = createContext(false);

export function useErrorCovered(): boolean {
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
