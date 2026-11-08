import { expect, test, type Page } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

// Перевірка інфраструктури мок-API (src/mocks): Service Worker MSW відповідає замість бекенду
test.describe.configure({ timeout: 60_000 });

async function openApp(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole('link', { name: 'Розклад ВТФК — на головну' }).first()).toBeVisible({ timeout: 30_000 });
}

async function search(page: Page, query: string): Promise<void> {
  await page.keyboard.press('ControlOrMeta+k');
  const input = page.getByPlaceholder('Група, викладач, аудиторія або сторінка');
  await expect(input).toBeVisible();
  await input.fill(query);
}

test.describe('мок-API', () => {
  test('пошук викладача відповідає з мок-даних і веде на його розклад', async ({ page }) => {
    await openApp(page, '/');
    await search(page, 'кренц');
    await page.getByRole('option', { name: /Кренцін/ }).first().click();
    await expect(page).toHaveURL(/\/t\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Розклад викладача');
  });

  test('прапорець ?mockError симулює збій ендпоінта', async ({ page }) => {
    await openApp(page, '/?mockError=503&mockErrorPath=/public/search');
    await search(page, 'кренц');
    await expect(page.getByText('Пошук на сервері зараз недоступний.', { exact: false })).toBeVisible();
  });

  test('адмінка відкривається з мок-ролями і тримає живе зʼєднання', async ({ page }) => {
    await loginWithMockRoles(page, 'ADMIN');
    await page.goto('/admin');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Панель диспетчера', { timeout: 30_000 });
    // здорове зʼєднання нічого не показує (видно лише збій), тож перевіряємо стан у атрибуті
    await expect(page.locator('[data-live-status="open"]')).toBeAttached({ timeout: 15_000 });
  });
});
