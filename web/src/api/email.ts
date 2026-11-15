/**
 * Листи на пошту (CONTRACTS §6, сервіс notification): мої налаштування листів, тестовий лист собі,
 * а для диспетчера — стан поштового сервера й журнал листів. Telegram тут не змінюється.
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notify } from '@/components/ui';
import { api } from './client';
import { isApiError, STALE_DATA_MESSAGE } from './errors';
import { qk } from './queryKeys';
import type {
  EmailDeliveryDto,
  EmailDeliveryStatus,
  EmailKind,
  EmailPreferencesDto,
  EmailPreferencesRequest,
  EmailStatusDto,
  EmailTestResultDto,
  Page,
} from './types';

/** Фільтри журналу листів: `page` з нуля, `size` 1..200. */
export interface EmailDeliveriesQuery {
  status?: EmailDeliveryStatus;
  kind?: EmailKind | 'TEST';
  page?: number;
  size?: number;
}

/** Що змінює людина одним натисканням: загальний перемикач або один вид листів. */
export type EmailPreferencePatch = { enabled: boolean } | { kind: EmailKind; enabled: boolean };

const MUTATION_KEY = ['notify', 'email', 'me', 'update'] as const;

export function useEmailPreferences() {
  return useQuery({
    queryKey: qk.notify.email.me(),
    queryFn: ({ signal }) => api.get<EmailPreferencesDto>('/notify/email/me', undefined, { signal }),
  });
}

/** Застосовує зміну до знімка налаштувань (для оптимістичного оновлення). */
export function applyEmailPatch(prefs: EmailPreferencesDto, patch: EmailPreferencePatch): EmailPreferencesDto {
  if (!('kind' in patch)) return { ...prefs, enabled: patch.enabled };
  return {
    ...prefs,
    kinds: prefs.kinds.map((setting) => (setting.kind === patch.kind && setting.switchable ? { ...setting, enabled: patch.enabled } : setting)),
  };
}

/**
 * Тіло PUT: стан усіх перемикачів, які людина бачить. Повний набір, а не одна зміна — так кілька швидких
 * натискань не губляться, хоч би як сервер розумів PUT (заміна чи злиття); типові значення він відкидає сам.
 */
export function emailPreferencesRequest(prefs: EmailPreferencesDto): EmailPreferencesRequest {
  const kinds: Partial<Record<EmailKind, boolean>> = {};
  for (const setting of prefs.kinds) if (setting.switchable) kinds[setting.kind] = setting.enabled;
  return { enabled: prefs.enabled, kinds, ...(prefs.version != null ? { version: prefs.version } : {}) };
}

/**
 * Перемикач діє одразу: оптимістично змінюємо кеш і надсилаємо весь стан. Запити йдуть по черзі (`scope`),
 * а тіло збирається з кешу в момент відправлення — тож у ньому вже всі попередні натискання.
 */
export function useUpdateEmailPreferences() {
  const queryClient = useQueryClient();
  const key = qk.notify.email.me();
  return useMutation({
    mutationKey: MUTATION_KEY,
    scope: { id: 'email-preferences' },
    mutationFn: (_patch: EmailPreferencePatch) => {
      const current = queryClient.getQueryData<EmailPreferencesDto>(key);
      if (!current) throw new Error('Налаштування листів ще не завантажено.');
      return api.put<EmailPreferencesDto>('/notify/email/me', emailPreferencesRequest(current));
    },
    onMutate: async (patch) => {
      await queryClient.cancelQueries({ queryKey: key });
      queryClient.setQueryData<EmailPreferencesDto>(key, (prefs) => (prefs ? applyEmailPatch(prefs, patch) : prefs));
    },
    onSuccess: (saved) => {
      // відповідь сервера — правда, але поки в черзі є ще натискання, вона не знає про них
      if (queryClient.isMutating({ mutationKey: MUTATION_KEY }) <= 1) queryClient.setQueryData(key, saved);
    },
    onError: (error) => {
      if (isApiError(error) && error.status === 409) notify.error(STALE_DATA_MESSAGE);
      else notify.error(error, 'Не вдалося зберегти налаштування листів');
      // стан перемикачів повертаємо з сервера: після кількох натискань «відкотити одне» вже неоднозначно
      void queryClient.invalidateQueries({ queryKey: key });
    },
  });
}

/** Тестовий лист на власну підтверджену адресу (не частіше 3 на годину). */
export function useSendTestEmail() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<EmailTestResultDto>('/notify/email/me/test'),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: [...qk.notify.email.all, 'deliveries'] }),
  });
}

export function useEmailStatus() {
  return useQuery({
    queryKey: qk.notify.email.status(),
    queryFn: ({ signal }) => api.get<EmailStatusDto>('/notify/email/status', undefined, { signal }),
    refetchInterval: 60_000,
  });
}

/** Журнал листів; поки є листи в черзі — оновлюємо частіше (лист заміни чекає 2 хв, перш ніж піти). */
export function useEmailDeliveries(query: EmailDeliveriesQuery) {
  return useQuery({
    queryKey: qk.notify.email.deliveries({ ...query }),
    queryFn: ({ signal }) => api.get<Page<EmailDeliveryDto>>('/notify/email/deliveries', { ...query }, { signal }),
    placeholderData: keepPreviousData,
    refetchInterval: (state) =>
      state.state.data?.items.some((d) => d.status === 'QUEUED' || d.status === 'RETRYING') ? 10_000 : 60_000,
  });
}
