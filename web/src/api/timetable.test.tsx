import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { qk } from './queryKeys';
import { fetchAllDisciplines, useVersionMetrics } from './timetable';
import type { DisciplineDto, MetricsDto, TimetableVersionDto } from './types';

vi.mock('@/auth/userManager', () => ({ getAccessToken: () => Promise.resolve(null) }));

const METRICS: MetricsDto = { groupWindows: 1, teacherWindows: 2, brokenDays: 3, latePairs: 4, maxPairsPerDay: 4, perGroup: [] };

function version(id: string, metrics: MetricsDto | null): TimetableVersionDto {
  return { id, metrics } as TimetableVersionDto;
}

function setup(versions: TimetableVersionDto[], updatedAt = Date.now()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 30_000 } } });
  client.setQueryData(qk.admin.timetable.versions('sem-1'), versions, { updatedAt });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return { client, wrapper };
}

afterEach(() => vi.unstubAllGlobals());

describe('useVersionMetrics', () => {
  it('бере метрики зі свіжого списку версій і не робить окремого запиту', () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    const { wrapper } = setup([version('v-1', METRICS)]);

    const { result } = renderHook(() => useVersionMetrics('v-1'), { wrapper });

    expect(result.current.data).toEqual(METRICS);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('без метрик у списку питає сервер', async () => {
    const fromServer = { ...METRICS, latePairs: 9 };
    const fetchSpy = vi.fn(() => Promise.resolve(Response.json(fromServer)));
    vi.stubGlobal('fetch', fetchSpy);
    const { wrapper } = setup([version('v-1', null)]);

    const { result } = renderHook(() => useVersionMetrics('v-1'), { wrapper });

    await waitFor(() => expect(result.current.data).toEqual(fromServer));
    expect(String((fetchSpy.mock.calls[0] as unknown[])[0])).toContain('/v-1/metrics');
  });

  it('застарілий список показує метрики одразу, але оновлює їх запитом', async () => {
    const fromServer = { ...METRICS, latePairs: 9 };
    const fetchSpy = vi.fn(() => Promise.resolve(Response.json(fromServer)));
    vi.stubGlobal('fetch', fetchSpy);
    const { wrapper } = setup([version('v-1', METRICS)], Date.now() - 60_000);

    const { result } = renderHook(() => useVersionMetrics('v-1'), { wrapper });

    expect(result.current.data).toEqual(METRICS);
    await waitFor(() => expect(result.current.data).toEqual(fromServer));
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });
});

describe('fetchAllDisciplines', () => {
  const items = (from: number, count: number) =>
    Array.from({ length: count }, (_, i) => ({ id: `d-${from + i}` }) as DisciplineDto);

  function stubPages(total: number) {
    const pending: Array<{ page: number; resolve: () => void }> = [];
    const fetchSpy = vi.fn((input: string) => {
      const url = new URL(input, 'http://localhost');
      const page = Number(url.searchParams.get('page'));
      const size = Number(url.searchParams.get('size'));
      const body = { items: items(page * size, Math.max(0, Math.min(size, total - page * size))), total, page, size };
      return new Promise<Response>((resolve) => pending.push({ page, resolve: () => resolve(Response.json(body)) }));
    });
    vi.stubGlobal('fetch', fetchSpy);
    return { fetchSpy, pending };
  }

  it('увесь каталог до 200 дисциплін — один запит найбільшою сторінкою', async () => {
    const { fetchSpy, pending } = stubPages(129);
    const result = fetchAllDisciplines();
    await vi.waitFor(() => expect(pending).toHaveLength(1));
    pending[0]!.resolve();

    expect(await result).toHaveLength(129);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(String((fetchSpy.mock.calls[0] as unknown[])[0])).toContain('size=200');
  });

  it('більший каталог: решту сторінок читає паралельно і складає їх по порядку', async () => {
    const { pending } = stubPages(450);
    const result = fetchAllDisciplines();
    await vi.waitFor(() => expect(pending).toHaveLength(1));
    pending[0]!.resolve();
    // сторінки 1 і 2 запитано, поки жодна з них ще не відповіла
    await vi.waitFor(() => expect(pending.map((p) => p.page)).toEqual([0, 1, 2]));
    pending[2]!.resolve();
    pending[1]!.resolve();

    const all = await result;
    expect(all).toHaveLength(450);
    expect(all[0]!.id).toBe('d-0');
    expect(all[449]!.id).toBe('d-449');
  });
});
