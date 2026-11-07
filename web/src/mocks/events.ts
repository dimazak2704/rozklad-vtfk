/**
 * Мок потоку подій SSE (GET /api/events/stream): зʼєднання лишається відкритим, ping кожні 25 с,
 * а обробники областей шлють події через `emitServerEvent` — як бекенд після зміни заміни чи публікації.
 */
import { HttpResponse } from 'msw';
import type { ServerEventMap, ServerEventType } from '@/api/types';

const PING_INTERVAL_MS = 25_000;
const encoder = new TextEncoder();
const clients = new Set<ReadableStreamDefaultController<Uint8Array>>();
let lastEventId = 0;

function send(controller: ReadableStreamDefaultController<Uint8Array>, frame: string): void {
  try {
    controller.enqueue(encoder.encode(frame));
  } catch {
    // клієнт уже закрив зʼєднання
    clients.delete(controller);
  }
}

/** Надсилає подію всім відкритим потокам (вкладкам, де відкрита адмінка чи кабінет). */
export function emitServerEvent<K extends ServerEventType>(type: K, data: ServerEventMap[K]): void {
  lastEventId += 1;
  const frame = `id: ${lastEventId}\nevent: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const controller of clients) send(controller, frame);
}

/** Скільки потоків зараз відкрито (для тестів і налагодження). */
export function openEventStreams(): number {
  return clients.size;
}

/** Відповідь `text/event-stream`, що не закривається, доки клієнт не відʼєднається. */
export function eventStreamResponse(): Response {
  let ping: ReturnType<typeof setInterval> | undefined;
  let self: ReadableStreamDefaultController<Uint8Array> | undefined;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      self = controller;
      clients.add(controller);
      send(controller, 'retry: 5000\n: мок-потік подій\n\n');
      ping = setInterval(() => send(controller, 'event: ping\ndata: \n\n'), PING_INTERVAL_MS);
    },
    cancel() {
      clearInterval(ping);
      if (self) clients.delete(self);
    },
  });
  return new HttpResponse(stream, {
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' },
  });
}
