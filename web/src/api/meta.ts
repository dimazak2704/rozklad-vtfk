/**
 * Запити, потрібні оболонці застосунку (шапка, пошук, «зараз»). Сторінкові області —
 * у власних файлах src/api/<область>.ts (див. src/api/README.md).
 */
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type { BellsDto, IsoDate, PublicMetaDto, SearchResultDto } from './types';

/** GET /api/public/meta — семестр, тиждень, чисельник/знаменник, поточна пара. */
export function usePublicMeta() {
  return useQuery({
    queryKey: qk.public.meta(),
    queryFn: ({ signal }) => api.get<PublicMetaDto>('/public/meta', undefined, { signal, auth: false }),
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
    // у шапці: одна повторна спроба, щоб недоступний сервер не тримав силует довго
    retry: 1,
  });
}

/** GET /api/public/search — нечіткий пошук груп, викладачів, аудиторій. */
export function usePublicSearch(query: string, limit = 10) {
  const term = query.trim();
  return useQuery({
    queryKey: qk.public.search(term),
    queryFn: ({ signal }) =>
      api.get<SearchResultDto[]>('/public/search', { q: term, limit }, { signal, auth: false }),
    enabled: term.length > 0,
    staleTime: 60_000,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

/** GET /api/public/bells?date= — дзвінки на конкретну дату (профіль, що діє). */
export function useBells(date: IsoDate | null | undefined) {
  return useQuery({
    queryKey: qk.public.bells(date ?? undefined),
    queryFn: ({ signal }) => api.get<BellsDto>('/public/bells', { date }, { signal, auth: false }),
    enabled: Boolean(date),
    staleTime: 60 * 60_000,
  });
}
