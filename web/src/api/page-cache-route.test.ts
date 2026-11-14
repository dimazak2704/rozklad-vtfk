import { describe, expect, it } from 'vitest';
import { isCachedPageNavigation } from './page-cache-route';

const ORIGIN = 'https://rozklad.example';

/** Параметри, які Workbox передає `urlPattern`, для переходу на сторінку цього сайту. */
function navigation(path: string, { mode = 'navigate', sameOrigin = true } = {}) {
  // склеюємо, а не розбираємо відносно ORIGIN: інакше `//password/…` став би адресою іншого хоста
  const url = new URL(ORIGIN + path);
  return { request: { mode, url: url.href } as unknown as Request, url, sameOrigin };
}

describe('кеш сторінок PWA (rozklad-pages)', () => {
  it('зберігає публічні й робочі сторінки для офлайну', () => {
    for (const path of ['/', '/changes', '/g/4КІ1', '/bells', '/me', '/admin/changes', '/login']) {
      expect(isCachedPageNavigation(navigation(path)), path).toBe(true);
    }
  });

  // Ключ кешу — повна адреса, а в ній одноразовий токен: на спільному комп'ютері наступна людина знайшла б
  // посилання в DevTools і задала б пароль чужому обліковому запису, поки воно дійсне.
  it('не зберігає сторінки з одноразовим токеном у шляху', () => {
    for (const path of [
      '/invite/AAAAfakeToken',
      '/password/AAAAfakeToken',
      '/password/AAAAfakeToken?from=mail',
      '/Invite/AAAAfakeToken',
      '/PASSWORD/AAAAfakeToken',
      '//password/AAAAfakeToken',
    ]) {
      expect(isCachedPageNavigation(navigation(path)), path).toBe(false);
    }
  });

  it('не зберігає API, сервіс входу і повернення після входу', () => {
    for (const path of ['/api/public/orders/1/file', '/auth/login', '/login/callback?code=1&state=2', '/login/silent.html']) {
      expect(isCachedPageNavigation(navigation(path)), path).toBe(false);
    }
  });

  it('пропускає запити, які не є переходом на сторінку цього сайту', () => {
    expect(isCachedPageNavigation(navigation('/changes', { mode: 'cors' }))).toBe(false);
    expect(isCachedPageNavigation(navigation('/changes', { sameOrigin: false }))).toBe(false);
  });

  it('самодостатня, бо workbox-build копіює її в sw.js як текст', () => {
    const source = isCachedPageNavigation.toString();
    expect(source).toMatch(/invite\|password/);
    expect(source).not.toMatch(/import|require\(/);
  });
});
