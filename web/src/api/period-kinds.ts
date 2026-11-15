/**
 * Довідник «Види періодів» (CONTRACTS §2 «Calendar», `/api/admin/period-kinds`): код як у графіку коледжу,
 * назва, колір, псевдоніми, шаблон примітки і ефект — що період цього виду робить із парами.
 *
 * Зміна виду зачіпає всі його періоди (тексти сайту й бота, а зміна ефекту — ще й пари), тож після збереження
 * оновлюємо все, що оновлює правка календаря.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { invalidateCalendar } from './calendar';
import { api } from './client';
import { qk } from './queryKeys';
import type { PeriodKindDto, PeriodKindImpactDto, PeriodKindRequest, Uuid } from './types';

/** Усі види (`active` — лише увімкнені чи лише вимкнені), за `position`, потім за кодом. */
export function usePeriodKinds(params: { active?: boolean } = {}) {
  return useQuery({
    queryKey: qk.admin.calendar.periodKinds(params),
    queryFn: ({ signal }) => api.get<PeriodKindDto[]>('/admin/period-kinds', { active: params.active }, { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useSavePeriodKind() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: Uuid | null; body: PeriodKindRequest }) =>
      id ? api.put<PeriodKindDto>(`/admin/period-kinds/${id}`, body) : api.post<PeriodKindDto>('/admin/period-kinds', body),
    onSuccess: () => invalidateCalendar(queryClient),
  });
}

/** Пробне збереження (`?dryRun=true`): що зміна виду зачепить. Нічого не зберігає й не інвалідує. */
export function usePeriodKindImpact() {
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: PeriodKindRequest }) =>
      api.put<PeriodKindImpactDto>(`/admin/period-kinds/${id}`, body, { query: { dryRun: true } }),
  });
}

export function useDeletePeriodKind() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(`/admin/period-kinds/${id}`),
    onSuccess: () => invalidateCalendar(queryClient),
  });
}

/** Вид → тіло PUT (для журналу дій і перемикання «увімкнено»). */
export function periodKindRequest(kind: PeriodKindDto): PeriodKindRequest {
  return {
    code: kind.code,
    name: kind.name,
    effect: kind.effect,
    color: kind.color,
    aliases: [...kind.aliases],
    noteTemplate: kind.noteTemplate,
    active: kind.active,
    position: kind.position,
    version: kind.version,
  };
}
