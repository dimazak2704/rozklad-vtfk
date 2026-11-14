import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installRoutePrefetch, resetRoutePrefetch, routeForPath } from './prefetch';
import type { AppRoute } from './routes';

function route(id: string, path: string, page = vi.fn(() => Promise.resolve({ default: () => null }))): AppRoute {
  return { id, path, title: id, description: id, layout: 'admin', page };
}

const orders = route('orders', '/admin/orders');
const group = route('group', '/g/:code');
const ROUTES = [orders, group];

function link(href: string, attrs: Record<string, string> = {}): HTMLAnchorElement {
  const a = document.createElement('a');
  a.href = href;
  for (const [name, value] of Object.entries(attrs)) a.setAttribute(name, value);
  a.innerHTML = '<span>текст</span>';
  document.body.append(a);
  return a;
}

let uninstall: () => void;

beforeEach(() => {
  resetRoutePrefetch();
  vi.mocked(orders.page).mockClear();
  vi.mocked(group.page).mockClear();
  uninstall = installRoutePrefetch(document, ROUTES);
});

afterEach(() => {
  uninstall();
  document.body.innerHTML = '';
});

describe('routeForPath', () => {
  it('знаходить маршрут з параметром і без хвоста адреси', () => {
    expect(routeForPath('/g/4КІ1', ROUTES)?.id).toBe('group');
    expect(routeForPath('/admin/orders', ROUTES)?.id).toBe('orders');
    expect(routeForPath('/admin/orders/extra', ROUTES)).toBeUndefined();
  });
});

describe('installRoutePrefetch', () => {
  it('наведення на посилання (навіть на вкладений елемент) вантажить код сторінки один раз', () => {
    const a = link('/admin/orders?date=2026-11-20');
    a.querySelector('span')!.dispatchEvent(new Event('pointerover', { bubbles: true }));
    a.dispatchEvent(new Event('focusin', { bubbles: true }));
    a.dispatchEvent(new Event('touchstart', { bubbles: true }));

    expect(orders.page).toHaveBeenCalledTimes(1);
    expect(group.page).not.toHaveBeenCalled();
  });

  it('не чіпає зовнішні посилання, нові вкладки й невідомі адреси', () => {
    link('https://example.com/admin/orders').dispatchEvent(new Event('pointerover', { bubbles: true }));
    link('/admin/orders', { target: '_blank' }).dispatchEvent(new Event('pointerover', { bubbles: true }));
    link('/nowhere').dispatchEvent(new Event('pointerover', { bubbles: true }));

    expect(orders.page).not.toHaveBeenCalled();
  });

  it('після невдалого завантаження (без мережі) пробує знову', async () => {
    vi.mocked(group.page).mockRejectedValueOnce(new Error('offline'));
    const a = link('/g/4КІ1');
    a.dispatchEvent(new Event('pointerover', { bubbles: true }));
    await Promise.resolve();
    await Promise.resolve();
    a.dispatchEvent(new Event('pointerover', { bubbles: true }));

    expect(group.page).toHaveBeenCalledTimes(2);
  });

  it('після відписки нічого не вантажить', () => {
    uninstall();
    link('/admin/orders').dispatchEvent(new Event('pointerover', { bubbles: true }));
    expect(orders.page).not.toHaveBeenCalled();
    uninstall = () => {};
  });
});
