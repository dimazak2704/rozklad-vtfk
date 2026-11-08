/**
 * Telegram-бот (CONTRACTS §6, сервіс notification): стан, привʼязані чати, коди привʼязки, журнал доставок.
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type {
  BindingCodeDto,
  BindingCodeRequest,
  BotStatusDto,
  ChatBindingDto,
  DeliveryDto,
  DeliveryStatus,
  Page,
  Uuid,
} from './types';

/** Фільтри й сторінка журналу доставок: `page` з нуля, `size` 1..200 (типово 20 на сервері). */
export interface DeliveriesQuery {
  orderId?: Uuid;
  status?: DeliveryStatus;
  page?: number;
  size?: number;
}

/** Найбільша сторінка журналу, яку віддає notification. */
export const MAX_DELIVERIES_PAGE = 200;

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

/** Журнал доставок — сторінка `{items,page,size,total}`; поки є повідомлення в черзі — оновлюємо частіше. */
export function useDeliveries(query: DeliveriesQuery) {
  return useQuery({
    queryKey: qk.notify.deliveries({ ...query }),
    queryFn: ({ signal }) => api.get<Page<DeliveryDto>>('/notify/deliveries', { ...query }, { signal }),
    placeholderData: keepPreviousData,
    refetchInterval: (state) =>
      state.state.data?.items.some((d) => d.status === 'QUEUED' || d.status === 'RETRYING') ? 3_000 : 30_000,
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

/** Тестове повідомлення власнику бота (TELEGRAM_OWNER_CHAT_ID): 200 із записом журналу доставки. */
export function useSendTestMessage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<DeliveryDto>('/notify/test'),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['notify', 'deliveries'] }),
  });
}
