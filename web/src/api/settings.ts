/**
 * Налаштування системи (CONTRACTS §2: GET/PUT /api/admin/settings) і довідкове для сторінки налаштувань:
 * профілі дзвінків (GET /api/public/bells без дати) та витрати ШІ (GET /api/import/ai/usage, лише читання).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type { AiUsageDto, BellsDto, SettingsDto } from './types';

export function useSettings() {
  return useQuery({
    queryKey: qk.admin.settings(),
    queryFn: ({ signal }) => api.get<SettingsDto>('/admin/settings', undefined, { signal }),
  });
}

export function useUpdateSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: SettingsDto) => api.put<SettingsDto>('/admin/settings', body),
    onSuccess: (saved) => {
      queryClient.setQueryData(qk.admin.settings(), saved);
      // назва коледжу й профіль дзвінків видно в шапці та розкладах
      void queryClient.invalidateQueries({ queryKey: qk.public.meta() });
      void queryClient.invalidateQueries({ queryKey: ['public', 'bells'] });
    },
  });
}

/** Усі профілі дзвінків (GET /api/public/bells без дати повертає `profiles`). */
export function useBellProfiles() {
  return useQuery({
    queryKey: qk.public.bells(),
    queryFn: ({ signal }) => api.get<BellsDto>('/public/bells', undefined, { signal, auth: false }),
    select: (bells) => bells.profiles ?? [],
    staleTime: 10 * 60_000,
  });
}

/** Витрати ШІ сьогодні, модель і денний бюджет (налаштовуються змінними середовища importer). */
export function useAiUsage() {
  return useQuery({
    queryKey: qk.importer.aiUsage(),
    queryFn: ({ signal }) => api.get<AiUsageDto>('/import/ai/usage', undefined, { signal }),
    staleTime: 60_000,
    retry: 1,
  });
}
