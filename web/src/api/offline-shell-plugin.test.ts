import { afterEach, describe, expect, it, vi } from 'vitest';
import { offlineShellPlugin, PAGES_CACHE } from './offline-shell-plugin';

function stubPagesCache(pages: Record<string, Response>) {
  const cache = {
    keys: vi.fn().mockResolvedValue(Object.keys(pages).map((url) => new Request(url))),
    match: vi.fn((req: Request) => Promise.resolve(pages[req.url])),
  };
  const open = vi.fn().mockResolvedValue(cache);
  vi.stubGlobal('caches', { open });
  return { open, cache };
}

describe('offlineShellPlugin', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('offline on a page never opened before, serves any saved page: the SPA shell is the same', async () => {
    const shell = new Response('<div id="root"></div>');
    const { open } = stubPagesCache({ 'http://localhost/changes': shell });

    await expect(offlineShellPlugin.handlerDidError({})).resolves.toBe(shell);
    expect(open).toHaveBeenCalledWith(PAGES_CACHE);
  });

  it('gives nothing when no page was saved yet, so the browser shows its own offline page', async () => {
    stubPagesCache({});

    await expect(offlineShellPlugin.handlerDidError({})).resolves.toBeUndefined();
  });

  it('is self-contained, because workbox-build copies it into sw.js as text', () => {
    const source = offlineShellPlugin.handlerDidError.toString();
    expect(source).toMatch(new RegExp(`caches\\.open\\(["']${PAGES_CACHE}["']\\)`));
    expect(source).not.toMatch(/import|require\(|PAGES_CACHE/);
  });
});
