// @vitest-environment node
import { describe, expect, it } from 'vitest';

// Читаємо файли як текст: ?raw для CSS у тестах порожній (css: false у vite.config.ts), а типів node
// у src немає, тож модуль fs беремо динамічно з мінімальним типом.
const fsModule = 'node:fs';
const { readFileSync } = (await import(/* @vite-ignore */ fsModule)) as {
  readFileSync: (path: URL, encoding: 'utf8') => string;
};
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const authCss = read('../styles/auth-page.css');
const themeCss = read('../styles/theme.css');
const viteConfig = read('../../vite.config.ts');

/**
 * Стилі сторінки входу сервісу auth (services/auth/.../templates/login.html) збираються з цього ж дизайну.
 * Якщо тема перейменує роль, сторінка входу тихо втратила б колір — тест ловить це до збірки.
 */
describe('auth-page.css', () => {
  const defined = new Set(
    [...`${themeCss}\n${authCss}`.matchAll(/(--[\w-]+)\s*:/g)].map((match) => match[1]),
  );

  it('бере кольори лише з визначених змінних теми', () => {
    // var(--x) без запасного значення має бути оголошена в theme.css або тут
    const used = [...authCss.matchAll(/var\(\s*(--[\w-]+)\s*\)/g)].map((match) => match[1]);
    expect(used.length).toBeGreaterThan(20);
    const missing = [...new Set(used)].filter((name) => !defined.has(name));
    expect(missing).toEqual([]);
  });

  it('значення з @theme бере через --theme(), а сирих кольорів не має (крім білого кола знака Google)', () => {
    const themed = [...authCss.matchAll(/--theme\(\s*(--[\w-]+)\s*\)/g)].map((match) => match[1]);
    expect(themed).toEqual(['--font-sans', '--font-display', '--ease-out']);
    themed.forEach((name) => expect(defined.has(name)).toBe(true));
    const hex = [...authCss.matchAll(/#[0-9a-fA-F]{3,8}\b/g)].map((match) => match[0]);
    expect(hex).toEqual(['#fff']);
    expect(authCss).not.toMatch(/oklch\(|rgb\(|hsl\(/);
  });

  it('збирається окремим входом у dist/brand/auth.css (сторінка входу посилається саме на цю адресу)', () => {
    expect(viteConfig).toContain("'src/styles/auth-page.css'");
    expect(viteConfig).toContain("'brand/auth.css'");
  });
});
