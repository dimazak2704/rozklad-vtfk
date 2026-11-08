import { expect, test } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

// Dev-сервер компілює сторінки на льоту: перший запуск буває повільним
test.describe.configure({ timeout: 60_000 });

test.describe('довідник аудиторій', () => {
  test.beforeEach(async ({ page }) => {
    await loginWithMockRoles(page, 'ADMIN');
    await page.goto('/admin/catalog/rooms');
    await expect(page.getByRole('heading', { level: 1, name: 'Аудиторії' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('rowheader', { name: '301' })).toBeVisible();
  });

  test('нова аудиторія: помилки форми й сервера, потім успіх', async ({ page }) => {
    await page.getByRole('button', { name: 'Додати аудиторію' }).click();
    const dialog = page.getByRole('dialog', { name: 'Нова аудиторія' });
    const submit = dialog.getByRole('button', { name: 'Додати аудиторію' });
    const name = dialog.getByRole('textbox', { name: /Назва/ });

    // клієнтська перевірка: порожня назва
    await submit.click();
    await expect(dialog.getByText('Вкажіть назву або номер аудиторії')).toBeVisible();
    await expect(name).toHaveAttribute('aria-invalid', 'true');

    // помилка поля від сервера
    await name.fill('—');
    await submit.click();
    await expect(dialog.getByText('Назва аудиторії має містити літери або цифри.')).toBeVisible();

    // дубль: 409 над формою, введене лишається
    await name.fill('С.З.');
    await submit.click();
    await expect(dialog.getByRole('alert')).toHaveText('Аудиторія «с.з.» уже існує.');
    await expect(name).toHaveValue('С.З.');

    await name.fill('512');
    await dialog.getByRole('spinbutton', { name: 'Місткість' }).fill('30');
    await submit.click();
    await expect(dialog).toBeHidden();
    await expect(page.getByText('Аудиторію 512 додано')).toBeVisible();

    await page.getByRole('searchbox', { name: 'Пошук аудиторії' }).fill('512');
    await expect(page).toHaveURL(/q=512/);
    const row = page.getByRole('row').filter({ has: page.getByRole('rowheader', { name: '512' }) });
    await expect(row).toContainText('30');
  });

  test('використану аудиторію не видаляє, пояснює чому й пропонує вимкнути', async ({ page }) => {
    await page.getByRole('searchbox', { name: 'Пошук аудиторії' }).fill('301');
    const row = page.getByRole('row').filter({ has: page.getByRole('rowheader', { name: '301' }) });
    await row.getByRole('button', { name: 'Дії: аудиторія 301' }).click();
    await page.getByRole('menuitem', { name: 'Видалити' }).click();
    await page.getByRole('alertdialog', { name: 'Видалити аудиторію?' }).getByRole('button', { name: 'Видалити' }).click();

    const blocked = page.getByRole('alertdialog', { name: 'Видалити не можна' });
    await expect(blocked).toContainText('вона є в розкладі, навантаженні або закріплена за групою');
    await blocked.getByRole('button', { name: 'Позначити неактивною' }).click();
    await expect(blocked).toBeHidden();
    await expect(row).toContainText('Неактивна');
  });
});
