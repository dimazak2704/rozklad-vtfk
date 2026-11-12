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

  test('«/» і набір по літері: жодна не губиться; Esc очищає, ще раз — виводить з поля', async ({ page }) => {
    const search = page.getByRole('searchbox', { name: 'Пошук аудиторії' });
    await focusPage(page);
    await page.keyboard.press('/');
    await expect(search).toBeFocused();
    await search.pressSequentially('301', { delay: 50 });
    await expect(search).toHaveValue('301');
    await expect(page).toHaveURL(/[?&]q=301/);

    await page.keyboard.press('Escape');
    await expect(search).toHaveValue('');
    await expect(search).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.locator('#main')).toBeFocused();
    // літерні клавіші знову працюють
    await page.keyboard.press('n');
    await expect(page.getByRole('dialog', { name: 'Нова аудиторія' })).toBeVisible();
  });

  test('навантаження: «/» і набір по літері', async ({ page }) => {
    await page.goto('/admin/load');
    const search = page.getByRole('searchbox', { name: 'Пошук у навантаженні' });
    await expect(search).toBeVisible({ timeout: 30_000 });
    await focusPage(page);
    await page.keyboard.press('/');
    await search.pressSequentially('Коваль', { delay: 50 });
    await expect(search).toHaveValue('Коваль');
  });

  test('створити, змінити, двічі скасувати й двічі повернути — правка лягає на відновлений запис', async ({ page }) => {
    const writes: string[] = [];
    page.on('response', (response) => {
      const method = response.request().method();
      if (/\/api\/admin\/rooms/.test(response.url()) && method !== 'GET') writes.push(`${method} ${response.status()}`);
    });
    await focusPage(page);
    await page.keyboard.press('n');
    const create = page.getByRole('dialog', { name: 'Нова аудиторія' });
    await create.getByRole('textbox', { name: /Назва/ }).fill('991');
    await page.keyboard.press('Enter');
    await expect(create).toBeHidden();
    await page.getByRole('searchbox', { name: 'Пошук аудиторії' }).fill('99');
    await page.getByRole('rowheader', { name: '991' }).click();
    const edit = page.getByRole('dialog', { name: 'Аудиторія 991' });
    await edit.getByRole('textbox', { name: /Назва/ }).fill('992');
    await page.keyboard.press('Enter');
    await expect(edit).toBeHidden();

    await focusPage(page);
    await page.keyboard.press('ControlOrMeta+z');
    await expect(page.getByText('Скасовано: змінено аудиторію 992')).toBeVisible();
    await page.keyboard.press('ControlOrMeta+z');
    await expect(page.getByText('Скасовано: додано аудиторію 991')).toBeVisible();
    await page.keyboard.press('ControlOrMeta+y');
    await expect(page.getByText('Повернуто: додано аудиторію 991')).toBeVisible();
    await page.keyboard.press('ControlOrMeta+y');
    await expect(page.getByText('Повернуто: змінено аудиторію 992')).toBeVisible();
    await expect(page.getByRole('rowheader', { name: '992' })).toBeVisible();
    expect(writes).toEqual(['POST 201', 'PUT 200', 'PUT 200', 'DELETE 204', 'POST 201', 'PUT 200']);

    await page.keyboard.press('ControlOrMeta+Shift+h');
    const journal = page.getByRole('dialog', { name: 'Журнал дій' });
    // фокус — у шторці, на першій дії
    await expect(journal.getByRole('button', { name: 'Скасувати: Змінено аудиторію 992' })).toBeFocused();
    await expect(journal).not.toContainText('Не скасовано');
    await expect(journal).not.toContainText('Не повернуто');
  });

  test('збереження без змін не надсилає запиту й не пише в журнал; Ctrl+S без форми — підказка', async ({ page }) => {
    const writes: string[] = [];
    page.on('request', (request) => {
      if (/\/api\/admin\/rooms/.test(request.url()) && request.method() !== 'GET') writes.push(request.method());
    });
    await page.getByRole('rowheader', { name: '301' }).click();
    const edit = page.getByRole('dialog', { name: 'Аудиторія 301' });
    await expect(edit).toBeVisible();
    await page.keyboard.press('ControlOrMeta+s');
    await expect(edit).toBeHidden();
    await expect(page.getByText('Змін немає — нічого не збережено')).toBeVisible();
    expect(writes).toEqual([]);

    await focusPage(page);
    await page.keyboard.press('ControlOrMeta+s');
    await expect(page.getByText('Немає що зберігати')).toBeVisible();
  });
});
