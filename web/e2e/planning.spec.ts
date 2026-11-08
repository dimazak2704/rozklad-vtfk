import { expect, test, type Locator, type Page } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

// Базовий розклад і генератор (мок-API): перетягування з перевіркою слота сервером і запуск генерації.
// Адмінка розрахована на планшет і більше — на телефонному проєкті ці сценарії не запускаємо.
test.describe.configure({ timeout: 90_000 });
test.skip(({ isMobile }) => isMobile, 'Редактор розкладу — для планшета й десктопа');

/** Сітка групи 4КІ3 з демо-чернетки, прокручена так, щоб цілі були подалі від країв вікна. */
async function openGroupGrid(page: Page, code: string): Promise<Locator> {
  await loginWithMockRoles(page, 'ADMIN');
  await page.goto(`/admin/timetable?q=${encodeURIComponent(code)}`);
  const grid = page.getByRole('grid', { name: new RegExp(code) });
  await expect(grid).toBeVisible({ timeout: 30_000 });
  await grid.evaluate((element) => window.scrollTo({ top: element.getBoundingClientRect().top + window.scrollY - 220 }));
  return grid;
}

/** Комірка «день|пара» сітки. */
const cell = (grid: Locator, weekday: number, pair: number) => grid.locator(`[data-cell$="|${weekday}|${pair}"]`);

/** Перетягування мишею з поступовим рухом (сенсору dnd-kit потрібен зсув понад 6 px). */
async function dragTo(page: Page, source: Locator, target: Locator, { drop = true } = {}): Promise<void> {
  const from = await source.boundingBox();
  const to = await target.boundingBox();
  if (!from || !to) throw new Error('Елемент не видно на екрані');
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(from.x + from.width / 2 + 12, from.y + from.height / 2 + 6, { steps: 4 });
  await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 12 });
  if (drop) await page.mouse.up();
}

test.describe('редактор базового розкладу', () => {
  test('пару можна перетягнути у вільний слот — сервер підтверджує, пара переїжджає', async ({ page }) => {
    const grid = await openGroupGrid(page, '4КІ3');
    const stp = cell(grid, 2, 4).getByRole('button', { name: /Сучасні технології проєктування/ });
    await dragTo(page, stp, cell(grid, 3, 4), { drop: false });
    const preview = page.getByTestId('drop-preview');
    await expect(preview).toHaveAttribute('data-state', 'ok');
    await expect(preview).toContainText('Вільно — можна ставити');
    await page.mouse.up();

    await expect(page.getByText(/Пару перенесено: СТП · 4КІ3: вт 4 → ср 4/)).toBeVisible();
    await expect(cell(grid, 3, 4).getByRole('button', { name: /Сучасні технології проєктування/ })).toBeVisible();
    await expect(cell(grid, 2, 4).getByRole('button', { name: /Сучасні технології проєктування/ })).toHaveCount(0);

    // Ctrl+Z повертає пару на місце
    await page.keyboard.press('ControlOrMeta+z');
    await expect(cell(grid, 2, 4).getByRole('button', { name: /Сучасні технології проєктування/ })).toBeVisible();
  });

  test('кидок туди, де викладач уже веде пару, показує конфлікт і не змінює розклад', async ({ page }) => {
    const grid = await openGroupGrid(page, '4КІ3');
    const stp = cell(grid, 2, 4).getByRole('button', { name: /Сучасні технології проєктування/ });
    await dragTo(page, stp, cell(grid, 1, 4), { drop: false });
    const preview = page.getByTestId('drop-preview');
    await expect(preview).toHaveAttribute('data-state', 'blocked');
    await expect(preview).toContainText('Кренцін веде одночасно 4КІ2 — СТП і 4КІ3 — СТП');
    await page.mouse.up();

    await expect(page.getByText('Пару не перенесено: у цьому слоті конфлікт')).toBeVisible();
    await expect(cell(grid, 2, 4).getByRole('button', { name: /Сучасні технології проєктування/ })).toBeVisible();
    await expect(cell(grid, 1, 4).getByRole('button', { name: /Сучасні технології проєктування/ })).toHaveCount(0);
  });

  test('з клавіатури: Enter — підхопити, стрілки — слот, Enter — поставити', async ({ page }) => {
    const grid = await openGroupGrid(page, '4КІ3');
    const stp = cell(grid, 2, 4).getByRole('button', { name: /Сучасні технології проєктування/ });
    await stp.focus();
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowRight');
    await expect(page.getByTestId('drop-preview')).toHaveAttribute('data-state', 'ok');
    await page.keyboard.press('Enter');
    await expect(cell(grid, 3, 4).getByRole('button', { name: /Сучасні технології проєктування/ })).toBeVisible();
  });
});

test.describe('генератор', () => {
  test('запуск доходить до кінця: графік, «Було / Стало» і нова чернетка', async ({ page }) => {
    await loginWithMockRoles(page, 'ADMIN');
    await page.goto('/admin/generation');
    await page.getByRole('spinbutton').fill('10');
    await page.getByRole('spinbutton').press('Enter');
    await page.getByRole('button', { name: 'Запустити генерацію' }).click();

    await expect(page.getByText('Розвʼязується').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('figure', { name: 'Хід розвʼязання' })).toBeVisible();

    const table = page.getByRole('table', { name: 'Було / Стало' });
    await expect(table).toBeVisible({ timeout: 30_000 });
    await expect(table.getByRole('row', { name: /Жорсткі конфлікти\s*75\s*0/ })).toBeVisible();
    await expect(table.getByRole('row', { name: /Рвані дні/ })).toBeVisible();

    await page.getByRole('link', { name: 'Відкрити чернетку' }).click();
    await expect(page).toHaveURL(/\/admin\/timetable\?version=/);
    await expect(page.getByRole('combobox', { name: 'Версія розкладу' })).toContainText('Згенеровано');
  });
});
