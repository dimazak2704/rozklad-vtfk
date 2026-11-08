import { expect, test, type Page } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

// Розбір і застосування в мок-API просуваються опитуванням (~1,2 с на крок)
test.describe.configure({ timeout: 90_000 });

/** Одруківки з таблиці розкладу (DOMAIN §10) і кандидат, з яким їх зіставляємо. */
const TYPOS: ReadonlyArray<readonly [raw: string, target: string]> = [
  ['Граболінська', 'Гарболінська'],
  ['Герасменко', 'Герасименко'],
  ['Інфом.', 'Інф.'],
  ['Вс.спц.', 'Вст.спц.'],
];

async function openImport(page: Page): Promise<void> {
  await loginWithMockRoles(page, 'ADMIN');
  await page.goto('/admin/import');
  await expect(page.getByRole('heading', { level: 1, name: 'Імпорт' })).toBeVisible({ timeout: 30_000 });
}

test.describe('імпорт', () => {
  test('таблиця розкладу: файл → розбір → рішення щодо одруківок → застосування', async ({ page }) => {
    await openImport(page);
    await expect(page.getByRole('region', { name: 'Витрати ШІ сьогодні' })).toBeVisible();

    await page.locator('input[type="file"]').setInputFiles({
      name: 'Розклад 2025-2026 (ІІ).xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: Buffer.from('PK test workbook'),
    });
    await expect(page.getByText('Таблиця Excel — схоже на розклад занять')).toBeVisible();
    await page.getByRole('button', { name: 'Завантажити й розібрати' }).click();

    await expect(page.getByRole('heading', { level: 2, name: 'Розклад 2025-2026 (ІІ).xlsx' })).toBeVisible();
    await expect(page).toHaveURL(/job=/);
    const applyPanel = page.getByRole('region', { name: 'Застосування імпорту' });
    await expect(applyPanel).toContainText('вирішіть 5 рядків', { timeout: 30_000 });

    // звіт показує й автоматично виправлений код групи
    await page.getByRole('tab', { name: /Звіт/ }).click();
    await page.getByRole('searchbox', { name: 'Пошук у звіті' }).fill('ІКІ1');
    await expect(page.getByRole('rowheader', { name: 'ІКІ1' })).toBeVisible();

    await page.getByRole('tab', { name: /Рішення/ }).click();
    for (const [, target] of TYPOS) {
      await page.getByRole('button', { name: `Так, це «${target}»` }).click();
    }
    const truncated = page.getByRole('listitem').filter({ hasText: '«Вс.»' });
    await truncated.getByRole('radio', { name: 'Створити дисципліну' }).click();
    await page.getByRole('button', { name: 'Зберегти рішення' }).click();
    await expect(applyPanel).toContainText('Готово до застосування');

    await applyPanel.getByRole('button', { name: 'Застосувати' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: 'Застосувати' }).click();
    await expect(page.getByRole('region', { name: 'Результат імпорту' })).toContainText('Імпорт застосовано', { timeout: 30_000 });
    // «Так, це …» запамʼятовує написання: чотири одруківки — чотири псевдоніми, «Вс.» — новий запис
    await expect(page.getByRole('region', { name: 'Результат імпорту' })).toContainText('4 псевдоніми');

    // написання з таблиці тепер — псевдонім у каталозі
    // переходимо посиланням, а не goto: перезавантаження сторінки скинуло б стан моків
    await page.getByRole('link', { name: 'Перевірити викладачів' }).click();
    await page.getByRole('searchbox', { name: 'Пошук викладача' }).fill('Грабол');
    await expect(page.getByRole('rowheader', { name: 'Гарболінська' })).toBeVisible({ timeout: 30_000 });
  });

  test('фото розпорядження: розпізнані рядки й примітки на аркуші', async ({ page }) => {
    await openImport(page);
    await page.getByRole('rowheader', { name: /розпорядження-35\.jpg/ }).click();
    await page.getByRole('tab', { name: 'Чернетка' }).click();
    const sheet = page.getByRole('article', { name: 'Розпізнане РОЗПОРЯДЖЕННЯ №35' });
    await expect(sheet).toContainText('ПОНЕДІЛОК - ЗНАМЕННИК');
    await expect(sheet.getByRole('rowheader', { name: '4КІ1' })).toBeVisible();
    await expect(sheet).toContainText('Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.');
  });
});
