import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clockState, resetClock } from '@/lib/clock';
import { prefetchPublicMeta } from './meta';
import { createQueryClient } from './queryClient';
import { qk } from './queryKeys';

const fetchMock = vi.fn<typeof fetch>();

function metaResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  fetchMock.mockReset();
  resetClock();
});

describe('prefetchPublicMeta', () => {
  it('кладе meta в кеш і звіряє годинник стенда ще до першого малювання', async () => {
    fetchMock.mockResolvedValueOnce(metaResponse({ collegeName: 'ВТФК', now: '2026-11-19T14:00:00Z', standTime: true }));
    const client = createQueryClient();

    await prefetchPublicMeta(client);

    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/public/meta');
    expect(client.getQueryData(qk.public.meta())).toMatchObject({ collegeName: 'ВТФК', standTime: true });
    expect(clockState().standTime).toBe(true);
  });

  it('сервер не відповів — не кидає помилку, кеш порожній, годинник браузера', async () => {
    fetchMock.mockResolvedValue(metaResponse({ title: 'Bad request' }, 400));
    const client = createQueryClient();

    await expect(prefetchPublicMeta(client)).resolves.toBeUndefined();

    expect(client.getQueryData(qk.public.meta())).toBeUndefined();
    expect(clockState()).toMatchObject({ standTime: false, offsetMs: 0 });
  });
});
