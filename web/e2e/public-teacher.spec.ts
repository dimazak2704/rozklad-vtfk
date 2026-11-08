import { expect, test } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

// Кабінет викладача з мок-API: демо-користувач krentsin (викладач «Кренцін»)
test.describe.configure({ timeout: 90_000 });

const NOW = new Date('2026-11-09T08:05:00Z');

test.describe('кабінет викладача', () => {
  test.beforeEach(async ({ page }) => {
    await loginWithMockRoles(page, 'TEACHER');
    await page.clock.setFixedTime(NOW);
  });

  test('підтвердження заміни: картка одразу зникає із запитів і зʼявляється в «Моїх замінах»', async ({ page }) => {
    await page.goto('/me');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Мій розклад', { timeout: 30_000 });
    const cards = page.getByRole('article', { name: /^Запит на заміну/ });
    await expect(cards).toHaveCount(2);
    const mine = page.getByRole('region', { name: 'Мої заміни' }).getByRole('listitem');
    await expect(mine).toHaveCount(1);
    const first = cards.first();
    const label = (await first.getAttribute('aria-label')) ?? '';
    await first.getByRole('button', { name: 'Підтверджую' }).click();

    await expect(page.getByRole('article', { name: label, exact: true })).toHaveCount(0);
    await expect(cards).toHaveCount(1);
    await expect(page.getByText('Заміну підтверджено')).toBeVisible();
    await expect(mine).toHaveCount(2);
    await expect(page.getByRole('region', { name: 'Мої заміни' })).toContainText('Підтверджено');
  });

  test('відмова з коментарем', async ({ page }) => {
    await page.goto('/me');
    const cards = page.getByRole('article', { name: /^Запит на заміну/ });
    await expect(cards).toHaveCount(2, { timeout: 30_000 });
    await cards.last().getByRole('button', { name: 'Не можу' }).click();
    await page.getByRole('textbox').last().fill('Маю пару в іншому корпусі');
    await page.getByRole('button', { name: 'Відмовитися' }).click();
    await expect(cards).toHaveCount(1);
    await expect(page.getByText('Ви відмовилися від заміни')).toBeVisible();
  });

  test('нова відсутність: валідація, повідомлення і поява в списку', async ({ page }) => {
    await page.goto('/me/absences?new=1');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Мої відсутності', { timeout: 30_000 });
    await page.getByRole('button', { name: 'Повідомити диспетчера' }).click();
    await expect(page.getByText('Оберіть причину')).toBeVisible();

    await page.getByRole('radio', { name: 'Окремі пари' }).click();
    await page.getByRole('button', { name: '3 пара' }).click();
    await page.getByRole('radio', { name: 'Відрядження' }).click();
    await page.getByRole('button', { name: 'Повідомити диспетчера' }).click();
    await expect(page.getByText('Відсутність повідомлено')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Попереду' })).toContainText('Відрядження');
  });

  test('побажання: клік змінює стан клітинки, «Зберегти» зберігає', async ({ page }) => {
    await page.goto('/me/preferences');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Побажання до розкладу', { timeout: 30_000 });
    const cell = page.getByRole('button', { name: /^Вівторок, 1 пара/ });
    await expect(cell).toHaveAccessibleName(/без побажань/);
    await cell.click();
    await expect(cell).toHaveAccessibleName(/небажано/);
    await cell.press('2');
    await expect(cell).toHaveAccessibleName(/неможливо/);
    await expect(page.getByRole('region', { name: 'Незбережені зміни' })).toContainText('1 клітинка');
    await page.getByRole('button', { name: 'Зберегти' }).click();
    await expect(page.getByText('Побажання збережено')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Незбережені зміни' })).toBeHidden();
  });
});
