/**
 * Telegram-бот (CONTRACTS §6, сервіс notification): стан, привʼязані чати, коди привʼязки, журнал доставок.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type {
  BindingCodeDto,
  BindingCodeRequest,
  BotStatusDto,
  ChatBindingDto,
  DeliveryDto,
  DeliveryStatus,
  Uuid,
} from './types';

export interface DeliveriesQuery {
  orderId?: Uuid;
  status?: DeliveryStatus;
}

export function useBotStatus() {
  return useQuery({
    queryKey: qk.notify.bot(),
    queryFn: ({ signal }) => api.get<BotStatusDto>('/notify/bot', undefined, { signal }),
    refetchInterval: 60_000,
  });
}

export function useBindings() {
  return useQuery({
    queryKey: qk.notify.bindings(),
    queryFn: ({ signal }) => api.get<ChatBindingDto[]>('/notify/bindings', undefined, { signal }),
  });
}

/** Журнал доставок; поки є повідомлення в черзі — оновлюємо частіше. */
export function useDeliveries(query: DeliveriesQuery) {
  return useQuery({
    queryKey: qk.notify.deliveries({ ...query }),
    queryFn: ({ signal }) => api.get<DeliveryDto[]>('/notify/deliveries', { ...query }, { signal }),
    refetchInterval: (state) =>
      state.state.data?.some((d) => d.status === 'QUEUED' || d.status === 'RETRYING') ? 3_000 : 30_000,
  });
}

export function useDeleteBinding() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(`/notify/bindings/${id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: qk.notify.bindings() }),
  });
}

export function useCreateBindingCode() {
  return useMutation({
    mutationFn: (body: BindingCodeRequest) => api.post<BindingCodeDto>('/notify/bindings/codes', body),
  });
}

/** Тестове повідомлення власнику бота (TELEGRAM_OWNER_CHAT_ID). */
export function useSendTestMessage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<unknown>('/notify/test'),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['notify', 'deliveries'] }),
  });
}
