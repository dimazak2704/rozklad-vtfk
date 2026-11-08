/**
 * Telegram-бот (CONTRACTS §6, сервіс notification): стан, привʼязані чати, коди привʼязки, журнал доставок.
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { qk } from './queryKeys';
import type {
  BindingCodeDto,
  BindingCodeRequest,
  BindingUpdateRequest,
  BotStatusDto,
  ChatBindingDto,
  DeliveryDto,
  DeliveryStatus,
  MyBindingDto,
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

/**
 * Журнал доставок — сторінка `{items,page,size,total}`; поки є повідомлення в черзі — оновлюємо частіше.
 * `keepPrevious` — лише для гортання сторінок однієї таблиці: для іншого розпорядження старі дані були б неправдою.
 */
export function useDeliveries(query: DeliveriesQuery, { keepPrevious = false }: { keepPrevious?: boolean } = {}) {
  return useQuery({
    queryKey: qk.notify.deliveries({ ...query }),
    queryFn: ({ signal }) => api.get<Page<DeliveryDto>>('/notify/deliveries', { ...query }, { signal }),
    placeholderData: keepPrevious ? keepPreviousData : undefined,
    refetchInterval: (state) =>
      state.state.data?.items.some((d) => d.status === 'QUEUED' || d.status === 'RETRYING') ? 3_000 : 30_000,
  });
}

/**
 * Привʼязка Telegram самого викладача. Поки відкрито посилання для підключення (`waiting`), стан
 * перевіряється частіше: людина підключає бота на телефоні, а кабінет має це побачити сам.
 */
export function useMyBinding({ waiting = false }: { waiting?: boolean } = {}) {
  return useQuery({
    queryKey: qk.notify.myBinding(),
    queryFn: ({ signal }) => api.get<MyBindingDto>('/notify/bindings/me', undefined, { signal }),
    refetchInterval: (query) => (waiting && !query.state.data?.bound ? 5_000 : false),
  });
}

export function useDeleteBinding() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete(`/notify/bindings/${id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: qk.notify.bindings() }),
  });
}

/** PATCH /notify/bindings/{id}: ранковий розклад о 7:30 для чату групи чи підписника (адмін). */
export function useSetBindingDigest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, digestEnabled }: { id: Uuid } & BindingUpdateRequest) =>
      api.patch<ChatBindingDto>(`/notify/bindings/${id}`, { digestEnabled } satisfies BindingUpdateRequest),
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
