import { expect, test, type Page } from '@playwright/test';

// Dev-сервер компілює модулі на льоту: перший запуск сторінки буває повільним
test.describe.configure({ timeout: 60_000 });

/** Чекаємо, доки застосунок змонтується (шапка зі знаком на місці). */
async function openApp(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole('link', { name: 'Розклад ВТФК — на головну' }).first()).toBeVisible({ timeout: 30_000 });
}

test.describe('оболонка публічного сайту', () => {
  test('головна має шапку зі знаком і вибір теми', async ({ page }, testInfo) => {
    await openApp(page, '/');
    await expect(page).toHaveTitle(/Розклад ВТФК/);
    // на десктопі тема — кнопка в смузі, на телефоні — перемикач у підвалі
    if (testInfo.project.name === 'mobile') {
      await expect(page.getByRole('radiogroup', { name: 'Тема оформлення' })).toBeVisible();
    } else {
      await expect(page.getByRole('button', { name: /^Тема:/ })).toBeVisible();
    }
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
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Розклад групи');
  });

  test('невідома адреса показує 404 з пропозицією групи', async ({ page }) => {
    await openApp(page, '/old/4ki2');
    await expect(page.getByRole('heading', { name: 'Такої сторінки немає' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Розклад групи 4КІ2' })).toBeVisible();
  });

  test('вибір теми зберігається після перезавантаження', async ({ page }, testInfo) => {
    await openApp(page, '/');
    if (testInfo.project.name === 'mobile') {
      await page.getByRole('radiogroup', { name: 'Тема оформлення' }).getByRole('radio', { name: 'Темна' }).click();
    } else {
      await page.getByRole('button', { name: /^Тема:/ }).click();
      await page.getByRole('menuitemradio', { name: 'Темна' }).click();
    }
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  });

  test('захищений розділ без входу веде на сторінку входу з поверненням', async ({ page }) => {
    await page.goto('/admin/orders');
    // без Keycloak лишаємося на /login з поясненням; з Keycloak — одразу його сторінка входу
    await expect(page).toHaveURL(/\/login\?returnTo=%2Fadmin%2Forders|\/realms\/rozklad\/protocol\/openid-connect\/auth/, {
      timeout: 30_000,
    });
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
