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
  /**
   * Потік відкрився. `resumed` — це не перша спроба: між попереднім потоком (чи завантаженням сторінки)
   * і цим події могли загубитися, бо сервер їх не повторює.
   */
  onOpen?: (info: { resumed: boolean }) => void;
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
 *
 * Потік завжди один: кожне підключення має свій номер, і все, що належить старому (перерване читання,
 * його помилка, його повтор), мовчки завершується. Розмонтування рве потік і скасовує таймери.
 */
export function useEventStream({
  path = '/events/stream',
  enabled = true,
  onEvent,
  onOpen,
  stallTimeoutMs = 60_000,
}: UseEventStreamOptions): EventStreamState {
  const [state, setState] = useState<EventStreamState>({ status: 'idle', lastEventAt: null, failures: 0 });
  const emit = useEffectEvent((event: ServerEvent) => onEvent(event));
  const opened = useEffectEvent((resumed: boolean) => onOpen?.({ resumed }));

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
    /** Номер поточного підключення: старше підключення нічого не планує й не доставляє. */
    let generation = 0;

    const update = (patch: Partial<EventStreamState>) => {
      if (!disposed) setState((prev) => ({ ...prev, ...patch }));
    };

    const clearTimers = () => {
      if (retryTimer) clearTimeout(retryTimer);
      if (stallTimer) clearTimeout(stallTimer);
      retryTimer = null;
      stallTimer = null;
    };

    const scheduleReconnect = (fatal = false) => {
      open = false;
      if (disposed) return;
      clearTimers();
      attempt += 1;
      if (fatal) {
        update({ status: 'closed', failures: attempt });
        return;
      }
      update({ status: 'reconnecting', failures: attempt });
      retryTimer = setTimeout(() => {
        retryTimer = null;
        void connect();
      }, backoffDelay(attempt - 1, retryBaseMs));
    };

    async function connect(): Promise<void> {
      if (disposed) return;
      const current = ++generation;
      const isCurrent = () => !disposed && current === generation;
      clearTimers();
      // попередній потік не переживає нового: рвемо його, а його catch побачить, що він уже не поточний
      controller?.abort();
      const own = new AbortController();
      controller = own;
      open = false;
      update({ status: attempt === 0 ? 'connecting' : 'reconnecting' });

      const headers: Record<string, string> = { Accept: 'text/event-stream', 'Cache-Control': 'no-cache' };
      const token = await getAccessToken();
      if (!isCurrent()) return;
      if (token) headers.Authorization = `Bearer ${token}`;
      if (lastEventId) headers['Last-Event-ID'] = lastEventId;

      const armStall = () => {
        if (stallTimer) clearTimeout(stallTimer);
        stallTimer = setTimeout(() => own.abort(), stallTimeoutMs);
      };

      try {
        const response = await fetch(buildUrl(path), { headers, signal: own.signal, cache: 'no-store' });
        if (!isCurrent()) {
          own.abort();
          return;
        }
        if (response.status === 401 || response.status === 403) {
          // без прав повтори нічого не дадуть; 401 ще раз спробуємо після оновлення токена
          scheduleReconnect(response.status === 403 || attempt >= 3);
          return;
        }
        if (!response.ok || !response.body) {
          scheduleReconnect();
          return;
        }

        const resumed = current > 1;
        attempt = 0;
        open = true;
        update({ status: 'open', failures: 0 });
        armStall();
        opened(resumed);

        const parser = createSseParser({
          onMessage: (message) => {
            if (!isCurrent()) return;
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
          if (!isCurrent()) return;
          if (done) break;
          armStall();
          parser.push(value);
        }
        scheduleReconnect();
      } catch {
        // перервали ми самі (новий потік, розмонтування) — нічого не плануємо; завис чи впала мережа — повтор
        if (isCurrent()) scheduleReconnect();
      }
    }

    const reconnectNow = () => {
      if (disposed) return;
      if (document.visibilityState !== 'visible') return;
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
      generation += 1;
      clearTimers();
      controller?.abort();
      controller = null;
      window.removeEventListener('online', reconnectNow);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [enabled, path, stallTimeoutMs]);

  return state;
}
