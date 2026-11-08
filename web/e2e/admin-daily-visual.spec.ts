import { mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

/**
 * Візуальна перевірка сторінок диспетчера (за запитом, не в звичайному прогоні):
 *   QA_SHOTS=1 PW_PORT=5182 npx playwright test e2e/admin-daily-visual.spec.ts --project=desktop
 * Знімки на всю сторінку — у QA_SHOTS_DIR (типово тимчасова тека ОС), обидві теми, ширини 768–1920.
 * QA_PAGES=changes,orders — лише вибрані сторінки; QA_WIDTHS=1440 — лише вибрані ширини.
 */
test.skip(!process.env.QA_SHOTS, 'Візуальні знімки — лише з QA_SHOTS=1');
test.describe.configure({ timeout: 240_000 });

const PAGES = [
  { id: 'dashboard', path: '/admin', heading: 'Панель диспетчера' },
  { id: 'changes', path: '/admin/changes', heading: 'Заміни' },
  { id: 'orders', path: '/admin/orders', heading: 'Розпорядження' },
  { id: 'telegram', path: '/admin/telegram', heading: 'Telegram' },
  { id: 'settings', path: '/admin/settings', heading: 'Налаштування' },
] as const;
const WIDTHS = (process.env.QA_WIDTHS ?? '768,1280,1440,1920').split(',').map(Number);
const THEMES = ['light', 'dark'] as const;
const OUT = process.env.QA_SHOTS_DIR ?? join(tmpdir(), 'rozklad-admin-daily-qa');
/** Понеділок 09.11.2026, 08:10 за Києвом: демо-ранок на «Сьогодні». */
const NOW = new Date('2026-11-09T06:10:00Z');

async function prepare(page: Page, theme: (typeof THEMES)[number]): Promise<void> {
  await loginWithMockRoles(page, 'ADMIN');
  await page.addInitScript(
    ([preference]) => {
      window.sessionStorage.setItem('rozklad.mockTelegram', 'manual');
      window.localStorage.setItem('rozklad.theme', JSON.stringify({ state: { preference }, version: 1 }));
    },
    [theme] as const,
  );
  await page.clock.setFixedTime(NOW);
}

const selected = new Set((process.env.QA_PAGES ?? PAGES.map((p) => p.id).join(',')).split(','));

for (const target of PAGES.filter((p) => selected.has(p.id))) {
  test(`знімки: ${target.id}`, async ({ page }) => {
    mkdirSync(OUT, { recursive: true });
    for (const theme of THEMES) {
      await prepare(page, theme);
      for (const width of WIDTHS) {
        await page.setViewportSize({ width, height: 900 });
        await page.goto(target.path);
        await expect(page.getByRole('heading', { level: 1, name: target.heading })).toBeVisible({ timeout: 30_000 });
        // дані мок-API приходять із затримкою 100–400 мс; чекаємо, поки зникнуть силуети
        await page.waitForTimeout(1_500);
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect.soft(overflow, `горизонтальна прокрутка на ${target.id} ${width}px`).toBeLessThanOrEqual(0);
        await page.screenshot({ path: join(OUT, `${target.id}-${width}-${theme}.png`), fullPage: true });
      }
    }
  });
}
