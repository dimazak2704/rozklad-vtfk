import { expect, test, type Page } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

// Dev-сервер компілює модулі на льоту: перший запуск сторінки буває повільним
test.describe.configure({ timeout: 60_000 });

/** Чекаємо, доки застосунок змонтується (шапка зі знаком на місці). */
async function openApp(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole('link', { name: 'Розклад ВТФК — на головну' }).first()).toBeVisible({ timeout: 30_000 });
}

test.describe('оболонка публічного сайту', () => {
  test('головна має шапку зі знаком і вибір теми', async ({ page }) => {
    await openApp(page, '/');
    await expect(page).toHaveTitle(/Розклад ВТФК/);
    // тема — одна на сайті: тихе меню «Тема: як у системі» у підвалі на будь-якій ширині
    await expect(page.getByRole('button', { name: 'Тема: як у системі' })).toBeVisible();
  });

  test('на сторінці групи активний розділ «Розклад» — рівно один у шапці й у вкладках', async ({ page }, testInfo) => {
    await openApp(page, '/g/4КІ1');
    const nav = testInfo.project.name === 'mobile'
      ? page.getByRole('navigation', { name: 'Основні розділи' })
      : page.getByRole('navigation', { name: 'Розділи сайту' });
    const current = nav.locator('[aria-current="page"]');
    await expect(current).toHaveCount(1);
    await expect(current).toHaveText(/Розклад/);
  });

  test('пошук за кодом групи латиницею веде на сторінку групи', async ({ page }) => {
    await openApp(page, '/');
    await page.keyboard.press('ControlOrMeta+k');
    const input = page.getByPlaceholder('Група, викладач, аудиторія або сторінка');
    await expect(input).toBeVisible();
    await input.fill('4ki1');
    await page.getByRole('option', { name: /4КІ1/ }).first().click();
    await expect(page).toHaveURL(/\/g\/4%D0%9A%D0%861$/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/4КІ1\s*—\s*розклад групи/);
  });

  test('невідома адреса показує 404 з пропозицією групи', async ({ page }) => {
    await openApp(page, '/old/4ki2');
    await expect(page.getByRole('heading', { name: 'Такої сторінки немає' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Розклад групи 4КІ2' })).toBeVisible();
  });

  test('вибір теми зберігається після перезавантаження', async ({ page }) => {
    await openApp(page, '/');
    await page.getByRole('button', { name: /^Тема:/ }).click();
    await page.getByRole('menuitemradio', { name: 'Темна' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.getByRole('button', { name: 'Тема: темна' })).toBeVisible();
  });

  test('захищений розділ без входу веде на сторінку входу з поверненням', async ({ page }) => {
    await page.goto('/admin/orders');
    // без стенда лишаємося на /login з поясненням; зі стендом — запит authorize і сторінка входу сервісу auth
    await expect(page).toHaveURL(/\/login\?returnTo=%2Fadmin%2Forders|\/auth\/oauth2\/authorize\?|\/auth\/login$/, {
      timeout: 30_000,
    });
  });
});

test.describe('позначка часу стенда в шапці', () => {
  /** Мок віддає standTime, як стенд із переведеним годинником (src/mocks/handlers/meta.ts). */
  async function standTime(page: Page): Promise<void> {
    await page.addInitScript(() => window.localStorage.setItem('rozklad.mock.stand-time', '2026-11-20T10:12+02:00'));
  }

  async function expectNoHorizontalScroll(page: Page): Promise<void> {
    await expect(page.locator('[data-stand-time]').first()).toBeVisible({ timeout: 30_000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  }

  test('гість: позначка й «Увійти» вміщаються в шапку без горизонтальної прокрутки', async ({ page }) => {
    await standTime(page);
    await openApp(page, '/');
    await expectNoHorizontalScroll(page);
    await expect(page.getByRole('link', { name: 'Увійти' })).toBeInViewport({ ratio: 1 });
  });

  test('викладач: позначка й аватар вміщаються в шапку кабінету', async ({ page }) => {
    await standTime(page);
    await loginWithMockRoles(page, 'TEACHER');
    await openApp(page, '/me');
    await expectNoHorizontalScroll(page);
    await expect(page.locator('header').getByRole('button').last()).toBeInViewport({ ratio: 1 });
  });
});

test.describe('сторінка компонентів (dev)', () => {
  test('рендериться без горизонтальної прокрутки', async ({ page }) => {
    await openApp(page, '/dev/kitchen-sink');
    await expect(page.getByRole('heading', { name: 'Компоненти', level: 1 })).toBeVisible({ timeout: 30_000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
