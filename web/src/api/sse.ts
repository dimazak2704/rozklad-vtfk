/**
 * Розбір потоку text/event-stream за специфікацією WHATWG (поля event/data/id/retry, коментарі,
 * будь-які кінці рядків). EventSource не вміє слати заголовок Authorization, тому читаємо потік
 * через fetch і розбираємо самі.
 */

export interface SseMessage {
  event: string;
  data: string;
  id?: string;
}

export interface SseParserCallbacks {
  onMessage: (message: SseMessage) => void;
  /** Сервер попросив інший інтервал перепідключення (`retry: 5000`). */
  onRetry?: (ms: number) => void;
}

export interface SseParser {
  push: (chunk: string) => void;
  /** Останній отриманий id (для заголовка Last-Event-ID). */
  lastEventId: () => string | undefined;
  reset: () => void;
}

export function createSseParser({ onMessage, onRetry }: SseParserCallbacks): SseParser {
  let buffer = '';
  let eventType = '';
  let data: string[] = [];
  let lastId: string | undefined;
  let pendingCr = false;

  const dispatch = () => {
    if (data.length === 0) {
      eventType = '';
      return;
    }
    const message: SseMessage = { event: eventType || 'message', data: data.join('\n') };
    if (lastId !== undefined) message.id = lastId;
    eventType = '';
    data = [];
    onMessage(message);
  };

  const processLine = (line: string) => {
    if (line === '') {
      dispatch();
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    switch (field) {
      case 'event':
        eventType = value;
        break;
      case 'data':
        data.push(value);
        break;
      case 'id':
        if (!value.includes('\u0000')) lastId = value;
        break;
      case 'retry':
        if (/^\d+$/.test(value)) onRetry?.(Number(value));
        break;
      default:
        break;
    }
  };

  return {
    push(chunk: string) {
      let text = chunk;
      // \r\n, що розірвався між шматками: \r уже обробили як кінець рядка
      if (pendingCr && text.startsWith('\n')) text = text.slice(1);
      pendingCr = false;
      buffer += text;
      let start = 0;
      for (let i = 0; i < buffer.length; i += 1) {
        const ch = buffer[i];
        if (ch === '\n' || ch === '\r') {
          processLine(buffer.slice(start, i));
          if (ch === '\r') {
            if (i + 1 < buffer.length) {
              if (buffer[i + 1] === '\n') i += 1;
            } else {
              pendingCr = true;
            }
          }
          start = i + 1;
        }
      }
      buffer = buffer.slice(start);
    },
    lastEventId: () => lastId,
    reset() {
      buffer = '';
      eventType = '';
      data = [];
      pendingCr = false;
    },
  };
}

/** Затримка перепідключення: експонента з «тремтінням», щоб клієнти не стукали одночасно. */
export function backoffDelay(attempt: number, baseMs = 1000, maxMs = 30_000, random: () => number = Math.random): number {
  const exp = Math.min(maxMs, baseMs * 2 ** Math.max(0, attempt));
  const jitter = exp * 0.2 * (random() * 2 - 1);
  return Math.max(250, Math.round(exp + jitter));
}
