import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ServerEvent } from './types';
import { useEventStream } from './useEventStream';

vi.mock('@/auth/userManager', () => ({ getAccessToken: () => Promise.resolve('token') }));

/** Одне зʼєднання фальшивого сервера: тіло — керований ReadableStream. */
interface FakeStream {
  signal: AbortSignal;
  push: (text: string) => void;
  end: () => void;
  aborted: () => boolean;
}

const encoder = new TextEncoder();

/** fetch, що на кожен запит віддає новий потік (або відповідь із заданим статусом). */
function installFakeServer(statuses: number[] = []) {
  const streams: FakeStream[] = [];
  const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
    const signal = init?.signal ?? new AbortController().signal;
    if (signal.aborted) return Promise.reject(new DOMException('aborted', 'AbortError'));
    const status = statuses.shift() ?? 200;
    if (status !== 200) return Promise.resolve(new Response(null, { status }));
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    let closed = false;
    const body = new ReadableStream<Uint8Array>({
      start(c) {
        controller = c;
      },
    });
    signal.addEventListener('abort', () => {
      if (closed) return;
      closed = true;
      controller.error(new DOMException('aborted', 'AbortError'));
    });
    streams.push({
      signal,
      push: (text) => controller.enqueue(encoder.encode(text)),
      end: () => {
        closed = true;
        controller.close();
      },
      aborted: () => signal.aborted,
    });
    return Promise.resolve(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
  });
  vi.stubGlobal('fetch', fetchMock);
  const live = () => streams.filter((s) => !s.aborted());
  return { streams, live, fetchMock };
}

/** Дає відпрацювати мікрозадачам і таймерам до `ms` (fetch, читання потоку, повтори). */
async function flush(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

/** Потоки в цих тестах мовчать: перевірку завислості вимикаємо, крім окремого тесту. */
const NO_STALL = 60 * 60_000;
const CHANGE = 'event: change.updated\ndata: {"id":"c1"}\n\n';

describe('useEventStream', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('подія «online» під час відкритого потоку лишає рівно один потік і кожну подію доставляє один раз', async () => {
    const server = installFakeServer();
    const events: ServerEvent[] = [];
    const { result, unmount } = renderHook(() => useEventStream({ onEvent: (e) => events.push(e), stallTimeoutMs: NO_STALL }));
    await flush();
    expect(result.current.status).toBe('open');
    expect(server.streams).toHaveLength(1);

    act(() => {
      window.dispatchEvent(new Event('online'));
    });
    // і перерване читання, і будь-який зайвий таймер повтору мали б уже спрацювати
    await flush(60_000);

    expect(server.live()).toHaveLength(1);
    expect(server.fetchMock).toHaveBeenCalledTimes(2);
    expect(result.current.status).toBe('open');

    for (const stream of server.live()) stream.push(CHANGE);
    await flush();
    expect(events.filter((e) => e.type === 'change.updated')).toHaveLength(1);

    unmount();
    expect(server.live()).toHaveLength(0);
  });

  it('кілька «online» поспіль теж дають один потік', async () => {
    const server = installFakeServer();
    renderHook(() => useEventStream({ onEvent: () => undefined, stallTimeoutMs: NO_STALL }));
    await flush();
    act(() => {
      window.dispatchEvent(new Event('online'));
      window.dispatchEvent(new Event('online'));
    });
    await flush();
    act(() => {
      window.dispatchEvent(new Event('online'));
    });
    await flush(120_000);
    expect(server.live()).toHaveLength(1);
  });

  it('після розриву з боку сервера перепідключається один раз і повідомляє, що це відновлення', async () => {
    const server = installFakeServer();
    const opens: boolean[] = [];
    const { result } = renderHook(() =>
      useEventStream({ onEvent: () => undefined, onOpen: ({ resumed }) => opens.push(resumed), stallTimeoutMs: NO_STALL }),
    );
    await flush();
    expect(opens).toEqual([false]);

    server.streams[0]!.end();
    await flush();
    expect(result.current.status).toBe('reconnecting');
    await flush(60_000);

    expect(server.fetchMock).toHaveBeenCalledTimes(2);
    expect(server.live()).toHaveLength(1);
    expect(result.current.status).toBe('open');
    expect(opens).toEqual([false, true]);
  });

  it('перша вдала спроба після невдалої теж вважається відновленням: події між ними могли загубитися', async () => {
    installFakeServer([503]);
    const opens: boolean[] = [];
    renderHook(() => useEventStream({ onEvent: () => undefined, onOpen: ({ resumed }) => opens.push(resumed) }));
    await flush(60_000);
    expect(opens).toEqual([true]);
  });

  it('завислий потік (без байтів довше за stallTimeoutMs) рветься і відкривається знову', async () => {
    const server = installFakeServer();
    renderHook(() => useEventStream({ onEvent: () => undefined, stallTimeoutMs: 10_000 }));
    await flush();
    await flush(10_000);
    expect(server.streams[0]!.aborted()).toBe(true);
    await flush(60_000);
    expect(server.live()).toHaveLength(1);
  });

  it('розмонтування під час очікування повтору скасовує таймер: нових запитів немає', async () => {
    const server = installFakeServer([503]);
    const { unmount } = renderHook(() => useEventStream({ onEvent: () => undefined }));
    await flush();
    expect(server.fetchMock).toHaveBeenCalledTimes(1);
    unmount();
    await flush(120_000);
    expect(server.fetchMock).toHaveBeenCalledTimes(1);
  });

  it('розмонтування до відповіді сервера не лишає потоку', async () => {
    const server = installFakeServer();
    const { unmount } = renderHook(() => useEventStream({ onEvent: () => undefined }));
    // fetch ще не викликано: хук чекає на токен
    unmount();
    await flush(120_000);
    expect(server.live()).toHaveLength(0);
  });

  it('401 повторює кілька разів і зупиняється; 403 зупиняє одразу', async () => {
    const server = installFakeServer([401, 401, 401, 401, 401, 401]);
    const { result } = renderHook(() => useEventStream({ onEvent: () => undefined }));
    await flush(10 * 60_000);
    expect(result.current.status).toBe('closed');
    expect(server.fetchMock).toHaveBeenCalledTimes(4);

    const forbidden = installFakeServer([403]);
    const second = renderHook(() => useEventStream({ onEvent: () => undefined }));
    await flush(10 * 60_000);
    expect(second.result.current.status).toBe('closed');
    expect(forbidden.fetchMock).toHaveBeenCalledTimes(1);
  });
});
