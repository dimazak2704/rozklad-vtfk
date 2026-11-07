import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useEffect, useEffectEvent } from 'react';
import { qk } from './queryKeys';
import type { ServerEvent, ServerEventMap, ServerEventType } from './types';
import { useEventStream, type EventStreamState } from './useEventStream';

/** Шина подій SSE для сторінок: одне зʼєднання на вкладку, багато підписників. */
const bus = new EventTarget();

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
      invalidate(qk.admin.absences.all);
      invalidate(['admin', 'dashboard']);
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
      invalidate(qk.public.scheduleAll);
      invalidate(['public', 'changes']);
      invalidate(qk.me.all);
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
