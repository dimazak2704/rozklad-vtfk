import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { invalidateFor, useLiveConnection } from './live';
import { qk } from './queryKeys';

vi.mock('@/auth/userManager', () => ({ getAccessToken: () => Promise.resolve(null) }));

function invalidatedKeys(run: (client: QueryClient) => void): unknown[] {
  const client = new QueryClient();
  const spy = vi.spyOn(client, 'invalidateQueries');
  run(client);
  return spy.mock.calls.map(([filters]) => filters?.queryKey);
}

const DAILY = [
  qk.admin.changes.all,
  qk.admin.absences.all,
  ['admin', 'lesson-options'],
  ['admin', 'dashboard'],
  qk.admin.orders.all,
  qk.admin.accounting.all,
];

describe('invalidateFor: schedule.changed', () => {
  it('після публікації версії оновлює і щоденні дані диспетчера: старі id пар там уже недійсні', () => {
    const data = { dates: ['2026-11-12'], groupIds: [], teacherIds: [], reason: 'TIMETABLE_PUBLISHED' };
    const keys = invalidatedKeys((client) => invalidateFor(client, { type: 'schedule.changed', data }));
    expect(keys).toEqual(expect.arrayContaining([qk.public.scheduleAll, ['public', 'changes'], qk.me.all, ...DAILY]));
    expect(keys).toContainEqual(['admin', 'timetable', 'versions']);
    // уроки редактора розкладу не перечитуємо посеред редагування
    expect(keys).not.toContainEqual(qk.admin.timetable.all);
  });
});

/** fetch для SSE: перший потік сервер закриває, другий тримає відкритим. */
function installStreams() {
  const closers: (() => void)[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(() => {
      let controller!: ReadableStreamDefaultController<Uint8Array>;
      const body = new ReadableStream<Uint8Array>({ start: (c) => void (controller = c) });
      closers.push(() => controller.close());
      return Promise.resolve(new Response(body, { status: 200 }));
    }),
  );
  return closers;
}

async function flush(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('useLiveConnection: розрив потоку', () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] }));
  afterEach(() => vi.useRealTimers());

  it('перше підключення нічого не перечитує, а відновлене — перечитує все, що оновили б загублені події', async () => {
    const closers = installStreams();
    const client = new QueryClient();
    const spy = vi.spyOn(client, 'invalidateQueries');
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    renderHook(() => useLiveConnection(true), { wrapper });

    await flush();
    expect(spy).not.toHaveBeenCalled();

    // сервер закрив потік (тайм-аут SseEmitter): за кілька секунд підключаємося знову
    closers[0]!();
    await flush(30_000);
    const keys = spy.mock.calls.map(([filters]) => filters?.queryKey);
    expect(keys).toEqual(expect.arrayContaining([...DAILY, qk.admin.generation.all, qk.public.all, qk.me.all]));
  });
});
