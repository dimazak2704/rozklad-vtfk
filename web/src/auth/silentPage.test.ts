// @vitest-environment node
import { describe, expect, it } from 'vitest';

// Файли читаємо як текст (типів node у src немає, тож fs беремо динамічно, як у authPageCss.test.ts)
const fsModule = 'node:fs';
const { readFileSync } = (await import(/* @vite-ignore */ fsModule)) as {
  readFileSync: (path: URL, encoding: 'utf8') => string;
};
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const silentHtml = read('../../login/silent.html');
const nginx = read('../../nginx.conf');
const viteConfig = read('../../vite.config.ts');

/** CSP з `add_header Content-Security-Policy "…"` усередині блоку, що починається з `start`. */
function cspIn(block: string): string {
  const match = /add_header Content-Security-Policy "([^"]+)"/.exec(block);
  if (!match?.[1]) throw new Error('немає CSP');
  return match[1];
}

describe('тихе оновлення входу (/login/silent.html)', () => {
  it('сторінка без вбудованих скриптів: CSP дозволяє лише скрипти з того ж походження', () => {
    expect(silentHtml).toContain('<script type="module" src="/src/auth/silentCallback.ts"></script>');
    expect(silentHtml).not.toMatch(/<script>/);
    expect(viteConfig).toContain("resolve(rootDir, 'login/silent.html')");
  });

  it('nginx дозволяє вбудовувати її лише самому сайту, решта сайту — без фреймів', () => {
    const start = nginx.indexOf('location = /login/silent.html');
    expect(start).toBeGreaterThan(0);
    const silent = cspIn(nginx.slice(start, nginx.indexOf('}', start)));
    expect(silent).toContain("frame-ancestors 'self'");
    expect(silent).not.toContain("'unsafe-inline'");
    const site = cspIn(nginx);
    expect(site).toContain("frame-ancestors 'none'");
  });

  it('маршрут /login лишається сторінкою SPA, хоч у dist є каталог login/', () => {
    const spa = nginx.slice(nginx.indexOf('  location / {'));
    expect(spa).toMatch(/try_files \$uri \/index\.html;/);
    expect(spa).not.toContain('$uri/');
    // переадресації nginx (/auth → /auth/login) без порту контейнера
    expect(nginx).toContain('absolute_redirect off;');
  });

  it('сервіс входу на тому ж походженні: у CSP сайту немає адрес Keycloak', () => {
    const site = cspIn(nginx);
    expect(site).toContain("connect-src 'self';");
    expect(site).toContain("form-action 'self';");
    expect(nginx).not.toContain('8180');
  });
});
