import { expect, test } from '@playwright/test';
import { expectNoHorizontalScroll } from './support/real';

/**
 * Стани помилок і роботи без мережі на справжньому стенді: що бачить людина, коли сервер відповів 404/400
 * або коли зникла мережа. Ширини: 1440, 768, 375.
 */

test('неіснуючий викладач (404) і зламане посилання (400) — зрозумілий стан, а не порожня сторінка', async ({ page }) => {
  await page.goto('/t/00000000-0000-4000-8000-000000000000');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Такого викладача в розкладі немає', { timeout: 30_000 });
  await expect(page.getByRole('link', { name: 'На головну', exact: true })).toBeVisible();
  await expectNoHorizontalScroll(page);

  await page.goto('/r/not-a-room');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Такої аудиторії в розкладі немає');

  await page.goto('/g/4%D0%9A%D0%869');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Групи «4КІ9» немає в розкладі');
  await expect(page.getByRole('link', { name: '4КІ1' })).toBeVisible();
});

test('без мережі: переглянутий тиждень зі збереженого, новий тиждень і нова сторінка — пояснення', async ({ page, context }) => {
  await page.goto('/g/4%D0%9A%D0%861?view=week&date=2026-11-16');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Розклад групи\s*4КІ1/, { timeout: 30_000 });
  const lessons = page.getByRole('main').getByRole('button', { name: /^\d пара, / });
  await expect(lessons.first()).toBeVisible();

  await context.setOffline(true);
  try {
    // уже завантажений тиждень лишається на екрані з попередженням
    await expect(page.getByText('Немає інтернету. Показано збережений розклад — заміни могли змінитися.')).toBeVisible();
    await expect(lessons.first()).toBeVisible();

    // наступний тиждень ще не переглядали: не показуємо замість нього попередній
    await page.getByRole('button', { name: 'Наступний тиждень' }).click();
    await expect(page.getByText('Цей тиждень не збережено на пристрої')).toBeVisible();
    await expect(page.getByRole('main').getByRole('button', { name: /^\d пара, / })).toHaveCount(0);

    // сторінка, код якої ще не завантажено: пояснення про мережу, шапка сайту лишається
    await page.getByRole('link', { name: 'Дзвінки' }).filter({ visible: true }).first().click();
    await expect(page.getByRole('heading', { level: 1, name: 'Немає інтернету' })).toBeVisible();
    await expect(page.getByRole('banner')).toBeVisible();
    await expectNoHorizontalScroll(page);
  } finally {
    await context.setOffline(false);
  }

  // мережа повернулась — сторінка відкривається після оновлення
  await page.getByRole('button', { name: 'Оновити сторінку' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Дзвінки', { timeout: 30_000 });
});

test('без мережі: заміни на ще не переглянутий день — пояснення, а не вічне завантаження', async ({ page, context }) => {
  await page.goto('/changes?date=2026-11-09');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Заміни', { timeout: 30_000 });
  await expect(page.getByText('Завантажуємо заміни…')).toHaveCount(0, { timeout: 30_000 });

  await context.setOffline(true);
  try {
    await page.getByRole('button', { name: 'Наступний день' }).first().click();
    await expect(page.getByText('Заміни на цю дату не збережено на пристрої')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('button', { name: /^Заміни на / })).toBeVisible();
    await expectNoHorizontalScroll(page);
  } finally {
    await context.setOffline(false);
  }
});
