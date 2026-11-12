import { expect, test } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

// Dev-сервер компілює сторінки на льоту: перший запуск буває повільним
test.describe.configure({ timeout: 60_000 });

test.describe('Доступи (моки)', () => {
  test.beforeEach(async ({ page }) => {
    await loginWithMockRoles(page, 'ADMIN');
    await page.goto('/admin/access');
    await expect(page.getByRole('heading', { level: 1, name: 'Доступи' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('rowheader', { name: /Диспетчер Розкладу/ })).toBeVisible();
  });

  test('пункт меню веде на сторінку, список з ролями й станами', async ({ page }, testInfo) => {
    // на телефоні бічної панелі немає — розділи адмінки в меню
    if (testInfo.project.name === 'desktop') await expect(page.getByRole('link', { name: 'Доступи' }).first()).toBeVisible();
    await expect(page.getByRole('table', { name: 'Хто може увійти' }).getByText('Відкликано')).toBeVisible();
  });

  test('надати доступ викладачеві з клавіатури, потім посилання для пароля', async ({ page }) => {
    await page.getByRole('button', { name: 'Надати доступ' }).click();
    const dialog = page.getByRole('dialog', { name: 'Надати доступ' });
    await dialog.getByRole('combobox', { name: /Викладач/ }).click();
    await page.keyboard.type('Колесник');
    await page.getByRole('option', { name: /Колесник/ }).first().click();
    await dialog.getByRole('textbox', { name: /E-mail/ }).fill('kolesnyk@vtc.vn.ua');
    await dialog.getByRole('button', { name: 'Надати доступ' }).click();
    await expect(dialog).toBeHidden();

    const row = page.getByRole('row', { name: /kolesnyk@vtc\.vn\.ua/ });
    await expect(row).toBeVisible();
    await row.hover();
    await row.getByRole('button', { name: /Дії/ }).click();
    await page.getByRole('menuitem', { name: 'Посилання для пароля' }).click();
    const link = page.getByRole('dialog', { name: 'Посилання для пароля' });
    await expect(link.getByRole('textbox', { name: 'Посилання для пароля' })).toHaveValue(/\/password\/demo-password-\d+$/);
    const url = await link.getByRole('textbox', { name: 'Посилання для пароля' }).inputValue();
    await link.getByRole('button', { name: 'Готово' }).click();

    // людина відкриває посилання: вибирає логін і пароль. Перехід усередині SPA — моки живуть у пам'яті сторінки
    await page.evaluate((path) => {
      window.history.pushState({}, '', path);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }, new URL(url).pathname);
    await expect(page.getByRole('heading', { level: 1, name: 'Новий пароль' })).toBeVisible();
    await page.getByRole('textbox', { name: /^Логін/ }).fill('kolesnyk');
    await page.getByLabel(/^Пароль/).fill('Long-enough-pass-1');
    await page.getByLabel(/Повторіть пароль/).fill('Long-enough-pass-1');
    await page.getByRole('button', { name: 'Зберегти пароль' }).click();
    await expect(page.getByRole('status')).toContainText('Ваш логін — kolesnyk');
  });

  test('відкликати доступ: підтвердження пояснює наслідки', async ({ page }) => {
    const row = page.getByRole('row', { name: /nepyivoda/ });
    await row.hover();
    await row.getByRole('button', { name: /Дії/ }).click();
    await page.getByRole('menuitem', { name: 'Відкликати доступ' }).click();
    const confirm = page.getByRole('alertdialog');
    await expect(confirm).toContainText('коли працівник іде з коледжу');
    await confirm.getByRole('button', { name: 'Відкликати доступ' }).click();
    await expect(row.getByText('Відкликано')).toBeVisible();
  });
});
