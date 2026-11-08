import { useEffect, useEffectEvent, useState } from 'react';
import { getAccessToken } from '@/auth/userManager';
import { buildUrl } from './client';
import { backoffDelay, createSseParser, type SseMessage } from './sse';
import type { ServerEvent, ServerEventType } from './types';

export type EventStreamStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface UseEventStreamOptions {
  /** Шлях відносно /api. */
  path?: string;
  /** Підключатися лише коли користувач увійшов. */
  enabled?: boolean;
  onEvent: (event: ServerEvent) => void;
  /** Без жодних байтів довше за цей час вважаємо зʼєднання завислим (сервер шле ping кожні 25 с). */
  stallTimeoutMs?: number;
}

export interface EventStreamState {
  status: EventStreamStatus;
  /** Коли прийшла остання подія (не ping). */
  lastEventAt: number | null;
  /** Скільки поспіль невдалих спроб. */
  failures: number;
}

const KNOWN_EVENTS: ReadonlySet<string> = new Set<ServerEventType>([
  'change.updated',
  'absence.created',
  'absence.updated',
  'generation.progress',
  'generation.completed',
  'order.published',
  'schedule.changed',
  'ping',
]);

function toServerEvent(message: SseMessage): ServerEvent | null {
  if (!KNOWN_EVENTS.has(message.event)) return null;
  let data: unknown = message.data;
  try {
    data = JSON.parse(message.data);
  } catch {
    // ping може бути порожнім або текстовим
  }
  return { type: message.event, data, id: message.id } as ServerEvent;
}

/**
 * Підписка на GET /api/events/stream через fetch (щоб передати Bearer-токен).
 * Перепідключається з експоненційною затримкою, надсилає Last-Event-ID, рве завислі зʼєднання,
 * одразу пробує знову, коли вкладка повертається або зʼявляється мережа.
 */
export function useEventStream({
  path = '/events/stream',
  enabled = true,
  onEvent,
  stallTimeoutMs = 60_000,
}: UseEventStreamOptions): EventStreamState {
  const [state, setState] = useState<EventStreamState>({ status: 'idle', lastEventAt: null, failures: 0 });
  const emit = useEffectEvent((event: ServerEvent) => onEvent(event));

  useEffect(() => {
    if (!enabled) return undefined;

    let disposed = false;
    let attempt = 0;
    let retryBaseMs = 1000;
    let lastEventId: string | undefined;
    let controller: AbortController | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let stallTimer: ReturnType<typeof setTimeout> | null = null;
    let open = false;

    const update = (patch: Partial<EventStreamState>) => {
      if (!disposed) setState((prev) => ({ ...prev, ...patch }));
    };

    const clearTimers = () => {
      if (retryTimer) clearTimeout(retryTimer);
      if (stallTimer) clearTimeout(stallTimer);
      retryTimer = null;
      stallTimer = null;
    };

    const armStall = () => {
      if (stallTimer) clearTimeout(stallTimer);
      stallTimer = setTimeout(() => controller?.abort(), stallTimeoutMs);
    };

    const scheduleReconnect = (fatal = false) => {
      open = false;
      if (disposed) return;
      attempt += 1;
      if (fatal) {
        update({ status: 'closed', failures: attempt });
        return;
      }
      update({ status: 'reconnecting', failures: attempt });
      retryTimer = setTimeout(() => void connect(), backoffDelay(attempt - 1, retryBaseMs));
    };

    async function connect(): Promise<void> {
      if (disposed) return;
      clearTimers();
      controller = new AbortController();
      update({ status: attempt === 0 ? 'connecting' : 'reconnecting' });

      const headers: Record<string, string> = { Accept: 'text/event-stream', 'Cache-Control': 'no-cache' };
      const token = await getAccessToken();
      if (token) headers.Authorization = `Bearer ${token}`;
      if (lastEventId) headers['Last-Event-ID'] = lastEventId;

      try {
        const response = await fetch(buildUrl(path), { headers, signal: controller.signal, cache: 'no-store' });
        if (response.status === 401 || response.status === 403) {
          // без прав повтори нічого не дадуть; 401 ще раз спробуємо після оновлення токена
          scheduleReconnect(response.status === 403 || attempt >= 3);
          return;
        }
        if (!response.ok || !response.body) {
          scheduleReconnect();
          return;
        }

        attempt = 0;
        open = true;
        update({ status: 'open', failures: 0 });
        armStall();

        const parser = createSseParser({
          onMessage: (message) => {
            if (message.id) lastEventId = message.id;
            const event = toServerEvent(message);
            if (!event) return;
            if (event.type !== 'ping') update({ lastEventAt: Date.now() });
            emit(event);
          },
          onRetry: (ms) => {
            retryBaseMs = ms;
          },
        });

        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          armStall();
          parser.push(value);
        }
        scheduleReconnect();
      } catch {
        if (!disposed) scheduleReconnect();
      }
    }

    const reconnectNow = () => {
      if (disposed) return;
      if (document.visibilityState !== 'visible') return;
      clearTimers();
      controller?.abort();
      attempt = 0;
      void connect();
    };

    const onVisibility = () => {
      if (document.visibilityState === 'visible' && !open) reconnectNow();
    };

    void connect();
    window.addEventListener('online', reconnectNow);
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      disposed = true;
      clearTimers();
      controller?.abort();
      window.removeEventListener('online', reconnectNow);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [enabled, path, stallTimeoutMs]);

  return state;
}
