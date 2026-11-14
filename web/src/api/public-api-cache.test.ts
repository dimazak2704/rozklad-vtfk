import { afterEach, describe, expect, it, vi } from 'vitest';
import { dropAuthorizedCopies, isCachedPublicApiRequest, PUBLIC_API_CACHE } from './public-api-cache';

const ORIGIN = 'https://rozklad.example';

/** Параметри, які Workbox передає `urlPattern`, для запиту API сторінки цього сайту. */
function apiRequest(path: string, { token = null as string | null, sameOrigin = true } = {}) {
  const url = new URL(ORIGIN + path);
  const request = new Request(url, token ? { headers: { Authorization: `Bearer ${token}` } } : undefined);
  return { request, url, sameOrigin };
}

/** Кеш `rozklad-public-api` з відповідями на ці запити; `delete` прибирає запис за тим самим запитом. */
function stubPublicApiCache(requests: Request[], { exists = true } = {}) {
  const stored = [...requests];
  const cache = {
    keys: vi.fn(() => Promise.resolve([...stored])),
    delete: vi.fn((request: Request) => {
      stored.splice(stored.indexOf(request), 1);
      return Promise.resolve(true);
    }),
  };
  const open = vi.fn().mockResolvedValue(cache);
  vi.stubGlobal('caches', { has: vi.fn().mockResolvedValue(exists), open });
  return { open, cache, stored };
}

describe('кеш публічних відповідей API PWA (rozklad-public-api)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('зберігає відповіді гостю для офлайну, як і раніше', () => {
    for (const path of [
      '/api/public/meta',
      '/api/public/groups',
      '/api/public/teachers',
      '/api/public/rooms',
      '/api/public/bells?date=2026-11-12',
      '/api/public/schedule/group/g1?from=2026-11-09&to=2026-11-13',
      '/api/public/changes?date=2026-11-12',
    ]) {
      expect(isCachedPublicApiRequest(apiRequest(path)), path).toBe(true);
    }
  });

  // Cache Storage зберігає запит із заголовками: токен персоналу лишився б на диску після виходу
  it('запит з токеном (персонал, архів) не бере: він іде в мережу й не зберігається', () => {
    for (const path of ['/api/public/schedule/group/g1?from=2026-10-19&to=2026-10-23', '/api/public/changes?date=2026-10-19']) {
      expect(isCachedPublicApiRequest(apiRequest(path, { token: 'teacher-token' })), path).toBe(false);
    }
  });

  it('не бере інші адреси API й чужі сайти', () => {
    for (const path of ['/api/public/ics/group/g1.ics', '/api/public/invites/AAAA', '/api/me/schedule', '/api/documents/orders/o1.pdf']) {
      expect(isCachedPublicApiRequest(apiRequest(path)), path).toBe(false);
    }
    expect(isCachedPublicApiRequest(apiRequest('/api/public/meta', { sameOrigin: false }))).toBe(false);
  });

  it('самодостатній, бо workbox-build копіює його в sw.js як текст', () => {
    const source = isCachedPublicApiRequest.toString();
    expect(source).toMatch(/headers\.has\(["']Authorization["']\)/);
    expect(source).not.toMatch(/import|require\(/);
  });

  it('прибирає збережені відповіді на запити з токеном, а копії гостя лишає', async () => {
    const guest = apiRequest('/api/public/changes?date=2026-11-12').request;
    const staff = apiRequest('/api/public/changes?date=2026-11-12', { token: 'old-token' }).request;
    const archive = apiRequest('/api/public/changes?date=2026-10-19', { token: 'older-token' }).request;
    const { open, cache, stored } = stubPublicApiCache([guest, staff, archive]);

    await dropAuthorizedCopies();

    expect(open).toHaveBeenCalledWith(PUBLIC_API_CACHE);
    // видаляє сам збережений запит: з Vary: Authorization лише він збігається з записом персоналу
    expect(cache.delete).toHaveBeenCalledTimes(2);
    expect(cache.delete).toHaveBeenCalledWith(staff);
    expect(cache.delete).toHaveBeenCalledWith(archive);
    expect(stored).toEqual([guest]);
  });

  it('кешу ще немає — не створює його', async () => {
    const { open } = stubPublicApiCache([], { exists: false });
    await dropAuthorizedCopies();
    expect(open).not.toHaveBeenCalled();
  });

  it('без Cache Storage (HTTP у локальній мережі) і при збої сховища нічого не ламає', async () => {
    vi.stubGlobal('caches', undefined);
    await expect(dropAuthorizedCopies()).resolves.toBeUndefined();

    vi.stubGlobal('caches', { has: vi.fn().mockRejectedValue(new DOMException('denied', 'SecurityError')) });
    await expect(dropAuthorizedCopies()).resolves.toBeUndefined();
  });
});
