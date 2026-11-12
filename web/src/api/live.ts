import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useEffect, useEffectEvent } from 'react';
import { qk } from './queryKeys';
import type { ServerEvent, ServerEventMap, ServerEventType } from './types';
import { useEventStream, type EventStreamState } from './useEventStream';

/** Шина подій SSE для сторінок: одне зʼєднання на вкладку, багато підписників. */
const bus = new EventTarget();

/**
 * Щоденна робота диспетчера: зміни, відсутності з ураженими парами, варіанти замін, панель, чернетки й
 * розпорядження, облік. Усе це посилається на конкретні пари розкладу.
 */
const DAILY_KEYS: readonly (readonly unknown[])[] = [
  qk.admin.changes.all,
  qk.admin.absences.all,
  ['admin', 'lesson-options'],
  ['admin', 'dashboard'],
  qk.admin.orders.all,
  qk.admin.accounting.all,
];

/** Список версій розкладу (без уроків редактора: їх не чіпаємо, поки диспетчер редагує). */
const TIMETABLE_VERSIONS_KEY = ['admin', 'timetable', 'versions'] as const;

/**
 * Усе, що оновили б події SSE. Сервер подій не повторює (немає id і повтору), тож після розриву
 * перечитуємо це самі: подія могла прийти, поки потоку не було.
 */
export function invalidateAfterGap(queryClient: QueryClient): void {
  for (const queryKey of [...DAILY_KEYS, qk.admin.generation.all, TIMETABLE_VERSIONS_KEY, qk.public.all, qk.me.all]) {
    void queryClient.invalidateQueries({ queryKey });
  }
}

/** Що інвалідувати за замовчуванням на кожну подію (сторінки можуть робити більше через useServerEvent). */
export function invalidateFor(queryClient: QueryClient, event: ServerEvent): void {
  const invalidate = (queryKey: readonly unknown[]) => void queryClient.invalidateQueries({ queryKey });
  switch (event.type) {
    case 'change.updated':
      invalidate(qk.admin.changes.all);
      invalidate(qk.admin.absences.all);
      invalidate(['admin', 'dashboard']);
      invalidate(qk.me.all);
      break;
    case 'absence.created':
    case 'absence.updated':
      // змінена чи скасована відсутність звільняє або додає пари: оновлюємо список, панель і кабінет
      invalidate(qk.admin.absences.all);
      invalidate(['admin', 'dashboard']);
      invalidate(qk.me.all);
      break;
    case 'generation.completed':
      invalidate(qk.admin.generation.all);
      invalidate(qk.admin.timetable.all);
      break;
    case 'order.published':
      invalidate(qk.admin.orders.all);
      invalidate(['admin', 'dashboard']);
      invalidate(qk.public.all);
      invalidate(qk.me.all);
      break;
    case 'schedule.changed':
      // публікація версії чи перерахунок календаря видаляють і створюють пари наново:
      // старі id пар у змінах, варіантах і чернетках диспетчера вже недійсні
      invalidate(qk.public.scheduleAll);
      invalidate(['public', 'changes']);
      invalidate(qk.me.all);
      for (const queryKey of DAILY_KEYS) invalidate(queryKey);
      invalidate(TIMETABLE_VERSIONS_KEY);
      break;
    case 'generation.progress':
    case 'ping':
      break;
  }
}

/**
 * Тримає SSE-зʼєднання (адмінка, кабінет), інвалідує кеш і розсилає події підписникам.
 * Викликайте один раз у макеті.
 */
export function useLiveConnection(enabled: boolean): EventStreamState {
  const queryClient = useQueryClient();
  return useEventStream({
    enabled,
    onOpen: ({ resumed }) => {
      if (resumed) invalidateAfterGap(queryClient);
    },
    onEvent: (event) => {
      invalidateFor(queryClient, event);
      bus.dispatchEvent(new CustomEvent(event.type, { detail: event.data }));
    },
  });
}

/** Підписка сторінки на подію: `useServerEvent('change.updated', (change) => …)`. */
export function useServerEvent<K extends ServerEventType>(type: K, handler: (data: ServerEventMap[K]) => void): void {
  const onEvent = useEffectEvent((data: ServerEventMap[K]) => handler(data));
  useEffect(() => {
    const listener = (event: Event) => onEvent((event as CustomEvent<ServerEventMap[K]>).detail);
    bus.addEventListener(type, listener);
    return () => bus.removeEventListener(type, listener);
  }, [type]);
}
