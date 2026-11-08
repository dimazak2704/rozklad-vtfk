/**
 * Налаштування системи (CONTRACTS §2: GET/PUT /api/admin/settings). Профілі дзвінків для вибору беруться
 * з useBellProfiles (api/public), витрати ШІ — з useAiUsage (api/import): один хук на ендпоінт.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type { SettingsDto } from './types';

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

