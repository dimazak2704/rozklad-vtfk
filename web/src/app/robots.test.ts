// @vitest-environment node
import { describe, expect, it } from 'vitest';

// файли читаємо як текст (типів node у src немає, тож fs беремо динамічно, як у nginxConf.test.ts)
const fsModule = 'node:fs';
const { readFileSync } = (await import(/* @vite-ignore */ fsModule)) as {
  readFileSync: (path: URL, encoding: 'utf8') => string;
};
const robots = readFileSync(new URL('../../public/robots.txt', import.meta.url), 'utf8');
const nginx = readFileSync(new URL('../../nginx.conf', import.meta.url), 'utf8');

describe('robots.txt', () => {
  it('публічний розклад відкритий для пошуковиків, кабінет і панель диспетчера — ні', () => {
    const lines = robots.split('\n').map((line) => line.trim());
    expect(lines).toContain('User-agent: *');
    expect(lines).toContain('Allow: /');
    expect(lines).toContain('Disallow: /admin');
    expect(lines).toContain('Disallow: /me');
  });

  it('nginx віддає файл, а не index.html: запасний маршрут SPA спершу шукає файл за шляхом', () => {
    const spaFallback = /location \/ \{[^}]*try_files \$uri \/index\.html;/;
    expect(nginx).toMatch(spaFallback);
    // жодне точне правило не перехоплює /robots.txt
    expect(nginx).not.toMatch(/location[^{]*robots/);
  });
});
