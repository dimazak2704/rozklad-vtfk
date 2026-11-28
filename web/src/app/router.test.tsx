import { createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { whenRouterReady } from './router';

/** Лінивий маршрут, код якого «приходить», коли тест викличе `load()`. */
function lazyPage() {
  let load: () => void = () => undefined;
  const loaded = new Promise<void>((resolve) => {
    load = resolve;
  });
  const router = createMemoryRouter([
    {
      path: '/',
      lazy: async () => {
        await loaded;
        return { Component: () => null };
      },
    },
  ]);
  return { router, load };
}

describe('whenRouterReady', () => {
  it('чекає, доки завантажиться код першої сторінки', async () => {
    const { router, load } = lazyPage();
    let ready = false;
    const waiting = whenRouterReady(router).then(() => {
      ready = true;
    });

    await Promise.resolve();
    expect(ready).toBe(false);

    load();
    await waiting;
    expect(router.state.initialized).toBe(true);
    router.dispose();
  });

  it('роутер уже готовий — не чекає', async () => {
    const router = createMemoryRouter([{ path: '/', Component: () => null }]);

    await expect(whenRouterReady(router)).resolves.toBeUndefined();
    router.dispose();
  });

  it('код сторінки не завантажився — однаково рендеримо: помилку покаже сторінка помилки маршруту', async () => {
    const router = createMemoryRouter([
      {
        path: '/',
        lazy: () => Promise.reject(new Error('chunk failed')),
      },
    ]);

    await whenRouterReady(router);

    expect(router.state.initialized).toBe(true);
    expect(router.state.errors).not.toBeNull();
    router.dispose();
  });
});
