import { expect, test, type Page } from '@playwright/test';
import { loginWithMockRoles } from './support/mock-auth';

// Dev-сервер компілює модулі на льоту: перший запуск сторінки буває повільним
test.describe.configure({ timeout: 90_000 });

/**
 * Понеділок 09.11.2026, 10:05 у Києві: іде друга пара, розпорядження на сьогодні опубліковане. Гостю відкрито
 * заміни з 02.11 (типово 7 днів); тиждень розпорядження №35 (19.10) бачить лише персонал — тут викладач.
 */
const NOW = new Date('2026-11-09T08:05:00Z');

async function open(page: Page, path: string): Promise<void> {
  await page.clock.setFixedTime(NOW);
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible({ timeout: 30_000 });
}

test.describe('публічний розклад', () => {
  test('пошук «4ki1» → розклад групи → день і тиждень → деталі заміни', async ({ page, isMobile }) => {
    await open(page, '/');
    const search = page.getByRole('combobox', { name: /Знайти розклад/ });
    await search.fill('4ki1');
    await expect(page.getByRole('option', { name: /4КІ1/ }).first()).toBeVisible();
    await search.press('Enter');

    await expect(page).toHaveURL(/\/g\/4%D0%9A%D0%861/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/4КІ1\s*—\s*розклад групи/);
    // з будь-якої сторінки розкладу є шлях на рівень вище
    await expect(page.getByRole('link', { name: 'Назад: Розклад' })).toBeVisible();

    // типове подання залежить від ширини: на телефоні — день, на десктопі — тиждень
    const views = page.getByRole('radiogroup', { name: 'Подання розкладу' });
    const week = views.getByRole('radio', { name: 'Тиждень' });
    const day = views.getByRole('radio', { name: 'День', exact: true });
    await week.click();
    await expect(week).toBeChecked();

    await day.click();
    await expect(page).toHaveURL(/view=day/);
    await expect(page.getByRole('radiogroup', { name: 'День тижня' })).toBeVisible();

    await week.click();
    await expect(page).toHaveURL(/view=week/);
    if (!isMobile) await expect(page.getByRole('table').filter({ hasText: 'Понеділок' })).toBeVisible();

    // тиждень розпорядження №35: СТП Кренціна замінено на НДЕКСМ Непийводи («Зв.зам.»); давній — для персоналу
    await loginWithMockRoles(page, 'TEACHER');
    await page.goto('/g/4%D0%9A%D0%861?view=week&date=2026-10-19');
    // зміни тижня — одним рядком у заголовку, самі зміни видно в клітинках
    await expect(page.getByRole('button', { name: /(заміна|заміни|замін) цього тижня/ })).toBeVisible();
    const lesson = page.getByRole('button', { name: /^2 пара, 09:40–10:40\. Надійність/ });
    if (isMobile) await lesson.click();
    else await lesson.hover();
    const details = page.getByRole('dialog', { name: /Деталі: НДЕКСМ, 2 пара/ });
    await expect(details).toBeVisible();
    // публічна картка показує причину словами, не скороченням із розпорядження
    await expect(details).toContainText(/Причина\s*взаємна заміна/);
    await expect(details).toContainText('СТП · Кренцін');
  });

  test('гість: давній тиждень — в архіві, з переходом до найранішого відкритого', async ({ page }) => {
    await open(page, '/g/4%D0%9A%D0%861?view=week&date=2026-10-19');
    await expect(page.getByText('Цей тиждень уже в архіві')).toBeVisible();
    await page.getByRole('button', { name: 'Найраніший доступний тиждень' }).click();
    await expect(page).toHaveURL(/date=2026-11-02/);
    // далі назад гість не гортає: «‹» тьмяний і пояснює чому
    await expect(page.getByRole('button', { name: /^Попередній тиждень/ })).toHaveAttribute('aria-disabled', 'true');
  });

  test('невідома група пропонує схожі коди', async ({ page }) => {
    await open(page, '/g/4%D0%9A%D0%869');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Групи «4КІ9» немає в розкладі');
    await expect(page.getByRole('link', { name: '4КІ1' })).toBeVisible();
  });
});

test.describe('заміни', () => {
  test('пошук групи й «лише мої»; файли розпорядження — у «⋯»', async ({ page }) => {
    // розпорядження №35 (19.10) — давнє: його бачить персонал
    await loginWithMockRoles(page, 'TEACHER');
    await open(page, '/changes?date=2026-10-19');
    await expect(page.getByRole('heading', { level: 1 })).toContainText('19 жовтня');
    // номер розпорядження — у картці «Джерело» під списком, а не в тексті сторінки
    await page.getByRole('button', { name: 'Джерело' }).click();
    await expect(page.getByText(/Розпорядження\s*№35/)).toBeVisible();
    await page.keyboard.press('Escape');
    const group = (code: string) => page.getByRole('link', { name: new RegExp(`^${code}`) });
    for (const code of ['1КІ2', '1ТК1', '4КІ1']) await expect(group(code)).toBeVisible();

    const search = page.getByRole('textbox', { name: 'Знайти групу' });
    if (await search.isVisible()) {
      await search.fill('4ki1');
      await expect(group('4КІ1')).toBeVisible();
      await expect(group('1КІ2')).toBeHidden();
      await page.getByRole('button', { name: 'Очистити пошук групи' }).click();
    }

    // «Лише мої» зʼявляється, щойно в обраному є група; без обраного — його немає зовсім
    await expect(page.getByRole('radio', { name: 'Лише мої' })).toHaveCount(0);
    await page.goto('/g/4%D0%9A%D0%861?date=2026-10-19');
    await page.getByRole('button', { name: 'Ще дії' }).click();
    await page.getByRole('menuitem', { name: 'Додати в обране' }).click();
    await page.goto('/changes?date=2026-10-19');
    await page.getByRole('radio', { name: 'Лише мої' }).click();
    await expect(group('4КІ1')).toBeVisible();
    await expect(group('1КІ2')).toBeHidden();
    await expect(group('1ТК1')).toBeHidden();
  });

  test('дата без розпорядження — зрозумілий порожній стан', async ({ page }) => {
    await open(page, '/changes?date=2026-11-04');
    await expect(page.getByText('Змін не було')).toBeVisible();
  });

  test('гість: давня дата — в архіві, кнопка веде до найранішої відкритої', async ({ page }) => {
    await open(page, '/changes?date=2026-10-19');
    await expect(page.getByText('Цей день уже в архіві')).toBeVisible();
    await page.getByRole('button', { name: 'Заміни на пн, 02.11' }).click();
    await expect(page).toHaveURL(/date=2026-11-02/);
    await expect(page.getByText('Цей день уже в архіві')).toBeHidden();
  });
});
