import { expect, test, type Page } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

/**
 * Клавіатура й журнал дій диспетчера: N — нова аудиторія, Enter — зберегти, Ctrl+Z — скасувати,
 * Ctrl+Shift+Z — повернути, Ctrl+Shift+H — журнал, Esc — закрити. Клавіші працюють і в українській
 * розкладці (за фізичною клавішею).
 */
test.describe.configure({ timeout: 90_000 });

/** Фокус на сторінці, а не в полі: так поводиться людина, що щойно клацнула по вільному місцю. */
async function focusPage(page: Page): Promise<void> {
  await page.locator('#main').focus();
}

/** Натискання в українській розкладці: браузер дає літеру кирилицею, а `code` — фізичну клавішу. */
async function pressUkrainian(page: Page, key: string, code: string): Promise<void> {
  await page.evaluate(
    ({ key: k, code: c }) => {
      const target = document.activeElement ?? document.body;
      target.dispatchEvent(new KeyboardEvent('keydown', { key: k, code: c, bubbles: true, cancelable: true }));
    },
    { key, code },
  );
}

test.describe('клавіатура й журнал дій', () => {
  test.skip(({ isMobile }) => isMobile, 'Клавіатурні скорочення — для десктопа');

  test.beforeEach(async ({ page }) => {
    await loginWithMockRoles(page, 'ADMIN');
    await page.goto('/admin/catalog/rooms');
    await expect(page.getByRole('heading', { level: 1, name: 'Довідники: Аудиторії' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('rowheader', { name: '301' })).toBeVisible();
  });

  test('нова аудиторія → Ctrl+Z прибирає її → Ctrl+Shift+Z повертає; журнал це показує', async ({ page }) => {
    await focusPage(page);
    await page.keyboard.press('n');
    const dialog = page.getByRole('dialog', { name: 'Нова аудиторія' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('textbox', { name: /Назва/ }).fill('777');
    // Enter у полі — зберегти
    await page.keyboard.press('Enter');
    await expect(dialog).toBeHidden();
    await expect(page.getByText('Додано аудиторію 777')).toBeVisible();

    const search = page.getByRole('searchbox', { name: 'Пошук аудиторії' });
    await search.fill('777');
    await expect(page.getByRole('rowheader', { name: '777' })).toBeVisible();

    // у полі пошуку Ctrl+Z — звичайне скасування тексту; дія скасовується зі сторінки
    await focusPage(page);
    await page.keyboard.press('ControlOrMeta+z');
    await expect(page.getByText('Скасовано: додано аудиторію 777')).toBeVisible();
    await expect(page.getByText('Аудиторії «777» немає.')).toBeVisible();

    await page.keyboard.press('ControlOrMeta+Shift+z');
    await expect(page.getByText('Повернуто: додано аудиторію 777')).toBeVisible();
    await expect(page.getByRole('rowheader', { name: '777' })).toBeVisible();

    await page.keyboard.press('ControlOrMeta+Shift+h');
    const journal = page.getByRole('dialog', { name: 'Журнал дій' });
    await expect(journal).toBeVisible();
    await expect(journal).toContainText('Додано аудиторію 777');
    // скасувати можна й звідси
    await journal.getByRole('button', { name: 'Скасувати: Додано аудиторію 777' }).click();
    await expect(journal).toContainText('Скасовано');
    await page.keyboard.press('Escape');
    await expect(journal).toBeHidden();
    await expect(page.getByText('Аудиторії «777» немає.')).toBeVisible();
  });

  test('Esc закриває вікно, а введене не губиться мовчки; Enter зберігає', async ({ page }) => {
    await focusPage(page);
    await page.keyboard.press('n');
    const dialog = page.getByRole('dialog', { name: 'Нова аудиторія' });
    await expect(dialog).toBeVisible();
    // нічого не введено — Esc просто закриває
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    await focusPage(page);
    await page.keyboard.press('n');
    await dialog.getByRole('textbox', { name: /Назва/ }).fill('778');
    await page.keyboard.press('Escape');
    const ask = page.getByRole('alertdialog', { name: 'Закрити без збереження?' });
    await expect(ask).toBeVisible();
    // Esc у підтвердженні — назад до форми, введене на місці
    await page.keyboard.press('Escape');
    await expect(ask).toBeHidden();
    await expect(dialog.getByRole('textbox', { name: /Назва/ })).toHaveValue('778');
    await dialog.getByRole('textbox', { name: /Назва/ }).focus();
    await page.keyboard.press('Enter');
    await expect(dialog).toBeHidden();
    await expect(page.getByText('Додано аудиторію 778')).toBeVisible();
  });

  test('українська розкладка: «/» (крапка) шукає в таблиці, «т» (N) відкриває нову аудиторію', async ({ page }) => {
    await focusPage(page);
    await pressUkrainian(page, '.', 'Slash');
    await expect(page.getByRole('searchbox', { name: 'Пошук аудиторії' })).toBeFocused();
    // палітра не відкрилася: «/» на сторінці з таблицею — пошук у таблиці
    await expect(page.getByRole('dialog')).toHaveCount(0);

    await focusPage(page);
    await pressUkrainian(page, 'т', 'KeyN');
    await expect(page.getByRole('dialog', { name: 'Нова аудиторія' })).toBeVisible();
  });
});
