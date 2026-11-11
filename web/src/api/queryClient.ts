import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { isApiError } from './errors';

/**
 * Політика кешу:
 * - розклад змінюється рідко, тож 30 с «свіжості» достатньо (SSE інвалідує раніше, коли треба);
 * - повтор лише для мережевих збоїв і 5xx/429: один раз для відповіді сервера, двічі для обриву мережі,
 *   з короткими паузами (0,7 і 1,4 с) — людина бачить помилку з «Спробувати ще раз» за 2–3 секунди,
 *   а не дивиться на скелет; 4xx одразу показуємо;
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
          const network = !isApiError(error) || error.kind === 'network' || error.kind === 'timeout';
          return failureCount < (network ? 2 : 1);
        },
        retryDelay: (attempt) => Math.min(700 * 2 ** attempt, 2000),
        refetchOnWindowFocus: true,
      },
      mutations: {
        retry: false,
      },
    },
  });
}

export const queryClient = createQueryClient();
