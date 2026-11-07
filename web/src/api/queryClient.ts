import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { isApiError } from './errors';

/**
 * Політика кешу:
 * - розклад змінюється рідко, тож 30 с «свіжості» достатньо (SSE інвалідує раніше, коли треба);
 * - повтор лише для мережевих збоїв і 5xx/429, максимум двічі; 4xx одразу показуємо;
 * - мутації не повторюються автоматично.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        // фонові оновлення не мають ламати сторінку; помилку показує сам компонент
        if (import.meta.env.DEV && !isApiError(error)) {
          console.error('Query failed', query.queryKey, error);
        }
      },
    }),
    mutationCache: new MutationCache(),
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 10 * 60_000,
        retry: (failureCount, error) => {
          if (isApiError(error) && !error.isRetryable) return false;
          return failureCount < 2;
        },
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
        refetchOnWindowFocus: true,
      },
      mutations: {
        retry: false,
      },
    },
  });
}

export const queryClient = createQueryClient();
